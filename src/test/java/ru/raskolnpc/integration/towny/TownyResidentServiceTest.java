package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownyResidentServiceTest {

    @Test
    void permitsMayorAndReturnsTownAndNationIds() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        Town town = mock(Town.class);
        Resident resident = mock(Resident.class);
        Nation nation = mock(Nation.class);
        UUID townId = UUID.randomUUID();
        UUID nationId = UUID.randomUUID();
        TownyAPI api = mock(TownyAPI.class);

        when(api.getTown(location)).thenReturn(town);
        when(api.getResident(player)).thenReturn(resident);
        when(town.isMayor(resident)).thenReturn(true);
        when(town.getUUID()).thenReturn(townId);
        when(api.getTownNationOrNull(town)).thenReturn(nation);
        when(nation.getUUID()).thenReturn(nationId);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);

            TownyResidentService.TownAssignment assignment =
                    new TownyResidentService().resolveAndAuthorize(player, location);

            assertEquals(townId.toString(), assignment.townId());
            assertEquals(nationId.toString(), assignment.nationId());
        }
    }

    @Test
    void permitsAssistantEvenWhenNotMayor() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        Town town = mock(Town.class);
        Resident resident = mock(Resident.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getTown(location)).thenReturn(town);
        when(api.getResident(player)).thenReturn(resident);
        when(town.isMayor(resident)).thenReturn(false);
        when(town.hasResidentWithRank(resident, "assistant")).thenReturn(true);
        when(town.getUUID()).thenReturn(UUID.randomUUID());
        when(api.getTownNationOrNull(town)).thenReturn(null);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);
            TownyResidentService.TownAssignment assignment =
                    new TownyResidentService().resolveAndAuthorize(player, location);
            assertNotNull(assignment.townId());
            assertNull(assignment.nationId());
        }
    }

    @Test
    void rejectsPlayersWithoutTownManagementOrAdminPermission() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        Town town = mock(Town.class);
        Resident resident = mock(Resident.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getTown(location)).thenReturn(town);
        when(api.getResident(player)).thenReturn(resident);
        when(town.isMayor(resident)).thenReturn(false);
        when(town.hasResidentWithRank(resident, "assistant")).thenReturn(false);
        when(player.hasPermission("towny.admin")).thenReturn(false);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(false);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);
            assertThrows(TownyIntegrationException.class,
                    () -> new TownyResidentService().resolveAndAuthorize(player, location));
            verify(api, never()).getTownNationOrNull(any());
        }
    }

    @Test
    void rejectsLocationsOutsideTownyTowns() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        TownyAPI api = mock(TownyAPI.class);
        when(api.getTown(location)).thenReturn(null);

        try (MockedStatic<TownyAPI> towny = mockStatic(TownyAPI.class)) {
            towny.when(TownyAPI::getInstance).thenReturn(api);
            assertThrows(TownyIntegrationException.class,
                    () -> new TownyResidentService().resolveAndAuthorize(player, location));
            verify(api, never()).getResident(any());
        }
    }
}
