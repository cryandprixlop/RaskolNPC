package ru.raskolnpc.guard.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A small persistent group of guards managed by server staff. */
public record GuardSquad(UUID id, String name, UUID createdBy, long createdAt, List<NpcId> members) {
    public GuardSquad {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdBy, "createdBy");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (name.isBlank() || name.length() > 32) throw new IllegalArgumentException("Squad name must contain 1–32 characters.");
        if (createdAt <= 0) throw new IllegalArgumentException("createdAt must be positive.");
        if (members.size() > 10) throw new IllegalArgumentException("A squad cannot contain more than 10 guards.");
    }
}