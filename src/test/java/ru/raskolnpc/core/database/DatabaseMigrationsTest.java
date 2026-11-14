package ru.raskolnpc.core.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void freshDatabaseAppliesEveryMigrationAndCanBeMigratedAgain() throws Exception {
        var file = temporaryDirectory.resolve("fresh.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            DatabaseMigrations.migrate(connection);
            assertEquals(14, schemaVersion(connection));
            assertTableExists(connection, "npcs");
            assertTableExists(connection, "resident_profiles");
            assertTableExists(connection, "resident_schedules");
            assertTableExists(connection, "resident_quest_assignments");
            assertTableExists(connection, "resident_business_stock");
            assertTableExists(connection, "guard_rent_payments");
            assertTableExists(connection, "guard_hiring_payments");
            assertTableExists(connection, "guard_orders");
            assertTableExists(connection, "guard_order_waypoints");
            assertTableExists(connection, "guard_squads");
            assertTableExists(connection, "guard_squad_members");

            assertDoesNotThrow(() -> DatabaseMigrations.migrate(connection));
            assertEquals(14, schemaVersion(connection));
            assertEquals(14, migrationCount(connection));
        }
    }

    @Test
    void versionThirteenUpgradePreservesOrdersAndAllowsRally() throws Exception {
        var file = temporaryDirectory.resolve("version-thirteen.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)");
                for (int version = 1; version <= 13; version++) {
                    statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES (" + version + ")");
                }
                statement.execute("CREATE TABLE npcs(id TEXT PRIMARY KEY)");
                statement.execute("""
                        CREATE TABLE guard_orders (
                            npc_id TEXT PRIMARY KEY REFERENCES npcs(id) ON DELETE CASCADE,
                            order_type TEXT NOT NULL CHECK (order_type IN (
                                'HOLD_POSITION', 'PATROL', 'DEFEND', 'ESCORT', 'RETURN_TO_POST'
                            )),
                            issuer_uuid TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE guard_order_waypoints (
                            npc_id TEXT NOT NULL REFERENCES guard_orders(npc_id) ON DELETE CASCADE,
                            sequence INTEGER NOT NULL CHECK (sequence BETWEEN 0 AND 15),
                            world_uuid TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL,
                            z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,
                            PRIMARY KEY (npc_id, sequence)
                        )
                        """);
                statement.execute("INSERT INTO npcs(id) VALUES ('guard-1')");
                statement.execute("""
                        INSERT INTO guard_orders(npc_id, order_type, issuer_uuid, updated_at)
                        VALUES ('guard-1', 'PATROL', 'issuer-1', 123)
                        """);
                statement.execute("""
                        INSERT INTO guard_order_waypoints(
                            npc_id, sequence, world_uuid, x, y, z, yaw, pitch
                        ) VALUES ('guard-1', 0, 'world-1', 1, 64, 2, 0, 0)
                        """);
            }

            DatabaseMigrations.migrate(connection);

            assertEquals(14, schemaVersion(connection));
            try (var statement = connection.createStatement()) {
                try (var result = statement.executeQuery(
                        "SELECT order_type, updated_at FROM guard_orders WHERE npc_id = 'guard-1'")) {
                    assertTrue(result.next());
                    assertEquals("PATROL", result.getString("order_type"));
                    assertEquals(123, result.getLong("updated_at"));
                }
                statement.execute("""
                        INSERT INTO guard_orders(npc_id, order_type, issuer_uuid, updated_at)
                        VALUES ('guard-1', 'RALLY', 'issuer-2', 456)
                        ON CONFLICT(npc_id) DO UPDATE SET order_type = excluded.order_type
                        """);
                assertEquals(0, foreignKeyViolations(connection));
            }
        }
    }

    @Test
    void refusesDatabaseCreatedByNewerPluginVersionWithoutChangingIt() throws Exception {
        var file = temporaryDirectory.resolve("future.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            DatabaseMigrations.migrate(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES (99)");
            }

            SQLException error = assertThrows(SQLException.class,
                    () -> DatabaseMigrations.migrate(connection));
            assertTrue(error.getMessage().contains("newer than this RaskolNPC build supports"));
            assertEquals(99, schemaVersion(connection));
            assertEquals(15, migrationCount(connection));
        }
    }

    private static int foreignKeyViolations(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            int count = 0;
            while (result.next()) count++;
            return count;
        }
    }

    private static int schemaVersion(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static int migrationCount(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void assertTableExists(java.sql.Connection connection, String table)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "Expected table to exist: " + table);
            }
        }
    }
}
