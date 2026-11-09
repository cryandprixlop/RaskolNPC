package ru.raskolnpc.npc.model;

import ru.raskolnpc.npc.entity.NpcSpawnLocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record NpcRecord(
        NpcId id,
        NpcType type,
        String name,
        UUID ownerUuid,
        String townId,
        String nationId,
        NpcState state,
        String dataJson,
        String createdAt,
        String updatedAt,
        UUID entityUuid,
        NpcSpawnLocation spawnLocation
) {
    public NpcRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");

        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("NPC name must contain 1-64 characters.");
        }

        dataJson = dataJson == null || dataJson.isBlank() ? "{}" : dataJson;
        createdAt = createdAt == null ? Instant.now().toString() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static NpcRecord create(
            NpcType type, String name, UUID ownerUuid,
            String townId, String nationId, String dataJson
    ) {
        String now = Instant.now().toString();
        return new NpcRecord(NpcId.create(), type, name, ownerUuid, townId,
                nationId, NpcState.UNLOADED, dataJson, now, now, null, null);
    }

    public NpcRecord withState(NpcState newState) {
        return new NpcRecord(id, type, name, ownerUuid, townId, nationId,
                newState, dataJson, createdAt, Instant.now().toString(),
                entityUuid, spawnLocation);
    }

    public NpcRecord withEntityBinding(UUID newEntityUuid, NpcSpawnLocation location) {
        return new NpcRecord(id, type, name, ownerUuid, townId, nationId,
                NpcState.ACTIVE, dataJson, createdAt, Instant.now().toString(),
                newEntityUuid, location);
    }
}
