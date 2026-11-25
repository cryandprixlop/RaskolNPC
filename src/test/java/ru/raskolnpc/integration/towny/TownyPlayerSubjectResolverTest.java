package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
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

        Resident resident = mock(Resident.class);
        Town town = mock(Town.class);
        Nation nation = mock(Nation.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getResident(player)).thenReturn(resident);
        when(resident.getTownOrNull()).thenReturn(town);
        when(api.getTownNationOrNull(town)).thenReturn(nation);
        when(town.getUUID()).thenReturn(townId);
        when(nation.getUUID()).thenReturn(nationId);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);

            RelationshipPolicy.Subject subject =
                    new TownyPlayerSubjectResolver(() -> true).resolve(player).orElseThrow();

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
        Resident resident = mock(Resident.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getResident(player)).thenReturn(resident);
        when(resident.getTownOrNull()).thenReturn(null);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);

            RelationshipPolicy.Subject subject =
                    new TownyPlayerSubjectResolver(() -> true).resolve(player).orElseThrow();

            assertEquals(playerId, subject.ownerId());
            assertNull(subject.townId());
            assertNull(subject.nationId());
            verify(api, never()).getTownNationOrNull(any());
        }
    }

    @Test
    void failsClosedWhenTownyLookupThrows() {
        Player player = mock(Player.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getResident(player)).thenThrow(new IllegalStateException("Towny unavailable"));

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);

            assertTrue(new TownyPlayerSubjectResolver(() -> true).resolve(player).isEmpty());
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
