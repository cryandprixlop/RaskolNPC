package ru.raskolnpc.npc.resident.trade;

import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral player economy boundary.
 *
 * Implementations must treat transactionId as an idempotency key. An UNKNOWN
 * result means the provider may have applied the operation and must be reconciled;
 * callers must never retry it blindly.
 */
public interface ResidentTradeEconomyProvider {
    enum Outcome { CONFIRMED, DECLINED, UNKNOWN }

    record Result(Outcome outcome, String providerReference, String detail) {
        public Result {
            java.util.Objects.requireNonNull(outcome, "outcome");
        }
    }

    CompletableFuture<Result> charge(String transactionId, java.util.UUID playerId, long amountMinor);

    CompletableFuture<Result> refund(String transactionId, java.util.UUID playerId, long amountMinor);
}
