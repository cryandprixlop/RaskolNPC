package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable, idempotent persistence boundary for resident market purchases. */
public interface ResidentTradeRepository {
    enum ReviewResolution { DELIVERED, CANCELLED_UNPAID, REFUNDED_NOT_DELIVERED }
    CompletableFuture<Optional<ResidentTradeTransaction>> reserve(
            String idempotencyKey, UUID buyerId, NpcId residentId, String material,
            ResidentProductQuality quality, int amount, long unitPriceMinor);

    CompletableFuture<Boolean> transition(
            String transactionId, ResidentTradeTransaction.Status expected,
            ResidentTradeTransaction.Status next, String providerReference, String error);

    /** Atomically cancels an unpaid purchase and restores its exact quality batch. */
    CompletableFuture<Boolean> cancelAndRelease(String transactionId, String reason);

    /** Atomically finalizes a confirmed refund and restores the exact reserved stock batch. */
    CompletableFuture<Boolean> completeRefundAndRelease(
            String transactionId, String providerReference, String detail);

    /** Applies an evidence-backed admin decision to a REVIEW_REQUIRED trade. */
    CompletableFuture<Boolean> resolveReview(String transactionId, ReviewResolution resolution, String evidence);

    CompletableFuture<Optional<ResidentTradeTransaction>> find(String transactionId);

    /** Lists a bounded set of transactions in one state, oldest first for admin reconciliation. */
    CompletableFuture<java.util.List<ResidentTradeTransaction>> findByStatus(
            ResidentTradeTransaction.Status status, int limit);

    CompletableFuture<Optional<ResidentTradeTransaction>> findByIdempotencyKey(String idempotencyKey);

    CompletableFuture<Integer> recoverUnfinished();
}
