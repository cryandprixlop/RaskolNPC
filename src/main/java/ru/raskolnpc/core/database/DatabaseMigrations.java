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
        if (!isApplied(connection, 5)) applyGuardHiringPaymentSchema(connection);
        if (!isApplied(connection, 6)) applyResidentProfileSchema(connection);
        if (!isApplied(connection, 7)) applyResidentScheduleSchema(connection);
        if (!isApplied(connection, 8)) applyResidentQuestSchema(connection);
        if (!isApplied(connection, 9)) applyResidentQuestSubmissionSchema(connection);
        if (!isApplied(connection, 10)) applyResidentQuestReviewRewardSchema(connection);
        if (!isApplied(connection, 11)) applyResidentBusinessStockSchema(connection);
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

}
