package ru.raskolnpc.command;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.time.DayOfWeek;

import static org.junit.jupiter.api.Assertions.*;

class ResidentScheduleCommandParserTest {
    @Test void parsesEnglishAndRussianDays() {
        assertEquals(DayOfWeek.MONDAY, ResidentScheduleCommandParser.parseDay("MONDAY"));
        assertEquals(DayOfWeek.MONDAY, ResidentScheduleCommandParser.parseDay("пн"));
        assertEquals(DayOfWeek.SUNDAY, ResidentScheduleCommandParser.parseDay("воскресенье"));
    }
    @Test void parsesStrictTimesAndAllowsMidnightOnlyAsEnd() {
        assertEquals(510, ResidentScheduleCommandParser.parseStartTime("08:30"));
        assertEquals(1440, ResidentScheduleCommandParser.parseEndTime("24:00"));
        assertThrows(IllegalArgumentException.class, () -> ResidentScheduleCommandParser.parseStartTime("24:00"));
        assertThrows(IllegalArgumentException.class, () -> ResidentScheduleCommandParser.parseEndTime("8:30"));
        assertThrows(IllegalArgumentException.class, () -> ResidentScheduleCommandParser.parseEndTime("24:01"));
    }
    @Test void parsesActivityAndDestinationIgnoringCase() {
        assertEquals(ResidentSchedule.Activity.SOCIALIZE, ResidentScheduleCommandParser.parseActivity("socialize"));
        assertEquals(ResidentDestination.DestinationType.COMMUNITY,
                ResidentScheduleCommandParser.parseDestinationType("community"));
    }
    @Test void rejectsUnknownValuesAndOvernightIntervals() {
        assertThrows(IllegalArgumentException.class, () -> ResidentScheduleCommandParser.parseDay("noday"));
        assertThrows(IllegalArgumentException.class, () -> ResidentScheduleCommandParser.parseActivity("sleeping"));
        assertThrows(IllegalArgumentException.class, () -> ResidentScheduleCommandParser.parseDestinationType("homebase"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentSchedule.Entry(
                DayOfWeek.MONDAY, ResidentScheduleCommandParser.parseStartTime("22:00"),
                ResidentScheduleCommandParser.parseEndTime("06:00"),
                ResidentSchedule.Activity.SLEEP, ResidentDestination.DestinationType.HOME));
    }
}
