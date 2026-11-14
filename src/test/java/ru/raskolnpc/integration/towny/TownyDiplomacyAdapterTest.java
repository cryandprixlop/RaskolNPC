package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.object.Nation;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TownyDiplomacyAdapterTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Test
    void requiresReciprocalAlliance() {
        Nation first = mock(Nation.class);
        Nation second = mock(Nation.class);
        when(first.hasAlly(second)).thenReturn(true);
        when(second.hasAlly(first)).thenReturn(true);
        var adapter = new TownyDiplomacyAdapter(id -> Map.of(FIRST, first, SECOND, second).get(id));

        assertEquals(RelationshipPolicy.DiplomacyRelation.ALLIED,
                adapter.relation(FIRST.toString(), SECOND.toString()));
    }

    @Test
    void eitherDeclaredEnemyDirectionIsHostile() {
        Nation first = mock(Nation.class);
        Nation second = mock(Nation.class);
        when(first.hasEnemy(second)).thenReturn(false);
        when(second.hasEnemy(first)).thenReturn(true);
        var adapter = new TownyDiplomacyAdapter(id -> Map.of(FIRST, first, SECOND, second).get(id));

        assertEquals(RelationshipPolicy.DiplomacyRelation.HOSTILE,
                adapter.relation(FIRST.toString(), SECOND.toString()));
    }

    @Test
    void missingNationDataIsUnknownAndExistingUnrelatedNationsAreNeutral() {
        var missing = new TownyDiplomacyAdapter(id -> null);
        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                missing.relation(FIRST.toString(), SECOND.toString()));

        Nation first = mock(Nation.class);
        Nation second = mock(Nation.class);
        var neutral = new TownyDiplomacyAdapter(id -> Map.of(FIRST, first, SECOND, second).get(id));
        assertEquals(RelationshipPolicy.DiplomacyRelation.NEUTRAL,
                neutral.relation(FIRST.toString(), SECOND.toString()));
    }
}