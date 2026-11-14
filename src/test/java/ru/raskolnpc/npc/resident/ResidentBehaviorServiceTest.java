package ru.raskolnpc.npc.resident;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.ResidentProfileRepository;
import ru.raskolnpc.storage.ResidentScheduleRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ResidentBehaviorServiceTest {
    @Test
    void startsNeedsProcessingOnFirstTickAndOnlyStartsAgainAtDayBoundary() {
        ResidentBehaviorService service = newService(new NpcRegistry());

        assertFalse(service.beginNeedsCycle(120L),
                "Initial startup cycle must not consume business stock.");
        assertFalse(service.beginNeedsCycle(120L),
                "The same in-game day must not start a second needs cycle.");
        assertTrue(service.beginNeedsCycle(121L),
                "A new in-game day must start a new needs cycle.");
        assertFalse(service.beginNeedsCycle(121L),
                "The new day must only be processed once.");
    }

    @Test
    void refreshesResidentRosterFromRegistryAfterRuntimeChanges() {
        NpcRegistry registry = new NpcRegistry();
        ResidentBehaviorService service = newService(registry);
        NpcRecord first = createResident("First");
        registry.register(first);
        service.refreshResidentRoster();
        assertEquals(1, service.residentCount());

        NpcRecord second = createResident("Second");
        registry.register(second);
        service.refreshResidentRoster();
        assertEquals(2, service.residentCount());

        registry.unregister(first.id());
        service.refreshResidentRoster();
        assertEquals(1, service.residentCount());
    }

    @Test
    void reconcilesPendingDailyNeedsWhenResidentsChangeDuringTheCycle() {
        NpcRegistry registry = new NpcRegistry();
        ResidentBehaviorService service = newService(registry);
        NpcRecord first = createResident("First");
        NpcRecord second = createResident("Second");
        registry.register(first);
        registry.register(second);
        service.refreshResidentRoster();

        assertFalse(service.beginNeedsCycle(10L));
        assertEquals(2, service.pendingNeedsCount());

        registry.unregister(first.id());
        NpcRecord third = createResident("Third");
        registry.register(third);
        service.refreshResidentRoster();

        assertEquals(2, service.pendingNeedsCount(),
                "Removed residents must leave the daily queue and newly-created residents must join it.");
    }

    @Test
    void handlesDayZeroAsAValidInitialDay() {
        ResidentBehaviorService service = newService(new NpcRegistry());

        assertFalse(service.beginNeedsCycle(0L));
        assertTrue(service.beginNeedsCycle(1L));
    }

    private static ResidentBehaviorService newService(NpcRegistry registry) {
        return new ResidentBehaviorService(
                mock(Plugin.class), registry, mock(NpcEntityManager.class),
                mock(ResidentScheduleRepository.class), mock(ResidentProfileRepository.class),
                8, 10L, 0.8);
    }

    private static NpcRecord createResident(String name) {
        return NpcRecord.create(NpcType.RESIDENT, name, null, "town", "nation", "{}");
    }
}
