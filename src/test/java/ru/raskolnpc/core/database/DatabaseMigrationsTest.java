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
            assertEquals(22, schemaVersion(connection));
            assertTableExists(connection, "npcs");
            assertTableExists(connection, "resident_profiles");
            assertTableExists(connection, "resident_schedules");
            assertTableExists(connection, "resident_quest_assignments");
            assertTableExists(connection, "resident_business_stock");
            assertTableExists(connection, "resident_business_stock_batches");
            assertTableExists(connection, "resident_trade_transactions");
            assertTableExists(connection, "resident_profession_changes");
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
            assertColumnExists(connection, "resident_trade_transactions", "business_id");
            assertColumnExists(connection, "resident_trade_transactions", "profession");

            assertDoesNotThrow(() -> DatabaseMigrations.migrate(connection));
            assertEquals(23, schemaVersion(connection));
            assertEquals(22, migrationCount(connection));
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
                statement.execute("CREATE TABLE npcs(id TEXT PRIMARY KEY, town_id TEXT)");
                statement.execute("CREATE TABLE resident_profiles(npc_id TEXT PRIMARY KEY REFERENCES npcs(id), profession TEXT NOT NULL)");
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

            assertEquals(23, schemaVersion(connection));
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
                statement.execute("CREATE TABLE npcs(id TEXT PRIMARY KEY, town_id TEXT)");
                statement.execute("CREATE TABLE resident_profiles(npc_id TEXT PRIMARY KEY REFERENCES npcs(id), profession TEXT NOT NULL)");
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

            assertEquals(23, schemaVersion(connection));
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
    void versionNineteenUpgradeBackfillsImmutableTradeOrigin() throws Exception {
        var file = temporaryDirectory.resolve("version-nineteen.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)");
                for (int version = 1; version <= 19; version++) {
                    statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES (" + version + ")");
                }
                statement.execute("CREATE TABLE npcs(id TEXT PRIMARY KEY, town_id TEXT)");
                statement.execute("CREATE TABLE resident_profiles(npc_id TEXT PRIMARY KEY REFERENCES npcs(id) ON DELETE CASCADE, profession TEXT NOT NULL)");
                statement.execute("""
                        CREATE TABLE resident_trade_transactions (
                            transaction_id TEXT PRIMARY KEY,
                            idempotency_key TEXT NOT NULL UNIQUE,
                            buyer_uuid TEXT NOT NULL,
                            resident_npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id) ON DELETE RESTRICT,
                            material TEXT NOT NULL,
                            quality TEXT NOT NULL CHECK (quality IN ('BASIC', 'STANDARD', 'FINE', 'MASTERWORK')),
                            amount INTEGER NOT NULL CHECK (amount BETWEEN 1 AND 1000000),
                            unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor >= 0),
                            total_price_minor INTEGER NOT NULL CHECK (total_price_minor >= 0),
                            status TEXT NOT NULL CHECK (status IN (
                                'STOCK_RESERVED', 'PAYMENT_PENDING', 'PAID', 'DELIVERED',
                                'CANCELLED', 'REFUND_PENDING', 'REFUNDED', 'REVIEW_REQUIRED'
                            )),
                            provider_reference TEXT,
                            last_error TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            CHECK (total_price_minor = unit_price_minor * amount)
                        )
                        """);
                statement.execute("INSERT INTO npcs(id, town_id) VALUES ('resident-town', 'town-7')");
                statement.execute("INSERT INTO npcs(id, town_id) VALUES ('resident-solo', NULL)");
                statement.execute("INSERT INTO resident_profiles(npc_id, profession) VALUES ('resident-town', 'BLACKSMITH')");
                statement.execute("INSERT INTO resident_profiles(npc_id, profession) VALUES ('resident-solo', 'FARMER')");
                statement.execute("""
                        INSERT INTO resident_trade_transactions(
                            transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                            material, quality, amount, unit_price_minor, total_price_minor,
                            status, created_at, updated_at
                        ) VALUES ('trade-1', 'key-1', 'buyer-1', 'resident-town',
                                  'IRON_INGOT', 'STANDARD', 1, 10, 10, 'DELIVERED', 100, 100)
                        """);
                statement.execute("""
                        INSERT INTO resident_trade_transactions(
                            transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                            material, quality, amount, unit_price_minor, total_price_minor,
                            status, created_at, updated_at
                        ) VALUES ('trade-2', 'key-2', 'buyer-2', 'resident-solo',
                                  'WHEAT', 'STANDARD', 1, 10, 10, 'CANCELLED', 101, 101)
                        """);
            }

            DatabaseMigrations.migrate(connection);

            assertEquals(23, schemaVersion(connection));
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT transaction_id, business_id, profession
                         FROM resident_trade_transactions ORDER BY transaction_id
                         """)) {
                assertTrue(result.next());
                assertEquals("trade-1", result.getString("transaction_id"));
                assertEquals("town:town-7", result.getString("business_id"));
                assertEquals("BLACKSMITH", result.getString("profession"));
                assertTrue(result.next());
                assertEquals("trade-2", result.getString("transaction_id"));
                assertEquals("resident:resident-solo", result.getString("business_id"));
                assertEquals("FARMER", result.getString("profession"));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void versionTwentyUpgradeKeepsTradeAuditAfterResidentProfileDeletion() throws Exception {
        var file = temporaryDirectory.resolve("version-twenty.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)");
                for (int version = 1; version <= 20; version++) {
                    statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES (" + version + ")");
                }
                statement.execute("CREATE TABLE resident_profiles(npc_id TEXT PRIMARY KEY)");
                statement.execute("""
                        CREATE TABLE resident_trade_transactions (
                            transaction_id TEXT PRIMARY KEY,
                            idempotency_key TEXT NOT NULL UNIQUE,
                            buyer_uuid TEXT NOT NULL,
                            resident_npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id) ON DELETE RESTRICT,
                            material TEXT NOT NULL,
                            quality TEXT NOT NULL,
                            amount INTEGER NOT NULL,
                            unit_price_minor INTEGER NOT NULL,
                            total_price_minor INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            provider_reference TEXT,
                            last_error TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            business_id TEXT,
                            profession TEXT
                        )
                        """);
                statement.execute("INSERT INTO resident_profiles(npc_id) VALUES ('resident-archive')");
                statement.execute("""
                        INSERT INTO resident_trade_transactions(
                            transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                            material, quality, amount, unit_price_minor, total_price_minor,
                            status, created_at, updated_at, business_id, profession
                        ) VALUES (
                            'trade-archive', 'key-archive', 'buyer-1', 'resident-archive',
                            'IRON_INGOT', 'STANDARD', 2, 50, 100,
                            'DELIVERED', 100, 100, 'town:archive', 'MINER'
                        )
                        """);
            }

            DatabaseMigrations.migrate(connection);

            assertEquals(23, schemaVersion(connection));
            try (var statement = connection.createStatement()) {
                try (var foreignKeys = statement.executeQuery(
                        "PRAGMA foreign_key_list(resident_trade_transactions)")) {
                    assertFalse(foreignKeys.next(),
                            "Trade audit rows must not depend on a live resident profile.");
                }
                statement.execute("DELETE FROM resident_profiles WHERE npc_id = 'resident-archive'");
                try (var result = statement.executeQuery("""
                        SELECT resident_npc_id, status, business_id, profession
                        FROM resident_trade_transactions WHERE transaction_id = 'trade-archive'
                        """)) {
                    assertTrue(result.next());
                    assertEquals("resident-archive", result.getString("resident_npc_id"));
                    assertEquals("DELIVERED", result.getString("status"));
                    assertEquals("town:archive", result.getString("business_id"));
                    assertEquals("MINER", result.getString("profession"));
                }
            }
        }
    }

    @Test
    void rollsBackPartiallyAppliedMigrationWhenAnAlterStatementFails() throws Exception {
        var file = temporaryDirectory.resolve("migration-rollback.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO schema_migrations(version) VALUES (1)");
                statement.execute("""
                        CREATE TABLE npcs (
                            id TEXT PRIMARY KEY,
                            spawn_world_uuid TEXT
                        )
                        """);
            }

            assertThrows(SQLException.class, () -> DatabaseMigrations.migrate(connection));

            assertEquals(1, schemaVersion(connection));
            assertFalse(columnExists(connection, "npcs", "entity_uuid"),
                    "The first ALTER TABLE must roll back when a later ALTER fails.");
            assertTrue(columnExists(connection, "npcs", "spawn_world_uuid"));
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
            assertEquals(23, migrationCount(connection));
        }
    }

    @Test
    void refusesIncompleteMigrationHistoryWithoutApplyingFurtherMigrations() throws Exception {
        var file = temporaryDirectory.resolve("incomplete-history.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            DatabaseMigrations.migrate(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 10");
            }

            SQLException error = assertThrows(SQLException.class,
                    () -> DatabaseMigrations.migrate(connection));
            assertTrue(error.getMessage().contains("migration history is incomplete"));
            assertEquals(23, schemaVersion(connection));
            assertEquals(22, migrationCount(connection));
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

    private static boolean columnExists(java.sql.Connection connection, String table, String column)
            throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) return true;
            }
        }
        return false;
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
