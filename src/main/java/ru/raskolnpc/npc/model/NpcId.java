package ru.raskolnpc.npc.model;

import java.util.Objects;
import java.util.UUID;

public record NpcId(UUID value) {

    public NpcId {
        Objects.requireNonNull(value, "value");
    }

    public static NpcId create() {
        return new NpcId(UUID.randomUUID());
    }

    public static NpcId fromString(String value) {
        return new NpcId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
