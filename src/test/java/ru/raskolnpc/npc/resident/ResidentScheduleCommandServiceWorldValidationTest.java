package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentDestination;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResidentScheduleCommandServiceWorldValidationTest {
    @Test
    void acceptsDestinationInResidentsSpawnWorld() {
        UUID world = UUID.randomUUID();
        NpcRecord resident = resident(world);
        ResidentDestination destination = destination(world);

        assertDoesNotThrow(() ->
                ResidentScheduleCommandService.validateDestinationWorld(
                        resident, List.of(), destination));
    }

    @Test
    void rejectsDestinationOutsideResidentsSpawnWorld() {
        NpcRecord resident = resident(UUID.randomUUID());
        ResidentDestination destination = destination(UUID.randomUUID());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ResidentScheduleCommandService.validateDestinationWorld(
                        resident, List.of(), destination));
        assertTrue(error.getMessage().contains("том же мире"));
    }

    @Test
    void firstDestinationEstablishesWorldForLegacyResidentWithoutSpawnLocation() {
        NpcRecord resident = NpcRecord.create(
                NpcType.RESIDENT, "Mira", null, null, null, null);
        UUID world = UUID.randomUUID();

        assertDoesNotThrow(() ->
                ResidentScheduleCommandService.validateDestinationWorld(
                        resident, List.of(), destination(world)));
    }

    @Test
    void rejectsDestinationDifferentFromPreviouslyConfiguredPoints() {
        NpcRecord resident = NpcRecord.create(
                NpcType.RESIDENT, "Mira", null, null, null, null);
        ResidentDestination existing = destination(UUID.randomUUID());
        ResidentDestination next = destination(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () ->
                ResidentScheduleCommandService.validateDestinationWorld(
                        resident, List.of(existing), next));
    }

    private static NpcRecord resident(UUID world) {
        return NpcRecord.create(NpcType.RESIDENT, "Mira", null, null, null, null,
                new NpcSpawnLocation(world, 0, 64, 0, 0, 0));
    }

    private static ResidentDestination destination(UUID world) {
        return new ResidentDestination(
                NpcRecord.create(NpcType.RESIDENT, "Temporary", null, null, null, null).id(),
                ResidentDestination.DestinationType.HOME,
                world, 10, 64, 10, 0, 0, "Home");
    }
}
