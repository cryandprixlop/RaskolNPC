package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.entity.Player;
import ru.raskolnpc.guard.GuardHiringService;

import java.util.Objects;

/** Towny-backed authorization for hiring guards in a kingdom. */
public final class TownyKingdomGuardAccess implements GuardHiringService.HiringAccess {
    private final NationResolver resolver;

    public TownyKingdomGuardAccess() {
        this(player -> {
            Resident resident = TownyAPI.getInstance().getResident(player);
            Nation nation = TownyAPI.getInstance().getNation(player);
            if (resident == null || nation == null) return null;
            return new NationContext(nation.getUUID().toString(), nation.isKing(resident));
        });
    }

    TownyKingdomGuardAccess(NationResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public boolean mayHire(Player player, String nationId) {
        Objects.requireNonNull(player, "player");
        if (nationId == null || nationId.isBlank()) return false;
        if (player.hasPermission("raskolnpc.admin") || player.hasPermission("towny.admin")) {
            return true;
        }
        NationContext context = resolver.resolve(player);
        return context != null && context.king() && nationId.equals(context.nationId());
    }

    @FunctionalInterface
    interface NationResolver {
        NationContext resolve(Player player);
    }

    record NationContext(String nationId, boolean king) { }
}
