package ru.raskolnpc.npc.entity;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcId;

import java.util.Optional;

public final class NpcEntityTagger {
    private final NamespacedKey npcIdKey;

    public NpcEntityTagger(Plugin plugin) {
        this.npcIdKey = new NamespacedKey(plugin, "npc_id");
    }

    public void tag(Entity entity, NpcId id) {
        entity.getPersistentDataContainer().set(
                npcIdKey, PersistentDataType.STRING, id.toString());
        entity.setPersistent(true);
    }

    public Optional<NpcId> read(Entity entity) {
        String raw = entity.getPersistentDataContainer()
                .get(npcIdKey, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(NpcId.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public boolean isNpc(Entity entity) {
        return read(entity).isPresent();
    }
}
