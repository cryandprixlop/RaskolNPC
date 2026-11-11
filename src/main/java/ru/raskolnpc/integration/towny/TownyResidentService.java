package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class TownyResidentService {
    private final TownyAccess towny;

    public TownyResidentService() {
        this(new TownyAccess() {
            @Override
            public Town getTown(Location location) {
                return TownyAPI.getInstance().getTown(location);
            }

            @Override
            public Resident getResident(Player player) {
                return TownyAPI.getInstance().getResident(player);
            }

            @Override
            public Nation getTownNationOrNull(Town town) {
                return TownyAPI.getInstance().getTownNationOrNull(town);
            }
        });
    }

    TownyResidentService(TownyAccess towny) {
        this.towny = towny;
    }

    public TownAssignment resolveAndAuthorize(Player player, Location location) {
        Town town = towny.getTown(location);
        if (town == null) {
            throw new TownyIntegrationException(
                    "Создавать жителей можно только на территории города Towny.");
        }

        Resident resident = towny.getResident(player);
        boolean townManager = resident != null
                && (town.isMayor(resident) || town.hasResidentWithRank(resident, "assistant"));
        boolean townyAdmin = player.hasPermission("towny.admin");
        boolean raskolAdmin = player.hasPermission("raskolnpc.admin");

        if (!townManager && !townyAdmin && !raskolAdmin) {
            throw new TownyIntegrationException(
                    "Создавать жителей этого города может только мэр, помощник мэра или администратор.");
        }

        Nation nation = towny.getTownNationOrNull(town);
        UUID nationId = nation == null ? null : nation.getUUID();
        return new TownAssignment(town.getUUID().toString(), nationId == null ? null : nationId.toString());
    }

    interface TownyAccess {
        Town getTown(Location location);

        Resident getResident(Player player);

        Nation getTownNationOrNull(Town town);
    }

    public record TownAssignment(String townId, String nationId) {
    }
}
