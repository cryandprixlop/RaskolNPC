package ru.raskolnpc.npc.resident;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * Moves AI-disabled resident entities in small, collision-checked teleport steps.
 * All methods must be called on the Bukkit primary thread.
 */
public final class ResidentMovementController {
    private final double stepSize;

    public ResidentMovementController(double stepSize) {
        if (!Double.isFinite(stepSize) || stepSize <= 0.0 || stepSize > 1.5) {
            throw new IllegalArgumentException("stepSize must be in (0, 1.5].");
        }
        this.stepSize = stepSize;
    }

    /**
     * Advances an entity toward a destination without loading chunks or enabling AI.
     * Returns true once the entity is within the arrival radius.
     */
    public boolean moveTowards(Entity entity, Location destination) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(destination, "destination");
        Location current = entity.getLocation();
        World world = current.getWorld();
        if (world == null || destination.getWorld() == null
                || !world.getUID().equals(destination.getWorld().getUID())) {
            return false;
        }
        if (Math.abs(destination.getY() - current.getY()) > 1.0) return false;

        double dx = destination.getX() - current.getX();
        double dz = destination.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.35) return true;

        double travel = Math.min(stepSize, distance);
        Location next = current.clone().add(
                new Vector(dx / distance * travel, 0.0, dz / distance * travel));
        next.setYaw(current.getYaw());
        next.setPitch(current.getPitch());

        if (!world.isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) return false;
        Block feet = world.getBlockAt(next);
        Block head = world.getBlockAt(next.getBlockX(), next.getBlockY() + 1, next.getBlockZ());
        Block floor = world.getBlockAt(next.getBlockX(), next.getBlockY() - 1, next.getBlockZ());
        if (!feet.isPassable() || !head.isPassable() || !floor.getType().isSolid()) return false;

        return entity.teleport(next);
    }
}
