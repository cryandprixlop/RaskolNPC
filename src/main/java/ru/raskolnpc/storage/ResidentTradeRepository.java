package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable, idempotent persistence boundary for resident market purchases. */
public interface ResidentTradeRepository {
    /**
     * Atomically reserves the requested quality tier and creates a journal entry.
     * Repeating an identical idempotency key returns the original entry without
     * consuming stock again; reusing the key for different purchase data fails.
     */
    CompletableFuture<Optional<ResidentTradeTransaction>> reserve(
            String idempotencyKey, UUID buyerId, NpcId residentId, String material,
            ResidentProductQuality quality, int amount, long unitPriceMinor);

    /** Compare-and-set state transition; stale or illegal transitions return false. */
    CompletableFuture<Boolean> transition(
            String transactionId, ResidentTradeTransaction.Status expected,
            ResidentTradeTransaction.Status next, String providerReference, String error);

    CompletableFuture<Optional<ResidentTradeTransaction>> find(String transactionId);

    CompletableFuture<Optional<ResidentTradeTransaction>> findByIdempotencyKey(String idempotencyKey);

    /** Marks non-terminal transactions from a previous process for manual reconciliation. */
    CompletableFuture<Integer> recoverUnfinished();
}
