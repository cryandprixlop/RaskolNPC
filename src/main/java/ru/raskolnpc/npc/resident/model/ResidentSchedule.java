package ru.raskolnpc.npc.resident.model;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Weekly resident schedule. Times are minutes from local midnight (0..1440),
 * with each entry contained within one calendar day.
 */
public record ResidentSchedule(List<Entry> entries) {
    public ResidentSchedule {
        Objects.requireNonNull(entries, "entries");
        List<Entry> copy = new ArrayList<>(entries);
        copy.forEach(entry -> Objects.requireNonNull(entry, "schedule entry"));
        copy.sort(Comparator.comparing(Entry::day)
                .thenComparingInt(Entry::startMinute));
        for (int i = 1; i < copy.size(); i++) {
            Entry previous = copy.get(i - 1);
            Entry current = copy.get(i);
            if (previous.day() == current.day()
                    && current.startMinute() < previous.endMinute()) {
                throw new IllegalArgumentException("Resident schedule entries must not overlap.");
            }
        }
        entries = List.copyOf(copy);
    }

    public record Entry(
            DayOfWeek day,
            int startMinute,
            int endMinute,
            Activity activity,
            ResidentDestination.DestinationType destinationType
    ) {
        public Entry {
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(destinationType, "destinationType");
            if (startMinute < 0 || startMinute >= 1440
                    || endMinute <= 0 || endMinute > 1440
                    || startMinute >= endMinute) {
                throw new IllegalArgumentException(
                        "Schedule interval must be within one day and have positive duration.");
            }
        }
    }

    public enum Activity {
        SLEEP,
        WORK,
        EAT,
        SOCIALIZE,
        LEISURE,
        REST,
        ERRAND
    }
}
