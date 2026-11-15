package ru.raskolnpc.npc.resident.trade;

import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.concurrent.CompletableFuture;

/**
 * Boundary for delivering a paid trade. Paper implementations must marshal
 * inventory access and item creation to the server thread.
 */
public interface ResidentTradeDeliveryProvider {
    enum Outcome { DELIVERED, REJECTED, UNKNOWN }

    record Result(Outcome outcome, String detail) {
        public Result {
            java.util.Objects.requireNonNull(outcome, "outcome");
        }
    }

    CompletableFuture<Result> deliver(ResidentTradeTransaction transaction);
}
