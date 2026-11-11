package ru.raskolnpc.guard;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.time.Instant;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuardRentServiceTest {
    private Plugin plugin;
    private NpcRegistry registry;
    private NpcLifecycleService lifecycle;
    private NpcEntityManager entities;
    private TownyTownBankEconomy economy;
    private LivingEntity living;
    private GuardRentService service;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        registry = new NpcRegistry();
        lifecycle = mock(NpcLifecycleService.class);
        when(lifecycle.isReady()).thenReturn(true);
        entities = mock(NpcEntityManager.class);
        economy = mock(TownyTownBankEconomy.class);
        living = mock(LivingEntity.class);
        when(entities.find(any())).thenReturn(Optional.of(living));
        service = new GuardRentService(plugin, registry, lifecycle, entities, economy, 3);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void storesDebtAndNextPaymentWithoutDroppingOtherGuardMetadata() {
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
    void resetsDebtAfterSuccessfulPayment() {
        String overdue = "{\"overdueDays\":2,\"nextRentAt\":100}";
        String paid = GuardRentService.metadata(overdue, 0, 86_500);

        assertEquals(0.0, GuardRentService.number(paid, "overdueDays", -1));
        assertEquals(86_500.0, GuardRentService.number(paid, "nextRentAt", -1));
    }

    @Test
    void updatesExistingMetadataInsteadOfDuplicatingKeys() {
        String json = "{\"overdueDays\":1,\"nextRentAt\":100}";
        String updated = GuardRentService.metadata(json, 2, 200);

        assertEquals(1, updated.split("\"overdueDays\"").length - 1);
        assertEquals(1, updated.split("\"nextRentAt\"").length - 1);
        assertEquals(2.0, GuardRentService.number(updated, "overdueDays", -1));
    }

    @Test
    void returnsFallbackForMissingMetadata() {
        assertEquals(-1.0, GuardRentService.number("{}", "dailyRent", -1));
        assertNull(GuardRentService.string("{}", "payerTownId"));
    }

    @Test
    void firstFailedPaymentPersistsDebtAndDisablesGuard() {
        NpcRecord guard = addDueGuard(NpcState.ACTIVE, 0);
        when(economy.withdraw(eq("town-uuid"), eq(30.0), anyString())).thenReturn(false);
        when(lifecycle.updateRecord(any())).thenReturn(CompletableFuture.completedFuture(true));

        service.checkDueGuards();
        MockBukkit.getMock().getScheduler().performTicks(2);

        ArgumentCaptor<NpcRecord> saved = ArgumentCaptor.forClass(NpcRecord.class);
        verify(lifecycle).updateRecord(saved.capture());
        NpcRecord updated = saved.getValue();
        assertEquals(NpcState.INACTIVE, updated.state());
        assertEquals(1.0, GuardRentService.number(updated.dataJson(), "overdueDays", -1));
        verify(living, times(2)).setAI(false);
        verify(living, never()).setAI(true);
        verify(economy).withdraw(eq("town-uuid"), eq(30.0), anyString());
    }

    @Test
    void successfulPaymentClearsDebtAndReactivatesGuard() {
        NpcRecord guard = addDueGuard(NpcState.INACTIVE, 2);
        when(economy.withdraw(eq("town-uuid"), eq(30.0), anyString())).thenReturn(true);
        when(lifecycle.updateRecord(any())).thenReturn(CompletableFuture.completedFuture(true));

        service.checkDueGuards();
        MockBukkit.getMock().getScheduler().performTicks(2);

        ArgumentCaptor<NpcRecord> saved = ArgumentCaptor.forClass(NpcRecord.class);
        verify(lifecycle).updateRecord(saved.capture());
        NpcRecord updated = saved.getValue();
        assertEquals(NpcState.ACTIVE, updated.state());
        assertEquals(0.0, GuardRentService.number(updated.dataJson(), "overdueDays", -1));
        assertTrue(GuardRentService.number(updated.dataJson(), "nextRentAt", -1)
                > Instant.now().getEpochSecond());
        verify(living).setAI(false);
        verify(living, never()).setAI(true);
    }

    @Test
    void thirdConsecutiveFailedPaymentDeletesGuardAndCompletesDeletion() {
        NpcRecord guard = addDueGuard(NpcState.INACTIVE, 2);
        when(economy.withdraw(eq("town-uuid"), eq(30.0), anyString())).thenReturn(false);
        when(lifecycle.delete(guard.id())).thenAnswer(invocation -> {
            registry.unregister(guard.id());
            return CompletableFuture.completedFuture(true);
        });
        when(lifecycle.completeDeletion(guard.id())).thenReturn(
                CompletableFuture.completedFuture(true));

        service.checkDueGuards();
        MockBukkit.getMock().getScheduler().performTicks(2);

        verify(entities).remove(guard.id());
        verify(lifecycle).completeDeletion(guard.id());
        verify(lifecycle, never()).updateRecord(any());
    }

    @Test
    void refundsTownWhenPaymentSucceededButPersistenceFailed() {
        NpcRecord guard = addDueGuard(NpcState.ACTIVE, 0);
        when(economy.withdraw(eq("town-uuid"), eq(30.0), anyString())).thenReturn(true);
        when(lifecycle.updateRecord(any())).thenReturn(
                CompletableFuture.completedFuture(false));
        when(economy.deposit(eq("town-uuid"), eq(30.0), anyString())).thenReturn(true);

        service.checkDueGuards();
        MockBukkit.getMock().getScheduler().performTicks(2);

        verify(economy).deposit(eq("town-uuid"), eq(30.0), contains("compensation"));
        assertEquals(NpcState.ACTIVE, registry.get(guard.id()).orElseThrow().state());
    }

    private NpcRecord addDueGuard(NpcState state, int overdueDays) {
        NpcRecord guard = guard(state, overdueDays);
        registry.put(guard);
        return guard;
    }

    private NpcRecord guard(NpcState state, int overdueDays) {
        String data = "{\"level\":3,\"dailyRent\":30.0,\"payerTownId\":\"town-uuid\","
                + "\"overdueDays\":" + overdueDays + ",\"nextRentAt\":1}";
        String now = Instant.now().toString();
        return new NpcRecord(NpcId.create(), NpcType.GUARD, "Gatekeeper", null,
                "town", "nation", state, data, now, now, null, null);
    }
}
