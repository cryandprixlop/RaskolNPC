package ru.raskolnpc.npc.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcDeletionRepository;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class NpcRecoveryService {
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final NpcDeletionRepository deletions;
    private final Set<NpcId> deathRecoveries = ConcurrentHashMap.newKeySet();
    private volatile boolean started;

    public NpcRecoveryService(Plugin plugin, NpcRegistry registry,
                              NpcEntityManager entities,
                              NpcDeletionRepository deletions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
    }

    public CompletableFuture<Void> recover() {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("NPC recovery must start on the server thread."));
        }
        started = true;
        return deletions.findPending().thenCompose(ids -> runOnMainThread(() -> {
            Set<NpcId> pending = Set.copyOf(ids);
            List<Entity> loadedEntities = Bukkit.getWorlds().stream()
                    .flatMap(world -> world.getEntities().stream())
                    .toList();
            CompletableFuture<?>[] removals = processLoadedEntities(loadedEntities, pending);
            CompletableFuture<?>[] spawns = recoverLoadedSpawnChunks(pending);
            CompletableFuture<?>[] all = java.util.Arrays.copyOf(
                    removals, removals.length + spawns.length);
            System.arraycopy(spawns, 0, all, removals.length, spawns.length);
            return CompletableFuture.allOf(all);
        }));
    }

    public void onEntitiesLoad(Chunk chunk, List<Entity> loadedEntities) {
        if (!Bukkit.isPrimaryThread() || !started || !plugin.isEnabled()) return;
        if (!hasChunkRecoveryCandidate(chunk, loadedEntities)) return;
        deletions.findPending().whenComplete((ids, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not read pending NPC deletions for a loaded chunk.", error);
                return;
            }
            runOnMainThread(() -> {
                Set<NpcId> pending = Set.copyOf(ids);
                CompletableFuture<?>[] removals =
                        processLoadedEntities(loadedEntities, pending);
                CompletableFuture<?>[] spawns = recoverChunk(chunk, pending);
                CompletableFuture<?>[] all = java.util.Arrays.copyOf(
                        removals, removals.length + spawns.length);
                System.arraycopy(spawns, 0, all, removals.length, spawns.length);
                return CompletableFuture.allOf(all);
            }).whenComplete((ignored, recoveryError) -> {
                if (recoveryError != null && plugin.isEnabled()) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Could not reconcile NPCs in a newly loaded chunk.", recoveryError);
                }
            });
        });
    }

    /**
     * Recovers an NPC after an unexpected entity death. A durable deletion request
     * always wins; only the exact currently-bound entity of an active record qualifies.
     */
    public void onEntityDeath(Entity deadEntity) {
        if (!Bukkit.isPrimaryThread() || !plugin.isEnabled()) return;
        var taggedId = entities.getNpcId(deadEntity);
        if (taggedId.isEmpty()) return;
        NpcId id = taggedId.get();
        entities.untrack(deadEntity);
        NpcRecord record = registry.get(id).orElse(null);
        if (record == null || record.state() != NpcState.ACTIVE
                || !deadEntity.getUniqueId().equals(record.entityUuid())
                || !deathRecoveries.add(id)) {
            return;
        }

        deletions.findPending().whenComplete((pendingIds, error) -> {
            if (error != null) {
                deathRecoveries.remove(id);
                if (plugin.isEnabled()) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Could not check deletion state after NPC death: " + id, error);
                }
                return;
            }
            runOnMainThread(() -> {
                NpcRecord current = registry.get(id).orElse(null);
                if (pendingIds.contains(id) || current == null
                        || current.state() != NpcState.ACTIVE
                        || !deadEntity.getUniqueId().equals(current.entityUuid())) {
                    return CompletableFuture.completedFuture(null);
                }

                Location location = current.spawnLocation() == null
                        ? null : current.spawnLocation().resolve();
                if (location == null || location.getWorld() == null
                        || !location.getWorld().isChunkLoaded(
                                location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    // A later chunk-load event will perform normal reconciliation.
                    return CompletableFuture.completedFuture(null);
                }
                return entities.spawnAsync(current, location).thenApply(restored -> null);
            }).whenComplete((ignored, recoveryError) -> {
                deathRecoveries.remove(id);
                if (recoveryError != null && plugin.isEnabled()) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Could not restore NPC after entity death: " + id, recoveryError);
                }
            });
        });
    }

    public void onEntitiesUnload(List<Entity> unloadingEntities) {
        if (!Bukkit.isPrimaryThread()) return;
        unloadingEntities.forEach(entities::untrack);
    }

    private CompletableFuture<?>[] processLoadedEntities(
            List<Entity> loaded, Set<NpcId> pending) {
        return loaded.stream()
                .filter(Entity::isValid)
                .map(entity -> {
                    var taggedId = entities.getNpcId(entity);
                    if (taggedId.isEmpty()) return CompletableFuture.completedFuture(null);
                    NpcId id = taggedId.get();
                    if (!pending.contains(id)) {
                        var record = registry.get(id).orElse(null);
                        if (record == null || record.state() != NpcState.ACTIVE
                                || (record.entityUuid() != null
                                && !record.entityUuid().equals(entity.getUniqueId()))) {
                            entities.removeEntity(entity);
                            return CompletableFuture.completedFuture(null);
                        }
                        entities.trackLoaded(entity);
                        return CompletableFuture.completedFuture(null);
                    }
                    entities.removeEntity(entity);
                    return deletions.complete(id);
                })
                .toArray(CompletableFuture[]::new);
    }

    private boolean hasChunkRecoveryCandidate(Chunk chunk, List<Entity> loadedEntities) {
        boolean hasTaggedEntity = loadedEntities.stream()
                .filter(Entity::isValid)
                .anyMatch(entity -> entities.getNpcId(entity).isPresent());
        if (hasTaggedEntity) return true;
        return registry.all().stream().anyMatch(record -> isSpawnedInChunk(record, chunk));
    }

    private CompletableFuture<?>[] recoverLoadedSpawnChunks(Set<NpcId> pending) {
        return registry.all().stream()
                .map(record -> recoverRecordIfChunkLoaded(record, null, pending))
                .toArray(CompletableFuture[]::new);
    }

    private CompletableFuture<?>[] recoverChunk(Chunk chunk, Set<NpcId> pending) {
        return registry.all().stream()
                .map(record -> recoverRecordIfChunkLoaded(record, chunk, pending))
                .toArray(CompletableFuture[]::new);
    }

    private CompletableFuture<?> recoverRecordIfChunkLoaded(
            NpcRecord record, Chunk onlyChunk, Set<NpcId> pending) {
        if (pending.contains(record.id()) || record.state() != NpcState.ACTIVE
                || deathRecoveries.contains(record.id())
                || !isSpawnedInChunk(record, onlyChunk)) {
            return CompletableFuture.completedFuture(null);
        }
        Location location = record.spawnLocation().resolve();
        if (location == null || location.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!location.getWorld().isChunkLoaded(chunkX, chunkZ)
                || entities.find(record.id()).isPresent()) {
            return CompletableFuture.completedFuture(null);
        }

        return entities.spawnAsync(record, location).whenComplete((restored, error) -> {
            if (error != null && plugin.isEnabled()) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not restore NPC " + record.id() + ".", error);
            }
        });
    }

    private boolean isSpawnedInChunk(NpcRecord record, Chunk chunk) {
        if (record.spawnLocation() == null) return false;
        Location location = record.spawnLocation().resolve();
        if (location == null || location.getWorld() == null) return false;
        if (chunk == null) return true;
        return chunk.getWorld().getUID().equals(location.getWorld().getUID())
                && chunk.getX() == (location.getBlockX() >> 4)
                && chunk.getZ() == (location.getBlockZ() >> 4);
    }

    private <T> CompletableFuture<T> runOnMainThread(
            java.util.concurrent.Callable<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Plugin is disabled."));
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    action.call().whenComplete((value, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else result.complete(value);
                    });
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }
}
