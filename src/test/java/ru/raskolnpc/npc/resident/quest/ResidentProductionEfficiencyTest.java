package ru.raskolnpc.npc.resident.quest;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import static org.junit.jupiter.api.Assertions.*;

class ResidentProductionEfficiencyTest {
    @Test
    void masteryTiersGrantProgressivelyLargerProductionBonuses() {
        assertEquals(0, ResidentProductionEfficiency.bonusPercent(ResidentSkillLevel.APPRENTICE));
        assertEquals(5, ResidentProductionEfficiency.bonusPercent(ResidentSkillLevel.NOVICE));
        assertEquals(10, ResidentProductionEfficiency.bonusPercent(ResidentSkillLevel.SPECIALIST));
        assertEquals(15, ResidentProductionEfficiency.bonusPercent(ResidentSkillLevel.EXPERT));
        assertEquals(20, ResidentProductionEfficiency.bonusPercent(ResidentSkillLevel.MASTER));
    }

    @Test
    void appliesBonusToOutputAndRoundsFractionalItemsDown() {
        assertEquals(4, ResidentProductionEfficiency.outputAmount(4, ResidentSkillLevel.APPRENTICE));
        assertEquals(4, ResidentProductionEfficiency.outputAmount(4, ResidentSkillLevel.NOVICE));
        assertEquals(4, ResidentProductionEfficiency.outputAmount(4, ResidentSkillLevel.SPECIALIST));
        assertEquals(4, ResidentProductionEfficiency.outputAmount(4, ResidentSkillLevel.EXPERT));
        assertEquals(4, ResidentProductionEfficiency.outputAmount(4, ResidentSkillLevel.MASTER));
        assertEquals(12, ResidentProductionEfficiency.outputAmount(10, ResidentSkillLevel.SPECIALIST));
        assertEquals(12, ResidentProductionEfficiency.outputAmount(10, ResidentSkillLevel.MASTER));
        assertEquals(120, ResidentProductionEfficiency.outputAmount(100, ResidentSkillLevel.MASTER));
    }

    @Test
    void rejectsInvalidOutputAmountsAndNullMastery() {
        assertThrows(IllegalArgumentException.class,
                () -> ResidentProductionEfficiency.outputAmount(0, ResidentSkillLevel.MASTER));
        assertThrows(IllegalArgumentException.class,
                () -> ResidentProductionEfficiency.outputAmount(2305, ResidentSkillLevel.MASTER));
        assertThrows(NullPointerException.class,
                () -> ResidentProductionEfficiency.bonusPercent(null));
    }
}
