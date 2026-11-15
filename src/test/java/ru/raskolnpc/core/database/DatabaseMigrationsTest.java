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
            assertEquals(18, schemaVersion(connection));
            assertTableExists(connection, "npcs");
            assertTableExists(connection, "resident_profiles");
            assertTableExists(connection, "resident_schedules");
            assertTableExists(connection, "resident_quest_assignments");
            assertTableExists(connection, "resident_business_stock");
            assertTableExists(connection, "resident_business_stock_batches");
            assertTableExists(connection, "guard_rent_payments");
            assertTableExists(connection, "guard_hiring_payments");
            assertTableExists(connection, "guard_orders");
            assertTableExists(connection, "guard_order_waypoints");
            assertTableExists(connection, "guard_squads");
            assertTableExists(connection, "guard_squad_members");
            assertTableExists(connection, "guard_conditions");
            assertColumnExists(connection, "guard_conditions", "injury_severity");
            assertColumnExists(connection, "guard_conditions", "last_injury_recovery_at");
            assertColumnExists(connection, "resident_skills", "experience");

            assertDoesNotThrow(() -> DatabaseMigrations.migrate(connection));
            assertEquals(17, schemaVersion(connection));
            assertEquals(18, migrationCount(connection));
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
                statement.execute("CREATE TABLE resident_profiles(npc_id TEXT PRIMARY KEY REFERENCES npcs(id))");
                statement.execute("CREATE TABLE resident_skills(npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id), profession TEXT NOT NULL, skill_level TEXT NOT NULL, PRIMARY KEY(npc_id, profession))");
                statement.execute("""
                        CREATE TABLE resident_business_stock (
                            business_id TEXT NOT NULL, profession TEXT NOT NULL,
                            material TEXT NOT NULL, amount INTEGER NOT NULL,
                            capacity INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                            PRIMARY KEY (business_id, profession, material)
                        )
                        """);
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

            assertEquals(17, schemaVersion(connection));
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
    void versionSeventeenUpgradeBackfillsPositiveStockAsStandardBatches() throws Exception {
        var file = temporaryDirectory.resolve("version-seventeen.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)");
                for (int version = 1; version <= 17; version++) {
                    statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES (" + version + ")");
                }
                statement.execute("""
                        CREATE TABLE resident_business_stock (
                            business_id TEXT NOT NULL, profession TEXT NOT NULL,
                            material TEXT NOT NULL, amount INTEGER NOT NULL,
                            capacity INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                            PRIMARY KEY (business_id, profession, material)
                        )
                        """);
                statement.execute("""
                        INSERT INTO resident_business_stock(
                            business_id, profession, material, amount, capacity, updated_at
                        ) VALUES ('town:1', 'BLACKSMITH', 'IRON_INGOT', 24, 64, 12345)
                        """);
                statement.execute("""
                        INSERT INTO resident_business_stock(
                            business_id, profession, material, amount, capacity, updated_at
                        ) VALUES ('town:1', 'BUILDER', 'STONE', 0, 64, 12346)
                        """);
            }

            DatabaseMigrations.migrate(connection);

            assertEquals(18, schemaVersion(connection));
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT business_id, profession, material, amount, quality,
                                created_at, source_type
                         FROM resident_business_stock_batches
                         """)) {
                assertTrue(result.next());
                assertEquals("town:1", result.getString("business_id"));
                assertEquals("BLACKSMITH", result.getString("profession"));
                assertEquals("IRON_INGOT", result.getString("material"));
                assertEquals(24, result.getInt("amount"));
                assertEquals("STANDARD", result.getString("quality"));
                assertEquals(12345, result.getLong("created_at"));
                assertEquals("LEGACY", result.getString("source_type"));
                assertFalse(result.next(), "Zero-amount stock must not create an empty batch.");
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
            assertEquals(19, migrationCount(connection));
        }
    }

    @Test
    void guardMoraleIsBoundedAndDeletedWithItsNpc() throws Exception {
        var file = temporaryDirectory.resolve("guard-morale.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                DatabaseMigrations.migrate(connection);
                statement.execute("INSERT INTO npcs(id, type, name, state) VALUES ('guard-1', 'GUARD', 'Guard', 'ACTIVE')");
                statement.execute("INSERT INTO guard_conditions(npc_id, morale, last_combat_at, updated_at) VALUES ('guard-1', 75, 1000, 1000)");
                assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE guard_conditions SET morale = 101 WHERE npc_id = 'guard-1'"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE guard_conditions SET injury_severity = 3 WHERE npc_id = 'guard-1'"));
                statement.execute("DELETE FROM npcs WHERE id = 'guard-1'");
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM guard_conditions")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            }
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

    private static void assertColumnExists(java.sql.Connection connection, String table, String column)
            throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) return;
            }
        }
        fail("Expected column to exist: " + table + "." + column);
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
