package ru.raskolnpc.integration.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.permissions.TownyPerms;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages the kingdom-general role exclusively through an already configured Towny nation rank.
 * This service never creates or edits Towny's rank configuration.
 */
public final class TownyKingdomGeneralService {
    private final Access access;
    private final String configuredRank;

    public TownyKingdomGeneralService(String configuredRank) {
        this(new TownyAccess(), configuredRank);
    }

    TownyKingdomGeneralService(Access access, String configuredRank) {
        this.access = Objects.requireNonNull(access, "access");
        if (configuredRank == null || configuredRank.isBlank()) {
            throw new IllegalArgumentException("guards.towny-general-rank must not be blank.");
        }
        this.configuredRank = configuredRank.trim();
    }

    public void appoint(Player actor, String targetName) {
        NationRef nation = requireKingNation(actor);
        String rank = requireConfiguredRank();
        ResidentRef target = requireTarget(targetName);
        requireSameNation(nation, target);
        if (access.hasRank(target, rank)) {
            throw new IllegalStateException(target.name() + " уже является генералом этой нации.");
        }
        if (!access.addRank(target, rank)) {
            throw new IllegalStateException("Towny не смог назначить ранг генерала игроку " + target.name() + ".");
        }
        try {
            access.save(target);
        } catch (RuntimeException | LinkageError saveError) {
            try {
                access.removeRank(target, rank);
                access.save(target);
            } catch (RuntimeException | LinkageError rollbackError) {
                saveError.addSuppressed(rollbackError);
            }
            throw new IllegalStateException("Не удалось сохранить назначение генерала в Towny.", saveError);
        }
    }

    public void dismiss(Player actor, String targetName) {
        NationRef nation = requireKingNation(actor);
        String rank = requireConfiguredRank();
        ResidentRef target = requireTarget(targetName);
        requireSameNation(nation, target);
        if (!access.hasRank(target, rank)) {
            throw new IllegalStateException(target.name() + " не является генералом этой нации.");
        }
        if (!access.removeRank(target, rank)) {
            throw new IllegalStateException("Towny не смог снять ранг генерала с игрока " + target.name() + ".");
        }
        try {
            access.save(target);
        } catch (RuntimeException | LinkageError saveError) {
            try {
                access.addRank(target, rank);
                access.save(target);
            } catch (RuntimeException | LinkageError rollbackError) {
                saveError.addSuppressed(rollbackError);
            }
            throw new IllegalStateException("Не удалось сохранить снятие полномочий в Towny.", saveError);
        }
    }

    /**
     * A king sees their own nation's generals. RaskolNPC administrators may inspect all nations.
     * Console access is intentionally read-only and requires the RaskolNPC admin permission.
     */
    public List<GeneralEntry> list(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        String rank = requireConfiguredRank();
        List<NationRef> nations;
        if (sender.hasPermission("raskolnpc.admin")) {
            nations = access.nations();
        } else if (sender instanceof Player player) {
            nations = List.of(requireKingNation(player));
        } else {
            throw new IllegalStateException("Только администратор или король нации может просматривать генералов.");
        }

        List<GeneralEntry> result = new ArrayList<>();
        for (NationRef nation : nations) {
            for (ResidentRef resident : access.residents(nation)) {
                if (access.hasRank(resident, rank)) {
                    result.add(new GeneralEntry(resident.name(), nation.name()));
                }
            }
        }
        result.sort(Comparator.comparing(GeneralEntry::nationName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(GeneralEntry::residentName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private NationRef requireKingNation(Player actor) {
        Objects.requireNonNull(actor, "actor");
        ResidentRef resident = access.resident(actor.getUniqueId());
        if (resident == null) {
            throw new IllegalStateException("Ваш персонаж не зарегистрирован в Towny.");
        }
        NationRef nation = access.nationOf(resident);
        if (nation == null) {
            throw new IllegalStateException("Вы не состоите в нации Towny.");
        }
        if (!access.isKing(nation, resident)) {
            throw new IllegalStateException("Назначать и снимать генералов может только король своей нации.");
        }
        return nation;
    }

    private ResidentRef requireTarget(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            throw new IllegalArgumentException("Укажите имя игрока.");
        }
        ResidentRef target = access.resident(targetName);
        if (target == null) {
            throw new IllegalStateException("Игрок " + targetName + " не найден среди жителей Towny.");
        }
        return target;
    }

    private void requireSameNation(NationRef nation, ResidentRef target) {
        NationRef targetNation = access.nationOf(target);
        if (targetNation == null || !nation.id().equals(targetNation.id())) {
            throw new IllegalStateException("Назначать генералом можно только жителя своей нации.");
        }
    }

    private String requireConfiguredRank() {
        String rank = access.matchNationRank(configuredRank);
        if (rank == null) {
            throw new IllegalStateException("Ранг '" + configuredRank
                    + "' не настроен в Towny. Добавьте его в Towny ranks и перезапустите сервер.");
        }
        return rank;
    }

    public record GeneralEntry(String residentName, String nationName) { }

    /**
     * Small service-owned views keep unit tests independent from Towny's concrete model classes.
     */
    interface NationRef {
        UUID id();
        String name();
    }

    interface ResidentRef {
        String name();
    }

    interface Access {
        String matchNationRank(String rank);
        ResidentRef resident(UUID uuid);
        ResidentRef resident(String name);
        NationRef nationOf(ResidentRef resident);
        boolean isKing(NationRef nation, ResidentRef resident);
        List<NationRef> nations();
        List<ResidentRef> residents(NationRef nation);
        boolean hasRank(ResidentRef resident, String rank);
        boolean addRank(ResidentRef resident, String rank);
        boolean removeRank(ResidentRef resident, String rank);
        void save(ResidentRef resident);
    }

    private static final class TownyAccess implements Access {
        @Override public String matchNationRank(String rank) {
            return TownyPerms.matchNationRank(rank);
        }

        @Override public ResidentRef resident(UUID uuid) {
            Resident resident = TownyAPI.getInstance().getResident(uuid);
            return resident == null ? null : new TownyResidentRef(resident);
        }

        @Override public ResidentRef resident(String name) {
            Resident resident = TownyAPI.getInstance().getResident(name);
            return resident == null ? null : new TownyResidentRef(resident);
        }

        @Override public NationRef nationOf(ResidentRef resident) {
            Nation nation = unwrap(resident).getNationOrNull();
            return nation == null ? null : new TownyNationRef(nation);
        }

        @Override public boolean isKing(NationRef nation, ResidentRef resident) {
            return unwrap(nation).isKing(unwrap(resident));
        }

        @Override public List<NationRef> nations() {
            return TownyAPI.getInstance().getNations().stream()
                    .map(TownyNationRef::new)
                    .map(NationRef.class::cast)
                    .toList();
        }

        @Override public List<ResidentRef> residents(NationRef nation) {
            return unwrap(nation).getResidents().stream()
                    .map(TownyResidentRef::new)
                    .map(ResidentRef.class::cast)
                    .toList();
        }

        @Override public boolean hasRank(ResidentRef resident, String rank) {
            return unwrap(resident).hasNationRank(rank);
        }

        @Override public boolean addRank(ResidentRef resident, String rank) {
            return unwrap(resident).addNationRank(rank);
        }

        @Override public boolean removeRank(ResidentRef resident, String rank) {
            return unwrap(resident).removeNationRank(rank);
        }

        @Override public void save(ResidentRef resident) {
            unwrap(resident).save();
        }

        private static Resident unwrap(ResidentRef resident) {
            return ((TownyResidentRef) resident).resident();
        }

        private static Nation unwrap(NationRef nation) {
            return ((TownyNationRef) nation).nation();
        }
    }

    private record TownyResidentRef(Resident resident) implements ResidentRef {
        @Override public String name() {
            return resident.getName();
        }
    }

    private record TownyNationRef(Nation nation) implements NationRef {
        @Override public UUID id() {
            return nation.getUUID();
        }

        @Override public String name() {
            return nation.getName();
        }
    }
}
