package ru.raskolnpc.integration.towny;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownyNpcLimitServiceTest {

    @Test
    void allowsExactlyFifteenResidentsAndRejectsTheSixteenth() {
        NpcRegistry registry = residentsInTown("town-a", 15);
        TownyNpcLimitService service = new TownyNpcLimitService();

        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("town-a", 15, registry));
    }

    @Test
    void countsPendingReservationsWhenCheckingTownLimit() {
        NpcRegistry registry = residentsInTown("town-a", 14);
        TownyNpcLimitService service = new TownyNpcLimitService();

        TownyNpcLimitService.Reservation lastSlot =
                service.reserve("town-a", 15, registry);

        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("town-a", 15, registry));

        lastSlot.close();
    }

    @Test
    void releasesReservationWhenClosed() {
        NpcRegistry registry = residentsInTown("town-a", 14);
        TownyNpcLimitService service = new TownyNpcLimitService();

        TownyNpcLimitService.Reservation reservation =
                service.reserve("town-a", 15, registry);
        reservation.close();

        assertDoesNotThrow(() -> service.reserve("town-a", 15, registry));
    }

    @Test
    void closingReservationMoreThanOnceDoesNotReleaseAnotherReservation() {
        NpcRegistry registry = residentsInTown("town-a", 13);
        TownyNpcLimitService service = new TownyNpcLimitService();

        TownyNpcLimitService.Reservation first =
                service.reserve("town-a", 15, registry);
        TownyNpcLimitService.Reservation second =
                service.reserve("town-a", 15, registry);

        first.close();
        first.close();

        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("town-a", 15, registry));

        second.close();
        assertDoesNotThrow(() -> service.reserve("town-a", 15, registry));
    }

    @Test
    void keepsReservationsForDifferentTownsIndependent() {
        NpcRegistry registry = residentsInTown("town-a", 15);
        TownyNpcLimitService service = new TownyNpcLimitService();

        assertDoesNotThrow(() -> service.reserve("town-b", 15, registry));
        assertThrows(TownyIntegrationException.class,
                () -> service.reserve("town-a", 15, registry));
    }

    @Test
    void ignoresGuardsAndCompanionsWhenCountingTownResidents() {
        NpcRegistry registry = new NpcRegistry();
        registry.put(record(NpcType.GUARD, "town-a"));
        registry.put(record(NpcType.COMPANION, "town-a"));
        TownyNpcLimitService service = new TownyNpcLimitService();

        assertDoesNotThrow(() -> service.reserve("town-a", 1, registry));
    }

    @Test
    void allowsReservationsWithoutTownBinding() {
        TownyNpcLimitService service = new TownyNpcLimitService();
        NpcRegistry registry = residentsInTown("town-a", 15);

        assertDoesNotThrow(() -> {
            service.reserve(null, 15, registry).close();
            service.reserve(" ", 15, registry).close();
        });
    }

    private NpcRegistry residentsInTown(String townId, int count) {
        NpcRegistry registry = new NpcRegistry();
        for (int i = 0; i < count; i++) {
            registry.put(record(NpcType.RESIDENT, townId));
        }
        return registry;
    }

    private NpcRecord record(NpcType type, String townId) {
        return NpcRecord.create(type, "Test NPC", null, townId, null, "{}");
    }
}
