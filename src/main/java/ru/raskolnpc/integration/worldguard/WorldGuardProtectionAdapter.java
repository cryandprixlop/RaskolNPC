package ru.raskolnpc.integration.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;

import java.util.Objects;
import java.util.function.Function;

/**
 * Queries WorldGuard's mob-damage flag for an NPC attack location.
 * This is an API adapter only; callers decide which NPC actions to gate.
 */
public final class WorldGuardProtectionAdapter {
    private final Function<Location, Boolean> mobDamageQuery;

    public WorldGuardProtectionAdapter() {
        RegionQuery query = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().createQuery();
        this.mobDamageQuery = location -> query.testState(
                BukkitAdapter.adapt(location), null, Flags.MOB_DAMAGE);
    }

    WorldGuardProtectionAdapter(Function<Location, Boolean> mobDamageQuery) {
        this.mobDamageQuery = Objects.requireNonNull(mobDamageQuery, "mobDamageQuery");
    }

    public Decision checkMobDamage(Location location) {
        if (location == null || location.getWorld() == null) return Decision.UNKNOWN;
        try {
            Boolean allowed = mobDamageQuery.apply(location.clone());
            if (allowed == null) return Decision.UNKNOWN;
            return allowed ? Decision.ALLOWED : Decision.DENIED;
        } catch (RuntimeException | LinkageError unavailable) {
            return Decision.UNKNOWN;
        }
    }

    public enum Decision {
        ALLOWED,
        DENIED,
        UNKNOWN
    }
}