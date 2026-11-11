package ru.raskolnpc.npc.lifecycle;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcRepository;
import ru.raskolnpc.storage.NpcDeletionRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NpcLifecycleService {
    private static final String RENT_DELETION_BLOCK_PREFIX =
            "Cannot delete guard while rent payment requires reconciliation: ";

    private final NpcRepository repository;
    private final NpcRegistry registry;
    private final int maximumTotal;
    private final NpcDeletionRepository deletions;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final Object creationLock = new Object();
    private int pendingCreations;

    public NpcLifecycleService(NpcRepository repository, NpcRegistry registry,
                               int maximumTotal, NpcDeletionRepository deletions) {
        this.repository = repository;
        this.registry = registry;
        this.maximumTotal = Math.max(1, maximumTotal);
        this.deletions = deletions;
    }

    public boolean isReady() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
    }

    private <T> CompletableFuture<T> notReady() {
        return CompletableFuture.failedFuture(new NpcLifecycleException(
                "NPC storage is still initializing. Please try again shortly."));
    }

    public CompletableFuture<NpcRecord> create(
            NpcType type, String name, UUID ownerUuid,
            String townId, String nationId, String dataJson
    ) {
        if (!isReady()) return notReady();
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
        if (!isReady()) return notReady();
        return repository.findById(id);
    }

    public CompletableFuture<List<NpcRecord>> loadAll() {
        ready.set(false);
        return repository.findAll().thenApply(records -> {
            records.forEach(registry::put);
            return records;
        });
    }

    public CompletableFuture<NpcRecord> load(NpcId id) {
        if (!isReady()) return notReady();
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

    public CompletableFuture<Boolean> updateRecord(NpcRecord record) {
        if (!isReady()) return notReady();
        return repository.update(record).thenApply(updated -> {
            if (updated) registry.put(record);
            return updated;
        });
    }

    public CompletableFuture<Boolean> unload(NpcId id) {
        if (!isReady()) return notReady();
        return repository.updateState(id, NpcState.UNLOADED).thenApply(updated -> {
            if (updated) {
                registry.get(id).ifPresent(record ->
                        registry.put(record.withState(NpcState.UNLOADED)));
            }
            return updated;
        });
    }

    public CompletableFuture<Boolean> delete(NpcId id) {
        if (!isReady()) return notReady();
        return deletions.deleteAndQueue(id).handle((deleted, error) -> {
            if (error != null) {
                SQLException blocked = findBlockedRentDeletion(error);
                if (blocked != null) {
                    throw new CompletionException(new NpcLifecycleException(
                            blocked.getMessage(), blocked));
                }
                throw new CompletionException(unwrap(error));
            }
            if (deleted) registry.unregister(id);
            return deleted;
        });
    }

    private static SQLException findBlockedRentDeletion(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().startsWith(RENT_DELETION_BLOCK_PREFIX)) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public CompletableFuture<Boolean> completeDeletion(NpcId id) {
        return deletions.complete(id);
    }

    public NpcRegistry registry() {
        return registry;
    }

    public void clearRuntimeRegistry() {
        ready.set(false);
        registry.clear();
    }
}
