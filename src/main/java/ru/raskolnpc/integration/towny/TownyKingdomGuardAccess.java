package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.raskolnpc.guard.GuardHiringService;

import java.util.Objects;
import java.util.function.Function;

/** Towny-backed authorization and placement lookup for kingdom guards. */
public final class TownyKingdomGuardAccess implements GuardHiringService.HiringAccess {
    private final TownResolver resolver;
    private final Function<Player, NationContext> nationResolver;

    public TownyKingdomGuardAccess() {
        this((player, location) -> {
            Town town = TownyAPI.getInstance().getTown(location);
            if (town == null) return null;
            Nation nation = TownyAPI.getInstance().getTownNationOrNull(town);
            Resident resident = TownyAPI.getInstance().getResident(player);
            if (nation == null) return null;
            return new Placement(town.getUUID().toString(), nation.getUUID().toString(),
                    resident != null && nation.isKing(resident));
        }, player -> {
            Resident resident = TownyAPI.getInstance().getResident(player);
            Nation nation = resident == null ? null : resident.getNationOrNull();
            return nation == null ? null
                    : new NationContext(nation.getUUID().toString(), nation.isKing(resident));
        });
    }

    TownyKingdomGuardAccess(TownResolver resolver) {
        this(resolver, player -> {
            Placement placement = resolver.resolve(player, player.getLocation());
            return placement == null ? null
                    : new NationContext(placement.nationId(), placement.king());
        });
    }

    TownyKingdomGuardAccess(TownResolver resolver,
                            Function<Player, NationContext> nationResolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.nationResolver = Objects.requireNonNull(nationResolver, "nationResolver");
    }

    /** Backwards-compatible test seam for authorization-only unit tests. */
    TownyKingdomGuardAccess(Function<Player, NationContext> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        this.resolver = (player, location) -> {
            NationContext context = resolver.apply(player);
            return context == null ? null
                    : new Placement("test-town", context.nationId(), context.king());
        };
        this.nationResolver = resolver;
    }

    /** Resolves the Towny town at the placement location and checks its nation king. */
    public Placement resolvePlacement(Player player, Location location) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(location, "location");
        Placement placement = resolver.resolve(player, location);
        if (placement == null || placement.townId() == null || placement.nationId() == null) {
            throw new TownyIntegrationException(
                    "Стражу можно размещать только на территории города, входящего в нацию Towny.");
        }
        boolean admin = player.hasPermission("raskolnpc.admin")
                || player.hasPermission("towny.admin");
        if (!admin && !placement.king()) {
            throw new TownyIntegrationException(
                    "Нанимать стражу может только король нации, которой принадлежит город размещения.");
        }
        return placement;
    }

    @Override
    public boolean mayHire(Player player, String nationId) {
        Objects.requireNonNull(player, "player");
        if (nationId == null || nationId.isBlank()) return false;
        if (player.hasPermission("raskolnpc.admin") || player.hasPermission("towny.admin")) {
            return true;
        }
        Placement placement = resolver.resolve(player, player.getLocation());
        return placement != null && placement.king()
                && nationId.equals(placement.nationId());
    }

    /** Allows a nation king to inspect only their own nation's guard roster. */
    public boolean mayViewNation(Player player, String nationId) {
        Objects.requireNonNull(player, "player");
        if (nationId == null || nationId.isBlank()) return false;
        if (player.hasPermission("raskolnpc.admin") || player.hasPermission("towny.admin")) {
            return true;
        }
        try {
            NationContext membership = nationResolver.apply(player);
            return membership != null && membership.king()
                    && nationId.equals(membership.nationId());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @FunctionalInterface
    interface TownResolver {
        Placement resolve(Player player, Location location);
    }

    public record Placement(String townId, String nationId, boolean king) { }

    record NationContext(String nationId, boolean king) { }
}
