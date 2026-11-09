package ru.raskolnpc.npc.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcDeletionRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class NpcRecoveryService {
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final NpcDeletionRepository deletions;

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
        return deletions.findPending().thenCompose(ids -> runOnMainThread(() -> {
            Set<NpcId> pending = Set.copyOf(ids);
            CompletableFuture<?>[] removals = processLoadedEntities(
                    Bukkit.getWorlds().stream()
                            .flatMap(world -> java.util.Arrays.stream(world.getEntities()))
                            .toList(), pending);
            entities.trackExistingLoadedWorlds();
            recoverLoadedSpawnChunks();
            return CompletableFuture.allOf(removals);
        }));
    }

    public void onEntitiesLoad(Chunk chunk, List<Entity> loadedEntities) {
        if (!Bukkit.isPrimaryThread() || !isReady()) return;
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
                entities.trackExistingLoadedWorlds();
                recoverChunk(chunk);
                return CompletableFuture.allOf(removals);
            });
        });
    }

    public void onEntitiesUnload(List<Entity> unloadingEntities) {
        if (!Bukkit.isPrimaryThread()) return;
        unloadingEntities.forEach(entities::untrack);
    }

    private boolean isReady() {
        return plugin.isEnabled() && registry != null;
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
                        entities.trackLoaded(entity);
                        return CompletableFuture.completedFuture(null);
                    }
                    entities.removeEntity(entity);
                    return deletions.complete(id);
                })
                .toArray(CompletableFuture[]::new);
    }

    private void recoverLoadedSpawnChunks() {
        for (NpcRecord record : registry.all()) {
            recoverRecordIfChunkLoaded(record, null);
        }
    }

    private void recoverChunk(Chunk chunk) {
        for (NpcRecord record : registry.all()) {
            recoverRecordIfChunkLoaded(record, chunk);
        }
    }

    private void recoverRecordIfChunkLoaded(NpcRecord record, Chunk onlyChunk) {
        if (record.spawnLocation() == null || entities.find(record.id()).isPresent()) return;
        Location location = record.spawnLocation().resolve();
        if (location == null || location.getWorld() == null) return;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (onlyChunk != null && (!onlyChunk.getWorld().getUID().equals(location.getWorld().getUID())
                || onlyChunk.getX() != chunkX || onlyChunk.getZ() != chunkZ)) return;
        if (!location.getWorld().isChunkLoaded(chunkX, chunkZ)) return;

        entities.spawnAsync(record, location).whenComplete((restored, error) -> {
            if (error != null && plugin.isEnabled()) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not restore NPC " + record.id() + ".", error);
            }
        });
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
