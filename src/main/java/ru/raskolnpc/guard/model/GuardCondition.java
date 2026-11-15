package ru.raskolnpc.guard.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Objects;

/** Persistent combat condition for one kingdom guard. */
public record GuardCondition(NpcId npcId, int morale, long lastCombatAt, long updatedAt,
        int injurySeverity, long lastInjuryRecoveryAt) {
    public GuardCondition(NpcId npcId, int morale, long lastCombatAt, long updatedAt) {
        this(npcId, morale, lastCombatAt, updatedAt, 0, 0);
    }

    public GuardCondition {
        Objects.requireNonNull(npcId, "npcId");
        if (morale < 0 || morale > 100) {
            throw new IllegalArgumentException("morale must be between 0 and 100.");
        }
        if (lastCombatAt < 0 || updatedAt <= 0) {
            throw new IllegalArgumentException("Condition timestamps are invalid.");
        }
        if (injurySeverity < 0 || injurySeverity > 2 || lastInjuryRecoveryAt < 0) {
            throw new IllegalArgumentException("Injury state is invalid.");
        }
    }

    /** Combines morale and injury penalties; severe injuries materially reduce combat output. */
    public double damageMultiplier() {
        double moraleMultiplier = 0.5 + morale / 200.0;
        double injuryMultiplier = switch (injurySeverity) {
            case 1 -> 0.85;
            case 2 -> 0.60;
            default -> 1.0;
        };
        return moraleMultiplier * injuryMultiplier;
    }

    public GuardCondition withMorale(int newMorale, long combatAt, long now) {
        return new GuardCondition(npcId, Math.max(0, Math.min(100, newMorale)),
                combatAt, Math.max(now, updatedAt + 1), injurySeverity, lastInjuryRecoveryAt);
    }

    public GuardCondition withCombatState(int newMorale, int newInjurySeverity, long now) {
        return new GuardCondition(npcId, Math.max(0, Math.min(100, newMorale)),
                now, Math.max(now, updatedAt + 1),
                Math.max(0, Math.min(2, newInjurySeverity)),
                newInjurySeverity > 0 ? now : 0);
    }

    public GuardCondition withRecovery(int newMorale, int newInjurySeverity, long recoveryAt, long now) {
        return new GuardCondition(npcId, Math.max(0, Math.min(100, newMorale)),
                lastCombatAt, Math.max(now, updatedAt + 1),
                Math.max(0, Math.min(2, newInjurySeverity)),
                newInjurySeverity > 0 ? recoveryAt : 0);
    }
}
