package ru.raskolnpc.npc.resident.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Persistent gameplay profile for a resident. All needs and mood values use
 * the inclusive 0..100 range; this class contains no Bukkit objects.
 */
public record ResidentProfile(
        NpcId npcId,
        int age,
        ResidentProfession profession,
        Set<ResidentTrait> traits,
        int hunger,
        int energy,
        int rest,
        int social,
        int housingSatisfaction,
        int mood,
        Map<ResidentProfession, ResidentSkillLevel> skills
) {
    public ResidentProfile {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(profession, "profession");
        Objects.requireNonNull(traits, "traits");
        Objects.requireNonNull(skills, "skills");
        if (age < 1 || age > 120) {
            throw new IllegalArgumentException("Resident age must be between 1 and 120.");
        }
        validateRange("hunger", hunger);
        validateRange("energy", energy);
        validateRange("rest", rest);
        validateRange("social", social);
        validateRange("housingSatisfaction", housingSatisfaction);
        validateRange("mood", mood);

        traits = traits.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(traits));
        EnumMap<ResidentProfession, ResidentSkillLevel> skillCopy =
                new EnumMap<>(ResidentProfession.class);
        skills.forEach((key, value) -> skillCopy.put(
                Objects.requireNonNull(key, "skill profession"),
                Objects.requireNonNull(value, "skill level")));
        skillCopy.putIfAbsent(profession, ResidentSkillLevel.APPRENTICE);
        skills = Collections.unmodifiableMap(skillCopy);
    }

    private static void validateRange(String field, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100.");
        }
    }

    public ResidentSkillLevel currentSkillLevel() {
        return skills.get(profession);
    }

    public enum ResidentTrait {
        HARD_WORKING,
        FRIENDLY,
        INTROVERTED,
        GENEROUS,
        CAUTIOUS,
        CURIOUS,
        DISCIPLINED,
        LAZY
    }
}
