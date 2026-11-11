package ru.raskolnpc.npc.entity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcRepository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcEntityManager {
    private final Plugin plugin;
    private final NpcEntityProvider provider;
    private final NpcEntityTagger tagger;
    private final NpcRepository repository;
    private final NpcRegistry registry;
    private final Map<NpcId, UUID> liveEntities = new ConcurrentHashMap<>();
    private final Map<NpcId, PendingEntityBinding> pendingEntityBindings =
            new ConcurrentHashMap<>();

    private record PendingEntityBinding(UUID entityUuid, NpcSpawnLocation location) {}

    public NpcEntityManager(Plugin plugin, NpcEntityProvider provider,
                            NpcEntityTagger tagger, NpcRepository repository,
                            NpcRegistry registry) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Spawns on the server thread and completes only after the entity binding
     * has been persisted. The returned record is safe to use as an operation result.
     */
    public CompletableFuture<NpcRecord> spawnAsync(NpcRecord record, Location location) {
        requireMainThread();
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(location, "location");
        if (liveEntities.containsKey(record.id())) {
            return CompletableFuture.failedFuture(
                    new NpcEntityException("NPC already has a live entity: " + record.id()));
        }

        final Entity entity;
        final NpcRecord boundRecord;
        try {
            entity = provider.spawn(record, location.clone());
            boundRecord = record.withEntityBinding(
                    entity.getUniqueId(), NpcSpawnLocation.from(location));
            liveEntities.put(record.id(), entity.getUniqueId());
            pendingEntityBindings.put(record.id(), new PendingEntityBinding(
                    entity.getUniqueId(), boundRecord.spawnLocation()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<NpcRecord> result = new CompletableFuture<>();
        repository.update(boundRecord).whenComplete((updated, error) -> {
            if (!plugin.isEnabled()) {
                result.completeExceptionally(new IllegalStateException(
                        "Plugin was disabled before NPC entity binding completed."));
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !Boolean.TRUE.equals(updated)) {
                        entity.remove();
                        liveEntities.remove(record.id(), entity.getUniqueId());
                        clearPendingBinding(record.id(), entity.getUniqueId());
                        String detail = error == null
                                ? "Repository did not update the NPC record."
                                : error.getMessage();
                        result.completeExceptionally(new NpcEntityException(
                                "Could not persist entity binding for NPC "
                                        + record.id() + ": " + detail));
                        return;
                    }
                    completePendingBinding(record.id(), entity.getUniqueId(), boundRecord)
                            .whenComplete((completed, persistError) -> {
                                if (persistError != null) {
                                    result.completeExceptionally(new NpcEntityException(
                                            "Could not persist the latest location for NPC "
                                                    + record.id() + ": " + persistError.getMessage()));
                                } else {
                                    result.complete(completed);
                                }
                            });
                });
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not schedule entity binding completion for NPC " + record.id()
                                + ". The spawned entity will remain tracked for shutdown cleanup.",
                        schedulingError);
                result.completeExceptionally(new NpcEntityException(
                        "Could not schedule entity binding completion for NPC " + record.id()
                                + ": " + schedulingError.getMessage()));
            }
        });
        return result;
    }

    /** Compatibility wrapper for internal callers that only need immediate spawning. */
    public Entity spawn(NpcRecord record, Location location) {
        requireMainThread();
        if (liveEntities.containsKey(record.id())) {
            throw new NpcEntityException("NPC already has a live entity: " + record.id());
        }
        Entity entity = provider.spawn(record, location.clone());
        NpcRecord boundRecord = record.withEntityBinding(
                entity.getUniqueId(), NpcSpawnLocation.from(location));
        liveEntities.put(record.id(), entity.getUniqueId());
        pendingEntityBindings.put(record.id(), new PendingEntityBinding(
                entity.getUniqueId(), boundRecord.spawnLocation()));
        repository.update(boundRecord).whenComplete((updated, error) -> {
            if (!plugin.isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !Boolean.TRUE.equals(updated)) {
                        entity.remove();
                        liveEntities.remove(record.id(), entity.getUniqueId());
                        clearPendingBinding(record.id(), entity.getUniqueId());
                        plugin.getLogger().severe("Could not persist entity binding for NPC "
                                + record.id() + (error == null ? "" : ": " + error.getMessage()));
                        return;
                    }
                    completePendingBinding(record.id(), entity.getUniqueId(), boundRecord)
                            .whenComplete((completed, persistError) -> {
                                if (persistError != null && plugin.isEnabled()) {
                                    plugin.getLogger().warning(
                                            "Could not persist the latest location for NPC " + record.id()
                                                    + ": " + persistError.getMessage());
                                }
                            });
                });
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not schedule entity binding completion for NPC " + record.id()
                                + ". The entity remains tracked for shutdown cleanup.",
                        schedulingError);
            }
        });
        return entity;
    }

    public Optional<Entity> find(NpcId id) {
        requireMainThread();
        UUID entityId = liveEntities.get(id);
        if (entityId == null) return Optional.empty();
        for (var world : Bukkit.getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (entity != null && entity.isValid()) return Optional.of(entity);
        }
        liveEntities.remove(id);
        return Optional.empty();
    }

    public boolean remove(NpcId id) {
        requireMainThread();
        Optional<Entity> entity = find(id);
        if (entity.isEmpty()) {
            UUID persistedEntityId = registry.get(id)
                    .map(NpcRecord::entityUuid)
                    .orElse(null);
            if (persistedEntityId != null) {
                entity = findLoadedEntity(persistedEntityId)
                        .filter(candidate -> tagger.read(candidate)
                                .filter(id::equals).isPresent());
            }
        }
        // Last-resort reconciliation for legacy records or stale entity UUIDs.
        if (entity.isEmpty()) {
            for (var world : Bukkit.getWorlds()) {
                for (Entity candidate : world.getEntities()) {
                    if (tagger.read(candidate).filter(id::equals).isPresent()) {
                        entity = Optional.of(candidate);
                        break;
                    }
                }
                if (entity.isPresent()) break;
            }
        }
        entity.ifPresent(this::removeEntity);
        liveEntities.remove(id);
        return entity.isPresent();
    }

    private Optional<Entity> findLoadedEntity(UUID entityId) {
        for (var world : Bukkit.getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (entity != null && entity.isValid()) return Optional.of(entity);
        }
        return Optional.empty();
    }

    public Optional<NpcId> getNpcId(Entity entity) {
        return tagger.read(entity);
    }

    public void trackLoaded(Entity entity) {
        requireMainThread();
        tagger.read(entity).ifPresent(id -> registry.get(id).ifPresent(record -> {
            UUID actual = entity.getUniqueId();
            UUID expected = record.entityUuid();
            if (expected != null) {
                if (expected.equals(actual)) liveEntities.putIfAbsent(id, actual);
                return;
            }

            if (liveEntities.putIfAbsent(id, actual) != null) return;
            NpcSpawnLocation initialLocation = NpcSpawnLocation.from(entity.getLocation());
            NpcRecord repaired = record.withEntityBinding(actual, initialLocation);
            pendingEntityBindings.put(id, new PendingEntityBinding(actual, initialLocation));
            repository.update(repaired).whenComplete((updated, error) -> {
                if (!plugin.isEnabled()) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || !Boolean.TRUE.equals(updated)) {
                            liveEntities.remove(id, actual);
                            clearPendingBinding(id, actual);
                            plugin.getLogger().warning(
                                    "Could not repair the entity binding for NPC " + id
                                            + (error == null ? "." : ": " + error.getMessage()));
                            return;
                        }
                        completePendingBinding(id, actual, repaired)
                                .whenComplete((completed, persistError) -> {
                                    if (persistError != null && plugin.isEnabled()) {
                                        plugin.getLogger().warning(
                                                "Could not persist the latest location for NPC " + id
                                                        + ": " + persistError.getMessage());
                                    }
                                });
                    });
                } catch (RuntimeException schedulingError) {
                    liveEntities.remove(id, actual);
                    clearPendingBinding(id, actual);
                    plugin.getLogger().warning("Could not schedule entity binding repair for NPC "
                            + id + ": " + schedulingError.getMessage());
                }
            });
        }));
    }

    public void trackExistingLoadedWorlds() {
        requireMainThread();
        for (var world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) trackLoaded(entity);
        }
    }

    public void removeEntity(Entity entity) {
        requireMainThread();
        tagger.read(entity).ifPresent(id -> liveEntities.remove(id, entity.getUniqueId()));
        if (entity.isValid()) entity.remove();
    }

    public void untrackAndPersistLocation(Entity entity) {
        requireMainThread();
        tagger.read(entity).ifPresent(id -> registry.get(id).ifPresent(record -> {
            UUID actual = entity.getUniqueId();
            NpcSpawnLocation location = NpcSpawnLocation.from(entity.getLocation());
            PendingEntityBinding pending = pendingEntityBindings.get(id);
            if (pending != null && pending.entityUuid().equals(actual)) {
                // Defer the location write until the initial UUID binding is persisted.
                // This avoids racing a conditional UPDATE against the first binding UPDATE.
                pendingEntityBindings.computeIfPresent(id, (ignored, current) ->
                        current.entityUuid().equals(actual)
                                ? new PendingEntityBinding(actual, location) : current);
                liveEntities.remove(id, actual);
                return;
            }
            // An unload may only update the location for the exact persisted binding.
            // A null or different UUID means this entity is stale or its binding is not ready.
            if (!actual.equals(record.entityUuid())) return;
            liveEntities.remove(id, actual);
            repository.updateEntityLocationIfBindingMatches(id, actual, location)
                    .whenComplete((updated, error) -> {
                        if (!plugin.isEnabled()) return;
                        try {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null || !Boolean.TRUE.equals(updated)) {
                                    plugin.getLogger().warning(
                                            "Could not persist last known location for NPC " + id
                                                    + (error == null ? "." : ": " + error.getMessage()));
                                    return;
                                }
                                // Do not let a delayed unload completion overwrite a newer binding.
                                registry.get(id)
                                        .filter(current -> actual.equals(current.entityUuid()))
                                        .ifPresent(current -> registry.put(
                                                current.withEntityBinding(actual, location)));
                            });
                        } catch (RuntimeException schedulingError) {
                            plugin.getLogger().warning("Could not schedule location update for NPC "
                                    + id + ": " + schedulingError.getMessage());
                        }
                    });
        }));
    }

    private CompletableFuture<NpcRecord> completePendingBinding(
            NpcId id, UUID entityUuid, NpcRecord boundRecord) {
        PendingEntityBinding pending = pendingEntityBindings.remove(id);
        NpcSpawnLocation latestLocation = pending != null
                && pending.entityUuid().equals(entityUuid)
                ? pending.location() : boundRecord.spawnLocation();
        NpcRecord completed = boundRecord.withEntityBinding(entityUuid, latestLocation);
        registry.put(completed);
        if (Objects.equals(latestLocation, boundRecord.spawnLocation())) {
            return CompletableFuture.completedFuture(completed);
        }
        return repository.updateEntityLocationIfBindingMatches(id, entityUuid, latestLocation)
                .thenApply(updated -> {
                    if (!Boolean.TRUE.equals(updated)) {
                        throw new NpcEntityException(
                                "Could not persist the latest location for NPC " + id);
                    }
                    return completed;
                });
    }

    private void clearPendingBinding(NpcId id, UUID entityUuid) {
        pendingEntityBindings.computeIfPresent(id, (ignored, pending) ->
                pending.entityUuid().equals(entityUuid) ? null : pending);
    }

    public void untrack(Entity entity) {
        requireMainThread();
        tagger.read(entity).ifPresent(id -> liveEntities.remove(id, entity.getUniqueId()));
    }

    public void clear() {
        requireMainThread();
        for (UUID entityId : liveEntities.values()) {
            for (var world : Bukkit.getWorlds()) {
                Entity entity = world.getEntity(entityId);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                    break;
                }
            }
        }
        liveEntities.clear();
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("NPC entity operations must run on the server thread.");
        }
    }
}
