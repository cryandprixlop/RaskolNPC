package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class SqliteGuardEquipmentRepository implements GuardEquipmentRepository {
    private final DatabaseManager database;
    public SqliteGuardEquipmentRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }
    @Override
    public CompletableFuture<Void> save(NpcId id, byte[] snapshot) {
        Objects.requireNonNull(id, "id");
        byte[] copy = Objects.requireNonNull(snapshot, "snapshot").clone();
        return database.execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO guard_equipment(npc_id, equipment_blob, updated_at)
                    SELECT ?, ?, ? WHERE EXISTS (
                        SELECT 1 FROM npcs WHERE id = ? AND type = 'GUARD'
                    )
                    ON CONFLICT(npc_id) DO UPDATE SET
                        equipment_blob = excluded.equipment_blob,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, id.toString());
                statement.setBytes(2, copy);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, id.toString());
                statement.executeUpdate();
            }
        });
    }
    @Override
    public CompletableFuture<Optional<byte[]>> find(NpcId id) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT equipment_blob FROM guard_equipment WHERE npc_id = ?")) {
                statement.setString(1, id.toString());
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getBytes(1)) : Optional.empty();
                }
            }
        });
    }
}
