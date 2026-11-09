package ru.raskolnpc.npc.lifecycle;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NpcLifecycleService {
    private final NpcRepository repository;
    private final NpcRegistry registry;
    private final int maximumTotal;
    private final Object creationLock = new Object();
    private int pendingCreations;

    public NpcLifecycleService(NpcRepository repository, NpcRegistry registry) {
        this(repository, registry, Integer.MAX_VALUE);
    }

    public NpcLifecycleService(NpcRepository repository, NpcRegistry registry,
                               int maximumTotal) {
        this.repository = repository;
        this.registry = registry;
        this.maximumTotal = Math.max(1, maximumTotal);
    }

    public CompletableFuture<NpcRecord> create(
            NpcType type, String name, UUID ownerUuid,
            String townId, String nationId, String dataJson
    ) {
        synchronized (creationLock) {
            if (registry.size() + pendingCreations >= maximumTotal) {
                return CompletableFuture.failedFuture(new NpcLifecycleException(
                        "The server NPC limit has been reached (" + maximumTotal + ")."));
            }
            pendingCreations++;
        }

        final NpcRecord record;
        try {
            record = NpcRecord.create(type, name, ownerUuid, townId, nationId, dataJson);
        } catch (RuntimeException exception) {
            releaseCreationSlot();
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<NpcRecord> operation = repository.insert(record)
                .thenApply(ignored -> {
                    if (!registry.register(record)) {
                        throw new NpcLifecycleException(
                                "Generated NPC identifier is already registered: " + record.id());
                    }
                    return record;
                });
        return operation.whenComplete((ignored, error) -> releaseCreationSlot());
    }

    private void releaseCreationSlot() {
        synchronized (creationLock) {
            pendingCreations = Math.max(0, pendingCreations - 1);
        }
    }

    public CompletableFuture<Optional<NpcRecord>> find(NpcId id) {
        return repository.findById(id);
    }

    public CompletableFuture<List<NpcRecord>> loadAll() {
        return repository.findAll().thenApply(records -> {
            records.forEach(registry::put);
            return records;
        });
    }

    public CompletableFuture<NpcRecord> load(NpcId id) {
        return repository.findById(id).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new NpcLifecycleException("NPC does not exist: " + id));
            }
            NpcRecord active = optional.get().withState(NpcState.ACTIVE);
            return repository.updateState(id, NpcState.ACTIVE).thenApply(updated -> {
                if (!updated) {
                    throw new NpcLifecycleException(
                            "NPC disappeared while it was being loaded: " + id);
                }
                registry.put(active);
                return active;
            });
        });
    }

    public CompletableFuture<Boolean> unload(NpcId id) {
        return repository.updateState(id, NpcState.UNLOADED).thenApply(updated -> {
            if (updated) {
                registry.get(id).ifPresent(record ->
                        registry.put(record.withState(NpcState.UNLOADED)));
            }
            return updated;
        });
    }

    public CompletableFuture<Boolean> delete(NpcId id) {
        return repository.delete(id).thenApply(deleted -> {
            if (deleted) registry.unregister(id);
            return deleted;
        });
    }

    public NpcRegistry registry() {
        return registry;
    }

    public void clearRuntimeRegistry() {
        registry.clear();
    }
}
