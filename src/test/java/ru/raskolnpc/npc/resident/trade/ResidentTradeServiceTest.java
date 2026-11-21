package ru.raskolnpc.npc.resident.trade;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.storage.ResidentProductQuality;
import ru.raskolnpc.storage.ResidentTradeRepository;
import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResidentTradeServiceTest {
    private final ResidentTradeRepository repository = mock(ResidentTradeRepository.class);
    private final ResidentTradeEconomyProvider economy = mock(ResidentTradeEconomyProvider.class);
    private final ResidentTradeDeliveryProvider delivery = mock(ResidentTradeDeliveryProvider.class);
    private final ResidentTradeService service = new ResidentTradeService(
            repository, economy, delivery, Logger.getLogger("resident-trade-test"));

    @Test
    void confirmedPaymentAndDeliveryCompleteTheTrade() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(eq(trade.transactionId()), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.DELIVERED, "items inserted")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.DELIVERED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null);
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.PAID, "PAY-1", "charged");
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.DELIVERED, null, "items inserted");
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void unknownPaymentIsNeverRetriedOrDeliveredAndMovesToReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(eq(trade.transactionId()), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.UNKNOWN, null, "provider timeout")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, "provider timeout");
        verifyNoInteractions(delivery);
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void confirmedDeliveryRejectionTriggersRefundAndOnlyThenReleasesStock() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(eq(trade.transactionId()), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.completeRefundAndRelease(trade.transactionId(), "REF-1", "refund confirmed"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.REJECTED, "inventory full")));
        when(economy.refund(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "REF-1", "refund confirmed")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REFUNDED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REFUND_PENDING, null, "inventory full");
        verify(repository).completeRefundAndRelease(trade.transactionId(), "REF-1", "refund confirmed");
    }


    @Test
    void synchronousDeliveryFailureRequiresReviewAndNeverRefunds() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(eq(trade.transactionId()), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenThrow(new IllegalStateException("inventory adapter failed"));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Delivery outcome is uncertain after adapter failure: IllegalStateException: inventory adapter failed");
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }


    @Test
    void paymentPendingPersistenceFailureIsQueuedForReviewBeforeAnyCharge() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite unavailable")));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Could not persist PAYMENT_PENDING before charging; payment was not started. IllegalStateException: sqlite unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(trade.transactionId(), result.transactionId());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Could not persist PAYMENT_PENDING before charging; payment was not started. IllegalStateException: sqlite unavailable");
        verifyNoInteractions(economy, delivery);
    }

    @Test
    void concurrentIdempotentRequestDoesNotMoveWinningPaymentIntoReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null))
                .thenReturn(CompletableFuture.completedFuture(false));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.IN_PROGRESS, result.outcome());
        assertEquals(trade.transactionId(), result.transactionId());
        verify(repository, times(1)).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null);
        verify(repository, never()).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, anyString());
        verifyNoInteractions(economy, delivery);
    }

    @Test
    void idempotentRetryOfPendingPaymentDoesNotMutateInFlightTrade() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.PAYMENT_PENDING);
        reserve(trade);
        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.IN_PROGRESS, result.outcome());
        verify(repository, never()).transition(anyString(), any(), any(), any(), any());
        verifyNoInteractions(economy, delivery);
    }


    @Test
    void deliveredItemsWithFailedJournalFinalizationAreQueuedForReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.PAID, "PAY-1", "charged"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.DELIVERED, null, "items inserted"))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Items may be delivered; final state requires reconciliation."))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.DELIVERED, "items inserted")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Items may be delivered; final state requires reconciliation.");
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }


    @Test
    void confirmedPaymentWithJournalPersistenceExceptionMovesTradeToReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.PAID, "PAY-1", "charged"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite unavailable")));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Provider confirmed payment but PAID state could not be persisted. Persistence error: IllegalStateException: sqlite unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        assertEquals(trade.transactionId(), result.transactionId());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Provider confirmed payment but PAID state could not be persisted. Persistence error: IllegalStateException: sqlite unavailable");
        verifyNoInteractions(delivery);
        verify(economy, never()).charge(anyString(), any(), anyLong());
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void deliveryFinalizationPersistenceExceptionMovesTradeToReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.PAID, "PAY-1", "charged"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.DELIVERED, null, "items inserted"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite unavailable")));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Items may be delivered; final state requires reconciliation. Persistence error: IllegalStateException: sqlite unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.DELIVERED, "items inserted")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Items may be delivered; final state requires reconciliation. Persistence error: IllegalStateException: sqlite unavailable");
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }


    @Test
    void declinedPaymentWithStockReleaseExceptionMovesTradeToReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.DECLINED, null, "declined")));
        when(repository.cancelAndRelease(trade.transactionId(), "declined"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite unavailable")));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Payment was declined but stock release failed; reconciliation required: IllegalStateException: sqlite unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Payment was declined but stock release failed; reconciliation required: IllegalStateException: sqlite unavailable");
        verifyNoInteractions(delivery);
    }

    @Test
    void refundPendingPersistenceFailureDoesNotCallRefundProvider() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(eq(trade.transactionId()), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REFUND_PENDING, null, "inventory full"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite unavailable")));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Delivery failed but REFUND_PENDING persistence failed; refund was not started: IllegalStateException: sqlite unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.REJECTED, "inventory full")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(economy, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void confirmedRefundWithStockRestoreExceptionMovesTradeToReview() {
        ResidentTradeTransaction trade = trade(ResidentTradeTransaction.Status.STOCK_RESERVED);
        reserve(trade);
        when(repository.transition(eq(trade.transactionId()), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.completeRefundAndRelease(trade.transactionId(), "REF-1", "refund confirmed"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite unavailable")));
        when(repository.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.REFUND_PENDING,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Refund was confirmed but stock restoration failed; reconciliation required: IllegalStateException: sqlite unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(economy.charge(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "PAY-1", "charged")));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.REJECTED, "inventory full")));
        when(economy.refund(trade.transactionId(), trade.buyerId(), trade.totalPriceMinor()))
                .thenReturn(CompletableFuture.completedFuture(new ResidentTradeEconomyProvider.Result(
                        ResidentTradeEconomyProvider.Outcome.CONFIRMED, "REF-1", "refund confirmed")));

        ResidentTradeService.PurchaseResult result = service.purchase(
                trade.idempotencyKey(), trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();

        assertEquals(ResidentTradeService.Outcome.REVIEW_REQUIRED, result.outcome());
        verify(repository).transition(trade.transactionId(),
                ResidentTradeTransaction.Status.REFUND_PENDING,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null,
                "Refund was confirmed but stock restoration failed; reconciliation required: IllegalStateException: sqlite unavailable");
    }

    private void reserve(ResidentTradeTransaction trade) {
        when(repository.reserve(eq(trade.idempotencyKey()), eq(trade.buyerId()),
                eq(trade.residentId()), eq(trade.material()), eq(trade.quality()),
                eq(trade.amount()), eq(trade.unitPriceMinor())))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(trade)));
    }

    private static ResidentTradeTransaction trade(ResidentTradeTransaction.Status status) {
        return new ResidentTradeTransaction("trade-1", "purchase-1", UUID.randomUUID(),
                NpcId.create(), "IRON_INGOT", ResidentProductQuality.STANDARD,
                2, 125, 250, status, null, null, 1_000, 1_000);
    }
}
