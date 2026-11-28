package ru.raskolnpc.integration.towny;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownyPlayerSubjectResolverTest {
    @Test
    void returnsEmptyWhenPlayerHasNoTownyResident() {
        Player player = mock(Player.class);
        TownyPlayerSubjectResolver.MembershipLookup lookup = mock(
                TownyPlayerSubjectResolver.MembershipLookup.class);
        when(lookup.resolve(player)).thenReturn(Optional.empty());

        assertTrue(new TownyPlayerSubjectResolver(() -> true, lookup).resolve(player).isEmpty());
    }

    @Test
    void resolvesTownAndNationMembershipFromResident() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        UUID townId = UUID.randomUUID();
        UUID nationId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> true,
                ignored -> Optional.of(new TownyPlayerSubjectResolver.Membership(
                        townId.toString(), nationId.toString())));

        RelationshipPolicy.Subject subject = resolver.resolve(player).orElseThrow();

        assertEquals(playerId, subject.ownerId());
        assertEquals(townId.toString(), subject.townId());
        assertEquals(nationId.toString(), subject.nationId());
    }

    @Test
    void returnsPlayerWithNoTownAndNationWhenResidentIsUnaffiliated() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> true,
                ignored -> Optional.of(new TownyPlayerSubjectResolver.Membership(null, null)));

        RelationshipPolicy.Subject subject = resolver.resolve(player).orElseThrow();

        assertEquals(playerId, subject.ownerId());
        assertNull(subject.townId());
        assertNull(subject.nationId());
    }

    @Test
    void failsClosedWhenTownyLookupThrows() {
        Player player = mock(Player.class);
        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> true,
                ignored -> { throw new IllegalStateException("Towny unavailable"); });

        assertTrue(resolver.resolve(player).isEmpty());
    }

    @Test
    void rejectsNullPlayer() {
        assertThrows(NullPointerException.class,
                () -> new TownyPlayerSubjectResolver(() -> true).resolve(null));
    }

    @Test
    void returnsEmptyWithoutCallingTownyWhenPluginIsDisabled() {
        Player player = mock(Player.class);
        TownyPlayerSubjectResolver.MembershipLookup lookup = mock(
                TownyPlayerSubjectResolver.MembershipLookup.class);

        assertTrue(new TownyPlayerSubjectResolver(() -> false, lookup).resolve(player).isEmpty());
        verifyNoInteractions(lookup);
    }
}
