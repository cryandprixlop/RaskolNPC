package ru.raskolnpc.guard;

import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardSquadRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates small persistent guard squads; command access is restricted to administrators. */
public final class GuardSquadService {
    public static final int DEFAULT_MAXIMUM_MEMBERS = 10;
    private final NpcRegistry registry;
    private final GuardSquadRepository repository;
    private final int maximumMembers;

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
        return repository.addMember(squadId, guardId, maximumMembers);
    }

    public CompletableFuture<Boolean> remove(NpcId guardId) {
        Objects.requireNonNull(guardId, "guardId");
        return repository.removeMember(guardId);
    }

    public CompletableFuture<Boolean> delete(UUID squadId) {
        return repository.delete(Objects.requireNonNull(squadId, "squadId"));
    }

    public CompletableFuture<List<GuardSquad>> list() {
        return repository.findAll();
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
