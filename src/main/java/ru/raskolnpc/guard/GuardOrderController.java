package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Executes persisted guard movement orders on one shared, bounded scheduler.
 * It never loads chunks and advances movement only through passable blocks.
 */
public final class GuardOrderController {
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final GuardOrderService orders;
    private final int batchSize;
    private final double movementStep;
    private final Map<ru.raskolnpc.npc.model.NpcId, Integer> patrolIndices =
            new ConcurrentHashMap<>();
    private BukkitTask task;
    private int cursor;

    public GuardOrderController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, GuardOrderService orders,
            int batchSize, double movementStep) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.batchSize = Math.max(1, batchSize);
        if (!Double.isFinite(movementStep) || movementStep <= 0 || movementStep > 1.5) {
            throw new IllegalArgumentException("movementStep must be greater than 0 and at most 1.5.");
        }
        this.movementStep = movementStep;
    }

    public void start() {
        requireMainThread();
        if (task != null) return;
        orders.loadAll().whenComplete((loaded, error) -> {
            if (!plugin.isEnabled()) return;
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not load persisted guard orders.", error);
                return;
            }
            try {
                task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not start the guard order scheduler.", schedulingError);
            }
        });
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Public for deterministic MockBukkit tests; must be called on the server thread. */
    public void tick() {
        requireMainThread();
        var snapshot = orders.snapshot();
        if (snapshot.isEmpty()) {
            cursor = 0;
            return;
        }
        int count = Math.min(batchSize, snapshot.size());
        int start = Math.floorMod(cursor, snapshot.size());
        for (int offset = 0; offset < count; offset++) {
            execute(snapshot.get((start + offset) % snapshot.size()));
        }
        cursor = (start + count) % snapshot.size();
    }

    private void execute(GuardOrder order) {
        NpcRecord record = registry.get(order.npcId()).orElse(null);
        if (record == null || record.type() != ru.raskolnpc.npc.model.NpcType.GUARD) return;
        Entity entity = entities.find(order.npcId()).orElse(null);
        if (entity == null || !entity.isValid()) return;

        Location target = switch (order.type()) {
            case HOLD_POSITION -> null;
            case RETURN_TO_POST -> record.spawnLocation() == null
                    ? null : record.spawnLocation().resolve();
            case DEFEND -> waypoint(order, 0);
            case PATROL -> nextPatrolTarget(order, entity.getLocation());
            case ESCORT -> escortTarget(order);
        };
        if (target == null) return;
        moveOneStep(entity, target);
    }

    private Location escortTarget(GuardOrder order) {
        Player owner = Bukkit.getPlayer(order.issuedBy());
        return owner == null || !owner.isOnline() ? null : owner.getLocation().clone();
    }

    private Location nextPatrolTarget(GuardOrder order, Location current) {
        if (order.waypoints().isEmpty()) return null;
        int index = Math.floorMod(patrolIndices.getOrDefault(order.npcId(), 0),
                order.waypoints().size());
        Location target = waypoint(order, index);
        if (target != null && current.getWorld() != null
                && current.getWorld().equals(target.getWorld())
                && current.distanceSquared(target) <= 1.0) {
            index = (index + 1) % order.waypoints().size();
            patrolIndices.put(order.npcId(), index);
            target = waypoint(order, index);
        }
        return target;
    }

    private Location waypoint(GuardOrder order, int index) {
        if (index < 0 || index >= order.waypoints().size()) return null;
        GuardOrder.Waypoint point = order.waypoints().get(index);
        World world = Bukkit.getWorld(point.worldId());
        return world == null ? null : new Location(world, point.x(), point.y(),
                point.z(), point.yaw(), point.pitch());
    }

    private void moveOneStep(Entity entity, Location target) {
        Location current = entity.getLocation();
        if (current.getWorld() == null || target.getWorld() == null
                || !current.getWorld().equals(target.getWorld())) return;
        World world = current.getWorld();
        if (!world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return;

        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.15) return;
        double step = Math.min(movementStep, distance);
        Location next = current.clone().add(dx / distance * step,
                dy / distance * step, dz / distance * step);
        if (!world.isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) return;
        Location destination = isPassableStep(next) ? next : findSidestep(current, dx, dz, step);
        if (destination == null) return;
        destination.setYaw(current.getYaw());
        destination.setPitch(current.getPitch());
        entity.teleport(destination);
    }

    private Location findSidestep(Location current, double dx, double dz, double step) {
        double horizontalLength = Math.sqrt(dx * dx + dz * dz);
        if (horizontalLength < 0.001) return null;
        double sideX = -dz / horizontalLength;
        double sideZ = dx / horizontalLength;
        Location first = current.clone().add(sideX * step, 0, sideZ * step);
        if (first.getWorld().isChunkLoaded(first.getBlockX() >> 4, first.getBlockZ() >> 4)
                && isPassableStep(first)) return first;
        Location second = current.clone().add(-sideX * step, 0, -sideZ * step);
        if (second.getWorld().isChunkLoaded(second.getBlockX() >> 4, second.getBlockZ() >> 4)
                && isPassableStep(second)) return second;
        return null;
    }

    private boolean isPassableStep(Location location) {
        return location.getBlock().isPassable()
                && location.clone().add(0, 1, 0).getBlock().isPassable();

    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Guard order movement must run on the server thread.");
        }
    }
}
