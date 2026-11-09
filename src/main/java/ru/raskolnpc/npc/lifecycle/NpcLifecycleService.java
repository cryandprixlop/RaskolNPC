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

    public NpcLifecycleService(NpcRepository repository, NpcRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    public CompletableFuture<NpcRecord> create(
            NpcType type,
            String name,
            UUID ownerUuid,
            String townId,
            String nationId,
            String dataJson
    ) {
        final NpcRecord record;
        try {
            record = NpcRecord.create(type, name, ownerUuid, townId, nationId, dataJson);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return repository.insert(record)
                .thenApply(ignored -> {
                    if (!registry.register(record)) {
                        throw new NpcLifecycleException(
                                "Generated NPC identifier is already registered: " + record.id());
                    }
                    return record;
                });
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
            if (deleted) {
                registry.unregister(id);
            }
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
