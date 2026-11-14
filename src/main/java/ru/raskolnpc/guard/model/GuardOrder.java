package ru.raskolnpc.guard.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable tactical instruction assigned to one kingdom guard. */
public record GuardOrder(
        NpcId npcId,
        Type type,
        List<Waypoint> waypoints,
        UUID issuedBy,
        long updatedAt
) {
    public GuardOrder {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(issuedBy, "issuedBy");
        waypoints = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
        if (waypoints.size() > 16) {
            throw new IllegalArgumentException("A guard order cannot contain more than 16 waypoints.");
        }
        if (updatedAt <= 0) throw new IllegalArgumentException("updatedAt must be positive.");
    }

    public enum Type {
        HOLD_POSITION,
        PATROL,
        DEFEND,
        ESCORT,
        RETURN_TO_POST
    }

    public record Waypoint(UUID worldId, double x, double y, double z, float yaw, float pitch) {
        public Waypoint {
            Objects.requireNonNull(worldId, "worldId");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Waypoint coordinates and rotation must be finite.");
            }
        }
    }
}
