package ru.raskolnpc.npc.entity;

import org.bukkit.entity.EntityType;
import ru.raskolnpc.npc.model.NpcType;

public enum NpcEntityType {
    RESIDENT(EntityType.VILLAGER),
    GUARD(EntityType.IRON_GOLEM),
    COMPANION(EntityType.WOLF);

    private final EntityType entityType;

    NpcEntityType(EntityType entityType) {
        this.entityType = entityType;
    }

    public EntityType entityType() {
        return entityType;
    }

    public static NpcEntityType forNpcType(NpcType type) {
        return switch (type) {
            case RESIDENT -> RESIDENT;
            case GUARD -> GUARD;
            case COMPANION -> COMPANION;
        };
    }
}
