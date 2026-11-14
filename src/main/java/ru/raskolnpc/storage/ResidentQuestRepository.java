package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.quest.ResidentQuest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence for player assignments to resident quests. */
public interface ResidentQuestRepository {
    /**
     * Creates an active assignment. Returns false when the same player already
     * has this quest active for this resident.
     */
    CompletableFuture<Boolean> accept(
            UUID playerId, NpcId residentId, String questId);

    CompletableFuture<Optional<ResidentQuest.Assignment>> findActive(
            UUID playerId, NpcId residentId, String questId);

    CompletableFuture<List<ResidentQuest.Assignment>> findActiveForPlayer(UUID playerId);

    /** Atomically changes ACCEPTED to COMPLETED; repeated calls return false. */
    CompletableFuture<Boolean> complete(
            UUID playerId, NpcId residentId, String questId);
}
