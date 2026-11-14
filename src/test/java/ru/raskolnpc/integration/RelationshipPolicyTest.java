package ru.raskolnpc.integration;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationshipPolicyTest {
    @Test
    void sameOwnerTownOrNationIsNeverHostile() {
        RelationshipPolicy.Diplomacy diplomacy = mock(RelationshipPolicy.Diplomacy.class);
        RelationshipPolicy policy = new RelationshipPolicy(diplomacy);
        UUID owner = UUID.randomUUID();

        assertEquals(RelationshipPolicy.Relation.SELF, policy.between(
                new RelationshipPolicy.Subject(owner, "town-a", "nation-a"),
                new RelationshipPolicy.Subject(owner, "town-b", "nation-b")));
        assertEquals(RelationshipPolicy.Relation.ALLY, policy.between(
                new RelationshipPolicy.Subject(null, "town-a", "nation-a"),
                new RelationshipPolicy.Subject(null, "TOWN-A", "nation-b")));
        assertEquals(RelationshipPolicy.Relation.ALLY, policy.between(
                new RelationshipPolicy.Subject(null, "town-a", "nation-a"),
                new RelationshipPolicy.Subject(null, "town-b", "NATION-A")));
        verifyNoInteractions(diplomacy);
    }

    @Test
    void onlyExplicitHostileDiplomacyAllowsTargeting() {
        RelationshipPolicy.Diplomacy diplomacy = (first, second) ->
                RelationshipPolicy.DiplomacyRelation.HOSTILE;
        RelationshipPolicy policy = new RelationshipPolicy(diplomacy);

        assertTrue(policy.mayTarget(
                new RelationshipPolicy.Subject(null, "town-a", "nation-a"),
                new RelationshipPolicy.Subject(null, "town-b", "nation-b")));
    }

    @Test
    void unknownOrMissingDiplomacyFailsClosedAsNeutral() {
        RelationshipPolicy policy = new RelationshipPolicy((first, second) ->
                RelationshipPolicy.DiplomacyRelation.UNKNOWN);

        var first = new RelationshipPolicy.Subject(null, "town-a", "nation-a");
        var second = new RelationshipPolicy.Subject(null, "town-b", "nation-b");
        assertEquals(RelationshipPolicy.Relation.NEUTRAL, policy.between(first, second));
        assertFalse(policy.mayTarget(first, second));
    }
}
