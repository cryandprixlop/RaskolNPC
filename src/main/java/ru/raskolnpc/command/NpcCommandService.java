package ru.raskolnpc.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class NpcCommandService {
    private final Plugin plugin;
    private final NpcLifecycleService lifecycle;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;

    public NpcCommandService(Plugin plugin, NpcLifecycleService lifecycle,
                             NpcRegistry registry, NpcEntityManager entities) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.entities = entities;
    }

    public CompletableFuture<NpcRecord> createResident(String name, Player player) {
        Location location = player.getLocation().clone();
        return lifecycle.create(NpcType.RESIDENT, name, null, null, null, "{}")
                .thenCompose(record -> onMainThread(() -> entities.spawnAsync(record, location))
                        .thenCompose(future -> future)
                        .handle((bound, error) -> {
                            if (error == null) return CompletableFuture.completedFuture(bound);
                            return lifecycle.delete(record.id()).handle((deleted, cleanupError) -> {
                                if (cleanupError != null) {
                                    error.addSuppressed(cleanupError);
                                }
                                throw new java.util.concurrent.CompletionException(error);
                            });
                        }).thenCompose(future -> future));
    }

    private <T> CompletableFuture<T> onMainThread(
            java.util.concurrent.Callable<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Plugin is disabled."));
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) {
                    result.completeExceptionally(
                            new IllegalStateException("Plugin is disabled."));
                    return;
                }
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

    public List<NpcRecord> list() {
        return registry.all().stream()
                .sorted(java.util.Comparator.comparing(NpcRecord::createdAt))
                .toList();
    }

    public CompletableFuture<Optional<NpcRecord>> find(NpcId id) {
        return lifecycle.find(id);
    }

    public CompletableFuture<Boolean> delete(NpcId id) {
        return lifecycle.delete(id).thenCompose(deleted -> {
            if (!deleted) return CompletableFuture.completedFuture(false);
            return onMainThread(() -> CompletableFuture.completedFuture(entities.remove(id)))
                    .thenApply(ignored -> true);
        });
    }
}
