package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardRentPaymentRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects guard rent using a durable SQLite ledger. Towny calls are made only
 * on the server thread; an ambiguous result is never retried automatically.
 */
public final class GuardRentService {
    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();
    private static final Pattern NUMBER = Pattern.compile("\"%s\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern STRING = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcLifecycleService lifecycle;
    private final NpcEntityManager entities;
    private final TownyTownBankEconomy economy;
    private final GuardRentPaymentRepository payments;
    private final int maximumDebtDays;
    private final Set<NpcId> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public GuardRentService(
            Plugin plugin,
            NpcRegistry registry,
            NpcLifecycleService lifecycle,
            NpcEntityManager entities,
            TownyTownBankEconomy economy,
            GuardRentPaymentRepository payments,
            int maximumDebtDays
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.maximumDebtDays = Math.max(1, Math.min(3, maximumDebtDays));
    }

    public void start() {
        requireMainThread();
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDueGuards, 20L, 20L * 60L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Must run after NPC records have been loaded and before normal rent polling.
     * IN_PROGRESS is deliberately not retried: it may have reached Towny's bank.
     */
    public CompletableFuture<Void> recoverPendingPayments() {
        return payments.moveUnfinishedToReview(
                        "Server restarted while the Towny payment result was not finalized.")
                .thenCompose(ignored -> payments.findByStatus(GuardRentPaymentStatus.PAID))
                .thenCompose(paid -> recoverPayments(paid, true))
                .thenCompose(ignored -> payments.findByStatus(GuardRentPaymentStatus.FAILED))
                .thenCompose(failed -> recoverPayments(failed, false));
    }

    private CompletableFuture<Void> recoverPayments(
            List<GuardRentPayment> pending, boolean paid
    ) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (GuardRentPayment payment : pending) {
            chain = chain.thenCompose(ignored -> applyKnownPayment(payment, paid));
        }
        return chain;
    }

    public void checkDueGuards() {
        requireMainThread();
        if (!lifecycle.isReady()) return;
        long now = Instant.now().getEpochSecond();

        for (NpcRecord record : registry.all()) {
            if (record.type() != NpcType.GUARD) continue;
            keepVanillaAiDisabled(record);

            try {
                long dueAt = (long) number(record.dataJson(), "nextRentAt", 0);
                if (dueAt <= 0 || dueAt > now || !inFlight.add(record.id())) continue;
                preparePayment(record, dueAt, now);
            } catch (RuntimeException exception) {
                inFlight.remove(record.id());
                plugin.getLogger().log(Level.SEVERE,
                        "Could not prepare rent for guard " + record.id(), exception);
            }
        }
    }

    private void preparePayment(NpcRecord record, long dueAt, long now) {
        double rent = number(record.dataJson(), "dailyRent", -1);
        String townId = string(record.dataJson(), "payerTownId");
        int debtDays = (int) number(record.dataJson(), "overdueDays", 0);

        if (!Double.isFinite(rent) || rent < 0 || townId == null || townId.isBlank()
                || debtDays < 0 || debtDays > maximumDebtDays) {
            inFlight.remove(record.id());
            plugin.getLogger().severe("Guard " + record.id()
                    + " has invalid rent metadata; automatic collection is suspended.");
            return;
        }

        int nextDebt = Math.min(maximumDebtDays, debtDays + 1);
        GuardRentPayment payment = new GuardRentPayment(
                UUID.randomUUID().toString(),
                record.id(),
                dueAt,
                townId,
                rent,
                GuardRentPaymentStatus.IN_PROGRESS,
                nextDebt,
                now + DAY_SECONDS,
                null,
                null,
                null
        );

        payments.createIfAbsent(payment).whenComplete((created, error) -> runMain(() -> {
            if (error != null) {
                logFailure("Could not create durable rent payment for guard " + record.id(), error);
                inFlight.remove(record.id());
                return;
            }
            if (!Boolean.TRUE.equals(created)) {
                payments.findByDue(record.id(), dueAt).whenComplete((existing, lookupError) ->
                        runMain(() -> {
                            if (lookupError != null) {
                                logFailure("Could not inspect existing rent payment for "
                                        + record.id(), lookupError);
                            } else if (existing.isPresent()) {
                                handleExistingPayment(existing.get());
                            }
                            inFlight.remove(record.id());
                        }));
                return;
            }
            withdrawOnce(record, payment);
        }));
    }

    private void withdrawOnce(NpcRecord record, GuardRentPayment payment) {
        requireMainThread();
        final boolean withdrawn;
        try {
            withdrawn = economy.withdraw(payment.townId(), payment.amount(),
                    "RaskolNPC daily guard rent: " + record.name());
        } catch (Throwable ambiguous) {
            payments.markReviewRequired(payment.paymentId(),
                    "Towny withdrawal threw an exception; the bank result is unknown: "
                            + safeMessage(ambiguous))
                    .whenComplete((changed, error) -> runMain(() -> {
                        if (error != null) logFailure(
                                "Could not flag ambiguous rent payment " + payment.paymentId(), error);
                        else if (!Boolean.TRUE.equals(changed)) plugin.getLogger().severe(
                                "Ambiguous rent payment could not leave IN_PROGRESS: "
                                        + payment.paymentId());
                        inFlight.remove(record.id());
                    }));
            return;
        }

        CompletableFuture<Boolean> transition = withdrawn
                ? payments.markPaid(payment.paymentId())
                : payments.markFailed(payment.paymentId(),
                        "Towny returned false: the town bank declined the withdrawal.");
        transition.whenComplete((changed, error) -> runMain(() -> {
            if (error != null) {
                // Keep IN_PROGRESS if the database result is uncertain. Startup recovery
                // moves it to REVIEW_REQUIRED, and this code never calls withdraw again.
                logFailure("Could not finalize Towny rent result for " + payment.paymentId(), error);
                inFlight.remove(record.id());
                return;
            }
            if (!Boolean.TRUE.equals(changed)) {
                plugin.getLogger().severe("Rent payment state was not changed after Towny returned "
                        + (withdrawn ? "success" : "failure") + ": " + payment.paymentId());
                inFlight.remove(record.id());
                return;
            }
            applyKnownPayment(payment, withdrawn).whenComplete((ignored, applyError) -> runMain(() -> {
                if (applyError != null) {
                    logFailure("Could not apply rent payment state for " + payment.paymentId(),
                            applyError);
                }
                inFlight.remove(record.id());
            }));
        }));
    }

    private void handleExistingPayment(GuardRentPayment payment) {
        switch (payment.status()) {
            case PAID -> applyKnownPayment(payment, true).whenComplete((ignored, error) -> {
                if (error != null) logFailure("Could not recover paid rent " + payment.paymentId(), error);
            });
            case FAILED -> applyKnownPayment(payment, false).whenComplete((ignored, error) -> {
                if (error != null) logFailure("Could not recover failed rent " + payment.paymentId(), error);
            });
            case IN_PROGRESS, REVIEW_REQUIRED ->
                    plugin.getLogger().warning("Rent payment " + payment.paymentId()
                            + " is not eligible for automatic retry (status "
                            + payment.status() + ").");
            case PAID_APPLIED, DEBT_APPLIED -> {
                // The ledger already records that the NPC-side effect was committed.
            }
        }
    }

    /** Returns unresolved rent operations for the administrative review screen. */
    public CompletableFuture<List<GuardRentPayment>> findReviewPayments() {
        return payments.findByStatus(GuardRentPaymentStatus.REVIEW_REQUIRED);
    }

    /**
     * Resolves an ambiguous operation without calling Towny again. The chosen
     * outcome is persisted first, then the already-decided NPC effect is applied.
     */
    public CompletableFuture<Boolean> resolveReview(
            String paymentId, boolean paid, UUID administratorUuid
    ) {
        GuardRentPaymentStatus resolution = paid
                ? GuardRentPaymentStatus.PAID : GuardRentPaymentStatus.FAILED;
        return payments.resolveReview(paymentId, resolution, administratorUuid)
                .thenCompose(resolved -> {
                    if (!resolved) return CompletableFuture.completedFuture(false);
                    return payments.findById(paymentId).thenCompose(found -> {
                        if (found.isEmpty()) return failed(
                                "Resolved rent payment disappeared: " + paymentId);
                        return applyKnownPayment(found.get(), paid)
                                .thenApply(ignored -> true);
                    });
                });
    }

    private CompletableFuture<Void> applyKnownPayment(
            GuardRentPayment payment, boolean paid
    ) {
        NpcRecord current = registry.get(payment.npcId()).orElse(null);
        if (!paid && payment.targetDebtDays() >= maximumDebtDays) {
            return payments.applyDebtAndQueueDeletion(payment.paymentId(), payment.npcId())
                    .thenCompose(applied -> {
                        if (!applied) return failed("Could not atomically queue guard deletion "
                                + payment.npcId());
                        return onMain(() -> {
                            registry.unregister(payment.npcId());
                            entities.remove(payment.npcId());
                        }).thenCompose(ignored -> lifecycle.completeDeletion(payment.npcId()))
                                .thenApply(ignored -> null);
                    });
        }

        if (current == null) {
            return failed("NPC record is missing while applying rent payment "
                    + payment.paymentId());
        }

        int debt = paid ? 0 : payment.targetDebtDays();
        NpcState state = paid ? NpcState.ACTIVE : NpcState.INACTIVE;
        String data = metadata(current.dataJson(), debt, payment.targetNextRentAt());
        NpcRecord updated = copy(current, state, data);

        CompletableFuture<Boolean> persisted = paid
                ? payments.applyPaid(payment.paymentId(), updated)
                : payments.applyDebt(payment.paymentId(), updated);

        return persisted.thenCompose(applied -> {
            if (!applied) return failed("Could not atomically apply rent payment "
                    + payment.paymentId());
            return onMain(() -> {
                registry.put(updated);
                if (!paid) keepVanillaAiDisabled(updated);
            });
        });
    }

    private CompletableFuture<Void> onMain(Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        runMain(() -> {
            try {
                action.run();
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private void keepVanillaAiDisabled(NpcRecord record) {
        requireMainThread();
        entities.find(record.id()).ifPresent(entity -> {
            if (entity instanceof LivingEntity living) living.setAI(false);
        });
    }

    private void runMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            if (Bukkit.isPrimaryThread()) action.run();
            else Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException schedulingError) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not schedule guard rent continuation on the server thread.",
                    schedulingError);
        }
    }

    private static CompletableFuture<Void> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private void logFailure(String message, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, message, error);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    static NpcRecord copy(NpcRecord record, NpcState state, String data) {
        return new NpcRecord(record.id(), record.type(), record.name(), record.ownerUuid(),
                record.townId(), record.nationId(), state, data, record.createdAt(),
                Instant.now().toString(), record.entityUuid(), record.spawnLocation());
    }

    static String metadata(String json, int debt, long nextDue) {
        String updated = replaceNumber(json, "overdueDays", debt);
        return replaceNumber(updated, "nextRentAt", nextDue);
    }

    static String replaceNumber(String json, String key, long value) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*-?[0-9]+");
        Matcher matcher = pattern.matcher(json);
        String replacement = "\"" + key + "\":" + value;
        return matcher.find() ? matcher.replaceFirst(Matcher.quoteReplacement(replacement))
                : json.substring(0, json.length() - 1) + "," + replacement + "}";
    }

    static double number(String json, String key, double fallback) {
        Matcher matcher = Pattern.compile(String.format(NUMBER.pattern(), Pattern.quote(key)))
                .matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    static String string(String json, String key) {
        Matcher matcher = Pattern.compile(String.format(STRING.pattern(), Pattern.quote(key)))
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Guard rent Towny and entity operations must run on the server thread.");
        }
    }
}
