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
        Nation nation = requireKingNation(actor);
        String rank = requireConfiguredRank();
        Resident target = requireTarget(targetName);
        requireSameNation(nation, target);
        if (access.hasRank(target, rank)) {
            throw new IllegalStateException(target.getName() + " уже является генералом этой нации.");
        }
        if (!access.addRank(target, rank)) {
            throw new IllegalStateException("Towny не смог назначить ранг генерала игроку " + target.getName() + ".");
        }
        try {
            access.save(target);
        } catch (RuntimeException saveError) {
            try {
                access.removeRank(target, rank);
                access.save(target);
            } catch (RuntimeException rollbackError) {
                saveError.addSuppressed(rollbackError);
            }
            throw new IllegalStateException("Не удалось сохранить назначение генерала в Towny.", saveError);
        }
    }

    public void dismiss(Player actor, String targetName) {
        Nation nation = requireKingNation(actor);
        String rank = requireConfiguredRank();
        Resident target = requireTarget(targetName);
        requireSameNation(nation, target);
        if (!access.hasRank(target, rank)) {
            throw new IllegalStateException(target.getName() + " не является генералом этой нации.");
        }
        if (!access.removeRank(target, rank)) {
            throw new IllegalStateException("Towny не смог снять ранг генерала с игрока " + target.getName() + ".");
        }
        try {
            access.save(target);
        } catch (RuntimeException saveError) {
            try {
                access.addRank(target, rank);
                access.save(target);
            } catch (RuntimeException rollbackError) {
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
        List<Nation> nations;
        if (sender.hasPermission("raskolnpc.admin")) {
            nations = access.nations();
        } else if (sender instanceof Player player) {
            nations = List.of(requireKingNation(player));
        } else {
            throw new IllegalStateException("Только администратор или король нации может просматривать генералов.");
        }

        List<GeneralEntry> result = new ArrayList<>();
        for (Nation nation : nations) {
            for (Resident resident : access.residents(nation)) {
                if (access.hasRank(resident, rank)) {
                    result.add(new GeneralEntry(resident.getName(), nation.getName()));
                }
            }
        }
        result.sort(Comparator.comparing(GeneralEntry::nationName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(GeneralEntry::residentName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private Nation requireKingNation(Player actor) {
        Objects.requireNonNull(actor, "actor");
        Resident resident = access.resident(actor.getUniqueId());
        if (resident == null) {
            throw new IllegalStateException("Ваш персонаж не зарегистрирован в Towny.");
        }
        Nation nation = access.nationOf(resident);
        if (nation == null) {
            throw new IllegalStateException("Вы не состоите в нации Towny.");
        }
        if (!access.isKing(nation, resident)) {
            throw new IllegalStateException("Назначать и снимать генералов может только король своей нации.");
        }
        return nation;
    }

    private Resident requireTarget(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            throw new IllegalArgumentException("Укажите имя игрока.");
        }
        Resident target = access.resident(targetName);
        if (target == null) {
            throw new IllegalStateException("Игрок " + targetName + " не найден среди жителей Towny.");
        }
        return target;
    }

    private void requireSameNation(Nation nation, Resident target) {
        Nation targetNation = access.nationOf(target);
        if (targetNation == null || !nation.getUUID().equals(targetNation.getUUID())) {
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

    interface Access {
        String matchNationRank(String rank);
        Resident resident(UUID uuid);
        Resident resident(String name);
        Nation nationOf(Resident resident);
        boolean isKing(Nation nation, Resident resident);
        List<Nation> nations();
        List<Resident> residents(Nation nation);
        boolean hasRank(Resident resident, String rank);
        boolean addRank(Resident resident, String rank);
        boolean removeRank(Resident resident, String rank);
        void save(Resident resident);
    }

    private static final class TownyAccess implements Access {
        @Override public String matchNationRank(String rank) {
            return TownyPerms.matchNationRank(rank);
        }
        @Override public Resident resident(UUID uuid) {
            return TownyAPI.getInstance().getResident(uuid);
        }
        @Override public Resident resident(String name) {
            return TownyAPI.getInstance().getResident(name);
        }
        @Override public Nation nationOf(Resident resident) {
            return resident.getNationOrNull();
        }
        @Override public boolean isKing(Nation nation, Resident resident) {
            return nation.isKing(resident);
        }
        @Override public List<Nation> nations() {
            return TownyAPI.getInstance().getNations();
        }
        @Override public List<Resident> residents(Nation nation) {
            return nation.getResidents();
        }
        @Override public boolean hasRank(Resident resident, String rank) {
            return resident.hasNationRank(rank);
        }
        @Override public boolean addRank(Resident resident, String rank) {
            return resident.addNationRank(rank);
        }
        @Override public boolean removeRank(Resident resident, String rank) {
            return resident.removeNationRank(rank);
        }
        @Override public void save(Resident resident) {
            resident.save();
        }
    }
}
