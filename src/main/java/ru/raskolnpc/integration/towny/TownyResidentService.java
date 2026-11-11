package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class TownyResidentService {
    private final TownyAccess towny;

    public TownyResidentService() {
        this((player, location) -> {
            Town town = TownyAPI.getInstance().getTown(location);
            if (town == null) return null;
            Resident resident = TownyAPI.getInstance().getResident(player);
            boolean mayor = resident != null && town.isMayor(resident);
            boolean assistant = resident != null && town.hasResidentWithRank(resident, "assistant");
            Nation nation = TownyAPI.getInstance().getTownNationOrNull(town);
            return new TownContext(town.getUUID().toString(),
                    nation == null ? null : nation.getUUID().toString(), mayor, assistant);
        });
    }

    TownyResidentService(TownyAccess towny) {
        this.towny = towny;
    }

    public TownAssignment resolveAndAuthorize(Player player, Location location) {
        TownContext context = towny.resolve(player, location);
        if (context == null) {
            throw new TownyIntegrationException(
                    "Создавать жителей можно только на территории города Towny.");
        }
        boolean townManager = context.mayor() || context.assistant();
        boolean townyAdmin = player.hasPermission("towny.admin");
        boolean raskolAdmin = player.hasPermission("raskolnpc.admin");
        if (!townManager && !townyAdmin && !raskolAdmin) {
            throw new TownyIntegrationException(
                    "Создавать жителей этого города может только мэр, помощник мэра или администратор.");
        }
        return new TownAssignment(context.townId(), context.nationId());
    }

    @FunctionalInterface
    interface TownyAccess {
        TownContext resolve(Player player, Location location);
    }

    record TownContext(String townId, String nationId, boolean mayor, boolean assistant) { }

    public record TownAssignment(String townId, String nationId) { }
}
