package ru.raskolnpc.guard.model;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;

import static org.junit.jupiter.api.Assertions.*;

class GuardConditionTest {
    @Test
    void moraleScalesDamageFromHalfToFullStrength() {
        NpcId id = NpcId.create();
        assertEquals(0.5, new GuardCondition(id, 0, 1, 1).damageMultiplier());
        assertEquals(0.75, new GuardCondition(id, 50, 1, 1).damageMultiplier());
        assertEquals(1.0, new GuardCondition(id, 100, 1, 1).damageMultiplier());
    }

    @Test
    void moraleIsClampedAndConditionTimestampMovesForward() {
        NpcId id = NpcId.create();
        GuardCondition initial = new GuardCondition(id, 4, 100, 200);
        GuardCondition weakened = initial.withMorale(-10, 300, 150);
        assertEquals(0, weakened.morale());
        assertEquals(300, weakened.lastCombatAt());
        assertEquals(201, weakened.updatedAt());

        GuardCondition recovered = weakened.withMorale(120, 300, 400);
        assertEquals(100, recovered.morale());
        assertEquals(400, recovered.updatedAt());
    }

    @Test
    void injuriesReduceCombatOutputAndRecoverInStages() {
        NpcId id = NpcId.create();
        GuardCondition light = new GuardCondition(id, 100, 1, 1, 1, 100);
        GuardCondition severe = new GuardCondition(id, 100, 1, 1, 2, 100);
        assertEquals(0.85, light.damageMultiplier());
        assertEquals(0.60, severe.damageMultiplier());

        GuardCondition recovering = severe.withRecovery(100, 1, 200, 300);
        assertEquals(1, recovering.injurySeverity());
        assertEquals(200, recovering.lastInjuryRecoveryAt());
        assertEquals(0.85, recovering.damageMultiplier());
    }

    @Test
    void rejectsInvalidInjurySeverityAndTimestamp() {
        NpcId id = NpcId.create();
        assertThrows(IllegalArgumentException.class,
                () -> new GuardCondition(id, 100, 1, 1, 3, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardCondition(id, 100, 1, 1, 1, -1));
    }

    @Test
    void rejectsOutOfRangeMoraleAndInvalidTimestamps() {
        NpcId id = NpcId.create();
        assertThrows(IllegalArgumentException.class,
                () -> new GuardCondition(id, 101, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardCondition(id, 50, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GuardCondition(id, 50, 1, 0));
    }
}
