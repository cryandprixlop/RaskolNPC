package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.raskolnpc.integration.PlayerSubjectResolver;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Resolves online players through their Towny resident membership, never their current location. */
public final class TownyPlayerSubjectResolver implements PlayerSubjectResolver {
    private final BooleanSupplier townyEnabled;

    public TownyPlayerSubjectResolver() {
        this(TownyPlayerSubjectResolver::isTownyEnabled);
    }

    public TownyPlayerSubjectResolver(BooleanSupplier townyEnabled) {
        this.townyEnabled = Objects.requireNonNull(townyEnabled, "townyEnabled");
    }

    private static boolean isTownyEnabled() {
        var plugin = Bukkit.getPluginManager().getPlugin("Towny");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public Optional<RelationshipPolicy.Subject> resolve(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            if (!townyEnabled.getAsBoolean()) return Optional.empty();
            Resident resident = TownyAPI.getInstance().getResident(player);
            if (resident == null) return Optional.empty();
            Town town = resident.getTownOrNull();
            Nation nation = town == null ? null : TownyAPI.getInstance().getTownNationOrNull(town);
            return Optional.of(new RelationshipPolicy.Subject(
                    player.getUniqueId(),
                    town == null ? null : town.getUUID().toString(),
                    nation == null ? null : nation.getUUID().toString()));
        } catch (RuntimeException | LinkageError unavailable) {
            // Missing or shutting-down Towny data must never be interpreted as hostility.
            return Optional.empty();
        }
    }
}
