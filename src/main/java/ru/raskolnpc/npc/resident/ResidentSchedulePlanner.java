package ru.raskolnpc.npc.resident;

import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.time.DayOfWeek;
import java.util.Objects;
import java.util.Optional;

/** Selects the schedule entry active at the supplied in-game day and minute. */
public final class ResidentSchedulePlanner {

    public Optional<ResidentSchedule.Entry> findCurrent(
            ResidentSchedule schedule, DayOfWeek day, int minuteOfDay) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(day, "day");
        if (minuteOfDay < 0 || minuteOfDay >= 1440) {
            throw new IllegalArgumentException("minuteOfDay must be between 0 and 1439.");
        }

        return schedule.entries().stream()
                .filter(entry -> entry.day() == day)
                .filter(entry -> minuteOfDay >= entry.startMinute()
                        && minuteOfDay < entry.endMinute())
                .findFirst();
    }
}
