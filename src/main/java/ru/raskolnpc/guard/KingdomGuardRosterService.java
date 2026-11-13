package ru.raskolnpc.guard;

import org.bukkit.entity.Player;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only, in-memory view of guards visible to a nation king or administrator. */
public final class KingdomGuardRosterService {
    private final NpcRegistry registry;
    private final TownyKingdomGuardAccess access;

    public KingdomGuardRosterService(NpcRegistry registry, TownyKingdomGuardAccess access) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.access = Objects.requireNonNull(access, "access");
    }

    public List<NpcRecord> listVisible(Player player) {
        Objects.requireNonNull(player, "player");
        return registry.all().stream()
                .filter(record -> record.type() == NpcType.GUARD)
                .filter(record -> access.mayViewNation(player, record.nationId()))
                .sorted(Comparator.comparing(NpcRecord::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(record -> record.id().toString()))
                .toList();
    }

    public Optional<NpcRecord> findVisible(Player player, NpcId id) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(id, "id");
        return registry.get(id)
                .filter(record -> record.type() == NpcType.GUARD)
                .filter(record -> access.mayViewNation(player, record.nationId()));
    }
}
