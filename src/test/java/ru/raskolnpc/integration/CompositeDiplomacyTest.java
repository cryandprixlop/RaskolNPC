package ru.raskolnpc.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeDiplomacyTest {
    @Test
    void verifiedHostilityOverridesAlliance() {
        var diplomacy = new CompositeDiplomacy(List.of(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.ALLIED,
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE));

        assertEquals(RelationshipPolicy.DiplomacyRelation.HOSTILE,
                diplomacy.relation("first", "second"));
    }

    @Test
    void fallsBackToKnownTownyResultWhenSiegeWarHasNoActiveSiege() {
        var diplomacy = new CompositeDiplomacy(List.of(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                (first, second) -> RelationshipPolicy.DiplomacyRelation.ALLIED));

        assertEquals(RelationshipPolicy.DiplomacyRelation.ALLIED,
                diplomacy.relation("first", "second"));
    }

    @Test
    void unknownProvidersDoNotCreateHostility() {
        var diplomacy = new CompositeDiplomacy(List.of(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.UNKNOWN));

        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                diplomacy.relation("first", "second"));
    }
}