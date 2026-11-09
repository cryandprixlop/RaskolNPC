package ru.raskolnpc.npc.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcRecord;

import java.util.Objects;

public final class PaperNpcEntityProvider implements NpcEntityProvider {
    private final Plugin plugin;
    private final NpcEntityTagger tagger;

    public PaperNpcEntityProvider(Plugin plugin, NpcEntityTagger tagger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
    }

    @Override
    public Entity spawn(NpcRecord record, Location location) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) {
            throw new NpcEntityException("Cannot spawn NPC without a loaded world.");
        }

        Entity entity = location.getWorld().spawnEntity(
                location, NpcEntityType.forNpcType(record.type()).entityType(), false);
        try {
            tagger.tag(entity, record.id());
            entity.setCustomName(record.name());
            entity.setCustomNameVisible(true);
            if (entity instanceof Mob mob) {
                mob.setPersistent(true);
                mob.setRemoveWhenFarAway(false);
                // AI and combat behavior are enabled by their dedicated domain systems.
                mob.setAI(false);
            }
            return entity;
        } catch (RuntimeException exception) {
            entity.remove();
            throw new NpcEntityException("Could not initialize spawned NPC entity.", exception);
        }
    }
}
