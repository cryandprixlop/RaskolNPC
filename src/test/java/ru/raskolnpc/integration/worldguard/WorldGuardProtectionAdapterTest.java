package ru.raskolnpc.integration.worldguard;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldGuardProtectionAdapterTest {
    @Test
    void mapsAllowedAndDeniedWorldGuardResults() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        Location location = new Location(world, 1, 64, 1);

        var allowed = new WorldGuardProtectionAdapter(ignored -> true);
        var denied = new WorldGuardProtectionAdapter(ignored -> false);

        assertEquals(WorldGuardProtectionAdapter.Decision.ALLOWED,
                allowed.checkMobDamage(location));
        assertEquals(WorldGuardProtectionAdapter.Decision.DENIED,
                denied.checkMobDamage(location));
    }

    @Test
    void returnsUnknownForMissingLocationOrUnavailableQuery() {
        var unavailable = new WorldGuardProtectionAdapter(ignored -> {
            throw new IllegalStateException("WorldGuard is stopping");
        });

        assertEquals(WorldGuardProtectionAdapter.Decision.UNKNOWN,
                unavailable.checkMobDamage(null));
        assertEquals(WorldGuardProtectionAdapter.Decision.UNKNOWN,
                unavailable.checkMobDamage(new Location(null, 0, 0, 0)));
        assertEquals(WorldGuardProtectionAdapter.Decision.UNKNOWN,
                unavailable.checkMobDamage(new Location(mock(World.class), 0, 0, 0)));
    }
}