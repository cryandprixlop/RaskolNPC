package ru.raskolnpc.npc.resident.model;

/** Persisted profession mastery progress, expressed as cumulative experience. */
public record ResidentSkillProgress(
        ResidentSkillLevel level,
        int totalExperience,
        int experienceIntoLevel,
        int experienceToNextLevel) {
    public ResidentSkillProgress {
        if (level == null) throw new IllegalArgumentException("level is required");
        if (totalExperience < 0 || experienceIntoLevel < 0 || experienceToNextLevel < 0) {
            throw new IllegalArgumentException("Experience values cannot be negative");
        }
    }
}
