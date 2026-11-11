package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardHiringPayment;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteGuardHiringPaymentRepository implements GuardHiringPaymentRepository {
    private static final String COLUMNS = """
            payment_id, npc_id, owner_uuid, town_id, nation_id, guard_name, level,
            hire_cost, daily_rent, status, failure_reason, resolved_by, resolved_at
            """;
    private final DatabaseManager database;

    public SqliteGuardHiringPaymentRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Boolean> create(GuardHiringPayment payment) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO guard_hiring_payments (
                        payment_id, npc_id, owner_uuid, town_id, nation_id, guard_name,
                        level, hire_cost, daily_rent, status, failure_reason, resolved_by, resolved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, payment.paymentId());
                statement.setString(2, payment.npcId());
                statement.setString(3, payment.ownerUuid().toString());
                statement.setString(4, payment.townId());
                statement.setString(5, payment.nationId());
                statement.setString(6, payment.guardName());
                statement.setInt(7, payment.level());
                statement.setDouble(8, payment.hireCost());
                statement.setDouble(9, payment.dailyRent());
                statement.setString(10, payment.status());
                statement.setString(11, payment.failureReason());
                statement.setString(12, payment.resolvedBy());
                statement.setString(13, payment.resolvedAt());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> attachNpcId(String paymentId, String npcId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_hiring_payments SET npc_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND npc_id IS NULL
                    """)) {
                statement.setString(1, npcId);
                statement.setString(2, paymentId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> transition(String paymentId, String expectedStatus,
                                                  String newStatus, String reason) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_hiring_payments SET status = ?, failure_reason = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND status = ?
                    """)) {
                statement.setString(1, newStatus);
                statement.setString(2, reason);
                statement.setString(3, paymentId);
                statement.setString(4, expectedStatus);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<GuardHiringPayment>> findById(String paymentId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM guard_hiring_payments WHERE payment_id = ?")) {
                statement.setString(1, paymentId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<GuardHiringPayment>> findReviewRequired() {
        return database.query(connection -> {
            List<GuardHiringPayment> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM guard_hiring_payments WHERE status = 'REVIEW_REQUIRED' ORDER BY created_at")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(map(result));
                }
            }
            return List.copyOf(values);
        });
    }

    @Override
    public CompletableFuture<Integer> moveUnfinishedToReview(String reason) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_hiring_payments SET status = 'REVIEW_REQUIRED',
                        failure_reason = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE status IN ('IN_PROGRESS', 'DEBIT_CONFIRMED', 'REFUND_IN_PROGRESS')
                    """)) {
                statement.setString(1, reason);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> resolveReview(String paymentId, String resolution,
                                                      UUID administratorUuid) {
        if (!"DEBIT_CONFIRMED".equals(resolution) && !"DEBIT_FAILED".equals(resolution)
                && !"REFUNDED".equals(resolution)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported resolution."));
        }
        if (administratorUuid == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Administrator UUID is required."));
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE guard_hiring_payments SET status = ?, resolved_by = ?,
                        resolved_at = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE payment_id = ? AND status = 'REVIEW_REQUIRED'
                    """)) {
                statement.setString(1, resolution);
                statement.setString(2, administratorUuid.toString());
                statement.setString(3, Instant.now().toString());
                statement.setString(4, paymentId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static GuardHiringPayment map(ResultSet result) throws java.sql.SQLException {
        return new GuardHiringPayment(
                result.getString("payment_id"), result.getString("npc_id"),
                UUID.fromString(result.getString("owner_uuid")), result.getString("town_id"),
                result.getString("nation_id"), result.getString("guard_name"),
                result.getInt("level"), result.getDouble("hire_cost"),
                result.getDouble("daily_rent"), result.getString("status"),
                result.getString("failure_reason"), result.getString("resolved_by"),
                result.getString("resolved_at"));
    }
}
