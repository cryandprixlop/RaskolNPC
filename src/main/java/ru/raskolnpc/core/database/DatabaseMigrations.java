package ru.raskolnpc.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseMigrations {

    private DatabaseMigrations() {
    }

    public static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        if (!isApplied(connection, 1)) applyInitialSchema(connection);
        if (!isApplied(connection, 2)) applyEntityBindingSchema(connection);
        if (!isApplied(connection, 3)) applyDeletionQueueSchema(connection);
        if (!isApplied(connection, 4)) applyGuardRentPaymentSchema(connection);
    }

    private static boolean isApplied(Connection connection, int version)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void applyInitialSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npcs (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        owner_uuid TEXT,
                        town_id TEXT,
                        nation_id TEXT,
                        state TEXT NOT NULL,
                        data_json TEXT NOT NULL DEFAULT '{}',
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npcs_type ON npcs(type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_npcs_owner ON npcs(owner_uuid)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (1)");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void applyEntityBindingSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE npcs ADD COLUMN entity_uuid TEXT");
            statement.execute("ALTER TABLE npcs ADD COLUMN spawn_world_uuid TEXT");
            statement.execute("ALTER TABLE npcs ADD COLUMN spawn_x REAL");
            statement.execute("ALTER TABLE npcs ADD COLUMN spawn_y REAL");
            statement.execute("ALTER TABLE npcs ADD COLUMN spawn_z REAL");
            statement.execute("ALTER TABLE npcs ADD COLUMN spawn_yaw REAL");
            statement.execute("ALTER TABLE npcs ADD COLUMN spawn_pitch REAL");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_npcs_entity_uuid ON npcs(entity_uuid)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (2)");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void applyDeletionQueueSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_npc_deletions (
                        npc_id TEXT PRIMARY KEY,
                        queued_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_pending_npc_deletions_queued "
                    + "ON pending_npc_deletions(queued_at)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (3)");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void applyGuardRentPaymentSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;

        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS guard_rent_payments (
                        payment_id TEXT PRIMARY KEY,
                        npc_id TEXT NOT NULL,
                        rent_due_at INTEGER NOT NULL CHECK (rent_due_at > 0),
                        town_id TEXT NOT NULL,
                        amount REAL NOT NULL CHECK (amount >= 0),
                        status TEXT NOT NULL CHECK (status IN (
                            'IN_PROGRESS',
                            'PAID',
                            'PAID_APPLIED',
                            'FAILED',
                            'DEBT_APPLIED',
                            'REVIEW_REQUIRED'
                        )),
                        target_debt_days INTEGER NOT NULL
                            CHECK (target_debt_days >= 0 AND target_debt_days <= 3),
                        target_next_rent_at INTEGER NOT NULL
                            CHECK (target_next_rent_at > 0),
                        failure_reason TEXT,
                        resolved_by TEXT,
                        resolved_at TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (npc_id, rent_due_at)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_guard_rent_payments_status
                    ON guard_rent_payments(status)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (4)");
            connection.commit();
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
}
