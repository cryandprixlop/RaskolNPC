package ru.raskolnpc.guard;

import org.bukkit.entity.Player;
import ru.raskolnpc.integration.towny.KingdomGuardService;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates guard hiring policy, payment and the guard creation callback.
 * Towny and economy implementations are injected to keep this service testable.
 */
public final class GuardHiringService {
    private final KingdomGuardService limits;
    private final NpcRegistry registry;
    private final int guardsPerNation;
    private final double baseHireCost;
    private final double baseDailyRent;
    private final HiringAccess access;
    private final Economy economy;
    private final GuardCreator creator;

    public GuardHiringService(KingdomGuardService limits, NpcRegistry registry,
                              int guardsPerNation, double baseHireCost,
                              double baseDailyRent, HiringAccess access,
                              Economy economy, GuardCreator creator) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.guardsPerNation = Math.max(1, guardsPerNation);
        this.baseHireCost = requireNonNegative(baseHireCost, "baseHireCost");
        this.baseDailyRent = requireNonNegative(baseDailyRent, "baseDailyRent");
        this.access = Objects.requireNonNull(access, "access");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.creator = Objects.requireNonNull(creator, "creator");
    }

    public HireResult hire(Player player, String nationId, int level) {
        Objects.requireNonNull(player, "player");
        if (nationId == null || nationId.isBlank()) {
            throw new IllegalArgumentException("nationId must not be blank");
        }
        if (level < 1) throw new IllegalArgumentException("Guard level must be at least 1.");
        if (!access.mayHire(player, nationId)) {
            throw new IllegalStateException(
                    "Нанимать стражу может только король королевства или администратор.");
        }

        double hireCost = costForLevel(baseHireCost, level);
        double dailyRent = costForLevel(baseDailyRent, level);
        try (KingdomGuardService.Reservation ignored =
                     limits.reserve(nationId, guardsPerNation, registry)) {
            UUID playerId = player.getUniqueId();
            if (!economy.withdraw(playerId, hireCost)) {
                throw new IllegalStateException("Недостаточно средств для найма стража.");
            }
            try {
                creator.create(player, nationId, level, dailyRent);
            } catch (RuntimeException exception) {
                economy.deposit(playerId, hireCost);
                throw exception;
            }
        }
        return new HireResult(level, hireCost, dailyRent);
    }

    /**
     * Charges one daily rent payment. Returns false when the balance is insufficient.
     */
    public boolean collectDailyRent(UUID payer, double amount) {
        Objects.requireNonNull(payer, "payer");
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Rent must be a finite non-negative amount.");
        }
        return economy.withdraw(payer, amount);
    }

    private static double costForLevel(double base, int level) {
        return base * level;
    }

    private static double requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative.");
        }
        return value;
    }

    @FunctionalInterface
    public interface HiringAccess {
        boolean mayHire(Player player, String nationId);
    }

    public interface Economy {
        boolean withdraw(UUID account, double amount);
        void deposit(UUID account, double amount);
    }

    @FunctionalInterface
    public interface GuardCreator {
        void create(Player player, String nationId, int level, double dailyRent);
    }

    public record HireResult(int level, double hireCost, double dailyRent) { }
}
