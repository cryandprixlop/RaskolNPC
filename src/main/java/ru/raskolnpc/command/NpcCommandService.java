package ru.raskolnpc.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.guard.GuardHiringService;
import ru.raskolnpc.guard.KingdomGuardRosterService;
import ru.raskolnpc.guard.model.GuardHiringPayment;
import ru.raskolnpc.storage.GuardHiringPaymentRepository;
import ru.raskolnpc.integration.towny.KingdomGuardService;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
import ru.raskolnpc.integration.towny.TownyNpcLimitService;
import ru.raskolnpc.integration.towny.TownyResidentService;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.ResidentProfileService;
import ru.raskolnpc.npc.resident.ResidentBehaviorService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.UUID;

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
    private volatile GuardHiringPaymentRepository hiringPayments;
    private volatile ResidentProfileService residentProfileService;
    private volatile ResidentBehaviorService residentBehaviorService;
    private volatile KingdomGuardRosterService guardRosterService;

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

    public void setGuardHiringPaymentRepository(GuardHiringPaymentRepository repository) {
        this.hiringPayments = repository;
    }

    public void setResidentProfileService(ResidentProfileService service) {
        this.residentProfileService = service;
    }

    public void setResidentBehaviorService(ResidentBehaviorService service) {
        this.residentBehaviorService = service;
    }

    private void refreshResidentBehavior() {
        ResidentBehaviorService behavior = residentBehaviorService;
        if (behavior != null) behavior.reload();
    }

    public void setGuardRosterService(KingdomGuardRosterService service) {
        this.guardRosterService = service;
    }

    public List<NpcRecord> guardRoster(Player player) {
        KingdomGuardRosterService roster = guardRosterService;
        if (roster == null) throw new IllegalStateException("Просмотр состава стражи недоступен.");
        return roster.listVisible(player);
    }

    public Optional<NpcRecord> findVisibleGuard(Player player, NpcId id) {
        KingdomGuardRosterService roster = guardRosterService;
        if (roster == null) throw new IllegalStateException("Просмотр состава стражи недоступен.");
        return roster.findVisible(player, id);
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
                NpcType.RESIDENT, name, null, town.townId(), town.nationId(), "{}",
                NpcSpawnLocation.from(location));
        creation.whenComplete((record, error) -> reservation.close());

        return creation.thenCompose(record -> {
            ResidentProfileService profiles = residentProfileService;
            if (profiles == null) {
                return cleanup(record, new IllegalStateException(
                        "Resident profile service is not initialized."));
            }
            return profiles.createIfMissing(record)
                    .thenCompose(ignored -> onMainThread(() -> entities.spawnAsync(record, location)))
                    .thenApply(ignored -> {
                        refreshResidentBehavior();
                        return record;
                    })
                    .exceptionallyCompose(error -> cleanup(record, error));
        });
    }

    /** Creates a GUARD bound to the nation and town at the player's current location. */
    public CompletableFuture<GuardHiringService.HireResult> hireGuard(
            String name, int level, Player player) {
        if (!isGuardHiringEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Найм стражи недоступен: интеграция Towny не настроена."));
        }
        GuardHiringPaymentRepository journal = hiringPayments;
        if (journal == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Журнал найма стражи не инициализирован; списание запрещено."));
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
        String paymentId = UUID.randomUUID().toString();
        GuardHiringPayment payment = new GuardHiringPayment(
                paymentId, null, player.getUniqueId(), placement.townId(),
                placement.nationId(), name, level, hireCost, dailyRent,
                "IN_PROGRESS", null, null, null);

        return journal.create(payment).thenCompose(created -> {
            if (!created) return CompletableFuture.failedFuture(
                    new IllegalStateException("Не удалось зарегистрировать операцию найма."));
            return onMainThread(() -> {
                final boolean withdrawn;
                try {
                    withdrawn = townBank.withdraw(placement.townId(), hireCost,
                            "RaskolNPC guard hire: " + name + " (level " + level + ")");
                } catch (Throwable ambiguous) {
                    return journal.transition(paymentId, "IN_PROGRESS", "REVIEW_REQUIRED",
                                    "Towny withdrawal threw; bank result is unknown: "
                                            + safeMessage(ambiguous))
                            .thenCompose(ignored -> CompletableFuture.failedFuture(
                                    new IllegalStateException("Результат списания неизвестен. "
                                            + "Операция передана администратору на проверку.", ambiguous)));
                }
                if (!withdrawn) {
                    return journal.transition(paymentId, "IN_PROGRESS", "DEBIT_FAILED",
                                    "Towny returned false; no debit was confirmed.")
                            .thenCompose(ignored -> CompletableFuture.failedFuture(
                                    new IllegalStateException("В банке города недостаточно средств.")));
                }
                return journal.transition(paymentId, "IN_PROGRESS", "DEBIT_CONFIRMED", null)
                        .thenCompose(marked -> {
                            if (!marked) return CompletableFuture.failedFuture(
                                    new IllegalStateException("Списание подтверждено, но результат "
                                            + "не записан. Требуется ручная проверка."));
                            return createGuardAfterConfirmedDebit(journal, payment, placement,
                                    name, level, dailyRent, location, reservation);
                        });
            });
        }).whenComplete((result, error) -> reservation.close());
    }

    private CompletableFuture<GuardHiringService.HireResult> createGuardAfterConfirmedDebit(
            GuardHiringPaymentRepository journal, GuardHiringPayment payment,
            TownyKingdomGuardAccess.Placement placement, String name, int level,
            double dailyRent, Location location,
            KingdomGuardService.Reservation reservation) {
        String data = "{\"level\":" + level + ",\"dailyRent\":" + dailyRent
                + ",\"payerTownId\":\"" + placement.townId()
                + "\",\"overdueDays\":0,\"nextRentAt\":"
                + (java.time.Instant.now().getEpochSecond() + 86400) + "}";
        final CompletableFuture<NpcRecord> creation;
        try {
            creation = releaseReservationAfterCreation(
                    lifecycle.create(NpcType.GUARD, name, payment.ownerUuid(),
                            placement.townId(), placement.nationId(), data,
                            NpcSpawnLocation.from(location)), reservation);
        } catch (Throwable creationError) {
            return markHiringReview(journal, payment.paymentId(),
                    "NPC creation threw after confirmed debit; cleanup outcome is unknown: "
                            + safeMessage(creationError), creationError);
        }

        return creation.handle((record, creationError) -> {
            if (creationError != null) {
                return markHiringReview(journal, payment.paymentId(),
                        "NPC persistence failed after confirmed debit; automatic refund was withheld: "
                                + safeMessage(unwrap(creationError)), unwrap(creationError));
            }
            return journal.attachNpcId(payment.paymentId(), record.id().toString())
                    .thenCompose(attached -> {
                        if (!attached) return markHiringReview(journal, payment.paymentId(),
                                "Could not attach created NPC ID to the confirmed hiring payment.",
                                new IllegalStateException("Could not attach NPC ID."));
                        CompletableFuture<GuardHiringService.HireResult> spawn =
                                onMainThread(() -> entities.spawnAsync(record, location))
                                        .handle((spawned, spawnError) -> {
                                            if (spawnError != null) {
                                                return cleanupGuardAfterSpawnFailure(record)
                                                        .thenCompose(cleaned -> {
                                                            if (!cleaned) return markHiringReview(
                                                                    journal, payment.paymentId(),
                                                                    "Guard spawn failed and NPC cleanup "
                                                                            + "was not confirmed; no refund was attempted.",
                                                                    unwrap(spawnError));
                                                            return refundConfirmedHire(journal, payment,
                                                                    placement, name, spawnError);
                                                        });
                                            }
                                            return journal.transition(payment.paymentId(),
                                                            "DEBIT_CONFIRMED", "COMPLETED", null)
                                                    .thenApply(completed -> {
                                                        if (!completed) throw new CompletionException(
                                                                new IllegalStateException("Guard spawned, but "
                                                                        + "hiring completion was not persisted."));
                                                        return new GuardHiringService.HireResult(
                                                                level, payment.hireCost(), dailyRent);
                                                    });
                                        }).thenCompose(java.util.function.Function.identity());
                        return spawn;
                    });
        }).thenCompose(java.util.function.Function.identity());
    }

    static CompletableFuture<NpcRecord> releaseReservationAfterCreation(
            CompletableFuture<NpcRecord> creation,
            KingdomGuardService.Reservation reservation) {
        return creation.whenComplete((record, error) -> reservation.close());
    }

    private CompletableFuture<Boolean> cleanupGuardAfterSpawnFailure(NpcRecord record) {
        return onMainThread(() -> {
            entities.remove(record.id());
            return lifecycle.delete(record.id()).thenCompose(deleted -> {
                if (!deleted) return CompletableFuture.completedFuture(false);
                return lifecycle.completeDeletion(record.id());
            });
        }).exceptionally(error -> false);
    }

    private CompletableFuture<GuardHiringService.HireResult> refundConfirmedHire(
            GuardHiringPaymentRepository journal, GuardHiringPayment payment,
            TownyKingdomGuardAccess.Placement placement, String name, Throwable spawnError) {
        return journal.transition(payment.paymentId(), "DEBIT_CONFIRMED",
                        "REFUND_IN_PROGRESS", "Guard spawn failed after confirmed cleanup: "
                                + safeMessage(unwrap(spawnError)))
                .thenCompose(started -> {
                    if (!started) return markHiringReview(journal, payment.paymentId(),
                            "Cleanup succeeded, but refund state could not be started; refund withheld.",
                            unwrap(spawnError));
                    return onMainThread(() -> {
                        final boolean refunded;
                        try {
                            refunded = townBank.deposit(placement.townId(), payment.hireCost(),
                                    "RaskolNPC guard hire refund: " + name);
                        } catch (Throwable refundError) {
                            return journal.transition(payment.paymentId(), "REFUND_IN_PROGRESS",
                                            "REVIEW_REQUIRED", "Towny refund threw; bank result is unknown: "
                                                    + safeMessage(refundError))
                                    .thenCompose(ignored -> CompletableFuture.failedFuture(
                                            new IllegalStateException("Результат возврата неизвестен.",
                                                    refundError)));
                        }
                        if (!refunded) {
                            return journal.transition(payment.paymentId(), "REFUND_IN_PROGRESS",
                                            "REVIEW_REQUIRED", "Towny returned false for the refund.")
                                    .thenCompose(ignored -> CompletableFuture.failedFuture(
                                            new IllegalStateException("Towny не подтвердил возврат средств.")));
                        }
                        return journal.transition(payment.paymentId(), "REFUND_IN_PROGRESS",
                                        "REFUNDED", null)
                                .thenCompose(saved -> saved
                                        ? CompletableFuture.<GuardHiringService.HireResult>failedFuture(
                                                new CompletionException(unwrap(spawnError)))
                                        : markHiringReview(journal, payment.paymentId(), "REFUND_IN_PROGRESS",
                                                "Refund was accepted by Towny but could not be finalized in SQLite.",
                                                unwrap(spawnError)));
                    });
                });
    }

    private CompletableFuture<GuardHiringService.HireResult> markHiringReview(
            GuardHiringPaymentRepository journal, String paymentId, String reason, Throwable cause) {
        return markHiringReview(journal, paymentId, "DEBIT_CONFIRMED", reason, cause);
    }

    private CompletableFuture<GuardHiringService.HireResult> markHiringReview(
            GuardHiringPaymentRepository journal, String paymentId, String expectedStatus,
            String reason, Throwable cause) {
        return journal.transition(paymentId, expectedStatus, "REVIEW_REQUIRED", reason)
                .handle((changed, journalError) -> {
                    if (journalError != null) cause.addSuppressed(journalError);
                    throw new CompletionException(new IllegalStateException(
                            "Найм требует ручной проверки. ID операции: " + paymentId, cause));
                });
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
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

    public CompletableFuture<List<GuardHiringPayment>> findReviewHiringPayments() {
        GuardHiringPaymentRepository journal = hiringPayments;
        return journal == null ? CompletableFuture.failedFuture(
                new IllegalStateException("Guard hiring journal is unavailable."))
                : journal.findReviewRequired();
    }

    public CompletableFuture<Boolean> resolveHiringReview(
            String paymentId, String resolution, UUID administratorUuid) {
        GuardHiringPaymentRepository journal = hiringPayments;
        return journal == null ? CompletableFuture.failedFuture(
                new IllegalStateException("Guard hiring journal is unavailable."))
                : journal.resolveReview(paymentId, resolution, administratorUuid);
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
            }).thenApply(deletedEntity -> {
                refreshResidentBehavior();
                return deletedEntity;
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
