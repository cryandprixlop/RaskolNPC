package ru.raskolnpc.storage;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResidentProductQualityTest {
    @Test
    void mapsEveryProfessionTierToDeterministicProductQuality() {
        assertEquals(ResidentProductQuality.BASIC,
                ResidentProductQuality.forSkillLevel(ResidentSkillLevel.APPRENTICE));
        assertEquals(ResidentProductQuality.STANDARD,
                ResidentProductQuality.forSkillLevel(ResidentSkillLevel.NOVICE));
        assertEquals(ResidentProductQuality.FINE,
                ResidentProductQuality.forSkillLevel(ResidentSkillLevel.SPECIALIST));
        assertEquals(ResidentProductQuality.MASTERWORK,
                ResidentProductQuality.forSkillLevel(ResidentSkillLevel.EXPERT));
        assertEquals(ResidentProductQuality.MASTERWORK,
                ResidentProductQuality.forSkillLevel(ResidentSkillLevel.MASTER));
    }

    @Test
    void rejectsMissingSkillTier() {
        assertThrows(NullPointerException.class,
                () -> ResidentProductQuality.forSkillLevel(null));
    }
}
