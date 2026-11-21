package ru.raskolnpc.command;

import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.time.DayOfWeek;
import java.util.Locale;
import java.util.Map;

/** Strict parser for resident schedule command arguments. */
public final class ResidentScheduleCommandParser {
    private static final Map<String, DayOfWeek> DAYS = Map.ofEntries(
            Map.entry("MONDAY", DayOfWeek.MONDAY), Map.entry("ПОНЕДЕЛЬНИК", DayOfWeek.MONDAY), Map.entry("ПН", DayOfWeek.MONDAY),
            Map.entry("TUESDAY", DayOfWeek.TUESDAY), Map.entry("ВТОРНИК", DayOfWeek.TUESDAY), Map.entry("ВТ", DayOfWeek.TUESDAY),
            Map.entry("WEDNESDAY", DayOfWeek.WEDNESDAY), Map.entry("СРЕДА", DayOfWeek.WEDNESDAY), Map.entry("СР", DayOfWeek.WEDNESDAY),
            Map.entry("THURSDAY", DayOfWeek.THURSDAY), Map.entry("ЧЕТВЕРГ", DayOfWeek.THURSDAY), Map.entry("ЧТ", DayOfWeek.THURSDAY),
            Map.entry("FRIDAY", DayOfWeek.FRIDAY), Map.entry("ПЯТНИЦА", DayOfWeek.FRIDAY), Map.entry("ПТ", DayOfWeek.FRIDAY),
            Map.entry("SATURDAY", DayOfWeek.SATURDAY), Map.entry("СУББОТА", DayOfWeek.SATURDAY), Map.entry("СБ", DayOfWeek.SATURDAY),
            Map.entry("SUNDAY", DayOfWeek.SUNDAY), Map.entry("ВОСКРЕСЕНЬЕ", DayOfWeek.SUNDAY), Map.entry("ВС", DayOfWeek.SUNDAY));

    private ResidentScheduleCommandParser() { }

    public static DayOfWeek parseDay(String value) {
        DayOfWeek day = DAYS.get(normalize(value));
        if (day == null) throw new IllegalArgumentException("Неизвестный день недели: " + value);
        return day;
    }
    public static int parseStartTime(String value) { return parseTime(value, false); }
    public static int parseEndTime(String value) { return parseTime(value, true); }

    private static int parseTime(String value, boolean allowEndOfDay) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d" + (allowEndOfDay ? "|24:00" : "")))
            throw new IllegalArgumentException("Время указывай строго в формате HH:mm"
                    + (allowEndOfDay ? " (конец может быть 24:00)." : "."));
        if (value.equals("24:00")) return 1440;
        String[] parts = value.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public static ResidentSchedule.Activity parseActivity(String value) {
        try { return ResidentSchedule.Activity.valueOf(normalize(value)); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Неизвестное занятие: " + value); }
    }
    public static ResidentDestination.DestinationType parseDestinationType(String value) {
        try { return ResidentDestination.DestinationType.valueOf(normalize(value)); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Неизвестный тип точки: " + value); }
    }
    public static String formatTime(int minute) {
        if (minute == 1440) return "24:00";
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }
    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Параметр не может быть пустым.");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
