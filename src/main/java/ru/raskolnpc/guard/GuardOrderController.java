package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import ru.raskolnpc.integration.PlayerSubjectResolver;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Map;
import java.util.function.BiPredicate;
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
    private RelationshipPolicy relationships;
    private PlayerSubjectResolver playerSubjects = PlayerSubjectResolver.none();
    private double maxPursuitDistance;
    private BiPredicate<Location, String> territoryAccess = (location, nationId) -> true;
    private final Map<ru.raskolnpc.npc.model.NpcId, Integer> patrolIndices =
            new ConcurrentHashMap<>();
    private BukkitTask task;
    private int cursor;

    public GuardOrderController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, GuardOrderService orders,
            int batchSize, double movementStep) {
        this(plugin, registry, entities, orders, batchSize, movementStep, null, 0.0);
    }

    public GuardOrderController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, GuardOrderService orders,
            int batchSize, double movementStep, RelationshipPolicy relationships,
            double maxPursuitDistance) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.batchSize = Math.max(1, batchSize);
        if (!Double.isFinite(movementStep) || movementStep <= 0 || movementStep > 1.5) {
            throw new IllegalArgumentException("movementStep must be greater than 0 and at most 1.5.");
        }
        this.movementStep = movementStep;
        if (!Double.isFinite(maxPursuitDistance) || maxPursuitDistance < 0
                || maxPursuitDistance > 32) {
            throw new IllegalArgumentException("maxPursuitDistance must be between 0 and 32.");
        }
        this.relationships = relationships;
        this.maxPursuitDistance = maxPursuitDistance;
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

    /** Configures the optional bounded pursuit policy before the controller starts. */
    public void setPursuitPolicy(RelationshipPolicy relationships, double maxPursuitDistance) {
        setPursuitPolicy(relationships, maxPursuitDistance, PlayerSubjectResolver.none());
    }

    /** Configures bounded pursuit of verified hostile NPCs and Towny players. */
    public void setPursuitPolicy(RelationshipPolicy relationships, double maxPursuitDistance,
                                 PlayerSubjectResolver playerSubjects) {
        requireMainThread();
        if (!Double.isFinite(maxPursuitDistance) || maxPursuitDistance < 0
                || maxPursuitDistance > 32) {
            throw new IllegalArgumentException("maxPursuitDistance must be between 0 and 32.");
        }
        this.relationships = relationships;
        this.maxPursuitDistance = maxPursuitDistance;
        this.playerSubjects = Objects.requireNonNull(playerSubjects, "playerSubjects");
    }

    /** Restricts every guard movement step to the hiring nation territory. */
    public void setTerritoryAccess(BiPredicate<Location, String> territoryAccess) {
        requireMainThread();
        this.territoryAccess = Objects.requireNonNull(territoryAccess, "territoryAccess");
    }

    /** Public for deterministic MockBukkit tests; must be called on the server thread. */
    public void tick() {
        requireMainThread();
        var guards = registry.all().stream()
                .filter(record -> record.type() == ru.raskolnpc.npc.model.NpcType.GUARD)
                .sorted(java.util.Comparator.comparing(record -> record.id().toString()))
                .toList();
        if (guards.isEmpty()) {
            cursor = 0;
            patrolIndices.clear();
            return;
        }
        java.util.Set<ru.raskolnpc.npc.model.NpcId> activeGuardIds = guards.stream()
                .map(NpcRecord::id)
                .collect(java.util.stream.Collectors.toSet());
        patrolIndices.keySet().retainAll(activeGuardIds);
        Map<ru.raskolnpc.npc.model.NpcId, GuardOrder> activeOrders = new java.util.HashMap<>();
        for (GuardOrder order : orders.snapshot()) activeOrders.put(order.npcId(), order);
        int count = Math.min(batchSize, guards.size());
        int start = Math.floorMod(cursor, guards.size());
        for (int offset = 0; offset < count; offset++) {
            NpcRecord guard = guards.get((start + offset) % guards.size());
            execute(guard, activeOrders.get(guard.id()));
        }
        cursor = (start + count) % guards.size();
    }

    private void execute(NpcRecord record, GuardOrder order) {
        Entity entity = entities.find(record.id()).orElse(null);
        if (!(entity instanceof LivingEntity livingEntity)
                || !livingEntity.isValid() || livingEntity.isDead()) return;

        Location pursuitTarget = nearestVerifiedHostile(record, livingEntity);
        if (pursuitTarget != null) {
            moveOneStep(record, entity, pursuitTarget);
            return;
        }

        if (order == null) return;
        Location target = switch (order.type()) {
            case HOLD_POSITION -> null;
            case RETURN_TO_POST -> record.spawnLocation() == null
                    ? null : record.spawnLocation().resolve();
            case DEFEND, RALLY -> waypoint(order, 0);
            case PATROL -> nextPatrolTarget(order, entity.getLocation());
            case ESCORT -> escortTarget(order);
        };
        if (target == null) return;
        moveOneStep(record, entity, target);
    }

    /**
     * Returns a nearby registered NPC only when diplomacy explicitly confirms hostility.
     * Players, unregistered entities, neutral relations and unknown relations are ignored.
     */
    private Location nearestVerifiedHostile(NpcRecord guard, Entity entity) {
        if (relationships == null || maxPursuitDistance <= 0
                || !(entity instanceof LivingEntity) || !entity.isValid()) return null;
        Location origin = entity.getLocation();
        if (origin.getWorld() == null) return null;
        double radiusSquared = maxPursuitDistance * maxPursuitDistance;
        return entity.getNearbyEntities(maxPursuitDistance, maxPursuitDistance,
                        maxPursuitDistance).stream()
                .filter(candidate -> candidate instanceof LivingEntity)
                .filter(candidate -> candidate.isValid() && !candidate.isDead())
                .filter(candidate -> candidate.getWorld().equals(origin.getWorld()))
                .filter(candidate -> candidate.getLocation().distanceSquared(origin) <= radiusSquared)
                .filter(candidate -> isVerifiedHostile(guard, candidate))
                .min(java.util.Comparator.comparingDouble(candidate ->
                        candidate.getLocation().distanceSquared(origin)))
                .map(Entity::getLocation)
                .map(Location::clone)
                .orElse(null);
    }

    private boolean isVerifiedHostile(NpcRecord guard, Entity candidate) {
        RelationshipPolicy.Subject victim;
        if (candidate instanceof Player player) {
            victim = playerSubjects.resolve(player).orElse(null);
            if (victim == null) return false;
        } else {
            var targetId = entities.getNpcId(candidate).orElse(null);
            if (targetId == null || targetId.equals(guard.id())) return false;
            NpcRecord target = registry.get(targetId).orElse(null);
            if (target == null) return false;
            victim = new RelationshipPolicy.Subject(
                    target.ownerUuid(), target.townId(), target.nationId());
        }
        RelationshipPolicy.Subject actor = new RelationshipPolicy.Subject(
                guard.ownerUuid(), guard.townId(), guard.nationId());
        return relationships.mayTarget(actor, victim);
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

    private void moveOneStep(NpcRecord guard, Entity entity, Location target) {
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
        // Check the intended step before querying blocks: territory lookup failures must
        // stop movement immediately, without attempting a fallback step.
        if (!hasTerritoryAccess(next, guard.nationId())) return;
        Location destination = isPassableStep(next) ? next : findSidestep(current, dx, dz, step);
        if (destination == null || !hasTerritoryAccess(destination, guard.nationId())) return;
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

    private boolean hasTerritoryAccess(Location location, String nationId) {
        try {
            return territoryAccess.test(location, nationId);
        } catch (RuntimeException unavailable) {
            return false;
        }
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
