package ru.raskolnpc.guard;

import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardSquadRepository;

import org.bukkit.Location;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates small persistent guard squads; command access is restricted to administrators. */
public final class GuardSquadService {
    public static final int DEFAULT_MAXIMUM_MEMBERS = 10;
    private final NpcRegistry registry;
    private final GuardSquadRepository repository;
    private final int maximumMembers;
    private volatile Map<NpcId, FormationSlot> formationSlots = Map.of();

    public GuardSquadService(NpcRegistry registry, GuardSquadRepository repository, int maximumMembers) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (maximumMembers < 2 || maximumMembers > 10) throw new IllegalArgumentException("maximumMembers must be between 2 and 10.");
        this.maximumMembers = maximumMembers;
    }

    public CompletableFuture<UUID> create(String name, UUID creator) {
        if (name == null || name.isBlank() || name.length() > 32) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Squad name must contain 1–32 characters."));
        }
        Objects.requireNonNull(creator, "creator");
        UUID id = UUID.randomUUID();
        return repository.create(id, name.trim(), creator, Instant.now().toEpochMilli())
                .thenApply(created -> {
                    if (!created) throw new IllegalStateException("Could not create guard squad.");
                    return id;
                });
    }

    public CompletableFuture<Boolean> add(UUID squadId, NpcId guardId) {
        Objects.requireNonNull(squadId, "squadId");
        Objects.requireNonNull(guardId, "guardId");
        if (registry.get(guardId).filter(record -> record.type() == NpcType.GUARD).isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Selected NPC is not a registered guard."));
        }
        return repository.addMember(squadId, guardId, maximumMembers)
                .thenCompose(changed -> changed
                        ? refreshMemberships().thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(false));
    }

    public CompletableFuture<Boolean> remove(NpcId guardId) {
        Objects.requireNonNull(guardId, "guardId");
        return repository.removeMember(guardId)
                .thenCompose(changed -> changed
                        ? refreshMemberships().thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(false));
    }

    public CompletableFuture<Boolean> delete(UUID squadId) {
        return repository.delete(Objects.requireNonNull(squadId, "squadId"))
                .thenCompose(changed -> changed
                        ? refreshMemberships().thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(false));
    }

    public CompletableFuture<List<GuardSquad>> list() {
        return repository.findAll();
    }

    /**
     * Loads persisted squad membership into an immutable in-memory snapshot.
     * Movement reads this snapshot only; it never performs database work per tick.
     */
    public CompletableFuture<Void> refreshMemberships() {
        return repository.findAll().thenAccept(squads -> {
            Map<NpcId, FormationSlot> refreshed = new HashMap<>();
            for (GuardSquad squad : squads) {
                List<NpcId> members = squad.members().stream()
                        .sorted(Comparator.comparing(NpcId::toString))
                        .toList();
                for (int index = 0; index < members.size(); index++) {
                    refreshed.put(members.get(index), new FormationSlot(index, members.size()));
                }
            }
            formationSlots = Map.copyOf(refreshed);
        });
    }

    /**
     * Resolves a deterministic wedge formation around a shared destination.
     * Rally orders already persist individual spread destinations and are not offset again.
     */
    public Location formationTarget(NpcId guardId, Location sharedTarget, GuardOrder.Type orderType) {
        Objects.requireNonNull(guardId, "guardId");
        Objects.requireNonNull(sharedTarget, "sharedTarget");
        Objects.requireNonNull(orderType, "orderType");
        Location target = sharedTarget.clone();
        if (orderType == GuardOrder.Type.RALLY || orderType == GuardOrder.Type.HOLD_POSITION) {
            return target;
        }
        FormationSlot slot = formationSlots.get(guardId);
        if (slot == null || slot.memberCount() < 2 || slot.index() == 0) return target;

        int row = 1;
        int firstIndexInRow = 1;
        while (slot.index() >= firstIndexInRow + row) {
            firstIndexInRow += row;
            row++;
        }
        int positionInRow = slot.index() - firstIndexInRow;
        double lateral = (positionInRow - (row - 1) / 2.0) * 2.0;
        double backward = row * 1.5;
        double yaw = Math.toRadians(target.getYaw());
        double sideX = Math.cos(yaw);
        double sideZ = Math.sin(yaw);
        double backX = Math.sin(yaw);
        double backZ = -Math.cos(yaw);
        target.add(sideX * lateral + backX * backward, 0,
                sideZ * lateral + backZ * backward);
        return target;
    }

    public record FormationSlot(int index, int memberCount) {
        public FormationSlot {
            if (memberCount < 1 || index < 0 || index >= memberCount) {
                throw new IllegalArgumentException("Invalid squad formation slot.");
            }
        }
    }

    /** Persists the same individual order for every member and reports partial failures. */
    public CompletableFuture<SquadOrderResult> issueOrder(
            UUID squadId, GuardOrderService orders, GuardOrder.Type type,
            List<GuardOrder.Waypoint> waypoints, UUID issuer) {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(type, "type");
        List<GuardOrder.Waypoint> route = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
        Objects.requireNonNull(issuer, "issuer");
        return findSquad(squadId).thenCompose(squad -> {
            List<CompletableFuture<Boolean>> results = java.util.stream.IntStream
                    .range(0, squad.members().size())
                    .mapToObj(index -> {
                        NpcId member = squad.members().get(index);
                        List<GuardOrder.Waypoint> memberRoute = type == GuardOrder.Type.RALLY
                                ? rallyPosition(route, index, squad.members().size()) : route;
                        return safely(() -> orders.issue(member, type, memberRoute, issuer));
                    })
                    .toList();
            return summarize(results);
        });
    }

    /** Clears persisted orders for every member and reports partial failures. */
    public CompletableFuture<SquadOrderResult> clearOrders(UUID squadId, GuardOrderService orders) {
        Objects.requireNonNull(orders, "orders");
        return findSquad(squadId).thenCompose(squad -> {
            List<CompletableFuture<Boolean>> results = squad.members().stream()
                    .map(member -> safely(() -> orders.clear(member)))
                    .toList();
            return summarize(results);
        });
    }


    /** Spreads rally destinations around the shared point so guards do not stack on one block. */
    private List<GuardOrder.Waypoint> rallyPosition(
            List<GuardOrder.Waypoint> route, int memberIndex, int memberCount) {
        if (route.size() != 1 || memberCount <= 1) return route;
        GuardOrder.Waypoint point = route.getFirst();
        double angle = 2.0 * Math.PI * memberIndex / memberCount;
        double radius = 1.25;
        return List.of(new GuardOrder.Waypoint(
                point.worldId(),
                point.x() + Math.cos(angle) * radius,
                point.y(),
                point.z() + Math.sin(angle) * radius,
                point.yaw(),
                point.pitch()));
    }

    private CompletableFuture<GuardSquad> findSquad(UUID squadId) {
        Objects.requireNonNull(squadId, "squadId");
        return repository.findAll().thenApply(squads -> squads.stream()
                .filter(squad -> squad.id().equals(squadId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Guard squad was not found.")));
    }

    private CompletableFuture<SquadOrderResult> summarize(List<CompletableFuture<Boolean>> results) {
        return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    int succeeded = (int) results.stream()
                            .filter(result -> Boolean.TRUE.equals(result.join())).count();
                    return new SquadOrderResult(results.size(), succeeded, results.size() - succeeded);
                });
    }

    private CompletableFuture<Boolean> safely(
            java.util.function.Supplier<CompletableFuture<?>> operation) {
        try {
            return operation.get().handle((success, error) -> error == null && success != null && !Boolean.FALSE.equals(success));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public record SquadOrderResult(int total, int succeeded, int failed) {
        public SquadOrderResult {
            if (total < 0 || succeeded < 0 || failed < 0 || succeeded + failed != total) {
                throw new IllegalArgumentException("Invalid squad order result counts.");
            }
        }
    }
}
