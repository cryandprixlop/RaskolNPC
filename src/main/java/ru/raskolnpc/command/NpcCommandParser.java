package ru.raskolnpc.command;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Optional;

public final class NpcCommandParser {
    private NpcCommandParser() {
    }

    public static Optional<NpcId> parseId(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        try {
            return Optional.of(NpcId.fromString(input));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static String joinName(String[] args, int start) {
        if (args.length <= start) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)).trim();
    }
}
