package ru.raskolnpc.guard;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KingdomGuardRosterServiceTest {
    private static final String OWN = "nation-own";
    private static final String OTHER = "nation-other";

    @Test
    void kingSeesOnlyOwnNationGuards() {
        Player king = player();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> new TownyKingdomGuardAccess.NationContext(OWN, true));
        NpcRegistry registry = new NpcRegistry();
        NpcRecord own = NpcRecord.create(NpcType.GUARD, "Own guard", null, "town-a", OWN, "{}");
        NpcRecord other = NpcRecord.create(NpcType.GUARD, "Other guard", null, "town-b", OTHER, "{}");
        registry.put(own);
        registry.put(other);

        List<NpcRecord> visible = new KingdomGuardRosterService(registry, access).listVisible(king);

        assertEquals(List.of(own), visible);
    }

    @Test
    void administratorSeesAllGuardsAndCanOpenCard() {
        Player admin = mock(Player.class);
        when(admin.hasPermission("raskolnpc.admin")).thenReturn(true);
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town-a", OTHER, "{}");
        registry.put(guard);
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> { throw new AssertionError("Admin must not need Towny lookup"); });

        KingdomGuardRosterService roster = new KingdomGuardRosterService(registry, access);

        assertEquals(List.of(guard), roster.listVisible(admin));
        assertEquals(guard, roster.findVisible(admin, guard.id()).orElseThrow());
    }

    @Test
    void ordinaryPlayerCannotSeeGuards() {
        Player player = player();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> new TownyKingdomGuardAccess.NationContext(OWN, false));
        NpcRegistry registry = new NpcRegistry();
        registry.put(NpcRecord.create(NpcType.GUARD, "Guard", null, "town-a", OWN, "{}"));

        assertTrue(new KingdomGuardRosterService(registry, access).listVisible(player).isEmpty());
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(false);
        when(player.hasPermission("towny.admin")).thenReturn(false);
        return player;
    }
}
