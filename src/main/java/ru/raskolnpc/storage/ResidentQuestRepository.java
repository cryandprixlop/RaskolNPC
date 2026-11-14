package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.quest.ResidentQuest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence for player assignments and resident quest submissions. */
public interface ResidentQuestRepository {
    CompletableFuture<Boolean> accept(UUID playerId, NpcId residentId, String questId,
                                     String requiredMaterial, int requiredAmount, int stockTarget);
    CompletableFuture<Optional<ResidentQuest.Assignment>> findActive(
            UUID playerId, NpcId residentId, String questId);
    CompletableFuture<List<ResidentQuest.Assignment>> findActiveForPlayer(UUID playerId);
    CompletableFuture<Boolean> complete(UUID playerId, NpcId residentId, String questId);

    /** Reserves an accepted assignment before inventory mutation. */
    CompletableFuture<Boolean> reserveSubmission(UUID playerId, NpcId residentId, String questId,
                                                String stockMaterial, int reputationReward,
                                                int stockAmount, int stockCapacity);
    /** Releases a reservation only when no inventory items have been removed. */
    CompletableFuture<Boolean> releaseSubmission(UUID playerId, NpcId residentId, String questId);
    /** Completes the assignment and credits reputation exactly once in one SQL transaction. */
    CompletableFuture<Boolean> completeSubmission(
            UUID playerId, NpcId residentId, String questId, int reputationReward);
    /** Marks an uncertain inventory/database boundary for manual review. */
    CompletableFuture<Boolean> markSubmissionForReview(
            UUID playerId, NpcId residentId, String questId, String reason);
    CompletableFuture<Integer> getReputation(UUID playerId);
    CompletableFuture<Integer> getBusinessStock(NpcId residentId, String material);
    /** Atomically removes stock for a business purchase or production use. */
    CompletableFuture<Boolean> withdrawBusinessStock(NpcId residentId, String material, int amount);
    /** Applies one configured daily consumption pass to all town business stock rows. */
    CompletableFuture<Integer> consumeBusinessStockDaily(int percentage);
    CompletableFuture<List<ResidentQuestReview>> findReviews(int limit);
    /** Resolves a review once; completion credits the stored reward (or the legacy fallback) exactly once. */
    CompletableFuture<Boolean> resolveReview(UUID playerId, NpcId residentId, String questId,
                                             boolean complete, int legacyReward);
    /** Moves reservations left unfinished by a previous process into manual review. */
    CompletableFuture<Integer> recoverUnfinishedSubmissions();
}
