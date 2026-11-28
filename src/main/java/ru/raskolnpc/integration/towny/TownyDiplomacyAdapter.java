package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import org.bukkit.Bukkit;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/** Reads reciprocal Towny alliances and declared nation enemies by UUID. */
public final class TownyDiplomacyAdapter implements RelationshipPolicy.Diplomacy {
    private final BiFunction<UUID, UUID, RelationshipPolicy.DiplomacyRelation> relationLookup;
    private final BooleanSupplier townyEnabled;

    public TownyDiplomacyAdapter() {
        this(TownyDiplomacyAdapter::lookupTownyRelation, TownyDiplomacyAdapter::isTownyEnabled);
    }

    TownyDiplomacyAdapter(BiFunction<UUID, UUID, RelationshipPolicy.DiplomacyRelation> relationLookup) {
        this(relationLookup, () -> true);
    }

    public TownyDiplomacyAdapter(
            BiFunction<UUID, UUID, RelationshipPolicy.DiplomacyRelation> relationLookup,
            BooleanSupplier townyEnabled) {
        this.relationLookup = java.util.Objects.requireNonNull(relationLookup, "relationLookup");
        this.townyEnabled = java.util.Objects.requireNonNull(townyEnabled, "townyEnabled");
    }

    private static boolean isTownyEnabled() {
        var plugin = Bukkit.getPluginManager().getPlugin("Towny");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public RelationshipPolicy.DiplomacyRelation relation(String firstNationId, String secondNationId) {
        UUID firstId = parse(firstNationId);
        UUID secondId = parse(secondNationId);
        if (firstId == null || secondId == null || firstId.equals(secondId)) {
            return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        }
        try {
            if (!townyEnabled.getAsBoolean()) return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
            RelationshipPolicy.DiplomacyRelation result = relationLookup.apply(firstId, secondId);
            return result == null ? RelationshipPolicy.DiplomacyRelation.UNKNOWN : result;
        } catch (RuntimeException | LinkageError unavailable) {
            return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        }
    }

    private static RelationshipPolicy.DiplomacyRelation lookupTownyRelation(UUID firstId, UUID secondId) {
        Nation first = TownyAPI.getInstance().getNation(firstId);
        Nation second = TownyAPI.getInstance().getNation(secondId);
        if (first == null || second == null) return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        return mapRelations(first.hasEnemy(second), second.hasEnemy(first),
                first.hasAlly(second), second.hasAlly(first));
    }

    static RelationshipPolicy.DiplomacyRelation mapRelations(
            boolean firstListsEnemy, boolean secondListsEnemy,
            boolean firstListsAlly, boolean secondListsAlly) {
        if (firstListsEnemy || secondListsEnemy) return RelationshipPolicy.DiplomacyRelation.HOSTILE;
        if (firstListsAlly && secondListsAlly) return RelationshipPolicy.DiplomacyRelation.ALLIED;
        return RelationshipPolicy.DiplomacyRelation.NEUTRAL;
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
