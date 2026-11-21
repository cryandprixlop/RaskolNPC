package ru.raskolnpc.npc.resident.quest;

import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.concurrent.CompletableFuture;

/**
 * Inventory delivery boundary. Implementations MUST be idempotent by transactionId,
 * execute Bukkit inventory access on the server thread, and return UNKNOWN if
 * delivery may have partially happened. Only a definitive FAILED result is refundable.
 */
public interface ResidentTradeDeliveryProvider {
    enum Outcome { DELIVERED, FAILED, UNKNOWN }

    record Result(Outcome outcome, String detail) {
        public Result {
            java.util.Objects.requireNonNull(outcome, "outcome");
        }
    }

    CompletableFuture<Result> deliver(ResidentTradeTransaction transaction);
}
