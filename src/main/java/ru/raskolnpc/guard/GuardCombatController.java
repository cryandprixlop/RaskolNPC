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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final java.util.function.BooleanSupplier worldGuardEnabled;
    private final PlayerSubjectResolver playerSubjects;
    private BiPredicate<Location, String> territoryAccess = (location, nationId) -> true;
    private final int batchSize;
    private final double detectionRadius;
    private final double damage;
    private final long attackCooldownMillis;
    private final Map<NpcId, Long> lastAttack = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> squadTargets = new HashMap<>();
    private GuardSquadService squadService;
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
        this(plugin, registry, entities, relationships, worldGuard, batchSize,
                detectionRadius, damage, attackCooldownMillis, playerSubjects,
                isWorldGuardEnabled(plugin), () -> isWorldGuardEnabled(plugin));
    }

    GuardCombatController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, RelationshipPolicy relationships,
            WorldGuardProtectionAdapter worldGuard, int batchSize,
            double detectionRadius, double damage, long attackCooldownMillis,
            PlayerSubjectResolver playerSubjects, boolean worldGuardRequired) {
        this(plugin, registry, entities, relationships, worldGuard, batchSize,
                detectionRadius, damage, attackCooldownMillis, playerSubjects,
                worldGuardRequired, () -> worldGuardRequired);
    }

    GuardCombatController(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, RelationshipPolicy relationships,
            WorldGuardProtectionAdapter worldGuard, int batchSize,
            double detectionRadius, double damage, long attackCooldownMillis,
            PlayerSubjectResolver playerSubjects, boolean worldGuardRequired,
            java.util.function.BooleanSupplier worldGuardEnabled) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.relationships = Objects.requireNonNull(relationships, "relationships");
        this.worldGuard = worldGuard;
        this.worldGuardRequired = worldGuardRequired;
        this.worldGuardEnabled = Objects.requireNonNull(worldGuardEnabled, "worldGuardEnabled");
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

    private static boolean isWorldGuardEnabled(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        var worldGuardPlugin = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        return worldGuardPlugin != null && worldGuardPlugin.isEnabled();
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
        squadTargets.clear();
    }

    /** Enables shared target selection using the preloaded squad-membership snapshot. */
    public void setSquadService(GuardSquadService squadService) {
        requireMainThread();
        this.squadService = Objects.requireNonNull(squadService, "squadService");
        squadTargets.clear();
    }

    /** Processes at most batchSize registered guards per invocation. */
    public void tick() {
        requireMainThread();
        var guards = registry.all().stream()
                .filter(record -> record.type() == NpcType.GUARD)
                // ConcurrentHashMap iteration order is unspecified; a stable order is
                // required so the round-robin cursor cannot repeatedly skip guards.
                .sorted(Comparator.comparing(record -> record.id().toString()))
                .toList();
        if (guards.isEmpty()) {
            cursor = 0;
            lastAttack.clear();
            squadTargets.clear();
            return;
        }
        if (squadService == null) {
            squadTargets.clear();
        } else {
            Set<UUID> activeSquads = guards.stream()
                    .map(record -> squadService.squadIdFor(record.id()).orElse(null))
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            squadTargets.keySet().retainAll(activeSquads);
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

        Entity target = selectTarget(guard, attacker, now);
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

    /**
     * A squad's stable leader selects one verified target. Members only attack that
     * shared target while it remains valid and within their own detection radius.
     */
    private Entity selectTarget(NpcRecord guard, LivingEntity attacker, long now) {
        if (squadService == null) return nearestHostile(guard, attacker);
        UUID squadId = squadService.squadIdFor(guard.id()).orElse(null);
        if (squadId == null) return nearestHostile(guard, attacker);

        NpcId leaderId = squadService.leaderFor(guard.id()).orElse(guard.id());
        NpcRecord leaderRecord = registry.get(leaderId).orElse(guard);
        Entity leaderEntity = entities.find(leaderId).orElse(null);
        LivingEntity selector = leaderEntity instanceof LivingEntity living
                && living.isValid() && !living.isDead() ? living : attacker;
        NpcRecord selectorRecord = selector == attacker ? guard : leaderRecord;

        Entity lockedTarget = squadTargets.get(squadId);
        if (lockedTarget != null) {
            if (isTargetEligible(selectorRecord, selector, lockedTarget)) {
                return isTargetEligible(guard, attacker, lockedTarget) ? lockedTarget : null;
            }
            squadTargets.remove(squadId);
        }

        Entity selected = nearestHostile(selectorRecord, selector);
        if (selected == null) return null;
        squadTargets.put(squadId, selected);
        return isTargetEligible(guard, attacker, selected) ? selected : null;
    }

    private Entity nearestHostile(NpcRecord guard, LivingEntity attacker) {
        Location origin = attacker.getLocation();
        if (origin.getWorld() == null) return null;
        return attacker.getNearbyEntities(detectionRadius, detectionRadius, detectionRadius).stream()
                .filter(candidate -> candidate instanceof LivingEntity)
                .filter(candidate -> candidate.isValid() && !candidate.isDead())
                .filter(candidate -> candidate.getLocation().getWorld() != null
                        && candidate.getLocation().getWorld().equals(origin.getWorld()))
                .filter(candidate -> candidate.getLocation().distanceSquared(origin)
                        <= detectionRadius * detectionRadius)
                .filter(candidate -> hasTerritoryAccess(candidate.getLocation(), guard.nationId()))
                .filter(candidate -> isVerifiedHostile(guard, candidate))
                .min(Comparator.comparingDouble(candidate ->
                        candidate.getLocation().distanceSquared(origin)))
                .orElse(null);
    }

    private boolean isTargetEligible(NpcRecord guard, LivingEntity attacker, Entity target) {
        if (!(target instanceof LivingEntity livingTarget)
                || !livingTarget.isValid() || livingTarget.isDead()) return false;
        Location origin = attacker.getLocation();
        Location targetLocation = livingTarget.getLocation();
        if (origin.getWorld() == null || targetLocation.getWorld() == null
                || !origin.getWorld().equals(targetLocation.getWorld())
                || targetLocation.distanceSquared(origin) > detectionRadius * detectionRadius) {
            return false;
        }
        return hasTerritoryAccess(targetLocation, guard.nationId())
                && isVerifiedHostile(guard, target);
    }

    private boolean isVerifiedHostile(NpcRecord guard, Entity candidate) {
        try {
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
        } catch (RuntimeException unavailable) {
            // Fail closed when Towny/SiegeWar diplomacy cannot be queried.
            return false;
        }
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
        try {
            // WorldGuard may be disabled after RaskolNPC starts. A stale RegionQuery
            // must never keep granting combat permission after its provider is offline.
            if (!worldGuardEnabled.getAsBoolean()) return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
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
