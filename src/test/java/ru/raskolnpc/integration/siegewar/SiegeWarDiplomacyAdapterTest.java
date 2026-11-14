package ru.raskolnpc.integration.siegewar;

import com.gmail.goosius.siegewar.objects.Siege;
import com.palmergames.bukkit.towny.object.Government;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiegeWarDiplomacyAdapterTest {
    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEFENDER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void reportsBothDirectionsOfAnActiveSiegeAsHostile() {
        Siege siege = siege(ATTACKER, DEFENDER);
        var adapter = new SiegeWarDiplomacyAdapter(() -> List.of(siege));

        assertEquals(RelationshipPolicy.DiplomacyRelation.HOSTILE,
                adapter.relation(ATTACKER.toString(), DEFENDER.toString()));
        assertEquals(RelationshipPolicy.DiplomacyRelation.HOSTILE,
                adapter.relation(DEFENDER.toString(), ATTACKER.toString()));
    }

    @Test
    void leavesUnrelatedNationsUnknownWhenNoActiveSiegeExists() {
        var adapter = new SiegeWarDiplomacyAdapter(List::of);

        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                adapter.relation(ATTACKER.toString(), OTHER.toString()));
    }

    @Test
    void invalidOrMissingNationIdsRemainUnknown() {
        var adapter = new SiegeWarDiplomacyAdapter(List::of);

        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                adapter.relation("not-a-uuid", DEFENDER.toString()));
        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                adapter.relation(null, DEFENDER.toString()));
    }

    @Test
    void apiFailureFailsClosedToUnknown() {
        var adapter = new SiegeWarDiplomacyAdapter(() -> {
            throw new IllegalStateException("SiegeWar is stopping");
        });

        assertEquals(RelationshipPolicy.DiplomacyRelation.UNKNOWN,
                adapter.relation(ATTACKER.toString(), DEFENDER.toString()));
    }

    private static Siege siege(UUID attackerId, UUID defenderId) {
        Government attacker = mock(Government.class);
        Government defender = mock(Government.class);
        when(attacker.getUUID()).thenReturn(attackerId);
        when(defender.getUUID()).thenReturn(defenderId);
        Siege siege = mock(Siege.class);
        when(siege.getAttackingNationIfPossibleElseTown()).thenReturn(attacker);
        when(siege.getDefendingNationIfPossibleElseTown()).thenReturn(defender);
        return siege;
    }
}
