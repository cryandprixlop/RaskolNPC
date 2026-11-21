package ru.raskolnpc.npc.resident;

import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight profession presentation. It does not generate items, edit blocks,
 * create repeating tasks, or perform database access.
 */
final class ResidentWorkActionController {
    private long actionIntervalTicks = 60L;
    private final Map<NpcId, Long> lastActionTicks = new HashMap<>();

    void setActionIntervalTicks(long ticks) {
        if (ticks < 1 || ticks > 24_000L) {
            throw new IllegalArgumentException("Work action interval must be between 1 and 24000 ticks.");
        }
        actionIntervalTicks = ticks;
    }

    boolean performIfReady(NpcId npcId, Entity entity, ResidentProfile profile, long currentTick) {
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(profile, "profile");
        if (!(entity instanceof Villager villager) || !entity.isValid()) return false;

        Long previous = lastActionTicks.get(npcId);
        if (previous != null && currentTick - previous < actionIntervalTicks) return false;
        lastActionTicks.put(npcId, currentTick);

        villager.swingMainHand();
        Particle particle = particleFor(profile.profession());
        entity.getWorld().spawnParticle(particle,
                entity.getLocation().add(0.0, 1.0, 0.0),
                2, 0.15, 0.2, 0.15, 0.01);
        return true;
    }

    private Particle particleFor(ResidentProfession profession) {
        return switch (profession) {
            case FARMER -> Particle.COMPOSTER;
            case MINER, LUMBERJACK, BUILDER -> Particle.CRIT;
            case FISHERMAN -> Particle.BUBBLE;
            case BLACKSMITH -> Particle.FLAME;
            case CRAFTSMAN, MERCHANT -> Particle.HAPPY_VILLAGER;
            case COOK -> Particle.SMOKE;
            case TEACHER -> Particle.ENCHANT;
        };
    }

    void clear() {
        lastActionTicks.clear();
    }
}
