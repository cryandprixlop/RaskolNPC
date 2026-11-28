package ru.raskolnpc.core.database;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void closeIsIdempotentAndRejectsNewOperations() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File(temporaryDirectory.toFile(), "plugin-data"));
        DatabaseManager manager = new DatabaseManager(plugin, "test.db", 1);

        assertDoesNotThrow(manager::close);
        assertDoesNotThrow(manager::close);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> manager.initialize().join());
        assertTrue(failure.getCause().getMessage().contains("closed"));
    }

    @Test
    void transactionRollsBackAndForeignKeysAreEnabledOnEveryConnection() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(
                new File(temporaryDirectory.toFile(), "transaction-data"));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DatabaseManagerTest"));
        DatabaseManager manager = new DatabaseManager(plugin, "transaction.db", 2);

        try {
            manager.initialize().join();
            manager.execute(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE parents (id INTEGER PRIMARY KEY)");
                    statement.execute("""
                            CREATE TABLE children (
                                id INTEGER PRIMARY KEY,
                                parent_id INTEGER NOT NULL REFERENCES parents(id)
                            )
                            """);
                }
            }).join();

            CompletionException transactionFailure = assertThrows(CompletionException.class,
                    () -> manager.transaction(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate("INSERT INTO parents(id) VALUES (1)");
                            statement.executeUpdate(
                                    "INSERT INTO children(id, parent_id) VALUES (1, 1)");
                        }
                        throw new SQLException("Force rollback for test.");
                    }).join());
            assertNotNull(transactionFailure.getCause());

            assertEquals(0, manager.query(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM parents")) {
                    result.next();
                    return result.getInt(1);
                }
            }).join());
            assertEquals(0, manager.query(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM children")) {
                    result.next();
                    return result.getInt(1);
                }
            }).join());

            CompletionException foreignKeyFailure = assertThrows(CompletionException.class,
                    () -> manager.execute(connection -> {
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate(
                                    "INSERT INTO children(id, parent_id) VALUES (2, 999)");
                        }
                    }).join());
            assertNotNull(foreignKeyFailure.getCause());
        } finally {
            manager.close();
        }
    }
}
