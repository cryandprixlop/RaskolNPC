package ru.raskolnpc.integration.towny;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownyDiplomacyAdapterTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Test
    void requiresReciprocalAlliance() {
        assertEquals(RelationshipPolicy.DiplomacyRelation.ALLIED,
                TownyDiplomacyAdapter.mapRelations(false, false, true, true));
        assertEquals(RelationshipPolicy.DiplomacyRelation.NEUTRAL,
                TownyDiplomacyAdapter.mapRelations(false, false, true, false));
    }

    @Test
    void eitherDeclaredEnemyDirectionIsHostile() {
        assertEquals(RelationshipPolicy.DiplomacyRelation.HOSTILE,
                TownyDiplomacyAdapter.mapRelations(false, true, true, true));
    }

    @Test
    void invalidIdsAndMissingTownyDataRemainUnknown() {
        var missing = new TownyDiplomacyAdapter((first, second) -> null);
        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                missing.relation(FIRST.toString(), SECOND.toString()));
        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                missing.relation("bad-id", SECOND.toString()));
    }

    @Test
    void providerFailureRemainsUnknown() {
        var unavailable = new TownyDiplomacyAdapter((first, second) -> {
            throw new IllegalStateException("Towny is stopping");
        });
        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                unavailable.relation(FIRST.toString(), SECOND.toString()));
    }
}