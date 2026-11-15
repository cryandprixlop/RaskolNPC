package ru.raskolnpc.guard.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Objects;

/** Persistent combat condition for one kingdom guard. */
public record GuardCondition(NpcId npcId, int morale, long lastCombatAt, long updatedAt) {
    public GuardCondition {
        Objects.requireNonNull(npcId, "npcId");
        if (morale < 0 || morale > 100) {
            throw new IllegalArgumentException("morale must be between 0 and 100.");
        }
        if (lastCombatAt < 0 || updatedAt <= 0) {
            throw new IllegalArgumentException("Condition timestamps are invalid.");
        }
    }

    /** Keeps a weakened guard useful while making low morale meaningfully costly. */
    public double damageMultiplier() {
        return 0.5 + morale / 200.0;
    }

    public GuardCondition withMorale(int newMorale, long combatAt, long now) {
        return new GuardCondition(npcId, Math.max(0, Math.min(100, newMorale)),
                combatAt, Math.max(now, updatedAt + 1));
    }
}
