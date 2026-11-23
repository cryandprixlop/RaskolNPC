package ru.raskolnpc.npc.resident;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.towny.TownyResidentService;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.quest.ResidentQuestService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResidentInteractionListenerTest {
    @Test
    void onlyManagerOfResidentTownCanReceiveProfessionRequest() {
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(location.clone()).thenReturn(location);
        when(player.getLocation()).thenReturn(location);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(false);
        TownyResidentService towny = mock(TownyResidentService.class);
        when(towny.resolveAndAuthorize(eq(player), any(Location.class)))
                .thenReturn(new TownyResidentService.TownAssignment("town-1", null));
        ResidentInteractionListener listener = listener(towny);

        assertTrue(listener.mayReceiveProfessionRequest(player, resident("town-1")));
        assertFalse(listener.mayReceiveProfessionRequest(player, resident("town-2")));
    }

    @Test
    void ordinaryPlayerCannotReceiveProfessionRequest() {
        Player player = mock(Player.class);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(false);
        Location location = mock(Location.class);
        when(location.clone()).thenReturn(location);
        when(player.getLocation()).thenReturn(location);
        TownyResidentService towny = mock(TownyResidentService.class);
        when(towny.resolveAndAuthorize(eq(player), any(Location.class)))
                .thenThrow(new SecurityException("not a town manager"));

        assertFalse(listener(towny).mayReceiveProfessionRequest(player, resident("town-1")));
    }

    @Test
    void raskolAdministratorCanReceiveProfessionRequestWithoutTowny() {
        Player player = mock(Player.class);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(true);

        assertTrue(listener(null).mayReceiveProfessionRequest(player, resident("town-1")));
    }

    private static ResidentInteractionListener listener(TownyResidentService towny) {
        ResidentInteractionListener listener = new ResidentInteractionListener(
                mock(Plugin.class), mock(NpcEntityManager.class), new NpcRegistry(),
                mock(ResidentProfileService.class), mock(ResidentDialogueService.class),
                mock(ResidentQuestService.class));
        listener.setTownyResidentService(towny);
        return listener;
    }

    private static NpcRecord resident(String townId) {
        return NpcRecord.create(NpcType.RESIDENT, "Resident", null, townId, null, "{}");
    }
}
