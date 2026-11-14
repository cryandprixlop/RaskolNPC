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
            assertEquals(13, schemaVersion(connection));
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
            assertEquals(12, schemaVersion(connection));
            assertEquals(13, migrationCount(connection));
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
            assertEquals(13, migrationCount(connection));
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
