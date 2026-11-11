package ru.raskolnpc.core.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class DatabaseManager implements AutoCloseable {

    private final JavaPlugin plugin;
    private final File databaseFile;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DatabaseManager(JavaPlugin plugin, String fileName, int workerCount) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), fileName);
        int workers = Math.max(1, Math.min(4, workerCount));

        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(512),
                runnable -> {
                    Thread thread = new Thread(runnable, "RaskolNPC-Database");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<Void> initialize() {
        return submit(() -> {
            Class.forName("org.sqlite.JDBC");
            File parent = databaseFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Could not create plugin data directory.");
            }

            try (Connection connection = openConnection()) {
                DatabaseMigrations.migrate(connection);
            }

            initialized.set(true);
            return null;
        });
    }

    public CompletableFuture<Void> execute(DatabaseTask task) {
        if (!initialized.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Database is not initialized."));
        }

        return submit(() -> {
            try (Connection connection = openConnection()) {
                task.run(connection);
            }
            return null;
        });
    }

    public <T> CompletableFuture<T> query(DatabaseQuery<T> query) {
        if (!initialized.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Database is not initialized."));
        }

        return submit(() -> {
            try (Connection connection = openConnection()) {
                return query.run(connection);
            }
        });
    }

    /**
     * Runs the supplied callback in one SQLite transaction on a database worker.
     * The callback must use only the supplied connection and must not perform
     * Bukkit or Towny operations.
     */
    public <T> CompletableFuture<T> transaction(DatabaseTransaction<T> task) {
        if (!initialized.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Database is not initialized."));
        }

        return submit(() -> {
            try (Connection connection = openConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                Throwable failure = null;

                try {
                    T result = task.run(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException | Error exception) {
                    failure = exception;
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                    throw exception;
                } finally {
                    try {
                        connection.setAutoCommit(previousAutoCommit);
                    } catch (SQLException restoreException) {
                        if (failure != null) {
                            failure.addSuppressed(restoreException);
                        } else {
                            throw restoreException;
                        }
                    }
                }
            }
        });
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.getAbsolutePath());

        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }

        return connection;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Database manager is closed."));
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run());
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Database operation failed.", throwable);
                    future.completeExceptionally(
                            new DatabaseException("Database operation failed.", throwable));
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }

        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning(
                            "Database workers did not stop within the shutdown timeout.");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface DatabaseTask {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface DatabaseQuery<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface DatabaseTransaction<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws Exception;
    }
}
