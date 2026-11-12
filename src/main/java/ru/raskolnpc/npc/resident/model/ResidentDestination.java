package ru.raskolnpc.npc.resident.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Objects;
import java.util.UUID;

/** A named world location assigned to one resident and one destination role. */
public record ResidentDestination(
        NpcId npcId,
        DestinationType type,
        UUID worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String label
) {
    public ResidentDestination {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Destination coordinates must be finite.");
        }
        if (label != null && label.length() > 64) {
            throw new IllegalArgumentException("Destination label must not exceed 64 characters.");
        }
        label = label == null || label.isBlank() ? null : label.trim();
    }

    public enum DestinationType {
        HOME,
        WORK,
        MARKET,
        COMMUNITY
    }
}
