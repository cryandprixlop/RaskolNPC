package ru.raskolnpc.npc.resident;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.resident.model.ResidentBehaviorData;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.storage.ResidentScheduleRepository;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Cached, centrally scheduled execution of resident weekly schedules. */
public final class ResidentBehaviorService {
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final ResidentScheduleRepository repository;
    private final ResidentSchedulePlanner planner = new ResidentSchedulePlanner();
    private final ResidentMovementController movement;
    private final int batchSize;
    private final long intervalTicks;
    private final Map<ru.raskolnpc.npc.model.NpcId, ResidentBehaviorData> cache = new HashMap<>();
    private final AtomicBoolean loading = new AtomicBoolean();
    private List<NpcRecord> residents = List.of();
    private int cursor;
    private int taskId = -1;

    public ResidentBehaviorService(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, ResidentScheduleRepository repository,
            int batchSize, long intervalTicks, double movementStep) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (batchSize < 1 || intervalTicks < 1) {
            throw new IllegalArgumentException("Batch size and interval must be positive.");
        }
        this.batchSize = batchSize;
        this.intervalTicks = intervalTicks;
        this.movement = new ResidentMovementController(movementStep);
    }

    /** Loads behavior data asynchronously once, then starts one shared timer. */
    public void start() {
        if (!loading.compareAndSet(false, true)) return;
        repository.findAllBehaviorData().whenComplete((snapshots, error) -> {
            if (!plugin.isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not load resident behavior data.", error);
                        loading.set(false);
                        return;
                    }
                    cache.clear();
                    for (ResidentBehaviorData snapshot : snapshots) {
                        cache.put(snapshot.npcId(), snapshot);
                    }
                    residents = registry.all().stream()
                            .filter(record -> record.type() == NpcType.RESIDENT)
                            .toList();
                    cursor = 0;
                    if (taskId == -1) {
                        taskId = Bukkit.getScheduler().runTaskTimer(
                                plugin, this::tick, intervalTicks, intervalTicks).getTaskId();
                    }
                });
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not schedule resident behavior startup.", schedulingError);
                loading.set(false);
            }
        });
    }

    private void tick() {
        if (!Bukkit.isPrimaryThread() || residents.isEmpty()) return;
        World clockWorld = Bukkit.getWorlds().stream().findFirst().orElse(null);
        if (clockWorld == null) return;

        long dayNumber = Math.floorDiv(clockWorld.getFullTime(), 24000L);
        DayOfWeek day = DayOfWeek.of((int) Math.floorMod(dayNumber, 7L) + 1);
        int minute = (int) (clockWorld.getTime() * 1440L / 24000L);

        int processed = 0;
        while (processed < batchSize && processed < residents.size()) {
            if (cursor >= residents.size()) cursor = 0;
            NpcRecord resident = residents.get(cursor++);
            processed++;
            ResidentBehaviorData data = cache.get(resident.id());
            if (data == null) continue;

            var activity = planner.findCurrent(data.schedule(), day, minute);
            if (activity.isEmpty()) continue;
            ResidentDestination destination = data.destinations().stream()
                    .filter(point -> point.type() == activity.get().destinationType())
                    .findFirst().orElse(null);
            if (destination == null) continue;

            World targetWorld = Bukkit.getWorld(destination.worldId());
            if (targetWorld == null || !targetWorld.isChunkLoaded(
                    ((int) Math.floor(destination.x())) >> 4,
                    ((int) Math.floor(destination.z())) >> 4)) continue;

            Entity entity = entities.find(resident.id()).orElse(null);
            if (entity == null || !entity.isValid()) continue;
            Location target = new Location(targetWorld, destination.x(), destination.y(),
                    destination.z(), destination.yaw(), destination.pitch());
            movement.moveTowards(entity, target);
        }
    }

    /** Reloads the cache after schedule or destination edits without querying per NPC. */
    public void reload() {
        if (!plugin.isEnabled()) return;
        repository.findAllBehaviorData().whenComplete((snapshots, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not refresh resident behavior cache.", error);
                    return;
                }
                cache.clear();
                snapshots.forEach(snapshot -> cache.put(snapshot.npcId(), snapshot));
            });
        });
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        cache.clear();
        residents = List.of();
        cursor = 0;
        loading.set(false);
    }

    public int cachedResidentCount() {
        return cache.size();
    }
}
