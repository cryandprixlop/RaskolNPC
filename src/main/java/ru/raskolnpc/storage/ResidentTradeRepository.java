package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable, idempotent persistence boundary for resident market purchases. */
public interface ResidentTradeRepository {
    CompletableFuture<Optional<ResidentTradeTransaction>> reserve(
            String idempotencyKey, UUID buyerId, NpcId residentId, String material,
            ResidentProductQuality quality, int amount, long unitPriceMinor);

    CompletableFuture<Boolean> transition(
            String transactionId, ResidentTradeTransaction.Status expected,
            ResidentTradeTransaction.Status next, String providerReference, String error);

    /** Atomically cancels an unpaid purchase and restores its exact quality batch. */
    CompletableFuture<Boolean> cancelAndRelease(String transactionId, String reason);

    CompletableFuture<Optional<ResidentTradeTransaction>> find(String transactionId);

    CompletableFuture<Optional<ResidentTradeTransaction>> findByIdempotencyKey(String idempotencyKey);

    CompletableFuture<Integer> recoverUnfinished();
}
