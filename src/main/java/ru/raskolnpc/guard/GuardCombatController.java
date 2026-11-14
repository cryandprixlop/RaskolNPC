package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.Location;
import java.util.function.BiPredicate;
import org.bukkit.scheduler.BukkitTask;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.integration.PlayerSubjectResolver;
import ru.raskolnpc.integration.worldguard.WorldGuardProtectionAdapter;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bounded first-pass guard combat: only registered NPCs with a verified hostile
 * Towny/SiegeWar relationship can be attacked. Unknown relations and failed
 * WorldGuard queries are never treated as permission to damage.
 */
public final class GuardCombatController {
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final RelationshipPolicy relationships;
    private final WorldGuardProtectionAdapter worldGuard;
    private final boolean worldGuardRequired;
    private final PlayerSubjectResolver playerSubjects;
    private BiPredicate<Location, String> territoryAccess = (location, nationId) -> true;
    private final int batchSize;
    private final double detectionRadius;
    private final double damage;
    private final long attackCooldownMillis;
    private final Map<NpcId, Long> lastAttack = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int cursor;

    public GuardCombatController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, RelationshipPolicy relationships,
            WorldGuardProtectionAdapter worldGuard, int batchSize,
            double detectionRadius, double damage, long attackCooldownMillis) {
        this(plugin, registry, entities, relationships, worldGuard, batchSize,
                detectionRadius, damage, attackCooldownMillis, PlayerSubjectResolver.none());
    }

    public GuardCombatController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, RelationshipPolicy relationships,
            WorldGuardProtectionAdapter worldGuard, int batchSize,
            double detectionRadius, double damage, long attackCooldownMillis,
            PlayerSubjectResolver playerSubjects) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.relationships = Objects.requireNonNull(relationships, "relationships");
        this.worldGuard = worldGuard;
        var wg = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        this.worldGuardRequired = wg != null && wg.isEnabled();
        this.batchSize = Math.max(1, batchSize);
        if (!Double.isFinite(detectionRadius) || detectionRadius < 2 || detectionRadius > 32) {
            throw new IllegalArgumentException("detectionRadius must be between 2 and 32.");
        }
        if (!Double.isFinite(damage) || damage <= 0 || damage > 20) {
            throw new IllegalArgumentException("damage must be greater than 0 and at most 20.");
        }
        if (attackCooldownMillis < 250 || attackCooldownMillis > 10000) {
            throw new IllegalArgumentException("attackCooldownMillis must be between 250 and 10000.");
        }
        this.detectionRadius = detectionRadius;
        this.damage = damage;
        this.attackCooldownMillis = attackCooldownMillis;
        this.playerSubjects = Objects.requireNonNull(playerSubjects, "playerSubjects");
    }

    public void start() {
        requireMainThread();
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastAttack.clear();
    }

    /** Processes at most batchSize registered guards per invocation. */
    public void tick() {
        requireMainThread();
        var guards = registry.all().stream()
                .filter(record -> record.type() == NpcType.GUARD)
                .toList();
        if (guards.isEmpty()) {
            cursor = 0;
            lastAttack.clear();
            return;
        }
        java.util.Set<NpcId> activeGuardIds = guards.stream()
                .map(NpcRecord::id)
                .collect(java.util.stream.Collectors.toSet());
        lastAttack.keySet().retainAll(activeGuardIds);
        int count = Math.min(batchSize, guards.size());
        int start = Math.floorMod(cursor, guards.size());
        for (int offset = 0; offset < count; offset++) {
            attackNearestHostile(guards.get((start + offset) % guards.size()));
        }
        cursor = (start + count) % guards.size();
    }

    private void attackNearestHostile(NpcRecord guard) {
        Entity guardEntity = entities.find(guard.id()).orElse(null);
        if (!(guardEntity instanceof LivingEntity attacker)
                || !attacker.isValid() || attacker.isDead()) return;
        long now = System.currentTimeMillis();
        long previous = lastAttack.getOrDefault(guard.id(), 0L);
        if (now - previous < attackCooldownMillis) return;

        Entity target = attacker.getNearbyEntities(
                        detectionRadius, detectionRadius, detectionRadius).stream()
                .filter(candidate -> candidate instanceof LivingEntity)
                .filter(candidate -> candidate.isValid() && !candidate.isDead())
                .filter(candidate -> candidate.getLocation().getWorld() != null
                        && candidate.getLocation().getWorld().equals(
                                attacker.getLocation().getWorld()))
                .filter(candidate -> candidate.getLocation().distanceSquared(attacker.getLocation())
                        <= detectionRadius * detectionRadius)
                .filter(candidate -> isVerifiedHostile(guard, candidate))
                .min(Comparator.comparingDouble(candidate ->
                        candidate.getLocation().distanceSquared(attacker.getLocation())))
                .orElse(null);
        if (!(target instanceof LivingEntity livingTarget)) return;
        if (!mayDamage(guard, attacker, livingTarget)) return;

        try {
            livingTarget.damage(damage, attacker);
            lastAttack.put(guard.id(), now);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.FINE,
                    "Guard combat action failed for " + guard.id(), error);
        }
    }

    private boolean isVerifiedHostile(NpcRecord guard, Entity candidate) {
        RelationshipPolicy.Subject victim;
        if (candidate instanceof Player player) {
            victim = playerSubjects.resolve(player).orElse(null);
            if (victim == null) return false;
        } else {
            NpcId targetId = entities.getNpcId(candidate).orElse(null);
            if (targetId == null || targetId.equals(guard.id())) return false;
            NpcRecord target = registry.get(targetId).orElse(null);
            if (target == null) return false;
            victim = new RelationshipPolicy.Subject(
                    target.ownerUuid(), target.townId(), target.nationId());
        }
        RelationshipPolicy.Subject actor = new RelationshipPolicy.Subject(
                guard.ownerUuid(), guard.townId(), guard.nationId());
        return relationships.mayTarget(actor, victim);
    }

    /** Restricts combat actions to the hiring nation when a territory provider is configured. */
    public void setTerritoryAccess(BiPredicate<Location, String> territoryAccess) {
        requireMainThread();
        this.territoryAccess = Objects.requireNonNull(territoryAccess, "territoryAccess");
    }

    private boolean mayDamage(NpcRecord guard, LivingEntity attacker, LivingEntity target) {
        Location attackerLocation = attacker.getLocation();
        Location targetLocation = target.getLocation();
        if (!hasTerritoryAccess(attackerLocation, guard.nationId())
                || !hasTerritoryAccess(targetLocation, guard.nationId())) return false;
        if (!worldGuardRequired) return true;
        if (worldGuard == null) return false;
        return worldGuard.checkMobDamage(attackerLocation)
                        == WorldGuardProtectionAdapter.Decision.ALLOWED
                && worldGuard.checkMobDamage(targetLocation)
                        == WorldGuardProtectionAdapter.Decision.ALLOWED;
    }

    private boolean hasTerritoryAccess(Location location, String nationId) {
        try {
            return territoryAccess.test(location, nationId);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Guard combat must run on the server thread.");
        }
    }
}
