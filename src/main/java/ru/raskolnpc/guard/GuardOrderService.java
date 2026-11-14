package ru.raskolnpc.guard;

import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardOrderRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Validates and persists tactical orders for registered kingdom guards. */
public final class GuardOrderService {
    private final NpcRegistry registry;
    private final GuardOrderRepository repository;
    private final ConcurrentMap<NpcId, GuardOrder> activeOrders = new ConcurrentHashMap<>();

    public GuardOrderService(NpcRegistry registry, GuardOrderRepository repository) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CompletableFuture<GuardOrder> issue(
            NpcId guardId, GuardOrder.Type type, List<GuardOrder.Waypoint> waypoints,
            UUID issuer) {
        Objects.requireNonNull(guardId, "guardId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(issuer, "issuer");

        if (registry.get(guardId).filter(record -> record.type() == NpcType.GUARD).isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("The selected NPC is not a registered guard."));
        }
        List<GuardOrder.Waypoint> route = List.copyOf(waypoints);
        if (route.size() > 16) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("A guard order cannot contain more than 16 waypoints."));
        }
        if (type == GuardOrder.Type.PATROL && route.size() < 2) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("A patrol route must contain at least two waypoints."));
        }
        if (type == GuardOrder.Type.DEFEND && route.size() != 1) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("DEFEND requires exactly one target waypoint."));
        }
        if (type == GuardOrder.Type.ESCORT && !route.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("ESCORT follows the player who issued the order; no waypoint is used."));
        }
        if ((type == GuardOrder.Type.HOLD_POSITION || type == GuardOrder.Type.RETURN_TO_POST)
                && !route.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(type + " does not accept custom waypoints."));
        }
        if (!route.isEmpty()) {
            UUID world = route.getFirst().worldId();
            if (route.stream().anyMatch(point -> !world.equals(point.worldId()))) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("All waypoints in one order must use the same world."));
            }
        }

        GuardOrder order = new GuardOrder(guardId, type, route, issuer,
                Instant.now().toEpochMilli());
        return repository.save(order).thenApply(saved -> {
            if (!saved) throw new IllegalStateException(
                    "Guard order could not be saved; the NPC may have been deleted.");
            activeOrders.put(guardId, order);
            return order;
        });
    }

    public CompletableFuture<Optional<GuardOrder>> find(NpcId guardId) {
        Objects.requireNonNull(guardId, "guardId");
        if (registry.get(guardId).filter(record -> record.type() == NpcType.GUARD).isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        GuardOrder cached = activeOrders.get(guardId);
        return cached == null ? repository.find(guardId).thenApply(found -> {
            found.ifPresent(order -> activeOrders.put(guardId, order));
            return found;
        }) : CompletableFuture.completedFuture(Optional.of(cached));
    }

    /** Loads durable orders once during startup; no database polling is needed by the tick controller. */
    public CompletableFuture<Integer> loadAll() {
        return repository.findAll().thenApply(orders -> {
            activeOrders.clear();
            for (GuardOrder order : orders) {
                if (registry.get(order.npcId())
                        .filter(record -> record.type() == NpcType.GUARD).isPresent()) {
                    activeOrders.put(order.npcId(), order);
                }
            }
            return activeOrders.size();
        });
    }

    public List<GuardOrder> snapshot() {
        return activeOrders.values().stream()
                .sorted(java.util.Comparator.comparing(order -> order.npcId().toString()))
                .toList();
    }

    public CompletableFuture<Boolean> clear(NpcId guardId) {
        Objects.requireNonNull(guardId, "guardId");
        if (registry.get(guardId).filter(record -> record.type() == NpcType.GUARD).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.delete(guardId).thenApply(deleted -> {
            if (deleted) activeOrders.remove(guardId);
            return deleted;
        });
    }
}
