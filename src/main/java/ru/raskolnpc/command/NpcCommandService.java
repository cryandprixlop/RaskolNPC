package ru.raskolnpc.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.guard.GuardHiringService;
import ru.raskolnpc.integration.towny.KingdomGuardService;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
import ru.raskolnpc.integration.towny.TownyNpcLimitService;
import ru.raskolnpc.integration.towny.TownyResidentService;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class NpcCommandService {
    private final Plugin plugin;
    private final NpcLifecycleService lifecycle;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final TownyResidentService townyResidents;
    private final TownyNpcLimitService townLimits;
    private final int residentsPerTown;
    private final TownyKingdomGuardAccess guardAccess;
    private final TownyTownBankEconomy townBank;
    private final KingdomGuardService guardLimits;
    private final int guardsPerNation;
    private final double guardBaseHireCost;
    private final double guardBaseDailyRent;

    public NpcCommandService(Plugin plugin, NpcLifecycleService lifecycle,
                             NpcRegistry registry, NpcEntityManager entities,
                             TownyResidentService townyResidents,
                             TownyNpcLimitService townLimits, int residentsPerTown) {
        this(plugin, lifecycle, registry, entities, townyResidents, townLimits,
                residentsPerTown, null, null, null, 30, 0, 0);
    }

    public NpcCommandService(Plugin plugin, NpcLifecycleService lifecycle,
                             NpcRegistry registry, NpcEntityManager entities,
                             TownyResidentService townyResidents,
                             TownyNpcLimitService townLimits, int residentsPerTown,
                             TownyKingdomGuardAccess guardAccess,
                             TownyTownBankEconomy townBank,
                             KingdomGuardService guardLimits, int guardsPerNation,
                             double guardBaseHireCost, double guardBaseDailyRent) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.entities = entities;
        this.townyResidents = townyResidents;
        this.townLimits = townLimits;
        this.residentsPerTown = Math.max(1, residentsPerTown);
        this.guardAccess = guardAccess;
        this.townBank = townBank;
        this.guardLimits = guardLimits;
        this.guardsPerNation = Math.max(1, guardsPerNation);
        this.guardBaseHireCost = requireMoney(guardBaseHireCost);
        this.guardBaseDailyRent = requireMoney(guardBaseDailyRent);
    }

    public boolean isTownyIntegrationEnabled() {
        return townyResidents != null;
    }

    public boolean isGuardHiringEnabled() {
        return guardAccess != null && townBank != null && guardLimits != null;
    }

    public CompletableFuture<NpcRecord> createResident(String name, Player player) {
        Location location = player.getLocation().clone();
        final TownyResidentService.TownAssignment town;
        final TownyNpcLimitService.Reservation reservation;
        try {
            if (townyResidents == null) {
                town = new TownyResidentService.TownAssignment(null, null);
            } else {
                town = townyResidents.resolveAndAuthorize(player, location);
            }
            reservation = townLimits.reserve(town.townId(), residentsPerTown, registry);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<NpcRecord> creation = lifecycle.create(
                NpcType.RESIDENT, name, null, town.townId(), town.nationId(), "{}");
        creation.whenComplete((record, error) -> reservation.close());

        return creation.thenCompose(record ->
                onMainThread(() -> entities.spawnAsync(record, location))
                        .exceptionallyCompose(error -> cleanup(record, error)));
    }

    /** Creates a GUARD bound to the nation and town at the player's current location. */
    public CompletableFuture<GuardHiringService.HireResult> hireGuard(
            String name, int level, Player player) {
        if (!isGuardHiringEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Найм стражи недоступен: интеграция Towny не настроена."));
        }
        if (level < 1 || level > 10) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Уровень стража должен быть от 1 до 10."));
        }
        if (name == null || name.isBlank() || name.length() > 64) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Имя должно содержать от 1 до 64 символов."));
        }

        Location location = player.getLocation().clone();
        final TownyKingdomGuardAccess.Placement placement;
        final KingdomGuardService.Reservation reservation;
        try {
            placement = guardAccess.resolvePlacement(player, location);
            reservation = guardLimits.reserve(placement.nationId(), guardsPerNation, registry);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        double hireCost = guardBaseHireCost * level;
        double dailyRent = guardBaseDailyRent * level;
        if (!townBank.withdraw(placement.townId(), hireCost,
                "RaskolNPC guard hire: " + name + " (level " + level + ")")) {
            reservation.close();
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "В банке города недостаточно средств для найма стража."));
        }

        String data = "{\"level\":" + level + ",\"dailyRent\":" + dailyRent
                + ",\"payerTownId\":\"" + placement.townId() + "\",\"overdueDays\":0,\"nextRentAt\":"
                + (java.time.Instant.now().getEpochSecond() + 86400) + "}";
        CompletableFuture<NpcRecord> creation = lifecycle.create(NpcType.GUARD, name,
                player.getUniqueId(), placement.townId(), placement.nationId(), data);
        return creation.thenCompose(record ->
                onMainThread(() -> entities.spawnAsync(record, location))
                        .exceptionallyCompose(error -> cleanup(record, error)))
                .handle((record, error) -> {
                    reservation.close();
                    if (error != null) {
                        try {
                            boolean refunded = townBank.deposit(placement.townId(), hireCost,
                                    "RaskolNPC guard hire refund: " + name);
                            if (!refunded) {
                                plugin.getLogger().severe("Could not refund guard hire cost "
                                        + hireCost + " to town " + placement.townId()
                                        + " after NPC creation failed.");
                            }
                        } catch (RuntimeException refundError) {
                            error.addSuppressed(refundError);
                            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                    "Town bank refund failed after guard creation error.", refundError);
                        }
                        throw new CompletionException(unwrap(error));
                    }
                    return new GuardHiringService.HireResult(level, hireCost, dailyRent);
                });
    }

    private CompletableFuture<NpcRecord> cleanup(NpcRecord record, Throwable error) {
        return lifecycle.delete(record.id())
                .thenCompose(deleted -> deleted
                        ? lifecycle.completeDeletion(record.id())
                        : CompletableFuture.completedFuture(false))
                .handle((ignored, cleanupError) -> {
                    if (cleanupError != null) error.addSuppressed(cleanupError);
                    throw new CompletionException(error);
                });
    }

    private <T> CompletableFuture<T> onMainThread(
            java.util.concurrent.Callable<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Plugin is disabled."));
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) {
                    result.completeExceptionally(
                            new IllegalStateException("Plugin is disabled."));
                    return;
                }
                try {
                    action.call().whenComplete((value, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else result.complete(value);
                    });
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    public List<NpcRecord> list() {
        if (!lifecycle.isReady()) {
            throw new IllegalStateException("NPC storage is still initializing.");
        }
        return registry.all().stream()
                .sorted(java.util.Comparator.comparing(NpcRecord::createdAt))
                .toList();
    }

    public CompletableFuture<Optional<NpcRecord>> find(NpcId id) {
        return lifecycle.find(id);
    }

    public CompletableFuture<Boolean> delete(NpcId id) {
        return lifecycle.delete(id).thenCompose(deleted -> {
            if (!deleted) return CompletableFuture.completedFuture(false);
            return onMainThread(() -> {
                boolean removed = entities.remove(id);
                return removed ? lifecycle.completeDeletion(id)
                        .thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(true);
            });
        });
    }

    private static double requireMoney(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Guard prices must be finite and non-negative.");
        }
        return value;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
