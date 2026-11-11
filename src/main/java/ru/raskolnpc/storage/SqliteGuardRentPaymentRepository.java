package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteGuardRentPaymentRepository implements GuardRentPaymentRepository {
    private static final String PAYMENT_COLUMNS = """
            payment_id, npc_id, rent_due_at, town_id, amount, status,
            target_debt_days, target_next_rent_at, failure_reason,
            resolved_by, resolved_at
            """;

    private final DatabaseManager database;

    public SqliteGuardRentPaymentRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Boolean> createIfAbsent(GuardRentPayment payment) {
        return database.transaction(connection -> {
            String sql = """
                    INSERT OR IGNORE INTO guard_rent_payments (
                        payment_id, npc_id, rent_due_at, town_id, amount, status,
                        target_debt_days, target_next_rent_at, failure_reason,
                        resolved_by, resolved_at
                    )
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (
                        SELECT 1 FROM npcs WHERE id = ? AND type = 'GUARD'
                    )
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindPayment(statement, payment);
                statement.setString(12, payment.npcId().toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<GuardRentPayment>> findById(String paymentId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PAYMENT_COLUMNS + " FROM guard_rent_payments WHERE payment_id = ?")) {
                statement.setString(1, paymentId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(mapPayment(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<GuardRentPayment>> findByDue(NpcId npcId, long rentDueAt) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT %s FROM guard_rent_payments WHERE npc_id = ? AND rent_due_at = ?
                    """.formatted(PAYMENT_COLUMNS))) {
                statement.setString(1, npcId.toString());
                statement.setLong(2, rentDueAt);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(mapPayment(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<GuardRentPayment>> findByStatus(GuardRentPaymentStatus status) {
        return database.query(connection -> {
            List<GuardRentPayment> payments = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PAYMENT_COLUMNS
                            + " FROM guard_rent_payments WHERE status = ? ORDER BY rent_due_at, payment_id")) {
                statement.setString(1, status.name());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        payments.add(mapPayment(result));
                    }
                }
            }
            return List.copyOf(payments);
        });
    }

    @Override
    public CompletableFuture<Boolean> markReviewRequired(String paymentId, String reason) {
        return transitionFromInProgress(paymentId, GuardRentPaymentStatus.REVIEW_REQUIRED, reason);
    }

    @Override
    public CompletableFuture<Boolean> markPaid(String paymentId) {
        return transitionFromInProgress(paymentId, GuardRentPaymentStatus.PAID, null);
    }

    @Override
    public CompletableFuture<Boolean> markFailed(String paymentId, String reason) {
        return transitionFromInProgress(paymentId, GuardRentPaymentStatus.FAILED, reason);
    }

    private CompletableFuture<Boolean> transitionFromInProgress(
            String paymentId, GuardRentPaymentStatus target, String reason
    ) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_rent_payments
                    SET status = ?, failure_reason = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND status = 'IN_PROGRESS'
                    """)) {
                statement.setString(1, target.name());
                setNullable(statement, 2, reason);
                statement.setString(3, paymentId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> moveUnfinishedToReview(String reason) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_rent_payments
                    SET status = 'REVIEW_REQUIRED', failure_reason = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE status = 'IN_PROGRESS'
                    """)) {
                setNullable(statement, 1, reason);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> applyPaid(String paymentId, NpcRecord updatedRecord) {
        return applyNpcRecord(paymentId, GuardRentPaymentStatus.PAID,
                GuardRentPaymentStatus.PAID_APPLIED, updatedRecord);
    }

    @Override
    public CompletableFuture<Boolean> applyDebt(String paymentId, NpcRecord updatedRecord) {
        return applyNpcRecord(paymentId, GuardRentPaymentStatus.FAILED,
                GuardRentPaymentStatus.DEBT_APPLIED, updatedRecord);
    }

    private CompletableFuture<Boolean> applyNpcRecord(
            String paymentId,
            GuardRentPaymentStatus expectedStatus,
            GuardRentPaymentStatus appliedStatus,
            NpcRecord record
    ) {
        return database.transaction(connection -> {
            updateNpc(connection, record);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_rent_payments
                    SET status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND npc_id = ? AND status = ?
                    """)) {
                statement.setString(1, appliedStatus.name());
                statement.setString(2, paymentId);
                statement.setString(3, record.id().toString());
                statement.setString(4, expectedStatus.name());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Rent payment status changed before application: " + paymentId);
                }
            }
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> applyDebtAndQueueDeletion(String paymentId, NpcId npcId) {
        return database.transaction(connection -> {
            boolean npcExists;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM npcs WHERE id = ?")) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    npcExists = result.next();
                }
            }

            if (npcExists) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO pending_npc_deletions(npc_id)
                        SELECT id FROM npcs WHERE id = ?
                        """)) {
                    statement.setString(1, npcId.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM npcs WHERE id = ?")) {
                    statement.setString(1, npcId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Could not delete guard NPC " + npcId);
                    }
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM pending_npc_deletions WHERE npc_id = ?")) {
                    statement.setString(1, npcId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Guard NPC is absent and has no pending deletion: " + npcId);
                        }
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_rent_payments
                    SET status = 'DEBT_APPLIED', updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND npc_id = ? AND status = 'FAILED'
                    """)) {
                statement.setString(1, paymentId);
                statement.setString(2, npcId.toString());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Rent debt payment is not in FAILED state: " + paymentId);
                }
            }
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> resolveReview(
            String paymentId,
            GuardRentPaymentStatus resolution,
            UUID administratorUuid
    ) {
        if (resolution != GuardRentPaymentStatus.PAID
                && resolution != GuardRentPaymentStatus.FAILED) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Review can only be resolved as PAID or FAILED."));
        }
        if (administratorUuid == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Administrator UUID must not be null."));
        }

        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_rent_payments
                    SET status = ?, resolved_by = ?, resolved_at = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND status = 'REVIEW_REQUIRED'
                    """)) {
                statement.setString(1, resolution.name());
                statement.setString(2, administratorUuid.toString());
                statement.setString(3, Instant.now().toString());
                statement.setString(4, paymentId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static void updateNpc(Connection connection, NpcRecord record) throws SQLException {
        String sql = """
                UPDATE npcs SET type = ?, name = ?, owner_uuid = ?, town_id = ?,
                    nation_id = ?, state = ?, data_json = ?, entity_uuid = ?,
                    spawn_world_uuid = ?, spawn_x = ?, spawn_y = ?, spawn_z = ?,
                    spawn_yaw = ?, spawn_pitch = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.type().name());
            statement.setString(2, record.name());
            setNullable(statement, 3, record.ownerUuid() == null
                    ? null : record.ownerUuid().toString());
            setNullable(statement, 4, record.townId());
            setNullable(statement, 5, record.nationId());
            statement.setString(6, record.state().name());
            statement.setString(7, record.dataJson());
            NpcSpawnLocation location = record.spawnLocation();
            setNullable(statement, 8, record.entityUuid() == null
                    ? null : record.entityUuid().toString());
            setNullable(statement, 9, location == null ? null : location.worldId().toString());
            if (location == null) {
                for (int index = 10; index <= 14; index++) {
                    statement.setNull(index, Types.REAL);
                }
            } else {
                statement.setDouble(10, location.x());
                statement.setDouble(11, location.y());
                statement.setDouble(12, location.z());
                statement.setFloat(13, location.yaw());
                statement.setFloat(14, location.pitch());
            }
            statement.setString(15, record.id().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("NPC record does not exist: " + record.id());
            }
        }
    }

    private static void bindPayment(PreparedStatement statement, GuardRentPayment payment)
            throws SQLException {
        statement.setString(1, payment.paymentId());
        statement.setString(2, payment.npcId().toString());
        statement.setLong(3, payment.rentDueAt());
        statement.setString(4, payment.townId());
        statement.setDouble(5, payment.amount());
        statement.setString(6, payment.status().name());
        statement.setInt(7, payment.targetDebtDays());
        statement.setLong(8, payment.targetNextRentAt());
        setNullable(statement, 9, payment.failureReason());
        setNullable(statement, 10, payment.resolvedBy());
        setNullable(statement, 11, payment.resolvedAt());
    }

    private static GuardRentPayment mapPayment(ResultSet result) throws SQLException {
        return new GuardRentPayment(
                result.getString("payment_id"),
                NpcId.fromString(result.getString("npc_id")),
                result.getLong("rent_due_at"),
                result.getString("town_id"),
                result.getDouble("amount"),
                GuardRentPaymentStatus.valueOf(result.getString("status")),
                result.getInt("target_debt_days"),
                result.getLong("target_next_rent_at"),
                result.getString("failure_reason"),
                result.getString("resolved_by"),
                result.getString("resolved_at")
        );
    }

    private static void setNullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
