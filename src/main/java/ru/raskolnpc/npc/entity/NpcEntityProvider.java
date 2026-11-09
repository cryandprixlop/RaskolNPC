package ru.raskolnpc.npc.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import ru.raskolnpc.npc.model.NpcRecord;

public interface NpcEntityProvider {
    Entity spawn(NpcRecord record, Location location);
}
