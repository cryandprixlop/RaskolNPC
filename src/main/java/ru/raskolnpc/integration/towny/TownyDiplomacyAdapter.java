package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import ru.raskolnpc.integration.RelationshipPolicy;

import java.util.UUID;
import java.util.function.Function;

/** Reads reciprocal Towny alliances and declared nation enemies by UUID. */
public final class TownyDiplomacyAdapter implements RelationshipPolicy.Diplomacy {
    private final Function<UUID, Nation> nationLookup;

    public TownyDiplomacyAdapter() {
        this(uuid -> TownyAPI.getInstance().getNation(uuid));
    }

    TownyDiplomacyAdapter(Function<UUID, Nation> nationLookup) {
        this.nationLookup = java.util.Objects.requireNonNull(nationLookup, "nationLookup");
    }

    @Override
    public RelationshipPolicy.DiplomacyRelation relation(String firstNationId, String secondNationId) {
        UUID firstId = parse(firstNationId);
        UUID secondId = parse(secondNationId);
        if (firstId == null || secondId == null || firstId.equals(secondId)) {
            return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        }
        try {
            Nation first = nationLookup.apply(firstId);
            Nation second = nationLookup.apply(secondId);
            if (first == null || second == null) return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
            if (first.hasEnemy(second) || second.hasEnemy(first)) {
                return RelationshipPolicy.DiplomacyRelation.HOSTILE;
            }
            if (first.hasAlly(second) && second.hasAlly(first)) {
                return RelationshipPolicy.DiplomacyRelation.ALLIED;
            }
            return RelationshipPolicy.DiplomacyRelation.NEUTRAL;
        } catch (RuntimeException unavailable) {
            return RelationshipPolicy.DiplomacyRelation.UNKNOWN;
        }
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