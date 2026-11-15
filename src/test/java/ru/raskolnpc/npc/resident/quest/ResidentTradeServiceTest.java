package ru.raskolnpc.npc.resident.quest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.storage.ResidentProductQuality;
import ru.raskolnpc.storage.ResidentTradeRepository;
import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ResidentTradeServiceTest {
    private ResidentTradeRepository repository;
    private ResidentTradePaymentProvider payments;
    private ResidentTradeDeliveryProvider delivery;
    private ResidentTradeService service;
    private ResidentTradeTransaction trade;

    @BeforeEach
    void setUp() {
        repository = mock(ResidentTradeRepository.class);
        payments = mock(ResidentTradePaymentProvider.class);
        delivery = mock(ResidentTradeDeliveryProvider.class);
        service = new ResidentTradeService(repository, payments, delivery);
        long now = System.currentTimeMillis();
        trade = new ResidentTradeTransaction("trade-1", "idem-1", UUID.randomUUID(),
                NpcId.create(), "IRON_INGOT", ResidentProductQuality.STANDARD,
                2, 50, 100, ResidentTradeTransaction.Status.STOCK_RESERVED,
                null, null, now, now);
        when(repository.reserve(anyString(), any(), any(), anyString(), any(),
                anyInt(), anyLong())).thenReturn(CompletableFuture.completedFuture(Optional.of(trade)));
        when(repository.transition(anyString(), any(), any(), nullable(String.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
    }

    @Test
    void paidTradeIsDeliveredAndMarkedCompleted() {
        when(payments.charge(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradePaymentProvider.Result(
                        ResidentTradePaymentProvider.Outcome.PAID, "charge-1", null)));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.DELIVERED, null)));

        assertEquals(ResidentTradeService.Result.COMPLETED, purchase());
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null);
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.PAID, "charge-1", null);
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.DELIVERED, null, null);
        verify(payments, never()).refund(any());
    }

    @Test
    void declinedPaymentReleasesReservedStockWithoutDelivery() {
        when(payments.charge(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradePaymentProvider.Result(
                        ResidentTradePaymentProvider.Outcome.DECLINED, null, "insufficient funds")));

        assertEquals(ResidentTradeService.Result.PAYMENT_DECLINED, purchase());
        verify(repository).cancelAndRelease("trade-1", "insufficient funds");
        verifyNoInteractions(delivery);
    }

    @Test
    void unknownPaymentOutcomeRequiresReconciliationAndNeverDelivers() {
        when(payments.charge(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradePaymentProvider.Result(
                        ResidentTradePaymentProvider.Outcome.UNKNOWN, null, "timeout")));

        assertEquals(ResidentTradeService.Result.REVIEW_REQUIRED, purchase());
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.PAYMENT_PENDING,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, "timeout");
        verifyNoInteractions(delivery);
    }

    @Test
    void definitiveDeliveryFailureTriggersRefund() {
        when(payments.charge(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradePaymentProvider.Result(
                        ResidentTradePaymentProvider.Outcome.PAID, "charge-2", null)));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.FAILED, "inventory full")));
        when(payments.refund(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradePaymentProvider.Result(
                        ResidentTradePaymentProvider.Outcome.REFUNDED, "refund-2", null)));

        assertEquals(ResidentTradeService.Result.DELIVERY_FAILED_REFUNDED, purchase());
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REFUND_PENDING, null, "inventory full");
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.REFUND_PENDING,
                ResidentTradeTransaction.Status.REFUNDED, "refund-2", null);
    }

    @Test
    void uncertainDeliveryDoesNotTriggerRefundThatCouldDuplicateItems() {
        when(payments.charge(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradePaymentProvider.Result(
                        ResidentTradePaymentProvider.Outcome.PAID, "charge-3", null)));
        when(delivery.deliver(trade)).thenReturn(CompletableFuture.completedFuture(
                new ResidentTradeDeliveryProvider.Result(
                        ResidentTradeDeliveryProvider.Outcome.UNKNOWN, "player disconnected")));

        assertEquals(ResidentTradeService.Result.REVIEW_REQUIRED, purchase());
        verify(repository).transition("trade-1", ResidentTradeTransaction.Status.PAID,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, "player disconnected");
        verify(payments, never()).refund(any());
    }

    private ResidentTradeService.Result purchase() {
        return service.purchase("idem-1", trade.buyerId(), trade.residentId(),
                trade.material(), trade.quality(), trade.amount(), trade.unitPriceMinor()).join();
    }
}
