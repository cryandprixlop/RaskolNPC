package ru.raskolnpc.npc.entity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

public record NpcSpawnLocation(UUID worldId, double x, double y, double z,
                               float yaw, float pitch) {
    public NpcSpawnLocation {
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Spawn location coordinates must be finite.");
        }
    }

    public static NpcSpawnLocation from(Location location) {
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Spawn location must belong to a world.");
        }
        return new NpcSpawnLocation(location.getWorld().getUID(), location.getX(),
                location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location resolve() {
        World world = Bukkit.getWorld(worldId);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }
}
