package ru.raskolnpc.npc.resident;

import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic rules for resident-initiated profession change requests.
 *
 * This policy only decides whether a resident is ready to ask. It does not
 * change a profession or bypass the town's existing authorization workflow.
 */
public final class ResidentProfessionChangePolicy {
    public static final long COOLDOWN_DAYS = 7;

    private static final int MIN_MOOD = 70;
    private static final int MIN_HUNGER = 50;
    private static final int MIN_ENERGY = 60;
    private static final int MIN_REST = 60;

    public enum Status {
        READY,
        COOLDOWN,
        NEEDS_RECOVERY,
        NO_MOTIVATION
    }

    public record Decision(Status status, long daysUntilAvailable,
            List<ResidentProfession> suggestedProfessions) {
        public Decision {
            Objects.requireNonNull(status, "status");
            if (daysUntilAvailable < 0) {
                throw new IllegalArgumentException("daysUntilAvailable cannot be negative.");
            }
            suggestedProfessions = List.copyOf(
                    Objects.requireNonNull(suggestedProfessions, "suggestedProfessions"));
        }

        public boolean canRequestChange() {
            return status == Status.READY && !suggestedProfessions.isEmpty();
        }
    }

    /**
     * Evaluates a resident using the world's monotonic full-time day counter.
     * A future last-change day is treated as cooldown rather than bypassed.
     */
    public Decision evaluate(ResidentProfile profile, long currentWorldDay,
            long lastProfessionChangeDay) {
        Objects.requireNonNull(profile, "profile");
        if (currentWorldDay < 0 || lastProfessionChangeDay < 0) {
            throw new IllegalArgumentException("World days cannot be negative.");
        }

        long elapsed = currentWorldDay - lastProfessionChangeDay;
        if (elapsed < COOLDOWN_DAYS) {
            return new Decision(Status.COOLDOWN,
                    COOLDOWN_DAYS - Math.max(0, elapsed), List.of());
        }

        if (profile.mood() < MIN_MOOD
                || profile.hunger() < MIN_HUNGER
                || profile.energy() < MIN_ENERGY
                || profile.rest() < MIN_REST) {
            return new Decision(Status.NEEDS_RECOVERY, 0, List.of());
        }

        boolean motivated = profile.traits().contains(ResidentProfile.ResidentTrait.CURIOUS)
                || profile.traits().contains(ResidentProfile.ResidentTrait.HARD_WORKING);
        if (!motivated) {
            return new Decision(Status.NO_MOTIVATION, 0, List.of());
        }

        List<ResidentProfession> alternatives = Arrays.stream(ResidentProfession.values())
                .filter(profession -> profession != profile.profession())
                .toList();
        return new Decision(Status.READY, 0, alternatives);
    }
}
