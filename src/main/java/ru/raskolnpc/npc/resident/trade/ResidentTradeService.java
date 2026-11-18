package ru.raskolnpc.npc.resident.trade;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.storage.ResidentProductQuality;
import ru.raskolnpc.storage.ResidentTradeRepository;
import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates stock reservation, player payment, item delivery, and compensation.
 *
 * This class deliberately has no Bukkit dependency. Economy and delivery adapters
 * own their thread requirements. Provider calls receive the durable transaction ID
 * as an idempotency key and uncertain external outcomes are sent to manual review.
 */
public final class ResidentTradeService {
    public enum Outcome {
        DELIVERED, INSUFFICIENT_STOCK, PAYMENT_DECLINED,
        REFUNDED, REVIEW_REQUIRED, IN_PROGRESS, FAILED
    }

    public record PurchaseResult(Outcome outcome, String transactionId, String detail) { }

    private final ResidentTradeRepository repository;
    private final ResidentTradeEconomyProvider economy;
    private final ResidentTradeDeliveryProvider delivery;
    private final Logger logger;

    public ResidentTradeService(ResidentTradeRepository repository,
            ResidentTradeEconomyProvider economy, ResidentTradeDeliveryProvider delivery,
            Logger logger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<PurchaseResult> purchase(
            String idempotencyKey, UUID buyerId, NpcId residentId, String material,
            ResidentProductQuality quality, int amount, long unitPriceMinor) {
        Objects.requireNonNull(buyerId, "buyerId");
        return repository.reserve(idempotencyKey, buyerId, residentId, material,
                quality, amount, unitPriceMinor).thenCompose(reserved -> {
            if (reserved.isEmpty()) {
                return completed(Outcome.INSUFFICIENT_STOCK, null, "Requested stock is unavailable.");
            }
            ResidentTradeTransaction trade = reserved.get();
            if (trade.status() != ResidentTradeTransaction.Status.STOCK_RESERVED) {
                if (trade.status() == ResidentTradeTransaction.Status.PAYMENT_PENDING
                        || trade.status() == ResidentTradeTransaction.Status.PAID
                        || trade.status() == ResidentTradeTransaction.Status.REFUND_PENDING) {
                    // These states may belong to the original request, whose provider call is still
                    // running. Startup recovery moves abandoned states to REVIEW_REQUIRED, so a
                    // live idempotent retry must observe rather than mutate an in-flight transaction.
                    return completed(Outcome.IN_PROGRESS, trade.transactionId(),
                            "This purchase is already in progress; no payment or delivery was repeated.");
                }
                return completed(statusOutcome(trade.status()), trade.transactionId(),
                        "Idempotent retry found an existing transaction in state " + trade.status() + ".");
            }
            return beginPayment(trade);
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Resident trade failed before payment could be confirmed.", unwrap(error));
            return new PurchaseResult(Outcome.FAILED, null, "Trade could not be started; inspect server logs.");
        });
    }

    private CompletableFuture<PurchaseResult> beginPayment(ResidentTradeTransaction trade) {
        return repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null)
                .thenCompose(changed -> {
                    if (!changed) {
                        // Another request may have claimed this idempotency key after reserve()
                        // returned STOCK_RESERVED. Do not force that in-flight payment into review:
                        // the winning request owns the provider call and its state transitions.
                        return completed(Outcome.IN_PROGRESS, trade.transactionId(),
                                "This purchase is already being processed; no second payment was started.");
                    }
                    return safeCharge(trade);
                });
    }

    private CompletableFuture<PurchaseResult> safeCharge(ResidentTradeTransaction trade) {
        CompletableFuture<ResidentTradeEconomyProvider.Result> call;
        try {
            call = economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor());
            if (call == null) throw new IllegalStateException("Economy provider returned a null future.");
        } catch (Throwable error) {
            return review(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING,
                    "Economy provider threw before a definitive result: " + concise(error));
        }
        return call.handle((result, error) -> {
            if (error != null || result == null) {
                return review(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING,
                        "Payment outcome is uncertain: " + (error == null ? "empty provider result" : concise(error)));
            }
            return switch (result.outcome()) {
                case DECLINED -> repository.cancelAndRelease(trade.transactionId(),
                        safeDetail(result.detail(), "Payment declined."))
                        .thenApply(released -> released
                                ? new PurchaseResult(Outcome.PAYMENT_DECLINED, trade.transactionId(), result.detail())
                                : new PurchaseResult(Outcome.REVIEW_REQUIRED, trade.transactionId(),
                                        "Payment declined but stock release needs reconciliation."));
                case UNKNOWN -> review(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING,
                        safeDetail(result.detail(), "Payment provider returned UNKNOWN."));
                case CONFIRMED -> paymentConfirmed(trade, result);
            };
        }).thenCompose(future -> future);
    }

    private CompletableFuture<PurchaseResult> paymentConfirmed(
            ResidentTradeTransaction trade, ResidentTradeEconomyProvider.Result payment) {
        return repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.PAID, payment.providerReference(), payment.detail())
                .thenCompose(changed -> {
                    if (!changed) {
                        return review(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING,
                                "Provider confirmed payment but PAID state could not be persisted.");
                    }
                    return deliverPaid(trade);
                });
    }

    private CompletableFuture<PurchaseResult> deliverPaid(ResidentTradeTransaction trade) {
        CompletableFuture<ResidentTradeDeliveryProvider.Result> call;
        try {
            call = delivery.deliver(trade);
            if (call == null) throw new IllegalStateException("Delivery provider returned a null future.");
        } catch (Throwable error) {
            // A synchronous adapter failure does not prove that inventory insertion
            // did not happen. Refunding here could duplicate items and currency.
            return review(trade, ResidentTradeTransaction.Status.PAID,
                    "Delivery outcome is uncertain after adapter failure: " + concise(error));
        }
        return call.handle((result, error) -> {
            if (error != null || result == null) {
                // An exception does not prove that no item was inserted.
                return review(trade, ResidentTradeTransaction.Status.PAID,
                        "Delivery outcome is uncertain: " + (error == null ? "empty delivery result" : concise(error)));
            }
            return switch (result.outcome()) {
                case DELIVERED -> repository.transition(trade.transactionId(),
                        ResidentTradeTransaction.Status.PAID,
                        ResidentTradeTransaction.Status.DELIVERED, null, result.detail())
                        .handle((changed, persistenceError) -> {
                            if (persistenceError != null || !Boolean.TRUE.equals(changed)) {
                                String detail = "Items may be delivered; final state requires reconciliation."
                                        + (persistenceError == null ? "" : " Persistence error: " + concise(persistenceError));
                                return review(trade, ResidentTradeTransaction.Status.PAID, detail);
                            }
                            return completed(Outcome.DELIVERED, trade.transactionId(), result.detail());
                        }).thenCompose(future -> future);
                case REJECTED -> startRefund(trade,
                        safeDetail(result.detail(), "Delivery rejected; starting compensation."));
                case UNKNOWN -> review(trade, ResidentTradeTransaction.Status.PAID,
                        safeDetail(result.detail(), "Delivery outcome is uncertain; refund withheld."));
            };
        }).thenCompose(future -> future);
    }

    private CompletableFuture<PurchaseResult> startRefund(ResidentTradeTransaction trade, String reason) {
        return repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REFUND_PENDING, null, reason)
                .thenCompose(changed -> {
                    if (!changed) {
                        return review(trade, ResidentTradeTransaction.Status.PAID,
                                "Delivery failed but REFUND_PENDING could not be persisted.");
                    }
                    return safeRefund(trade, reason);
                });
    }

    private CompletableFuture<PurchaseResult> safeRefund(ResidentTradeTransaction trade, String reason) {
        CompletableFuture<ResidentTradeEconomyProvider.Result> call;
        try {
            call = economy.refund(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor());
            if (call == null) throw new IllegalStateException("Economy provider returned a null refund future.");
        } catch (Throwable error) {
            return review(trade, ResidentTradeTransaction.Status.REFUND_PENDING,
                    "Refund outcome is uncertain: " + concise(error));
        }
        return call.handle((result, error) -> {
            if (error != null || result == null) {
                return review(trade, ResidentTradeTransaction.Status.REFUND_PENDING,
                        "Refund outcome is uncertain: " + (error == null ? "empty provider result" : concise(error)));
            }
            if (result.outcome() != ResidentTradeEconomyProvider.Outcome.CONFIRMED) {
                return review(trade, ResidentTradeTransaction.Status.REFUND_PENDING,
                        safeDetail(result.detail(), "Refund was not confirmed by economy provider."));
            }
            return repository.completeRefundAndRelease(trade.transactionId(),
                    result.providerReference(), safeDetail(result.detail(), reason))
                    .thenApply(completed -> completed
                            ? new PurchaseResult(Outcome.REFUNDED, trade.transactionId(), result.detail())
                            : new PurchaseResult(Outcome.REVIEW_REQUIRED, trade.transactionId(),
                                    "Refund confirmed; stock release requires reconciliation."));
        }).thenCompose(future -> future);
    }

    private CompletableFuture<PurchaseResult> review(ResidentTradeTransaction trade,
            ResidentTradeTransaction.Status expected, String detail) {
        return repository.transition(trade.transactionId(), expected,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, detail)
                .handle((changed, error) -> {
                    if (error != null || !Boolean.TRUE.equals(changed)) {
                        logger.log(Level.SEVERE, "Could not persist review state for trade "
                                + trade.transactionId(), error);
                    }
                    return new PurchaseResult(Outcome.REVIEW_REQUIRED, trade.transactionId(), detail);
                });
    }

    private static Outcome statusOutcome(ResidentTradeTransaction.Status status) {
        return switch (status) {
            case DELIVERED -> Outcome.DELIVERED;
            case CANCELLED -> Outcome.PAYMENT_DECLINED;
            case REFUNDED -> Outcome.REFUNDED;
            case REVIEW_REQUIRED, PAYMENT_PENDING, PAID, REFUND_PENDING -> Outcome.REVIEW_REQUIRED;
            case STOCK_RESERVED -> Outcome.FAILED;
        };
    }

    private static CompletableFuture<PurchaseResult> completed(
            Outcome outcome, String transactionId, String detail) {
        return CompletableFuture.completedFuture(new PurchaseResult(outcome, transactionId, detail));
    }

    private static String safeDetail(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail.substring(0, Math.min(detail.length(), 512));
    }

    private static String concise(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message.substring(0, Math.min(256, message.length())));
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
