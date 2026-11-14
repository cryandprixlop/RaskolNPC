package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.model.NpcId;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteGuardSquadRepository implements GuardSquadRepository {
    private final DatabaseManager database;

    public SqliteGuardSquadRepository(DatabaseManager database) {
        this.database = java.util.Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletableFuture<Boolean> create(UUID id, String name, UUID creator, long createdAt) {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO guard_squads(squad_id, name, created_by, created_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, id.toString());
                statement.setString(2, name);
                statement.setString(3, creator.toString());
                statement.setLong(4, createdAt);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> addMember(UUID squadId, NpcId guardId, int maximumMembers) {
        return database.transaction(connection -> {
            try (var guard = connection.prepareStatement(
                    "SELECT 1 FROM npcs WHERE id = ? AND type = 'GUARD'")) {
                guard.setString(1, guardId.toString());
                try (ResultSet result = guard.executeQuery()) {
                    if (!result.next()) return false;
                }
            }
            try (var count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM guard_squad_members WHERE squad_id = ?")) {
                count.setString(1, squadId.toString());
                try (ResultSet result = count.executeQuery()) {
                    if (!result.next() || result.getInt(1) >= maximumMembers) return false;
                }
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO guard_squad_members(squad_id, npc_id)
                    SELECT ?, ? WHERE EXISTS (
                        SELECT 1 FROM guard_squads WHERE squad_id = ?
                    )
                    """)) {
                insert.setString(1, squadId.toString());
                insert.setString(2, guardId.toString());
                insert.setString(3, squadId.toString());
                return insert.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removeMember(NpcId guardId) {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM guard_squad_members WHERE npc_id = ?")) {
                statement.setString(1, guardId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID squadId) {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM guard_squads WHERE squad_id = ?")) {
                statement.setString(1, squadId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<List<GuardSquad>> findAll() {
        return database.query(connection -> {
            List<GuardSquad> squads = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT squad_id, name, created_by, created_at
                    FROM guard_squads ORDER BY created_at, name
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = UUID.fromString(result.getString("squad_id"));
                    List<NpcId> members = new ArrayList<>();
                    try (var memberQuery = connection.prepareStatement("""
                            SELECT npc_id FROM guard_squad_members
                            WHERE squad_id = ? ORDER BY npc_id
                            """)) {
                        memberQuery.setString(1, id.toString());
                        try (ResultSet memberRows = memberQuery.executeQuery()) {
                            while (memberRows.next()) members.add(NpcId.fromString(memberRows.getString(1)));
                        }
                    }
                    squads.add(new GuardSquad(id, result.getString("name"),
                            UUID.fromString(result.getString("created_by")),
                            result.getLong("created_at"), members));
                }
            }
            return List.copyOf(squads);
        });
    }
}