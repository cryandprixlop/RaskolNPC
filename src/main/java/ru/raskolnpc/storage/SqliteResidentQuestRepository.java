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
