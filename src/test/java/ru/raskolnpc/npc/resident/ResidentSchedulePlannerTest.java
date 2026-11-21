package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResidentSchedulePlannerTest {
    private final ResidentSchedulePlanner planner = new ResidentSchedulePlanner();

    @Test
    void selectsEntryAtStartAndBeforeEnd() {
        var entry = new ResidentSchedule.Entry(DayOfWeek.MONDAY, 480, 600,
                ResidentSchedule.Activity.WORK, ResidentDestination.DestinationType.WORK);
        var schedule = new ResidentSchedule(List.of(entry));

        assertTrue(planner.findCurrent(schedule, DayOfWeek.MONDAY, 480).isPresent());
        assertTrue(planner.findCurrent(schedule, DayOfWeek.MONDAY, 599).isPresent());
        assertTrue(planner.findCurrent(schedule, DayOfWeek.MONDAY, 600).isEmpty());
    }

    @Test
    void doesNotSelectEntryOnAnotherDay() {
        var schedule = new ResidentSchedule(List.of(new ResidentSchedule.Entry(
                DayOfWeek.MONDAY, 480, 600, ResidentSchedule.Activity.WORK,
                ResidentDestination.DestinationType.WORK)));

        assertTrue(planner.findCurrent(schedule, DayOfWeek.TUESDAY, 500).isEmpty());
    }

    @Test
    void returnsHomeWhenNoActivityIsScheduled() {
        var schedule = new ResidentSchedule(List.of(new ResidentSchedule.Entry(
                DayOfWeek.MONDAY, 480, 600, ResidentSchedule.Activity.WORK,
                ResidentDestination.DestinationType.WORK)));

        assertEquals(ResidentDestination.DestinationType.HOME,
                planner.destinationFor(schedule, DayOfWeek.MONDAY, 600));
        assertEquals(ResidentDestination.DestinationType.HOME,
                planner.destinationFor(schedule, DayOfWeek.TUESDAY, 500));
    }

    @Test
    void usesScheduledDestinationDuringActivity() {
        var schedule = new ResidentSchedule(List.of(new ResidentSchedule.Entry(
                DayOfWeek.MONDAY, 480, 600, ResidentSchedule.Activity.WORK,
                ResidentDestination.DestinationType.WORK)));

        assertEquals(ResidentDestination.DestinationType.WORK,
                planner.destinationFor(schedule, DayOfWeek.MONDAY, 500));
    }

    @Test
    void rejectsInvalidMinute() {
        assertThrows(IllegalArgumentException.class, () -> planner.findCurrent(
                new ResidentSchedule(List.of()), DayOfWeek.MONDAY, 1440));
        assertThrows(IllegalArgumentException.class, () -> planner.destinationFor(
                new ResidentSchedule(List.of()), DayOfWeek.MONDAY, 1440));
    }
}
