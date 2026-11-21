package ru.raskolnpc.integration.towny;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TownyGuardTerritoryAccessTest {
    @Test
    void permitsOnlyLocationsOwnedByHiringNation() {
        World world = mock(World.class);
        Location location = new Location(world, 10, 64, 10);
        TownyGuardTerritoryAccess access =
                new TownyGuardTerritoryAccess((queried, nationId) -> nationId.equals("nation-a"));

        assertTrue(access.apply(location, "nation-a"));
        assertFalse(access.apply(location, "nation-b"));
    }

    @Test
    void failsClosedForWildernessMissingNationAndLookupFailure() {
        World world = mock(World.class);
        Location location = new Location(world, 10, 64, 10);

        assertFalse(new TownyGuardTerritoryAccess((queried, nationId) -> false)
                .apply(location, "nation-a"));
        assertFalse(new TownyGuardTerritoryAccess((queried, nationId) -> {
            throw new IllegalStateException("Towny unavailable");
        }).apply(location, "nation-a"));
        assertFalse(new TownyGuardTerritoryAccess((queried, nationId) -> {
            throw new NoSuchMethodError("Towny API mismatch");
        }).apply(location, "nation-a"));
        assertFalse(new TownyGuardTerritoryAccess((queried, nationId) -> true)
                .apply(location, null));
        assertFalse(new TownyGuardTerritoryAccess((queried, nationId) -> true)
                .apply(null, "nation-a"));
    }
}
