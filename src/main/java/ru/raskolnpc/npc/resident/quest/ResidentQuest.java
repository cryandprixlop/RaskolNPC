package ru.raskolnpc.npc.resident.quest;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable definition and persisted assignment data for a resident delivery quest. */
public record ResidentQuest(
        String id,
        ResidentProfession profession,
        String requiredMaterial,
        int requiredAmount,
        int reputationReward
) {
    public ResidentQuest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profession, "profession");
        Objects.requireNonNull(requiredMaterial, "requiredMaterial");
        id = id.trim().toLowerCase(Locale.ROOT);
        requiredMaterial = requiredMaterial.trim().toUpperCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Quest id must contain 1-64 lowercase letters, digits, '.', '_' or '-'.");
        }
        if (!requiredMaterial.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Required material must be a Bukkit material key.");
        }
        if (requiredAmount < 1 || requiredAmount > 2304) {
            throw new IllegalArgumentException("Required amount must be between 1 and 2304.");
        }
        if (reputationReward < 0 || reputationReward > 10000) {
            throw new IllegalArgumentException("Reputation reward must be between 0 and 10000.");
        }
    }

    public enum AssignmentStatus {
        ACCEPTED,
        COMPLETED
    }

    /** A player's persisted assignment to one resident's quest. */
    public record Assignment(
            UUID playerId,
            NpcId residentId,
            String questId,
            AssignmentStatus status,
            Instant acceptedAt,
            Instant completedAt
    ) {
        public Assignment {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(residentId, "residentId");
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            questId = questId.trim().toLowerCase(Locale.ROOT);
            if (!questId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Invalid quest id.");
            }
            if (status == AssignmentStatus.ACCEPTED && completedAt != null) {
                throw new IllegalArgumentException("An accepted quest cannot have a completion time.");
            }
            if (status == AssignmentStatus.COMPLETED && completedAt == null) {
                throw new IllegalArgumentException("A completed quest must have a completion time.");
            }
        }
    }
}
