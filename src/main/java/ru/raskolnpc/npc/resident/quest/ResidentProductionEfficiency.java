package ru.raskolnpc.npc.resident.quest;

import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import java.util.Objects;

/** Deterministic production yield bonus granted by a resident's current profession mastery. */
public final class ResidentProductionEfficiency {
    private ResidentProductionEfficiency() {
    }

    public static int bonusPercent(ResidentSkillLevel skillLevel) {
        return switch (Objects.requireNonNull(skillLevel, "skillLevel")) {
            case APPRENTICE -> 0;
            case NOVICE -> 5;
            case SPECIALIST -> 10;
            case EXPERT -> 15;
            case MASTER -> 20;
        };
    }

    /**
     * Applies a tier bonus without changing recipe inputs. Fractional bonus items are
     * rounded down, so small batches remain predictable and never exceed recipe capacity
     * by an unbounded rounding rule.
     */
    public static int outputAmount(int baseAmount, ResidentSkillLevel skillLevel) {
        if (baseAmount < 1 || baseAmount > 2304) {
            throw new IllegalArgumentException("Base output amount must be between 1 and 2304.");
        }
        int bonus = bonusPercent(skillLevel);
        return baseAmount + (int) ((long) baseAmount * bonus / 100L);
    }
}
