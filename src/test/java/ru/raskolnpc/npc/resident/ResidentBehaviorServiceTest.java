package ru.raskolnpc.npc.resident;

import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.ResidentProfileRepository;
import ru.raskolnpc.storage.ResidentScheduleRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ResidentBehaviorServiceTest {
    @Test
    void startsNeedsProcessingOnFirstTickAndOnlyStartsAgainAtDayBoundary() {
        ResidentBehaviorService service = new ResidentBehaviorService(
                mock(Plugin.class),
                mock(NpcRegistry.class),
                mock(NpcEntityManager.class),
                mock(ResidentScheduleRepository.class),
                mock(ResidentProfileRepository.class),
                8,
                10L,
                0.8
        );

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
        ResidentBehaviorService service = new ResidentBehaviorService(
                mock(Plugin.class), registry, mock(NpcEntityManager.class),
                mock(ResidentScheduleRepository.class), mock(ResidentProfileRepository.class),
                8, 10L, 0.8);
        ru.raskolnpc.npc.model.NpcRecord first =
                ru.raskolnpc.npc.model.NpcRecord.create(
                        ru.raskolnpc.npc.model.NpcType.RESIDENT,
                        "First", null, "town", "nation", "{}");
        registry.register(first);
        service.refreshResidentRoster();
        assertEquals(1, service.residentCount());

        ru.raskolnpc.npc.model.NpcRecord second =
                ru.raskolnpc.npc.model.NpcRecord.create(
                        ru.raskolnpc.npc.model.NpcType.RESIDENT,
                        "Second", null, "town", "nation", "{}");
        registry.register(second);
        service.refreshResidentRoster();
        assertEquals(2, service.residentCount());

        registry.unregister(first.id());
        service.refreshResidentRoster();
        assertEquals(1, service.residentCount());
    }

    @Test
    void handlesDayZeroAsAValidInitialDay() {
        ResidentBehaviorService service = new ResidentBehaviorService(
                mock(Plugin.class),
                mock(NpcRegistry.class),
                mock(NpcEntityManager.class),
                mock(ResidentScheduleRepository.class),
                mock(ResidentProfileRepository.class),
                8,
                10L,
                0.8
        );

        assertFalse(service.beginNeedsCycle(0L));
        assertTrue(service.beginNeedsCycle(1L));
    }
}
