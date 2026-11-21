package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardCondition;
import ru.raskolnpc.npc.model.NpcId;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class SqliteGuardConditionRepository implements GuardConditionRepository {
    private final DatabaseManager database;

    public SqliteGuardConditionRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletableFuture<List<GuardCondition>> findAll() {
        return database.query(connection -> {
            List<GuardCondition> conditions = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT c.npc_id, c.morale, c.last_combat_at, c.updated_at,
                           c.injury_severity, c.last_injury_recovery_at
                    FROM guard_conditions c
                    INNER JOIN npcs n ON n.id = c.npc_id
                    WHERE n.type = 'GUARD'
                    ORDER BY c.npc_id
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conditions.add(new GuardCondition(
                            NpcId.fromString(result.getString("npc_id")),
                            result.getInt("morale"),
                            result.getLong("last_combat_at"),
                            result.getLong("updated_at"),
                            result.getInt("injury_severity"),
                            result.getLong("last_injury_recovery_at")));
                }
            }
            return List.copyOf(conditions);
        });
    }

    @Override
    public CompletableFuture<Void> save(GuardCondition condition) {
        Objects.requireNonNull(condition, "condition");
        return database.execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO guard_conditions(
                        npc_id, morale, last_combat_at, updated_at,
                        injury_severity, last_injury_recovery_at
                    )
                    SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (
                        SELECT 1 FROM npcs WHERE id = ? AND type = 'GUARD'
                    )
                    ON CONFLICT(npc_id) DO UPDATE SET
                        morale = excluded.morale,
                        last_combat_at = excluded.last_combat_at,
                        updated_at = excluded.updated_at,
                        injury_severity = excluded.injury_severity,
                        last_injury_recovery_at = excluded.last_injury_recovery_at
                    WHERE excluded.updated_at >= guard_conditions.updated_at
                    """)) {
                statement.setString(1, condition.npcId().toString());
                statement.setInt(2, condition.morale());
                statement.setLong(3, condition.lastCombatAt());
                statement.setLong(4, condition.updatedAt());
                statement.setInt(5, condition.injurySeverity());
                statement.setLong(6, condition.lastInjuryRecoveryAt());
                statement.setString(7, condition.npcId().toString());
                statement.executeUpdate();
            }
        });
    }
}
