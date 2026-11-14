package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Location;

import java.util.Objects;
import java.util.function.BiFunction;

/** Allows guard movement only inside territory owned by the guard's hiring nation. */
public final class TownyGuardTerritoryAccess implements BiFunction<Location, String, Boolean> {
    private final BiFunction<Location, String, Boolean> lookup;

    public TownyGuardTerritoryAccess() {
        this((location, nationId) -> {
            Town town = TownyAPI.getInstance().getTown(location);
            if (town == null) return false;
            Nation nation = TownyAPI.getInstance().getTownNationOrNull(town);
            return nation != null && nation.getUUID().toString().equalsIgnoreCase(nationId);
        });
    }

    TownyGuardTerritoryAccess(BiFunction<Location, String, Boolean> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public Boolean apply(Location location, String nationId) {
        if (location == null || location.getWorld() == null
                || nationId == null || nationId.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(lookup.apply(location.clone(), nationId));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
