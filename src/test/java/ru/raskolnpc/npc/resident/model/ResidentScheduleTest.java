package ru.raskolnpc.npc.resident.model;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;

import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResidentScheduleTest {
    @Test
    void sortsEntriesAndRejectsOverlappingIntervals() {
        ResidentSchedule.Entry evening = entry(17 * 60, 18 * 60);
        ResidentSchedule.Entry morning = entry(8 * 60, 12 * 60);

        ResidentSchedule schedule = new ResidentSchedule(List.of(evening, morning));

        assertEquals(morning, schedule.entries().get(0));
        assertEquals(evening, schedule.entries().get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> schedule.entries().add(evening));
        assertThrows(IllegalArgumentException.class, () -> new ResidentSchedule(List.of(
                entry(8 * 60, 12 * 60), entry(11 * 60, 13 * 60))));
    }

    @Test
    void rejectsInvalidTimeRangesAndAllowsAdjacentActivities() {
        assertThrows(IllegalArgumentException.class, () -> entry(600, 600));
        assertThrows(IllegalArgumentException.class, () -> entry(-1, 30));
        assertThrows(IllegalArgumentException.class, () -> entry(1400, 1441));

        assertDoesNotThrow(() -> new ResidentSchedule(List.of(
                entry(8 * 60, 12 * 60), entry(12 * 60, 13 * 60))));
    }

    @Test
    void validatesAndNormalizesDestinationData() {
        ResidentDestination destination = new ResidentDestination(
                NpcId.create(), ResidentDestination.DestinationType.HOME,
                UUID.randomUUID(), 1, 64, -3, 0, 0, "  Home  ");

        assertEquals("Home", destination.label());
        assertThrows(IllegalArgumentException.class, () -> new ResidentDestination(
                NpcId.create(), ResidentDestination.DestinationType.WORK,
                UUID.randomUUID(), Double.NaN, 64, 0, 0, 0, null));
    }

    private static ResidentSchedule.Entry entry(int start, int end) {
        return new ResidentSchedule.Entry(DayOfWeek.MONDAY, start, end,
                ResidentSchedule.Activity.WORK,
                ResidentDestination.DestinationType.WORK);
    }
}
