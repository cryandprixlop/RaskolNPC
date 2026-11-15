package ru.raskolnpc.npc.resident.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidentSkillProgressionTest {
    @Test
    void mapsCumulativeExperienceToMasteryThresholds() {
        assertEquals(ResidentSkillLevel.APPRENTICE,
                ResidentSkillProgression.fromTotalExperience(99).level());
        assertEquals(ResidentSkillLevel.NOVICE,
                ResidentSkillProgression.fromTotalExperience(100).level());
        assertEquals(ResidentSkillLevel.SPECIALIST,
                ResidentSkillProgression.fromTotalExperience(300).level());
        assertEquals(ResidentSkillLevel.EXPERT,
                ResidentSkillProgression.fromTotalExperience(700).level());
        assertEquals(ResidentSkillLevel.MASTER,
                ResidentSkillProgression.fromTotalExperience(1500).level());
        assertEquals(0,
                ResidentSkillProgression.fromTotalExperience(1500).experienceToNextLevel());
    }
}
