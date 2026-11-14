package ru.raskolnpc.npc.resident.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.List;
import java.util.Objects;

/** Immutable snapshot of all persisted behavior data for one resident. */
public record ResidentBehaviorData(
        NpcId npcId,
        ResidentSchedule schedule,
        List<ResidentDestination> destinations
) {
    public ResidentBehaviorData {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(schedule, "schedule");
        destinations = List.copyOf(Objects.requireNonNull(destinations, "destinations"));
    }
}
