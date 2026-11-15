package ru.raskolnpc.npc.resident.quest;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.storage.ResidentProductQuality;
import ru.raskolnpc.storage.ResidentTradeRepository;
import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates durable reservation, payment, item delivery and compensation. */
public final class ResidentTradeService {
    public enum Result {
        COMPLETED, INSUFFICIENT_STOCK, PAYMENT_DECLINED,
        DELIVERY_FAILED_REFUNDED, REVIEW_REQUIRED
    }

    private final ResidentTradeRepository repository;
    private final ResidentTradePaymentProvider payments;
    private final ResidentTradeDeliveryProvider delivery;

    public ResidentTradeService(ResidentTradeRepository repository,
            ResidentTradePaymentProvider payments, ResidentTradeDeliveryProvider delivery) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    public CompletableFuture<Result> purchase(String idempotencyKey, UUID buyerId,
            NpcId residentId, String material, ResidentProductQuality quality,
            int amount, long unitPriceMinor) {
        return repository.reserve(idempotencyKey, buyerId, residentId, material,
                quality, amount, unitPriceMinor).thenCompose(reserved -> {
            if (reserved.isEmpty()) return CompletableFuture.completedFuture(Result.INSUFFICIENT_STOCK);
            ResidentTradeTransaction trade = reserved.get();
            if (trade.status() == ResidentTradeTransaction.Status.DELIVERED) {
                return CompletableFuture.completedFuture(Result.COMPLETED);
            }
            if (trade.status() != ResidentTradeTransaction.Status.STOCK_RESERVED) {
                return CompletableFuture.completedFuture(Result.REVIEW_REQUIRED);
            }
            return repository.transition(trade.transactionId(),
                    ResidentTradeTransaction.Status.STOCK_RESERVED,
                    ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null)
                    .thenCompose(changed -> {
                        if (!changed) return CompletableFuture.completedFuture(Result.REVIEW_REQUIRED);
                        return charge(trade);
                    });
        });
    }

    private CompletableFuture<Result> charge(ResidentTradeTransaction trade) {
        return safe(() -> payments.charge(withStatus(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING)))
                .thenCompose(payment -> {
            if (payment == null || payment.outcome() == ResidentTradePaymentProvider.Outcome.UNKNOWN) {
                return markReview(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING,
                        payment == null ? "Payment provider returned no result."
                                : payment.detail());
            }
            if (payment.outcome() == ResidentTradePaymentProvider.Outcome.DECLINED) {
                return repository.cancelAndRelease(trade.transactionId(),
                        payment.detail()).thenApply(released -> released
                        ? Result.PAYMENT_DECLINED : Result.REVIEW_REQUIRED);
            }
            if (payment.outcome() != ResidentTradePaymentProvider.Outcome.PAID) {
                return markReview(trade, ResidentTradeTransaction.Status.PAYMENT_PENDING,
                        "Payment provider returned an invalid charge outcome.");
            }
            return repository.transition(trade.transactionId(),
                    ResidentTradeTransaction.Status.PAYMENT_PENDING,
                    ResidentTradeTransaction.Status.PAID,
                    payment.providerReference(), payment.detail()).thenCompose(paid -> {
                if (!paid) return markReview(trade,
                        ResidentTradeTransaction.Status.PAYMENT_PENDING,
                        "Payment succeeded but durable PAID transition failed.");
                return deliver(trade);
            });
        }).exceptionallyCompose(error -> markReview(trade,
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                "Payment outcome uncertain: " + safeMessage(error)));
    }

    private CompletableFuture<Result> deliver(ResidentTradeTransaction trade) {
        return safe(() -> delivery.deliver(withStatus(trade, ResidentTradeTransaction.Status.PAID)))
                .thenCompose(result -> {
            if (result == null || result.outcome() == ResidentTradeDeliveryProvider.Outcome.UNKNOWN) {
                return markReview(trade, ResidentTradeTransaction.Status.PAID,
                        result == null ? "Delivery provider returned no result." : result.detail());
            }
            if (result.outcome() == ResidentTradeDeliveryProvider.Outcome.DELIVERED) {
                return repository.transition(trade.transactionId(),
                        ResidentTradeTransaction.Status.PAID,
                        ResidentTradeTransaction.Status.DELIVERED, null, null)
                        .thenApply(changed -> changed ? Result.COMPLETED : Result.REVIEW_REQUIRED);
            }
            return repository.transition(trade.transactionId(),
                    ResidentTradeTransaction.Status.PAID,
                    ResidentTradeTransaction.Status.REFUND_PENDING, null, result.detail())
                    .thenCompose(refundPending -> {
                        if (!refundPending) return CompletableFuture.completedFuture(Result.REVIEW_REQUIRED);
                        return refund(trade);
                    });
        }).exceptionallyCompose(error -> markReview(trade,
                ResidentTradeTransaction.Status.PAID,
                "Delivery outcome uncertain: " + safeMessage(error)));
    }

    private CompletableFuture<Result> refund(ResidentTradeTransaction trade) {
        return safe(() -> payments.refund(withStatus(trade, ResidentTradeTransaction.Status.REFUND_PENDING)))
                .thenCompose(refund -> {
            if (refund == null || refund.outcome() != ResidentTradePaymentProvider.Outcome.REFUNDED) {
                return markReview(trade, ResidentTradeTransaction.Status.REFUND_PENDING,
                        refund == null ? "Refund provider returned no result." : refund.detail());
            }
            return repository.transition(trade.transactionId(),
                    ResidentTradeTransaction.Status.REFUND_PENDING,
                    ResidentTradeTransaction.Status.REFUNDED,
                    refund.providerReference(), refund.detail())
                    .thenApply(changed -> changed
                            ? Result.DELIVERY_FAILED_REFUNDED : Result.REVIEW_REQUIRED);
        }).exceptionallyCompose(error -> markReview(trade,
                ResidentTradeTransaction.Status.REFUND_PENDING,
                "Refund outcome uncertain: " + safeMessage(error)));
    }

    private CompletableFuture<Result> markReview(ResidentTradeTransaction trade,
            ResidentTradeTransaction.Status expected, String reason) {
        return repository.transition(trade.transactionId(), expected,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, reason)
                .handle((changed, error) -> Result.REVIEW_REQUIRED);
    }

    private static <T> CompletableFuture<T> safe(
            java.util.function.Supplier<CompletableFuture<T>> operation) {
        try {
            return Objects.requireNonNull(operation.get(), "provider future");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static ResidentTradeTransaction withStatus(ResidentTradeTransaction trade,
            ResidentTradeTransaction.Status status) {
        return new ResidentTradeTransaction(trade.transactionId(), trade.idempotencyKey(),
                trade.buyerId(), trade.residentId(), trade.material(), trade.quality(),
                trade.amount(), trade.unitPriceMinor(), trade.totalPriceMinor(), status,
                trade.providerReference(), trade.lastError(), trade.createdAt(),
                Math.max(trade.updatedAt(), System.currentTimeMillis()));
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message.substring(0, Math.min(900, message.length()));
    }
}
