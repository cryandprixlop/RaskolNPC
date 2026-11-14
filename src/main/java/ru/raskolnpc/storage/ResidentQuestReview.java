package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A resident quest submission that needs an administrator's decision. */
public record ResidentQuestReview(
        UUID playerId,
        NpcId residentId,
        String questId,
        String reason,
        Instant updatedAt,
        Integer reputationReward
) {
    public ResidentQuestReview {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
