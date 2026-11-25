package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/** Allows guard movement only inside territory owned by the guard's hiring nation. */
public final class TownyGuardTerritoryAccess implements BiFunction<Location, String, Boolean> {
    private final BiFunction<Location, String, Boolean> lookup;
    private final BooleanSupplier townyEnabled;

    public TownyGuardTerritoryAccess() {
        this((location, nationId) -> {
            Town town = TownyAPI.getInstance().getTown(location);
            if (town == null) return false;
            Nation nation = TownyAPI.getInstance().getTownNationOrNull(town);
            return nation != null && nation.getUUID().toString().equalsIgnoreCase(nationId);
        }, TownyGuardTerritoryAccess::isTownyEnabled);
    }

    TownyGuardTerritoryAccess(BiFunction<Location, String, Boolean> lookup) {
        this(lookup, () -> true);
    }

    public TownyGuardTerritoryAccess(
            BiFunction<Location, String, Boolean> lookup, BooleanSupplier townyEnabled) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.townyEnabled = Objects.requireNonNull(townyEnabled, "townyEnabled");
    }

    private static boolean isTownyEnabled() {
        var plugin = Bukkit.getPluginManager().getPlugin("Towny");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public Boolean apply(Location location, String nationId) {
        if (location == null || location.getWorld() == null
                || nationId == null || nationId.isBlank()) return false;
        try {
            if (!townyEnabled.getAsBoolean()) return false;
            return Boolean.TRUE.equals(lookup.apply(location.clone(), nationId));
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
