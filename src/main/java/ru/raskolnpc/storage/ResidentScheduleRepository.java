package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence for resident weekly schedules and destination points. */
public interface ResidentScheduleRepository {
    CompletableFuture<Void> saveSchedule(NpcId npcId, ResidentSchedule schedule);

    CompletableFuture<Optional<ResidentSchedule>> findSchedule(NpcId npcId);

    CompletableFuture<Void> saveDestination(ResidentDestination destination);

    CompletableFuture<List<ResidentDestination>> findDestinations(NpcId npcId);

    CompletableFuture<Boolean> deleteDestination(
            NpcId npcId, ResidentDestination.DestinationType type);
}
