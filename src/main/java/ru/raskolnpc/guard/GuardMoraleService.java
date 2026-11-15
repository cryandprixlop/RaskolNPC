package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.raskolnpc.guard.model.GuardCondition;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardConditionRepository;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks guard morale independently of entity lifetime. All Bukkit access is
 * confined to the server thread; repository operations run on database workers.
 */
public final class GuardMoraleService implements Listener {
    private static final long RECOVERY_INTERVAL_TICKS = 1200L;
    private static final long RECOVERY_DELAY_MILLIS = 120_000L;
    private static final long DEFAULT_INJURY_RECOVERY_MILLIS = 300_000L;

    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final GuardConditionRepository repository;
    private final Map<NpcId, GuardCondition> conditions = new ConcurrentHashMap<>();
    private final java.util.Set<NpcId> retreating = ConcurrentHashMap.newKeySet();
    private int retreatThreshold = 20;
    private int rallyThreshold = 35;
    private int alliedDeathPenalty = 8;
    private double lightInjuryDamage = 4.0;
    private double severeInjuryDamage = 8.0;
    private long injuryRecoveryMillis = DEFAULT_INJURY_RECOVERY_MILLIS;
    private BukkitTask recoveryTask;
    private boolean listenerRegistered;

    public GuardMoraleService(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, GuardConditionRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CompletableFuture<Void> load() {
        return repository.findAll().thenAccept(loaded -> {
            conditions.clear();
            retreating.clear();
            for (GuardCondition condition : loaded) {
                NpcRecord record = registry.get(condition.npcId()).orElse(null);
                if (record != null && record.type() == NpcType.GUARD) {
                    conditions.put(condition.npcId(), condition);
                    if (condition.morale() <= retreatThreshold) retreating.add(condition.npcId());
                }
            }
        });
    }

    public void start() {
        requireMainThread();
        if (recoveryTask != null) return;
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
        recoveryTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::recoverMorale, RECOVERY_INTERVAL_TICKS, RECOVERY_INTERVAL_TICKS);
    }

    public void stop() {
        if (recoveryTask != null) {
            recoveryTask.cancel();
            recoveryTask = null;
        }
        if (listenerRegistered) {
            HandlerList.unregisterAll(this);
            listenerRegistered = false;
        }
    }

    /** Configures hysteresis thresholds; rally must be above retreat. */
    public void setRetreatThresholds(int retreatThreshold, int rallyThreshold) {
        requireMainThread();
        if (retreatThreshold < 0 || retreatThreshold > 100
                || rallyThreshold <= retreatThreshold || rallyThreshold > 100) {
            throw new IllegalArgumentException("Invalid guard morale retreat thresholds.");
        }
        this.retreatThreshold = retreatThreshold;
        this.rallyThreshold = rallyThreshold;
        for (GuardCondition condition : conditions.values()) {
            if (condition.morale() <= retreatThreshold) retreating.add(condition.npcId());
            else if (condition.morale() >= rallyThreshold) retreating.remove(condition.npcId());
        }
    }

    /** True while the guard is routed; recovery must reach the rally threshold. */
    public boolean isRetreating(NpcId guardId) {
        return retreating.contains(guardId);
    }

    /** Applies a bounded morale loss from a tactical event such as a nearby ally falling. */
    public void applyMoraleLoss(NpcId guardId, int amount) {
        requireMainThread();
        if (amount <= 0) return;
        NpcRecord record = registry.get(guardId).orElse(null);
        if (record == null || record.type() != NpcType.GUARD) return;
        long now = System.currentTimeMillis();
        GuardCondition previous = conditions.getOrDefault(guardId,
                new GuardCondition(guardId, 100, 0, Math.max(1, now)));
        GuardCondition changed = previous.withMorale(previous.morale() - amount, now, now);
        conditions.put(guardId, changed);
        if (changed.morale() <= retreatThreshold) retreating.add(guardId);
        persist(changed);
    }

    public void setInjuryPolicy(double lightDamage, double severeDamage, long recoveryMillis) {
        requireMainThread();
        if (!Double.isFinite(lightDamage) || !Double.isFinite(severeDamage)
                || lightDamage <= 0 || severeDamage <= lightDamage || recoveryMillis < 60_000L) {
            throw new IllegalArgumentException("Invalid guard injury policy.");
        }
        lightInjuryDamage = lightDamage;
        severeInjuryDamage = severeDamage;
        injuryRecoveryMillis = recoveryMillis;
    }

    public void setAlliedDeathPenalty(int penalty) {
        requireMainThread();
        if (penalty < 0 || penalty > 50) throw new IllegalArgumentException("allied death penalty must be between 0 and 50.");
        alliedDeathPenalty = penalty;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onGuardDeath(EntityDeathEvent event) {
        requireMainThread();
        if (alliedDeathPenalty == 0) return;
        NpcId fallenId = entities.getNpcId(event.getEntity()).orElse(null);
        NpcRecord fallen = fallenId == null ? null : registry.get(fallenId).orElse(null);
        if (fallen == null || fallen.type() != NpcType.GUARD || fallen.nationId() == null) return;
        Location origin = event.getEntity().getLocation();
        if (origin.getWorld() == null) return;
        double radiusSquared = 12.0 * 12.0;
        for (NpcRecord ally : registry.all()) {
            if (ally.type() != NpcType.GUARD || ally.id().equals(fallenId)
                    || !Objects.equals(ally.nationId(), fallen.nationId())) continue;
            Entity entity = entities.find(ally.id()).orElse(null);
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) continue;
            Location location = living.getLocation();
            if (location.getWorld() != null && location.getWorld().equals(origin.getWorld())
                    && location.distanceSquared(origin) <= radiusSquared) {
                applyMoraleLoss(ally.id(), alliedDeathPenalty);
            }
        }
    }

    public int morale(NpcId guardId) {
        GuardCondition condition = conditions.get(guardId);
        return condition == null ? 100 : condition.morale();
    }

    public int injurySeverity(NpcId guardId) {
        GuardCondition condition = conditions.get(guardId);
        return condition == null ? 0 : condition.injurySeverity();
    }

    public double damageMultiplier(NpcId guardId) {
        GuardCondition condition = conditions.get(guardId);
        return condition == null ? 1.0 : condition.damageMultiplier();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onGuardDamaged(EntityDamageEvent event) {
        requireMainThread();
        NpcId guardId = entities.getNpcId(event.getEntity()).orElse(null);
        if (guardId == null) return;
        NpcRecord record = registry.get(guardId).orElse(null);
        if (record == null || record.type() != NpcType.GUARD) return;
        if (!Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0) return;

        GuardCondition previous = conditions.getOrDefault(guardId,
                new GuardCondition(guardId, 100, 0, Math.max(1, System.currentTimeMillis())));
        double finalDamage = event.getFinalDamage();
        int loss = Math.max(1, (int) Math.ceil(finalDamage * 2.0));
        int injuryIncrease = finalDamage >= severeInjuryDamage ? 2
                : finalDamage >= lightInjuryDamage ? 1 : 0;
        int severity = Math.min(2, previous.injurySeverity() + injuryIncrease);
        long now = System.currentTimeMillis();
        GuardCondition changed = previous.withCombatState(previous.morale() - loss, severity, now);
        conditions.put(guardId, changed);
        if (changed.morale() <= retreatThreshold) retreating.add(guardId);
        persist(changed);
    }

    private void recoverMorale() {
        requireMainThread();
        long now = System.currentTimeMillis();
        for (Map.Entry<NpcId, GuardCondition> entry : conditions.entrySet()) {
            GuardCondition current = entry.getValue();
            NpcRecord record = registry.get(entry.getKey()).orElse(null);
            if (record == null || record.type() != NpcType.GUARD) {
                conditions.remove(entry.getKey(), current);
                retreating.remove(entry.getKey());
                continue;
            }
            if (now - current.lastCombatAt() < RECOVERY_DELAY_MILLIS) continue;

            int recoveredMorale = Math.min(100, current.morale() + 1);
            int recoveredInjury = current.injurySeverity();
            long injuryRecoveryAt = current.lastInjuryRecoveryAt();
            if (recoveredInjury > 0 && now - injuryRecoveryAt >= injuryRecoveryMillis) {
                recoveredInjury--;
                injuryRecoveryAt = recoveredInjury > 0 ? now : 0;
            }

            Entity entity = entities.find(entry.getKey()).orElse(null);
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                double maximum = living.getMaxHealth();
                if (Double.isFinite(maximum) && maximum > 0 && living.getHealth() < maximum) {
                    living.setHealth(Math.min(maximum, living.getHealth() + 1.0));
                }
            }

            if (recoveredMorale == current.morale()
                    && recoveredInjury == current.injurySeverity()) continue;
            GuardCondition recovered = current.withRecovery(
                    recoveredMorale, recoveredInjury, injuryRecoveryAt, now);
            if (conditions.replace(entry.getKey(), current, recovered)) {
                if (recovered.morale() >= rallyThreshold) retreating.remove(entry.getKey());
                persist(recovered);
            }
        }
    }

    private void persist(GuardCondition condition) {
        repository.save(condition).exceptionally(error -> {
            plugin.getLogger().warning("Could not persist guard morale for "
                    + condition.npcId() + ": " + error.getMessage());
            return null;
        });
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Guard morale Bukkit operations must run on the main thread.");
        }
    }
}
