package ru.raskolnpc.npc.resident;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * Moves AI-disabled resident entities in small, collision-checked teleport steps.
 * If the direct step is blocked, it tries two small side steps instead of
 * repeatedly pushing the resident into the same obstacle. All methods run on
 * the Bukkit primary thread and never load chunks.
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

        double directionX = dx / distance;
        double directionZ = dz / distance;
        double travel = Math.min(stepSize, distance);
        Location direct = step(current, directionX * travel, directionZ * travel);
        if (isSafeStep(world, direct)) return entity.teleport(direct);

        // A short lateral detour helps pass single-block obstructions without
        // introducing pathfinding, extra tasks, or chunk-loading side effects.
        double forward = Math.min(travel, stepSize * 0.5);
        double lateral = Math.min(stepSize, 0.75);
        Location left = step(current,
                directionX * forward - directionZ * lateral,
                directionZ * forward + directionX * lateral);
        if (isSafeStep(world, left) && entity.teleport(left)) return true;

        Location right = step(current,
                directionX * forward + directionZ * lateral,
                directionZ * forward - directionX * lateral);
        return isSafeStep(world, right) && entity.teleport(right);
    }

    private static Location step(Location current, double dx, double dz) {
        Location next = current.clone().add(dx, 0.0, dz);
        next.setYaw(current.getYaw());
        next.setPitch(current.getPitch());
        return next;
    }

    private static boolean isSafeStep(World world, Location next) {
        int chunkX = next.getBlockX() >> 4;
        int chunkZ = next.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) return false;

        Block feet = world.getBlockAt(next);
        Block head = world.getBlockAt(next.getBlockX(), next.getBlockY() + 1, next.getBlockZ());
        Block floor = world.getBlockAt(next.getBlockX(), next.getBlockY() - 1, next.getBlockZ());
        return feet.isPassable() && head.isPassable()
                && floor.getType() != Material.AIR
                && floor.getType().isSolid();
    }
}
