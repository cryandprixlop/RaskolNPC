package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent, wall-clock based rent collection for Towny kingdom guards. */
public final class GuardRentService {
    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();
    private static final Pattern NUMBER = Pattern.compile("\"%s\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern STRING = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcLifecycleService lifecycle;
    private final NpcEntityManager entities;
    private final TownyTownBankEconomy economy;
    private final int maximumDebtDays;
    private BukkitTask task;

    public GuardRentService(Plugin plugin, NpcRegistry registry,
                            NpcLifecycleService lifecycle, NpcEntityManager entities,
                            TownyTownBankEconomy economy, int maximumDebtDays) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.maximumDebtDays = Math.max(1, maximumDebtDays);
    }

    public void start() {
        requireMainThread();
        if (task != null) return;
        // Frequent lightweight due-time check; payment dates use epoch time, not ticks.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDueGuards, 20L, 20L * 60L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void checkDueGuards() {
        requireMainThread();
        if (!lifecycle.isReady()) return;
        long now = Instant.now().getEpochSecond();
        for (NpcRecord record : registry.all()) {
            if (record.type() != NpcType.GUARD) continue;
            try {
                long dueAt = (long) number(record.dataJson(), "nextRentAt", 0);
                if (dueAt <= 0 || dueAt > now) continue;
                collect(record, now);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not process rent for guard " + record.id(), exception);
            }
        }
    }

    private void collect(NpcRecord record, long now) {
        double rent = number(record.dataJson(), "dailyRent", -1);
        String payerTownId = string(record.dataJson(), "payerTownId");
        int debtDays = (int) number(record.dataJson(), "overdueDays", 0);
        if (!Double.isFinite(rent) || rent < 0 || payerTownId == null || payerTownId.isBlank()) {
            plugin.getLogger().severe("Guard " + record.id()
                    + " has invalid rent metadata; automatic removal is suspended.");
            return;
        }

        if (economy.withdraw(payerTownId, rent, "RaskolNPC daily guard rent: " + record.name())) {
            String data = metadata(record.dataJson(), 0, now + DAY_SECONDS);
            NpcRecord paid = copy(record, NpcState.ACTIVE, data);
            lifecycle.updateRecord(paid).whenComplete((updated, error) -> runMain(() -> {
                if (error != null || !Boolean.TRUE.equals(updated)) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Could not persist successful rent payment for " + record.id(), error);
                    return;
                }
                setAi(record, true);
            }));
            return;
        }

        int nextDebt = debtDays + 1;
        if (nextDebt >= maximumDebtDays) {
            lifecycle.delete(record.id()).thenCompose(deleted -> deleted
                    ? removeEntityAndComplete(record) : CompletableFuture.completedFuture(false))
                    .whenComplete((removed, error) -> {
                        if (error != null) plugin.getLogger().log(Level.SEVERE,
                                "Could not dismiss guard after rent debt: " + record.id(), error);
                    });
            return;
        }

        NpcRecord unpaid = copy(record, NpcState.INACTIVE,
                metadata(record.dataJson(), nextDebt, now + DAY_SECONDS));
        lifecycle.updateRecord(unpaid).whenComplete((updated, error) -> runMain(() -> {
            if (error != null || !Boolean.TRUE.equals(updated)) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not persist rent debt for guard " + record.id(), error);
                return;
            }
            setAi(record, false);
        }));
    }

    private CompletableFuture<Boolean> removeEntityAndComplete(NpcRecord record) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runMain(() -> {
            try {
                entities.remove(record.id());
                lifecycle.completeDeletion(record.id()).whenComplete((done, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else result.complete(Boolean.TRUE.equals(done));
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private void setAi(NpcRecord record, boolean enabled) {
        requireMainThread();
        entities.find(record.id()).ifPresent(entity -> {
            if (entity instanceof LivingEntity living) living.setAI(enabled);
        });
    }

    private void runMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    private static NpcRecord copy(NpcRecord record, NpcState state, String data) {
        return new NpcRecord(record.id(), record.type(), record.name(), record.ownerUuid(),
                record.townId(), record.nationId(), state, data, record.createdAt(),
                Instant.now().toString(), record.entityUuid(), record.spawnLocation());
    }

    private static String metadata(String json, int debt, long nextDue) {
        String updated = replaceNumber(json, "overdueDays", debt);
        return replaceNumber(updated, "nextRentAt", nextDue);
    }

    private static String replaceNumber(String json, String key, long value) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*-?[0-9]+");
        Matcher matcher = pattern.matcher(json);
        String replacement = "\"" + key + "\":" + value;
        return matcher.find() ? matcher.replaceFirst(Matcher.quoteReplacement(replacement))
                : json.substring(0, json.length() - 1) + "," + replacement + "}";
    }

    private static double number(String json, String key, double fallback) {
        Matcher matcher = Pattern.compile(String.format(NUMBER.pattern(), Pattern.quote(key)))
                .matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    private static String string(String json, String key) {
        Matcher matcher = Pattern.compile(String.format(STRING.pattern(), Pattern.quote(key)))
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Guard rent Towny and entity operations must run on main thread.");
        }
    }
}
