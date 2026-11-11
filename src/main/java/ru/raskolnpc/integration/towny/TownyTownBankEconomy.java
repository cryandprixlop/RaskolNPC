package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;

import java.util.UUID;

/** Withdraws guard rent from the bank of the town where the guard is stationed. */
public final class TownyTownBankEconomy {
    /**
     * Returns Towny's definitive result. Exceptions are intentionally propagated:
     * callers must treat an exception as an unknown outcome, not as a failed debit.
     */
    public boolean withdraw(String townId, double amount, String reason) {
        if (!valid(townId, amount)) return false;
        Town town = findTown(townId);
        return town != null && town.getAccount().withdraw(amount, reason);
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
