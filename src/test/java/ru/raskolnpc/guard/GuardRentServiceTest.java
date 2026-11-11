package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardRentPaymentRepository;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GuardRentServiceTest {

    @Test
    void updatesExistingMetadataInsteadOfDuplicatingKeys() {
        String json = "{\"overdueDays\":1,\"nextRentAt\":100}";
        String updated = GuardRentService.metadata(json, 2, 200);

        assertEquals(1, updated.split("\"overdueDays\"").length - 1);
        assertEquals(1, updated.split("\"nextRentAt\"").length - 1);
        assertEquals(2.0, GuardRentService.number(updated, "overdueDays", -1));
        assertEquals(200.0, GuardRentService.number(updated, "nextRentAt", -1));
    }

    @Test
    void preservesUnrelatedGuardMetadata() {
        String original = "{\"level\":3,\"dailyRent\":30.0,\"payerTownId\":\"town-uuid\"}";
        String updated = GuardRentService.metadata(original, 2, 1_800_000_000L);

        assertEquals(3.0, GuardRentService.number(updated, "level", -1));
        assertEquals(30.0, GuardRentService.number(updated, "dailyRent", -1));
        assertEquals(2.0, GuardRentService.number(updated, "overdueDays", -1));
        assertEquals(1_800_000_000.0,
                GuardRentService.number(updated, "nextRentAt", -1));
        assertEquals("town-uuid", GuardRentService.string(updated, "payerTownId"));
    }

    @Test
    void returnsFallbackForMissingMetadata() {
        assertEquals(-1.0, GuardRentService.number("{}", "dailyRent", -1));
        assertNull(GuardRentService.string("{}", "payerTownId"));
    }

    @Test
    void paymentIdentifiersAreUuidBacked() {
        NpcId id = NpcId.create();
        assertEquals(id, NpcId.fromString(id.toString()));
    }

    @Test
    void recoveryContinuesAfterOnePaymentFailsAndReportsTheFailure() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            GuardRentPaymentRepository payments = mock(GuardRentPaymentRepository.class);
            NpcRegistry registry = new NpcRegistry();

            GuardRentPayment first = paidPayment(registry, "first");
            GuardRentPayment second = paidPayment(registry, "second");
            when(payments.moveUnfinishedToReview(anyString()))
                    .thenReturn(CompletableFuture.completedFuture(0));
            when(payments.findByStatus(GuardRentPaymentStatus.PAID))
                    .thenReturn(CompletableFuture.completedFuture(List.of(first, second)));
            when(payments.findByStatus(GuardRentPaymentStatus.FAILED))
                    .thenReturn(CompletableFuture.completedFuture(List.of()));
            when(payments.applyPaid(eq("first"), any(NpcRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            new IllegalStateException("simulated database failure")));
            when(payments.applyPaid(eq("second"), any(NpcRecord.class)))
                    .thenReturn(CompletableFuture.completedFuture(true));

            GuardRentService service = new GuardRentService(plugin, registry,
                    mock(NpcLifecycleService.class), mock(NpcEntityManager.class),
                    mock(TownyTownBankEconomy.class), payments, 3);

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> service.recoverPendingPayments().join());

            assertTrue(failure.getCause().getMessage().contains("Could not recover 1 of 2"));
            assertEquals(1, failure.getCause().getSuppressed().length);
            verify(payments).applyPaid(eq("first"), any(NpcRecord.class));
            verify(payments).applyPaid(eq("second"), any(NpcRecord.class));
            assertTrue(registry.get(second.npcId()).isPresent());
        }
    }

    @Test
    void recoveryCompletesWhenEveryPaymentIsApplied() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            GuardRentPaymentRepository payments = mock(GuardRentPaymentRepository.class);
            NpcRegistry registry = new NpcRegistry();

            GuardRentPayment payment = paidPayment(registry, "paid");
            when(payments.moveUnfinishedToReview(anyString()))
                    .thenReturn(CompletableFuture.completedFuture(0));
            when(payments.findByStatus(GuardRentPaymentStatus.PAID))
                    .thenReturn(CompletableFuture.completedFuture(List.of(payment)));
            when(payments.findByStatus(GuardRentPaymentStatus.FAILED))
                    .thenReturn(CompletableFuture.completedFuture(List.of()));
            when(payments.applyPaid(eq("paid"), any(NpcRecord.class)))
                    .thenReturn(CompletableFuture.completedFuture(true));

            GuardRentService service = new GuardRentService(plugin, registry,
                    mock(NpcLifecycleService.class), mock(NpcEntityManager.class),
                    mock(TownyTownBankEconomy.class), payments, 3);

            assertDoesNotThrow(() -> service.recoverPendingPayments().join());
            verify(payments).applyPaid(eq("paid"), any(NpcRecord.class));
        }
    }


    @Test
    void startFailsClearlyWhenSchedulerRejectsRentTask() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            org.bukkit.scheduler.BukkitScheduler scheduler =
                    mock(org.bukkit.scheduler.BukkitScheduler.class);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class),
                    anyLong(), anyLong())).thenThrow(new IllegalStateException("scheduler stopping"));

            Plugin plugin = mock(Plugin.class);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
            GuardRentService service = new GuardRentService(plugin, new NpcRegistry(),
                    mock(NpcLifecycleService.class), mock(NpcEntityManager.class),
                    mock(TownyTownBankEconomy.class), mock(GuardRentPaymentRepository.class), 3);

            IllegalStateException failure = assertThrows(IllegalStateException.class, service::start);
            assertTrue(failure.getMessage().contains("Could not start"));
        }
    }

    private static GuardRentPayment paidPayment(
            NpcRegistry registry, String paymentId
    ) {
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Guard " + paymentId,
                null, "town-id", null,
                "{\"dailyRent\":10,\"overdueDays\":1,\"nextRentAt\":100}");
        registry.put(record);
        return new GuardRentPayment(paymentId, record.id(), 100, "town-id", 10,
                GuardRentPaymentStatus.PAID, 0, 200, null, null, null);
    }
}
