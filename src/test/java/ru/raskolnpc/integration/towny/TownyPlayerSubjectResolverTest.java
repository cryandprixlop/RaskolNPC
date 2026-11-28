package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownyPlayerSubjectResolverTest {
    @Test
    void returnsEmptyWhenPlayerHasNoTownyResident() {
        Player player = mock(Player.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getResident(player)).thenReturn(null);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);

            assertTrue(new TownyPlayerSubjectResolver(() -> true).resolve(player).isEmpty());
        }
    }

    @Test
    void resolvesTownAndNationMembershipFromResident() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        UUID townId = UUID.randomUUID();
        UUID nationId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> true,
                ignored -> java.util.Optional.of(new TownyPlayerSubjectResolver.Membership(
                        townId.toString(), nationId.toString())));
        RelationshipPolicy.Subject subject = resolver.resolve(player).orElseThrow();

            assertEquals(playerId, subject.ownerId());
            assertEquals(townId.toString(), subject.townId());
            assertEquals(nationId.toString(), subject.nationId());
        }
    }

    @Test
    void returnsPlayerWithNoTownAndNationWhenResidentIsUnaffiliated() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> true,
                ignored -> java.util.Optional.of(new TownyPlayerSubjectResolver.Membership(null, null)));
        RelationshipPolicy.Subject subject = resolver.resolve(player).orElseThrow();

            assertEquals(playerId, subject.ownerId());
            assertNull(subject.townId());
            assertNull(subject.nationId());
        }
    }

    @Test
    void failsClosedWhenTownyLookupThrows() {
        Player player = mock(Player.class);
        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> true,
                ignored -> { throw new IllegalStateException("Towny unavailable"); });
        assertTrue(resolver.resolve(player).isEmpty());
        }
    }

    @Test
    void rejectsNullPlayer() {
        assertThrows(NullPointerException.class,
                () -> new TownyPlayerSubjectResolver(() -> true).resolve(null));
    }

    @Test
    void returnsEmptyWithoutCallingTownyWhenPluginIsDisabled() {
        Player player = mock(Player.class);
        TownyPlayerSubjectResolver resolver = new TownyPlayerSubjectResolver(() -> false);
        assertTrue(resolver.resolve(player).isEmpty());
    }
}
