package ru.raskolnpc.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseMigrations {
    private static final int CURRENT_SCHEMA_VERSION = 21;

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

        int installedVersion = installedVersion(connection);
        validateMigrationHistory(connection, installedVersion);
        if (installedVersion > CURRENT_SCHEMA_VERSION) {
            throw new SQLException("Database schema version " + installedVersion
                    + " is newer than this RaskolNPC build supports ("
                    + CURRENT_SCHEMA_VERSION + "). Refusing to start to prevent data loss.");
        }

        if (!isApplied(connection, 1)) applyInitialSchema(connection);
        if (!isApplied(connection, 2)) applyEntityBindingSchema(connection);
        if (!isApplied(connection, 3)) applyDeletionQueueSchema(connection);
        if (!isApplied(connection, 4)) applyGuardRentPaymentSchema(connection);
        if (!isApplied(connection, 5)) applyGuardHiringPaymentSchema(connection);
        if (!isApplied(connection, 6)) applyResidentProfileSchema(connection);
        if (!isApplied(connection, 7)) applyResidentScheduleSchema(connection);
        if (!isApplied(connection, 8)) applyResidentQuestSchema(connection);
        if (!isApplied(connection, 9)) applyResidentQuestSubmissionSchema(connection);
        if (!isApplied(connection, 10)) applyResidentQuestReviewRewardSchema(connection);
        if (!isApplied(connection, 11)) applyResidentBusinessStockSchema(connection);
        if (!isApplied(connection, 12)) applyGuardOrderSchema(connection);
        if (!isApplied(connection, 13)) applyGuardSquadSchema(connection);
        if (!isApplied(connection, 14)) applyGuardRallyOrderSchema(connection);
        if (!isApplied(connection, 15)) applyGuardMoraleSchema(connection);
        if (!isApplied(connection, 16)) applyGuardInjurySchema(connection);
        if (!isApplied(connection, 17)) applyResidentSkillExperienceSchema(connection);
        if (!isApplied(connection, 18)) applyResidentBusinessStockBatchSchema(connection);
        if (!isApplied(connection, 19)) applyResidentTradeJournalSchema(connection);
        if (!isApplied(connection, 20)) applyResidentTradeOriginSchema(connection);
        if (!isApplied(connection, 21)) applyResidentTradeAuditRetentionSchema(connection);
    }

    private static void validateMigrationHistory(Connection connection, int installedVersion)
            throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT version FROM schema_migrations ORDER BY version ASC")) {
            int expected = 1;
            while (result.next()) {
                int actual = result.getInt(1);
                if (actual != expected) {
                    throw new SQLException("Database migration history is incomplete: expected version "
                            + expected + " but found " + actual
                            + ". Refusing to modify a potentially inconsistent database.");
                }
                expected++;
            }
            if (expected - 1 != installedVersion) {
                throw new SQLException("Database migration history is inconsistent. "
                        + "Refusing to modify the database.");
            }
        }
    }

    private static int installedVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return result.next() ? result.getInt(1) : 0;
        }
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

    private static void applyGuardHiringPaymentSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS guard_hiring_payments (
                        payment_id TEXT PRIMARY KEY,
                        npc_id TEXT,
                        owner_uuid TEXT NOT NULL,
                        town_id TEXT NOT NULL,
                        nation_id TEXT NOT NULL,
                        guard_name TEXT NOT NULL,
                        level INTEGER NOT NULL CHECK (level >= 1 AND level <= 10),
                        hire_cost REAL NOT NULL CHECK (hire_cost >= 0),
                        daily_rent REAL NOT NULL CHECK (daily_rent >= 0),
                        status TEXT NOT NULL CHECK (status IN (
                            'IN_PROGRESS', 'DEBIT_CONFIRMED', 'DEBIT_FAILED',
                            'REFUND_IN_PROGRESS', 'COMPLETED', 'REFUNDED',
                            'REVIEW_REQUIRED'
                        )),
                        failure_reason TEXT,
                        resolved_by TEXT,
                        resolved_at TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_guard_hiring_payments_status
                    ON guard_hiring_payments(status)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (5)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyResidentProfileSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_profiles (
                        npc_id TEXT PRIMARY KEY REFERENCES npcs(id) ON DELETE CASCADE,
                        age INTEGER NOT NULL CHECK (age BETWEEN 1 AND 120),
                        profession TEXT NOT NULL,
                        hunger INTEGER NOT NULL CHECK (hunger BETWEEN 0 AND 100),
                        energy INTEGER NOT NULL CHECK (energy BETWEEN 0 AND 100),
                        rest INTEGER NOT NULL CHECK (rest BETWEEN 0 AND 100),
                        social INTEGER NOT NULL CHECK (social BETWEEN 0 AND 100),
                        housing_satisfaction INTEGER NOT NULL
                            CHECK (housing_satisfaction BETWEEN 0 AND 100),
                        mood INTEGER NOT NULL CHECK (mood BETWEEN 0 AND 100)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_traits (
                        npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id)
                            ON DELETE CASCADE,
                        trait TEXT NOT NULL,
                        PRIMARY KEY (npc_id, trait)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_skills (
                        npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id)
                            ON DELETE CASCADE,
                        profession TEXT NOT NULL,
                        skill_level TEXT NOT NULL,
                        PRIMARY KEY (npc_id, profession)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_resident_profiles_profession "
                    + "ON resident_profiles(profession)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (6)");
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
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyResidentScheduleSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_schedules (
                        npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id)
                            ON DELETE CASCADE,
                        day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
                        start_minute INTEGER NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
                        end_minute INTEGER NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
                        activity TEXT NOT NULL,
                        destination_type TEXT NOT NULL CHECK (destination_type IN (
                            'HOME', 'WORK', 'MARKET', 'COMMUNITY'
                        )),
                        PRIMARY KEY (npc_id, day_of_week, start_minute),
                        CHECK (start_minute < end_minute)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_resident_schedules_day_start
                    ON resident_schedules(day_of_week, start_minute)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_destinations (
                        npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id)
                            ON DELETE CASCADE,
                        destination_type TEXT NOT NULL CHECK (destination_type IN (
                            'HOME', 'WORK', 'MARKET', 'COMMUNITY'
                        )),
                        world_uuid TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        label TEXT,
                        PRIMARY KEY (npc_id, destination_type)
                    )
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (7)");
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
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyResidentQuestSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_quest_assignments (
                        assignment_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        resident_npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id)
                            ON DELETE CASCADE,
                        quest_id TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'COMPLETED')),
                        accepted_at INTEGER NOT NULL CHECK (accepted_at > 0),
                        completed_at INTEGER,
                        CHECK (
                            (status = 'ACCEPTED' AND completed_at IS NULL)
                            OR (status = 'COMPLETED' AND completed_at IS NOT NULL)
                        )
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_resident_quest_active_unique
                    ON resident_quest_assignments(player_uuid, resident_npc_id, quest_id)
                    WHERE status = 'ACCEPTED'
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_resident_quest_player_status
                    ON resident_quest_assignments(player_uuid, status, accepted_at)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (8)");
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
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    private static void applyResidentQuestSubmissionSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_quest_submissions (
                        player_uuid TEXT NOT NULL,
                        resident_npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id)
                            ON DELETE CASCADE,
                        quest_id TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN (
                            'IN_PROGRESS', 'COMPLETED', 'REVIEW_REQUIRED'
                        )),
                        reason TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, resident_npc_id, quest_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_reputation (
                        player_uuid TEXT PRIMARY KEY,
                        points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_resident_quest_submissions_status
                    ON resident_quest_submissions(status, updated_at)
                    """);
            statement.execute("""
                    UPDATE resident_quest_submissions
                    SET status = 'REVIEW_REQUIRED',
                        reason = 'Server restarted before submission was finalized.',
                        updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000
                    WHERE status = 'IN_PROGRESS'
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (9)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyResidentQuestReviewRewardSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE resident_quest_submissions
                    ADD COLUMN reputation_reward INTEGER
                    CHECK (reputation_reward IS NULL OR reputation_reward >= 0)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (10)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyResidentBusinessStockSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_business_stock (
                        business_id TEXT NOT NULL,
                        profession TEXT NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount >= 0),
                        capacity INTEGER NOT NULL CHECK (capacity >= 0),
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (business_id, profession, material)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_resident_business_stock_business
                    ON resident_business_stock(business_id, profession)
                    """);
            statement.execute("""
                    ALTER TABLE resident_quest_submissions
                    ADD COLUMN stock_material TEXT
                    """);
            statement.execute("""
                    ALTER TABLE resident_quest_submissions
                    ADD COLUMN stock_amount INTEGER
                    CHECK (stock_amount IS NULL OR stock_amount >= 0)
                    """);
            statement.execute("""
                    ALTER TABLE resident_quest_submissions
                    ADD COLUMN stock_capacity INTEGER
                    CHECK (stock_capacity IS NULL OR stock_capacity >= 0)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (11)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyGuardOrderSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS guard_orders (
                        npc_id TEXT PRIMARY KEY REFERENCES npcs(id) ON DELETE CASCADE,
                        order_type TEXT NOT NULL CHECK (order_type IN (
                            'HOLD_POSITION', 'PATROL', 'DEFEND', 'ESCORT', 'RETURN_TO_POST'
                        )),
                        issuer_uuid TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS guard_order_waypoints (
                        npc_id TEXT NOT NULL REFERENCES guard_orders(npc_id) ON DELETE CASCADE,
                        sequence INTEGER NOT NULL CHECK (sequence BETWEEN 0 AND 15),
                        world_uuid TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        PRIMARY KEY (npc_id, sequence)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_guard_orders_type "
                    + "ON guard_orders(order_type)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (12)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyGuardSquadSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS guard_squads (
                        squad_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 32),
                        created_by TEXT NOT NULL,
                        created_at INTEGER NOT NULL CHECK (created_at > 0)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS guard_squad_members (
                        squad_id TEXT NOT NULL REFERENCES guard_squads(squad_id) ON DELETE CASCADE,
                        npc_id TEXT NOT NULL UNIQUE REFERENCES npcs(id) ON DELETE CASCADE,
                        PRIMARY KEY (squad_id, npc_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_guard_squad_members_squad "
                    + "ON guard_squad_members(squad_id)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (13)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    /**
     * Adds the RALLY order type while preserving all persisted orders and waypoints.
     * SQLite cannot alter a CHECK constraint in place, so both related tables are
     * copied to replacement tables and swapped within one migration transaction.
     */
    private static void applyGuardRallyOrderSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE guard_orders_new (
                        npc_id TEXT PRIMARY KEY REFERENCES npcs(id) ON DELETE CASCADE,
                        order_type TEXT NOT NULL CHECK (order_type IN (
                            'HOLD_POSITION', 'PATROL', 'DEFEND', 'RALLY',
                            'ESCORT', 'RETURN_TO_POST'
                        )),
                        issuer_uuid TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE guard_order_waypoints_new (
                        npc_id TEXT NOT NULL REFERENCES guard_orders_new(npc_id) ON DELETE CASCADE,
                        sequence INTEGER NOT NULL CHECK (sequence BETWEEN 0 AND 15),
                        world_uuid TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        PRIMARY KEY (npc_id, sequence)
                    )
                    """);
            statement.execute("""
                    INSERT INTO guard_orders_new(npc_id, order_type, issuer_uuid, updated_at)
                    SELECT npc_id, order_type, issuer_uuid, updated_at FROM guard_orders
                    """);
            statement.execute("""
                    INSERT INTO guard_order_waypoints_new(
                        npc_id, sequence, world_uuid, x, y, z, yaw, pitch
                    )
                    SELECT npc_id, sequence, world_uuid, x, y, z, yaw, pitch
                    FROM guard_order_waypoints
                    """);
            statement.execute("DROP TABLE guard_order_waypoints");
            statement.execute("DROP TABLE guard_orders");
            statement.execute("ALTER TABLE guard_orders_new RENAME TO guard_orders");
            statement.execute("ALTER TABLE guard_order_waypoints_new RENAME TO guard_order_waypoints");
            statement.execute("CREATE INDEX idx_guard_orders_type ON guard_orders(order_type)");
            statement.execute("CREATE INDEX idx_guard_order_waypoints_world "
                    + "ON guard_order_waypoints(world_uuid)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (14)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    private static void applyGuardMoraleSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE guard_conditions (
                        npc_id TEXT PRIMARY KEY REFERENCES npcs(id) ON DELETE CASCADE,
                        morale INTEGER NOT NULL CHECK (morale BETWEEN 0 AND 100),
                        last_combat_at INTEGER NOT NULL CHECK (last_combat_at >= 0),
                        updated_at INTEGER NOT NULL CHECK (updated_at > 0)
                    )
                    """);
            statement.execute("CREATE INDEX idx_guard_conditions_last_combat "
                    + "ON guard_conditions(last_combat_at)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (15)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

    private static void applyGuardInjurySchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE guard_conditions ADD COLUMN injury_severity INTEGER NOT NULL DEFAULT 0 CHECK (injury_severity BETWEEN 0 AND 2)");
            statement.execute("ALTER TABLE guard_conditions ADD COLUMN last_injury_recovery_at INTEGER NOT NULL DEFAULT 0 CHECK (last_injury_recovery_at >= 0)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (16)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    private static void applyResidentSkillExperienceSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE resident_skills ADD COLUMN experience INTEGER NOT NULL DEFAULT 0 CHECK (experience >= 0)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES (17)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    private static void applyResidentBusinessStockBatchSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS resident_business_stock_batches (
                        batch_id TEXT PRIMARY KEY,
                        business_id TEXT NOT NULL,
                        profession TEXT NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount > 0),
                        quality TEXT NOT NULL CHECK (quality IN (
                            'BASIC', 'STANDARD', 'FINE', 'MASTERWORK'
                        )),
                        created_at INTEGER NOT NULL,
                        source_type TEXT NOT NULL DEFAULT 'LEGACY',
                        source_ref TEXT
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_resident_business_stock_batches_stock
                    ON resident_business_stock_batches(
                        business_id, profession, material, created_at, batch_id
                    )
                    """);
            statement.execute("""
                    INSERT INTO resident_business_stock_batches(
                        batch_id, business_id, profession, material, amount,
                        quality, created_at, source_type, source_ref
                    )
                    SELECT
                        lower(hex(randomblob(16))),
                        business_id, profession, material, amount,
                        'STANDARD', updated_at, 'LEGACY', NULL
                    FROM resident_business_stock
                    WHERE amount > 0
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (18)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    private static void applyResidentTradeJournalSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE resident_trade_transactions (
                        transaction_id TEXT PRIMARY KEY,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        buyer_uuid TEXT NOT NULL,
                        resident_npc_id TEXT NOT NULL REFERENCES resident_profiles(npc_id) ON DELETE RESTRICT,
                        material TEXT NOT NULL,
                        quality TEXT NOT NULL CHECK (quality IN (
                            'BASIC', 'STANDARD', 'FINE', 'MASTERWORK'
                        )),
                        amount INTEGER NOT NULL CHECK (amount BETWEEN 1 AND 1000000),
                        unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor >= 0),
                        total_price_minor INTEGER NOT NULL CHECK (total_price_minor >= 0),
                        status TEXT NOT NULL CHECK (status IN (
                            'STOCK_RESERVED', 'PAYMENT_PENDING', 'PAID', 'DELIVERED',
                            'CANCELLED', 'REFUND_PENDING', 'REFUNDED', 'REVIEW_REQUIRED'
                        )),
                        provider_reference TEXT,
                        last_error TEXT,
                        created_at INTEGER NOT NULL CHECK (created_at > 0),
                        updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
                        CHECK (total_price_minor = unit_price_minor * amount)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_resident_trade_buyer
                    ON resident_trade_transactions(buyer_uuid, created_at)
                    """);
            statement.execute("""
                    CREATE INDEX idx_resident_trade_status
                    ON resident_trade_transactions(status, updated_at)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (19)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }



    private static void applyResidentTradeOriginSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE resident_trade_transactions ADD COLUMN business_id TEXT");
            statement.execute("ALTER TABLE resident_trade_transactions ADD COLUMN profession TEXT");
            statement.execute("""
                    UPDATE resident_trade_transactions
                    SET business_id = CASE
                        WHEN npcs.town_id IS NULL OR trim(npcs.town_id) = ''
                            THEN 'resident:' || resident_trade_transactions.resident_npc_id
                        ELSE 'town:' || npcs.town_id
                    END,
                    profession = resident_profiles.profession
                    FROM npcs
                    JOIN resident_profiles ON resident_profiles.npc_id = npcs.id
                    WHERE resident_trade_transactions.resident_npc_id = npcs.id
                    """);
            statement.execute("""
                    CREATE INDEX idx_resident_trade_origin
                    ON resident_trade_transactions(business_id, profession)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (20)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }


    /**
     * Trade rows are an immutable audit journal, not owned by the live resident profile.
     * Keep the historical NPC id while allowing deletion of residents with terminal trades.
     */
    private static void applyResidentTradeAuditRetentionSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE resident_trade_transactions_new (
                        transaction_id TEXT PRIMARY KEY,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        buyer_uuid TEXT NOT NULL,
                        resident_npc_id TEXT NOT NULL,
                        material TEXT NOT NULL,
                        quality TEXT NOT NULL CHECK (quality IN (
                            'BASIC', 'STANDARD', 'FINE', 'MASTERWORK'
                        )),
                        amount INTEGER NOT NULL CHECK (amount BETWEEN 1 AND 1000000),
                        unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor >= 0),
                        total_price_minor INTEGER NOT NULL CHECK (total_price_minor >= 0),
                        status TEXT NOT NULL CHECK (status IN (
                            'STOCK_RESERVED', 'PAYMENT_PENDING', 'PAID', 'DELIVERED',
                            'CANCELLED', 'REFUND_PENDING', 'REFUNDED', 'REVIEW_REQUIRED'
                        )),
                        provider_reference TEXT,
                        last_error TEXT,
                        created_at INTEGER NOT NULL CHECK (created_at > 0),
                        updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
                        business_id TEXT,
                        profession TEXT,
                        CHECK (total_price_minor = unit_price_minor * amount)
                    )
                    """);
            statement.execute("""
                    INSERT INTO resident_trade_transactions_new(
                        transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                        material, quality, amount, unit_price_minor, total_price_minor,
                        status, provider_reference, last_error, created_at, updated_at,
                        business_id, profession
                    )
                    SELECT transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                           material, quality, amount, unit_price_minor, total_price_minor,
                           status, provider_reference, last_error, created_at, updated_at,
                           business_id, profession
                    FROM resident_trade_transactions
                    """);
            statement.execute("DROP TABLE resident_trade_transactions");
            statement.execute("ALTER TABLE resident_trade_transactions_new RENAME TO resident_trade_transactions");
            statement.execute("""
                    CREATE INDEX idx_resident_trade_buyer
                    ON resident_trade_transactions(buyer_uuid, created_at)
                    """);
            statement.execute("""
                    CREATE INDEX idx_resident_trade_status
                    ON resident_trade_transactions(status, updated_at)
                    """);
            statement.execute("""
                    CREATE INDEX idx_resident_trade_origin
                    ON resident_trade_transactions(business_id, profession)
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES (21)");
            connection.commit();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try { connection.rollback(); }
            catch (SQLException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreException) {
                if (failure != null) failure.addSuppressed(restoreException);
                else throw restoreException;
            }
        }
    }

}