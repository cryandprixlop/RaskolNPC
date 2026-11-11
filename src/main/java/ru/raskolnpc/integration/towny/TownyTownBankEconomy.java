package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;

import java.util.UUID;

/** Charges and refunds the bank belonging to the town where a guard is stationed. */
public final class TownyTownBankEconomy {
    public boolean withdraw(String townId, double amount, String reason) {
        if (!valid(townId, amount)) return false;
        Town town = findTown(townId);
        return town != null && town.getAccount().withdraw(amount, reason);
    }

    public boolean deposit(String townId, double amount, String reason) {
        if (!valid(townId, amount)) return false;
        Town town = findTown(townId);
        return town != null && town.getAccount().deposit(amount, reason);
    }

    private Town findTown(String townId) {
        try {
            return TownyAPI.getInstance().getTown(UUID.fromString(townId));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean valid(String townId, double amount) {
        return townId != null && !townId.isBlank()
                && Double.isFinite(amount) && amount >= 0;
    }
}
