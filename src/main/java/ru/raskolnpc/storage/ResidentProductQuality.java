package ru.raskolnpc.storage;

import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

/** Persisted quality tier for one immutable batch of resident business stock. */
public enum ResidentProductQuality {
    BASIC,
    STANDARD,
    FINE,
    MASTERWORK;

    /** Maps persisted profession mastery to the quality of newly crafted goods. */
    public static ResidentProductQuality forSkillLevel(ResidentSkillLevel skillLevel) {
        return switch (java.util.Objects.requireNonNull(skillLevel, "skillLevel")) {
            case APPRENTICE -> BASIC;
            case NOVICE -> STANDARD;
            case SPECIALIST -> FINE;
            case EXPERT, MASTER -> MASTERWORK;
        };
    }
}
