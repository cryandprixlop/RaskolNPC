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
import java.util.concurrent.ConcurrentHashMap;

public final class NpcEntityManager {
    private final Plugin plugin;
    private final NpcEntityProvider provider;
    private final NpcEntityTagger tagger;
    private final NpcRepository repository;
    private final NpcRegistry registry;
    private final Map<NpcId, UUID> liveEntities = new ConcurrentHashMap<>();

    public NpcEntityManager(Plugin plugin, NpcEntityProvider provider,
                            NpcEntityTagger tagger, NpcRepository repository,
                            NpcRegistry registry) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Entity spawn(NpcRecord record, Location location) {
        requireMainThread();
        if (liveEntities.containsKey(record.id())) {
            throw new NpcEntityException("NPC already has a live entity: " + record.id());
        }
        Entity entity = provider.spawn(record, location);
        liveEntities.put(record.id(), entity.getUniqueId());
        return entity;
    }

    public Optional<Entity> find(NpcId id) {
        requireMainThread();
        UUID entityId = liveEntities.get(id);
        if (entityId == null) {
            return Optional.empty();
        }
        for (var world : Bukkit.getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (entity != null && entity.isValid()) {
                return Optional.of(entity);
            }
        }
        liveEntities.remove(id);
        return Optional.empty();
    }

    public boolean remove(NpcId id) {
        requireMainThread();
        Optional<Entity> entity = find(id);
        entity.ifPresent(Entity::remove);
        liveEntities.remove(id);
        return entity.isPresent();
    }

    public Optional<NpcId> getNpcId(Entity entity) {
        return tagger.read(entity);
    }

    public void trackLoaded(Entity entity) {
        requireMainThread();
        tagger.read(entity).ifPresent(id -> {
            if (registry.get(id).isPresent()) {
                liveEntities.put(id, entity.getUniqueId());
            }
        });
    }

    public void untrack(Entity entity) {
        requireMainThread();
        tagger.read(entity).ifPresent(id ->
                liveEntities.remove(id, entity.getUniqueId()));
    }

    public void clear() {
        requireMainThread();
        liveEntities.clear();
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("NPC entity operations must run on the server thread.");
        }
    }
}
