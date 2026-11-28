package ru.raskolnpc.integration.siegewar;

import com.gmail.goosius.siegewar.SiegeWarAPI;
import com.gmail.goosius.siegewar.objects.Siege;
import com.palmergames.bukkit.towny.object.Government;
import org.bukkit.Bukkit;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reads active attacker/defender pairs through SiegeWar's documented public API.
 * It never treats the absence of a siege as proof of peace or hostility.
 */
public final class SiegeWarDiplomacyAdapter implements RelationshipPolicy.Diplomacy {
    private final Supplier<? extends Collection<Siege>> activeSieges;
    private final BooleanSupplier siegeWarEnabled;

    public SiegeWarDiplomacyAdapter() {
        this(SiegeWarAPI::getActiveSieges, SiegeWarDiplomacyAdapter::isSiegeWarEnabled);
    }

    SiegeWarDiplomacyAdapter(Supplier<? extends Collection<Siege>> activeSieges) {
        this(activeSieges, () -> true);
    }

    public SiegeWarDiplomacyAdapter(
            Supplier<? extends Collection<Siege>> activeSieges, BooleanSupplier siegeWarEnabled) {
        this.activeSieges = Objects.requireNonNull(activeSieges, "activeSieges");
        this.siegeWarEnabled = Objects.requireNonNull(siegeWarEnabled, "siegeWarEnabled");
    }

    private static boolean isSiegeWarEnabled() {
        var plugin = Bukkit.getPluginManager().getPlugin("SiegeWar");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public RelationshipPolicy.DiplomacyRelation relation(
            String firstNationId, String secondNationId) {
        UUID first = parse(firstNationId);
        UUID second = parse(secondNationId);
        if (first == null || second == null || first.equals(second)) {
            return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        }

        try {
            if (!siegeWarEnabled.getAsBoolean()) return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
            Collection<Siege> sieges = activeSieges.get();
            if (sieges == null) return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
            for (Siege siege : sieges) {
                if (siege == null) continue;
                Government attacker = siege.getAttackingNationIfPossibleElseTown();
                Government defender = siege.getDefendingNationIfPossibleElseTown();
                if (attacker == null || defender == null) continue;

                UUID attackerId = attacker.getUUID();
                UUID defenderId = defender.getUUID();
                if ((first.equals(attackerId) && second.equals(defenderId))
                        || (first.equals(defenderId) && second.equals(attackerId))) {
                    return RelationshipPolicy.DiplomacyRelation.HOSTILE;
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // SiegeWar may be shutting down or its API may be temporarily unavailable.
            return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        }
        return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
