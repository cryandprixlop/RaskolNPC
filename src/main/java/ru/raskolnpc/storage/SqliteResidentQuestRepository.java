package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.quest.ResidentQuest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteResidentQuestRepository implements ResidentQuestRepository {
    private final DatabaseManager database;

    private record SubmissionSnapshot(
            Integer reputationReward, String stockMaterial,
            Integer stockAmount, Integer stockCapacity) { }

    private record BusinessIdentity(String businessId, String profession) { }

    public SqliteResidentQuestRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Boolean> accept(UUID playerId, NpcId residentId, String questId,
            String requiredMaterial, int requiredAmount, int stockTarget) {
        String normalizedQuestId = normalizeQuestId(questId);
        String material = normalizeMaterial(requiredMaterial);
        if (requiredAmount < 1 || stockTarget < 0) {
            throw new IllegalArgumentException("Invalid quest stock requirements.");
        }
        return database.transaction(connection -> {
            requireResident(connection, residentId);
            if (stockTarget > 0) {
                BusinessIdentity business = businessIdentity(connection, residentId);
                int currentStock = readBusinessStock(connection, business, material);
                if ((long) currentStock + requiredAmount > stockTarget) return false;
            }
            try (var statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO resident_quest_assignments (
                        player_uuid, resident_npc_id, quest_id, status, accepted_at
                    ) VALUES (?, ?, ?, 'ACCEPTED', ?)
                    """)) {
                statement.setString(1, requirePlayer(playerId).toString());
                statement.setString(2, residentId.toString());
                statement.setString(3, normalizedQuestId);
                statement.setLong(4, System.currentTimeMillis());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ResidentQuest.Assignment>> findActive(
            UUID playerId, NpcId residentId, String questId) {
        String normalizedQuestId = normalizeQuestId(questId);
        return database.query(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT player_uuid, resident_npc_id, quest_id, status, accepted_at, completed_at
                    FROM resident_quest_assignments
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'ACCEPTED'
                    """)) {
                statement.setString(1, requirePlayer(playerId).toString());
                statement.setString(2, residentId.toString());
                statement.setString(3, normalizedQuestId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(readAssignment(result))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<ResidentQuest.Assignment>> findActiveForPlayer(UUID playerId) {
        return database.query(connection -> {
            List<ResidentQuest.Assignment> assignments = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT player_uuid, resident_npc_id, quest_id, status, accepted_at, completed_at
                    FROM resident_quest_assignments
                    WHERE player_uuid = ? AND status = 'ACCEPTED'
                    ORDER BY accepted_at, resident_npc_id, quest_id
                    """)) {
                statement.setString(1, requirePlayer(playerId).toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) assignments.add(readAssignment(result));
                }
            }
            return List.copyOf(assignments);
        });
    }

    @Override
    public CompletableFuture<Boolean> complete(UUID playerId, NpcId residentId, String questId) {
        String normalizedQuestId = normalizeQuestId(questId);
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE resident_quest_assignments
                    SET status = 'COMPLETED', completed_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'ACCEPTED'
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, requirePlayer(playerId).toString());
                statement.setString(3, residentId.toString());
                statement.setString(4, normalizedQuestId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> reserveSubmission(UUID playerId, NpcId residentId, String questId,
            String stockMaterial, int reputationReward, int stockAmount, int stockCapacity) {
        String id = normalizeQuestId(questId);
        String material = normalizeMaterial(stockMaterial);
        if (reputationReward < 0 || stockAmount < 0 || stockCapacity < 0
                || (stockCapacity > 0 && stockAmount > stockCapacity)) {
            throw new IllegalArgumentException("Invalid resident quest reward or stock snapshot.");
        }
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO resident_quest_submissions (
                        player_uuid, resident_npc_id, quest_id, status, created_at, updated_at,
                        reputation_reward, stock_material, stock_amount, stock_capacity
                    )
                    SELECT ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (
                        SELECT 1 FROM resident_quest_assignments
                        WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                          AND status = 'ACCEPTED'
                    )
                    ON CONFLICT(player_uuid, resident_npc_id, quest_id) DO UPDATE SET
                        status = 'IN_PROGRESS',
                        reason = NULL,
                        created_at = excluded.created_at,
                        updated_at = excluded.updated_at,
                        reputation_reward = excluded.reputation_reward,
                        stock_material = excluded.stock_material,
                        stock_amount = excluded.stock_amount,
                        stock_capacity = excluded.stock_capacity
                    WHERE resident_quest_submissions.status = 'COMPLETED'
                      AND EXISTS (
                          SELECT 1 FROM resident_quest_assignments
                          WHERE player_uuid = excluded.player_uuid
                            AND resident_npc_id = excluded.resident_npc_id
                            AND quest_id = excluded.quest_id
                            AND status = 'ACCEPTED'
                      )
                    """)) {
                long now = System.currentTimeMillis();
                statement.setString(1, requirePlayer(playerId).toString());
                statement.setString(2, residentId.toString());
                statement.setString(3, id);
                statement.setLong(4, now);
                statement.setLong(5, now);
                statement.setInt(6, reputationReward);
                statement.setString(7, material);
                statement.setInt(8, stockAmount);
                statement.setInt(9, stockCapacity);
                statement.setString(10, playerId.toString());
                statement.setString(11, residentId.toString());
                statement.setString(12, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> releaseSubmission(UUID playerId, NpcId residentId, String questId) {
        String id = normalizeQuestId(questId);
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    DELETE FROM resident_quest_submissions
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'IN_PROGRESS'
                    """)) {
                statement.setString(1, requirePlayer(playerId).toString());
                statement.setString(2, residentId.toString());
                statement.setString(3, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> completeSubmission(
            UUID playerId, NpcId residentId, String questId, int reputationReward) {
        String id = normalizeQuestId(questId);
        if (reputationReward < 0) throw new IllegalArgumentException("Reputation reward cannot be negative.");
        return database.transaction(connection -> {
            long now = System.currentTimeMillis();
            SubmissionSnapshot snapshot = readSubmissionSnapshot(
                    connection, playerId, residentId, id, "IN_PROGRESS");
            if (snapshot == null) return false;
            try (var update = connection.prepareStatement("""
                    UPDATE resident_quest_assignments
                    SET status = 'COMPLETED', completed_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'ACCEPTED'
                    """)) {
                update.setLong(1, now);
                update.setString(2, requirePlayer(playerId).toString());
                update.setString(3, residentId.toString());
                update.setString(4, id);
                if (update.executeUpdate() != 1) return false;
            }
            try (var update = connection.prepareStatement("""
                    UPDATE resident_quest_submissions
                    SET status = 'COMPLETED', reason = NULL, updated_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'IN_PROGRESS'
                    """)) {
                update.setLong(1, now);
                update.setString(2, playerId.toString());
                update.setString(3, residentId.toString());
                update.setString(4, id);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Submission reservation changed before completion.");
                }
            }
            int reward = snapshot.reputationReward() == null
                    ? reputationReward : snapshot.reputationReward();
            creditReputation(connection, playerId, reward, now);
            addBusinessStock(connection, residentId, snapshot.stockMaterial(),
                    snapshot.stockAmount(), snapshot.stockCapacity(), now);
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> markSubmissionForReview(
            UUID playerId, NpcId residentId, String questId, String reason) {
        String id = normalizeQuestId(questId);
        String safeReason = reason == null ? "Uncertain submission result." : reason;
        if (safeReason.length() > 500) safeReason = safeReason.substring(0, 500);
        final String finalReason = safeReason;
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE resident_quest_submissions
                    SET status = 'REVIEW_REQUIRED', reason = ?, updated_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'IN_PROGRESS'
                    """)) {
                statement.setString(1, finalReason);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, requirePlayer(playerId).toString());
                statement.setString(4, residentId.toString());
                statement.setString(5, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<List<ResidentQuestReview>> findReviews(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return database.query(connection -> {
            List<ResidentQuestReview> reviews = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT player_uuid, resident_npc_id, quest_id, reason, updated_at, reputation_reward
                    FROM resident_quest_submissions
                    WHERE status = 'REVIEW_REQUIRED'
                    ORDER BY updated_at, player_uuid
                    LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        int rewardValue = result.getInt("reputation_reward");
                        Integer reward = result.wasNull() ? null : rewardValue;
                        reviews.add(new ResidentQuestReview(
                                UUID.fromString(result.getString("player_uuid")),
                                NpcId.fromString(result.getString("resident_npc_id")),
                                result.getString("quest_id"),
                                result.getString("reason"),
                                Instant.ofEpochMilli(result.getLong("updated_at")),
                                reward));
                    }
                }
            }
            return List.copyOf(reviews);
        });
    }

    @Override
    public CompletableFuture<Boolean> resolveReview(UUID playerId, NpcId residentId,
            String questId, boolean complete, int legacyReward) {
        String id = normalizeQuestId(questId);
        if (legacyReward < 0) throw new IllegalArgumentException("Legacy reward cannot be negative.");
        return database.transaction(connection -> {
            SubmissionSnapshot snapshot = readSubmissionSnapshot(
                    connection, playerId, residentId, id, "REVIEW_REQUIRED");
            if (snapshot == null) return false;

            long now = System.currentTimeMillis();
            if (!complete) {
                try (var delete = connection.prepareStatement("""
                        DELETE FROM resident_quest_submissions
                        WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                          AND status = 'REVIEW_REQUIRED'
                          AND EXISTS (
                              SELECT 1 FROM resident_quest_assignments
                              WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                                AND status = 'ACCEPTED'
                          )
                        """)) {
                    delete.setString(1, playerId.toString());
                    delete.setString(2, residentId.toString());
                    delete.setString(3, id);
                    delete.setString(4, playerId.toString());
                    delete.setString(5, residentId.toString());
                    delete.setString(6, id);
                    return delete.executeUpdate() == 1;
                }
            }

            try (var update = connection.prepareStatement("""
                    UPDATE resident_quest_assignments
                    SET status = 'COMPLETED', completed_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'ACCEPTED'
                    """)) {
                update.setLong(1, now);
                update.setString(2, playerId.toString());
                update.setString(3, residentId.toString());
                update.setString(4, id);
                if (update.executeUpdate() != 1) return false;
            }
            try (var update = connection.prepareStatement("""
                    UPDATE resident_quest_submissions
                    SET status = 'COMPLETED', reason = NULL, updated_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'REVIEW_REQUIRED'
                    """)) {
                update.setLong(1, now);
                update.setString(2, playerId.toString());
                update.setString(3, residentId.toString());
                update.setString(4, id);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Review state changed during administrative resolution.");
                }
            }
            int reward = snapshot.reputationReward() == null
                    ? legacyReward : snapshot.reputationReward();
            creditReputation(connection, playerId, reward, now);
            addBusinessStock(connection, residentId, snapshot.stockMaterial(),
                    snapshot.stockAmount(), snapshot.stockCapacity(), now);
            return true;
        });
    }

    @Override
    public CompletableFuture<Integer> getReputation(UUID playerId) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT points FROM resident_reputation WHERE player_uuid = ?")) {
                statement.setString(1, requirePlayer(playerId).toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt("points") : 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getBusinessStock(NpcId residentId, String material) {
        String normalizedMaterial = normalizeMaterial(material);
        return database.query(connection -> {
            BusinessIdentity business = businessIdentity(connection, residentId);
            return readBusinessStock(connection, business, normalizedMaterial);
        });
    }

    @Override
    public CompletableFuture<Boolean> withdrawBusinessStock(
            NpcId residentId, String material, int amount) {
        String normalizedMaterial = normalizeMaterial(material);
        if (amount < 1 || amount > 1_000_000) {
            throw new IllegalArgumentException("Stock withdrawal amount must be between 1 and 1000000.");
        }
        return database.transaction(connection -> {
            BusinessIdentity business = businessIdentity(connection, residentId);
            try (var update = connection.prepareStatement("""
                    UPDATE resident_business_stock
                    SET amount = amount - ?, updated_at = ?
                    WHERE business_id = ? AND profession = ? AND material = ?
                      AND amount >= ?
                    """)) {
                long now = System.currentTimeMillis();
                update.setInt(1, amount);
                update.setLong(2, now);
                update.setString(3, business.businessId());
                update.setString(4, business.profession());
                update.setString(5, normalizedMaterial);
                update.setInt(6, amount);
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> recoverUnfinishedSubmissions() {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE resident_quest_submissions
                    SET status = 'REVIEW_REQUIRED',
                        reason = 'Server restarted before submission was finalized.',
                        updated_at = ?
                    WHERE status = 'IN_PROGRESS'
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM resident_quest_submissions WHERE status = 'REVIEW_REQUIRED'")) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        });
    }

    private static SubmissionSnapshot readSubmissionSnapshot(Connection connection,
            UUID playerId, NpcId residentId, String questId, String status) throws SQLException {
        try (var select = connection.prepareStatement("""
                SELECT reputation_reward, stock_material, stock_amount, stock_capacity
                FROM resident_quest_submissions
                WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ? AND status = ?
                """)) {
            select.setString(1, requirePlayer(playerId).toString());
            select.setString(2, residentId.toString());
            select.setString(3, questId);
            select.setString(4, status);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) return null;
                int rewardValue = result.getInt("reputation_reward");
                Integer reward = result.wasNull() ? null : rewardValue;
                String stockMaterial = result.getString("stock_material");
                int amountValue = result.getInt("stock_amount");
                Integer stockAmount = result.wasNull() ? null : amountValue;
                int capacityValue = result.getInt("stock_capacity");
                Integer stockCapacity = result.wasNull() ? null : capacityValue;
                return new SubmissionSnapshot(reward, stockMaterial, stockAmount, stockCapacity);
            }
        }
    }

    private static void creditReputation(Connection connection, UUID playerId,
            int reward, long now) throws SQLException {
        try (var update = connection.prepareStatement("""
                INSERT INTO resident_reputation(player_uuid, points, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    points = resident_reputation.points + excluded.points,
                    updated_at = excluded.updated_at
                """)) {
            update.setString(1, requirePlayer(playerId).toString());
            update.setInt(2, reward);
            update.setLong(3, now);
            update.executeUpdate();
        }
    }

    private static void addBusinessStock(Connection connection, NpcId residentId,
            String material, Integer amount, Integer capacity, long now) throws SQLException {
        if (material == null || amount == null || capacity == null
                || amount <= 0 || capacity <= 0) return;
        BusinessIdentity business = businessIdentity(connection, residentId);
        try (var update = connection.prepareStatement("""
                INSERT INTO resident_business_stock(
                    business_id, profession, material, amount, capacity, updated_at
                ) VALUES (?, ?, ?, MIN(?, ?), ?, ?)
                ON CONFLICT(business_id, profession, material) DO UPDATE SET
                    amount = MIN(resident_business_stock.amount + excluded.amount, excluded.capacity),
                    capacity = excluded.capacity,
                    updated_at = excluded.updated_at
                """)) {
            update.setString(1, business.businessId());
            update.setString(2, business.profession());
            update.setString(3, normalizeMaterial(material));
            update.setInt(4, amount);
            update.setInt(5, capacity);
            update.setInt(6, capacity);
            update.setLong(7, now);
            update.executeUpdate();
        }
    }

    private static BusinessIdentity businessIdentity(Connection connection, NpcId residentId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT n.town_id, rp.profession
                FROM npcs n
                JOIN resident_profiles rp ON rp.npc_id = n.id
                WHERE n.id = ?
                """)) {
            statement.setString(1, residentId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Resident profile does not exist: " + residentId);
                String townId = result.getString("town_id");
                String businessId = townId == null || townId.isBlank()
                        ? "resident:" + residentId : "town:" + townId;
                return new BusinessIdentity(businessId, result.getString("profession"));
            }
        }
    }

    private static int readBusinessStock(Connection connection, BusinessIdentity business,
            String material) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT amount FROM resident_business_stock
                WHERE business_id = ? AND profession = ? AND material = ?
                """)) {
            statement.setString(1, business.businessId());
            statement.setString(2, business.profession());
            statement.setString(3, normalizeMaterial(material));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("amount") : 0;
            }
        }
    }

    private static String normalizeMaterial(String material) {
        if (material == null) throw new IllegalArgumentException("Material cannot be null.");
        String normalized = material.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid material key: " + material);
        }
        return normalized;
    }

    private static ResidentQuest.Assignment readAssignment(ResultSet result) throws SQLException {
        long completedAt = result.getLong("completed_at");
        Instant completed = result.wasNull() ? null : Instant.ofEpochMilli(completedAt);
        return new ResidentQuest.Assignment(
                UUID.fromString(result.getString("player_uuid")),
                NpcId.fromString(result.getString("resident_npc_id")),
                result.getString("quest_id"),
                ResidentQuest.AssignmentStatus.valueOf(result.getString("status")),
                Instant.ofEpochMilli(result.getLong("accepted_at")),
                completed
        );
    }

    private static void requireResident(Connection connection, NpcId residentId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM resident_profiles WHERE npc_id = ?")) {
            statement.setString(1, residentId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Resident profile does not exist: " + residentId);
                }
            }
        }
    }

    private static UUID requirePlayer(UUID playerId) {
        if (playerId == null) throw new IllegalArgumentException("playerId cannot be null.");
        return playerId;
    }

    private static String normalizeQuestId(String questId) {
        if (questId == null) throw new IllegalArgumentException("questId cannot be null.");
        String normalized = questId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid quest id: " + questId);
        }
        return normalized;
    }
}
