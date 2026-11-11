package ru.raskolnpc.integration.towny;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KingdomGuardServiceTest {
    @Test
    void rejectsTheThirtyFirstGuardInOneNation() {
        NpcRegistry registry = guardsInNation("nation-a", 30);
        KingdomGuardService service = new KingdomGuardService();

        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("nation-a", 30, registry));
    }

    @Test
    void countsPendingReservationsAgainstNationLimit() {
        NpcRegistry registry = guardsInNation("nation-a", 29);
        KingdomGuardService service = new KingdomGuardService();

        KingdomGuardService.Reservation lastSlot = service.reserve("nation-a", 30, registry);
        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("nation-a", 30, registry));
        lastSlot.close();
    }

    @Test
    void keepsDifferentNationsIndependent() {
        NpcRegistry registry = guardsInNation("nation-a", 30);
        KingdomGuardService service = new KingdomGuardService();

        assertDoesNotThrow(() -> service.reserve("nation-b", 30, registry));
        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("nation-a", 30, registry));
    }

    @Test
    void ignoresResidentsAndCompanionsWhenCountingGuards() {
        NpcRegistry registry = new NpcRegistry();
        registry.put(record(NpcType.RESIDENT, "nation-a"));
        registry.put(record(NpcType.COMPANION, "nation-a"));
        KingdomGuardService service = new KingdomGuardService();

        assertDoesNotThrow(() -> service.reserve("nation-a", 1, registry));
    }

    @Test
    void rejectsMissingNationBinding() {
        KingdomGuardService service = new KingdomGuardService();
        NpcRegistry registry = new NpcRegistry();

        assertThrows(TownyIntegrationException.class,
                () -> service.reserve(null, 30, registry));
        assertThrows(TownyIntegrationException.class,
                () -> service.reserve(" ", 30, registry));
    }

    @Test
    void concurrentReservationsNeverExceedTheNationLimit() throws Exception {
        int limit = 30;
        int contenders = 60;
        KingdomGuardService service = new KingdomGuardService();
        NpcRegistry registry = new NpcRegistry();
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<KingdomGuardService.Reservation> accepted =
                java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        accepted.add(service.reserve("nation-a", limit, registry));
                        return true;
                    } catch (TownyIntegrationException rejected) {
                        return false;
                    }
                }));
            }

            ready.await();
            start.countDown();
            int successful = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) successful++;
            }

            assertEquals(limit, successful);
            assertEquals(limit, accepted.size());
        } finally {
            accepted.forEach(KingdomGuardService.Reservation::close);
            executor.shutdownNow();
        }
    }

    private NpcRegistry guardsInNation(String nationId, int count) {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < count; i++) registry.put(record(NpcType.GUARD, nationId));
        return registry;
    }

    private NpcRecord record(NpcType type, String nationId) {
        return NpcRecord.create(type, "Test NPC", null, null, nationId, "{}");
    }
}
