package ru.raskolnpc.integration.towny;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownyKingdomGuardAccessTest {
    private static final String NATION_ID = "nation-123";

    @Test
    void permitsKingOfTheRequestedNation() {
        Player player = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> new TownyKingdomGuardAccess.NationContext(NATION_ID, true));
        assertTrue(access.mayHire(player, NATION_ID));
    }

    @Test
    void rejectsKingOfAnotherNation() {
        Player player = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> new TownyKingdomGuardAccess.NationContext("another-nation", true));
        assertFalse(access.mayHire(player, NATION_ID));
    }

    @Test
    void rejectsOrdinaryNationResident() {
        Player player = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> new TownyKingdomGuardAccess.NationContext(NATION_ID, false));
        assertFalse(access.mayHire(player, NATION_ID));
    }

    @Test
    void rejectsPlayerWithoutNation() {
        Player player = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(ignored -> null);
        assertFalse(access.mayHire(player, NATION_ID));
    }

    @Test
    void allowsRaskolNpcAdministratorOverride() {
        Player player = mock(Player.class);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(true);
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> { throw new AssertionError("Resolver must not run for administrators"); });
        assertTrue(access.mayHire(player, NATION_ID));
    }

    @Test
    void allowsTownyAdministratorOverride() {
        Player player = mock(Player.class);
        when(player.hasPermission("towny.admin")).thenReturn(true);
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                ignored -> { throw new AssertionError("Resolver must not run for administrators"); });
        assertTrue(access.mayHire(player, NATION_ID));
    }

    @Test
    void rejectsBlankNationIdEvenForAdministrator() {
        Player player = mock(Player.class);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(true);
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(ignored -> null);
        assertFalse(access.mayHire(player, " "));
    }

    @Test
    void kingCanViewOwnNationGuardsWhileStandingOutsideTheNation() {
        Player king = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                (player, location) -> new TownyKingdomGuardAccess.Placement(
                        "foreign-town", "another-nation", false),
                ignored -> new TownyKingdomGuardAccess.NationContext(NATION_ID, true));

        assertTrue(access.mayViewNation(king, NATION_ID));
        assertFalse(access.mayViewNation(king, "another-nation"));
    }

    @Test
    void incompatibleTownyApiRejectsGuardPlacement() {
        Player player = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                (ignored, location) -> { throw new NoSuchMethodError("Towny API mismatch"); },
                ignored -> null);
        assertThrows(TownyIntegrationException.class,
                () -> access.resolvePlacement(player, mock(org.bukkit.Location.class)));
    }

    @Test
    void townyMembershipLookupFailureDoesNotGrantRosterAccess() {
        Player player = playerWithoutAdmin();
        TownyKingdomGuardAccess access = new TownyKingdomGuardAccess(
                (ignored, location) -> null,
                ignored -> { throw new IllegalStateException("Towny unavailable"); });

        assertFalse(access.mayViewNation(player, NATION_ID));
    }

    private Player playerWithoutAdmin() {
        Player player = mock(Player.class);
        when(player.hasPermission("raskolnpc.admin")).thenReturn(false);
        when(player.hasPermission("towny.admin")).thenReturn(false);
        return player;
    }
}
