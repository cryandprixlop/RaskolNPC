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
                .thenApply(record -> {
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (plugin.isEnabled()) {
                                try {
                                    entities.spawn(record, location);
                                } catch (RuntimeException exception) {
                                    lifecycle.delete(record.id());
                                    plugin.getLogger().severe("Could not spawn resident "
                                            + record.id() + ": " + exception.getMessage());
                                }
                            }
                        });
                    }
                    return record;
                });
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
        return lifecycle.delete(id).thenApply(deleted -> {
            if (deleted && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.isEnabled()) entities.remove(id);
                });
            }
            return deleted;
        });
    }
}
