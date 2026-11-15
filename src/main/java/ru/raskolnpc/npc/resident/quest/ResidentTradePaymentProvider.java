package ru.raskolnpc.npc.resident.quest;

import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.concurrent.CompletableFuture;

/**
 * Economy boundary for resident purchases. Implementations MUST use transactionId
 * as the provider idempotency key and return UNKNOWN when the external outcome
 * cannot be established. Implementations that call Bukkit/Vault must marshal
 * those calls onto the server thread themselves.
 */
public interface ResidentTradePaymentProvider {
    enum Outcome { PAID, DECLINED, REFUNDED, UNKNOWN }

    record Result(Outcome outcome, String providerReference, String detail) {
        public Result {
            java.util.Objects.requireNonNull(outcome, "outcome");
        }
    }

    CompletableFuture<Result> charge(ResidentTradeTransaction transaction);

    /** Refunds a previously confirmed charge; must also be idempotent by transactionId. */
    CompletableFuture<Result> refund(ResidentTradeTransaction transaction);
}
