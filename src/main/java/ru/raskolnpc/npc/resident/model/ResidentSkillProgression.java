package ru.raskolnpc.npc.resident.model;

/** Centralized cumulative XP thresholds for resident profession mastery. */
public final class ResidentSkillProgression {
    private static final int[] THRESHOLDS = {0, 100, 300, 700, 1500};

    private ResidentSkillProgression() { }

    public static ResidentSkillProgress fromTotalExperience(int experience) {
        if (experience < 0) throw new IllegalArgumentException("experience cannot be negative");
        int bounded = Math.min(experience, Integer.MAX_VALUE);
        int tier = 0;
        for (int i = 1; i < THRESHOLDS.length; i++) {
            if (bounded >= THRESHOLDS[i]) tier = i;
            else break;
        }
        int floor = THRESHOLDS[tier];
        int next = tier == THRESHOLDS.length - 1 ? 0 : THRESHOLDS[tier + 1] - bounded;
        return new ResidentSkillProgress(
                ResidentSkillLevel.values()[tier], bounded, bounded - floor, next);
    }
}
