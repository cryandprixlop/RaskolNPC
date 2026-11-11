package ru.raskolnpc.integration.towny;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownyResidentServiceTest {
    @Test
    void permitsMayorAndReturnsTownAndNationIds() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        String townId = UUID.randomUUID().toString();
        String nationId = UUID.randomUUID().toString();
        TownyResidentService.TownyAccess api = (p, l) ->
                new TownyResidentService.TownContext(townId, nationId, true, false);
        TownyResidentService.TownAssignment result =
                new TownyResidentService(api).resolveAndAuthorize(player, location);
        assertEquals(townId, result.townId());
        assertEquals(nationId, result.nationId());
        verifyNoInteractions(player);
    }

    @Test
    void permitsAssistantEvenWhenNotMayor() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        String townId = UUID.randomUUID().toString();
        TownyResidentService.TownyAccess api = (p, l) ->
                new TownyResidentService.TownContext(townId, null, false, true);
        TownyResidentService.TownAssignment result =
                new TownyResidentService(api).resolveAndAuthorize(player, location);
        assertEquals(townId, result.townId());
        assertNull(result.nationId());
    }

    @Test
    void rejectsPlayersWithoutTownManagementOrAdminPermission() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        TownyResidentService.TownyAccess api = (p, l) ->
                new TownyResidentService.TownContext(UUID.randomUUID().toString(), null, false, false);
        when(player.hasPermission("towny.admin")).thenReturn(false);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(false);
        assertThrows(TownyIntegrationException.class,
                () -> new TownyResidentService(api).resolveAndAuthorize(player, location));
    }

    @Test
    void rejectsLocationsOutsideTownyTowns() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        TownyResidentService.TownyAccess api = (p, l) -> null;
        assertThrows(TownyIntegrationException.class,
                () -> new TownyResidentService(api).resolveAndAuthorize(player, location));
        verifyNoInteractions(player);
    }

    @Test
    void allowsRaskolNpcAdministratorWithoutTownRank() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        String townId = UUID.randomUUID().toString();
        TownyResidentService.TownyAccess api = (p, l) ->
                new TownyResidentService.TownContext(townId, null, false, false);
        when(player.hasPermission("towny.admin")).thenReturn(false);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(true);
        TownyResidentService.TownAssignment result =
                new TownyResidentService(api).resolveAndAuthorize(player, location);
        assertEquals(townId, result.townId());
    }
}
