package ru.raskolnpc.integration;

import java.util.List;
import java.util.Objects;

/** Combines providers while ensuring any verified hostile result takes precedence. */
public final class CompositeDiplomacy implements RelationshipPolicy.Diplomacy {
    private final List<RelationshipPolicy.Diplomacy> providers;

    public CompositeDiplomacy(List<RelationshipPolicy.Diplomacy> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    @Override
    public RelationshipPolicy.DiplomacyRelation relation(String firstNationId, String secondNationId) {
        boolean allied = false;
        boolean known = false;
        for (RelationshipPolicy.Diplomacy provider : providers) {
            if (provider == null) continue;
            RelationshipPolicy.DiplomacyRelation result = provider.relation(firstNationId, secondNationId);
            if (result == RelationshipPolicy.DiplomacyRelation.HOSTILE) return result;
            if (result == RelationshipPolicy.DiplomacyRelation.ALLIED) allied = true;
            if (result == RelationshipPolicy.DiplomacyRelation.ALLIED
                    || result == RelationshipPolicy.DiplomacyRelation.NEUTRAL) known = true;
        }
        if (allied) return RelationshipPolicy.DiplomacyRelation.ALLIED;
        return known ? RelationshipPolicy.DiplomacyRelation.NEUTRAL
                : RelationshipPolicy.DiplomacyRelation.UNKNOWN;
    }
}