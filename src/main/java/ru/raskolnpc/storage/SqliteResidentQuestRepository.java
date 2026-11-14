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

    public SqliteResidentQuestRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Boolean> accept(UUID playerId, NpcId residentId, String questId) {
        String normalizedQuestId = normalizeQuestId(questId);
        return database.transaction(connection -> {
            requireResident(connection, residentId);
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
    public CompletableFuture<Boolean> reserveSubmission(UUID playerId, NpcId residentId, String questId) {
        String id = normalizeQuestId(questId);
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO resident_quest_submissions (
                        player_uuid, resident_npc_id, quest_id, status, created_at, updated_at
                    )
                    SELECT ?, ?, ?, 'IN_PROGRESS', ?, ?
                    WHERE EXISTS (
                        SELECT 1 FROM resident_quest_assignments
                        WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                          AND status = 'ACCEPTED'
                    )
                    ON CONFLICT(player_uuid, resident_npc_id, quest_id) DO UPDATE SET
                        status = 'IN_PROGRESS',
                        reason = NULL,
                        created_at = excluded.created_at,
                        updated_at = excluded.updated_at
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
                statement.setString(6, playerId.toString());
                statement.setString(7, residentId.toString());
                statement.setString(8, id);
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
            try (var update = connection.prepareStatement("""
                    UPDATE resident_quest_assignments
                    SET status = 'COMPLETED', completed_at = ?
                    WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                      AND status = 'ACCEPTED'
                      AND EXISTS (
                          SELECT 1 FROM resident_quest_submissions
                          WHERE player_uuid = ? AND resident_npc_id = ? AND quest_id = ?
                            AND status = 'IN_PROGRESS'
                      )
                    """)) {
                update.setLong(1, now);
                update.setString(2, requirePlayer(playerId).toString());
                update.setString(3, residentId.toString());
                update.setString(4, id);
                update.setString(5, playerId.toString());
                update.setString(6, residentId.toString());
                update.setString(7, id);
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
            try (var update = connection.prepareStatement("""
                    INSERT INTO resident_reputation(player_uuid, points, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        points = resident_reputation.points + excluded.points,
                        updated_at = excluded.updated_at
                    """)) {
                update.setString(1, playerId.toString());
                update.setInt(2, reputationReward);
                update.setLong(3, now);
                update.executeUpdate();
            }
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
