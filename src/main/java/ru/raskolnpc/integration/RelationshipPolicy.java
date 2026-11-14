package ru.raskolnpc.integration;

import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral relationship policy shared by NPC subsystems.
 * Missing diplomacy information is neutral; it is never interpreted as hostility.
 */
public final class RelationshipPolicy {
    private final Diplomacy diplomacy;

    public RelationshipPolicy(Diplomacy diplomacy) {
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
    }

    public Relation between(Subject first, Subject second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.ownerId() != null && first.ownerId().equals(second.ownerId())) {
            return Relation.SELF;
        }
        if (same(first.townId(), second.townId()) && first.townId() != null) {
            return Relation.ALLY;
        }
        if (same(first.nationId(), second.nationId()) && first.nationId() != null) {
            return Relation.ALLY;
        }
        if (first.nationId() == null || second.nationId() == null) {
            return Relation.NEUTRAL;
        }
        DiplomacyRelation result = diplomacy.relation(first.nationId(), second.nationId());
        return switch (result) {
            case ALLIED -> Relation.ALLY;
            case HOSTILE -> Relation.HOSTILE;
            case NEUTRAL, UNKNOWN -> Relation.NEUTRAL;
        };
    }

    public boolean mayTarget(Subject actor, Subject target) {
        return between(actor, target) == Relation.HOSTILE;
    }

    private static boolean same(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    public enum Relation {
        SELF,
        ALLY,
        NEUTRAL,
        HOSTILE
    }

    public enum DiplomacyRelation {
        ALLIED,
        NEUTRAL,
        HOSTILE,
        UNKNOWN
    }

    @FunctionalInterface
    public interface Diplomacy {
        DiplomacyRelation relation(String firstNationId, String secondNationId);
    }

    public record Subject(UUID ownerId, String townId, String nationId) { }
}
