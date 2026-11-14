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
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;
import ru.raskolnpc.storage.ResidentProfileRepository;
import ru.raskolnpc.storage.ResidentQuestRepository;
import ru.raskolnpc.storage.ResidentScheduleRepository;

import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Cached, centrally scheduled execution of resident weekly schedules. */
public final class ResidentBehaviorService {
    private record LoadedData(
            List<ResidentBehaviorData> behavior,
            List<ResidentProfile> profiles
    ) { }

    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final ResidentScheduleRepository repository;
    private final ResidentProfileRepository profileRepository;
    private volatile ResidentQuestRepository questRepository;
    private volatile int dailyStockConsumptionPercent = 10;
    private final ResidentSchedulePlanner planner = new ResidentSchedulePlanner();
    private final ResidentNeedsController needsController = new ResidentNeedsController();
    private final ResidentMovementController movement;
    private final ResidentWorkActionController workActions = new ResidentWorkActionController();
    private final int batchSize;
    private final long intervalTicks;
    private final Map<ru.raskolnpc.npc.model.NpcId, ResidentBehaviorData> cache = new HashMap<>();
    private final Map<ru.raskolnpc.npc.model.NpcId, ResidentProfile> profileCache = new HashMap<>();
    private final AtomicBoolean loading = new AtomicBoolean();
    private List<NpcRecord> residents = List.of();
    private int cursor;
    private int taskId = -1;
    private long behaviorTick;
    private long lastNeedsDay = Long.MIN_VALUE;
    private List<NpcRecord> needsQueue = List.of();
    private int needsCursor;

    public ResidentBehaviorService(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, ResidentScheduleRepository repository,
            ResidentProfileRepository profileRepository,
            int batchSize, long intervalTicks, double movementStep) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        if (batchSize < 1 || intervalTicks < 1) {
            throw new IllegalArgumentException("Batch size and interval must be positive.");
        }
        this.batchSize = batchSize;
        this.intervalTicks = intervalTicks;
        this.movement = new ResidentMovementController(movementStep);
    }

    public void setBusinessStockRepository(ResidentQuestRepository repository) {
        this.questRepository = Objects.requireNonNull(repository, "repository");
    }

    public void setDailyStockConsumptionPercent(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Daily stock consumption must be between 0 and 100.");
        }
        this.dailyStockConsumptionPercent = percentage;
    }

    /** Loads behavior data and profession profiles in bulk, then starts one shared timer. */
    public void start() {
        if (!loading.compareAndSet(false, true)) return;
        loadAllData().whenComplete((loaded, error) -> {
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
                    applyLoadedData(loaded);
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

    private java.util.concurrent.CompletableFuture<LoadedData> loadAllData() {
        return repository.findAllBehaviorData().thenCombine(
                profileRepository.findAll(), LoadedData::new);
    }

    private void applyLoadedData(LoadedData loaded) {
        cache.clear();
        loaded.behavior().forEach(snapshot -> cache.put(snapshot.npcId(), snapshot));
        profileCache.clear();
        loaded.profiles().forEach(profile -> profileCache.put(profile.npcId(), profile));
    }

    private void tick() {
        if (!Bukkit.isPrimaryThread() || residents.isEmpty()) return;
        behaviorTick++;
        World clockWorld = Bukkit.getWorlds().stream().findFirst().orElse(null);
        if (clockWorld == null) return;

        long dayNumber = Math.floorDiv(clockWorld.getFullTime(), 24000L);
        boolean firstNeedsCycle = lastNeedsDay == Long.MIN_VALUE;
        if (firstNeedsCycle || dayNumber != lastNeedsDay) {
            // Run the first daily needs cycle after startup as well as at each
            // subsequent in-game day boundary. Without this initialization,
            // residents would keep their initial needs unchanged for day one.
            lastNeedsDay = dayNumber;
            needsQueue = residents;
            needsCursor = 0;
            if (!firstNeedsCycle) {
                ResidentQuestRepository stocks = questRepository;
                int consumption = dailyStockConsumptionPercent;
                if (stocks != null && consumption > 0) {
                    stocks.consumeBusinessStockDaily(consumption).whenComplete((changed, error) -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Could not apply daily resident business stock consumption.", error);
                        } else if (changed > 0) {
                            plugin.getLogger().fine("Applied daily consumption to " + changed
                                    + " resident business stock row(s).");
                        }
                    });
                }
            }
        }
        DayOfWeek day = DayOfWeek.of((int) Math.floorMod(dayNumber, 7L) + 1);
        int minute = (int) (clockWorld.getTime() * 1440L / 24000L);

        int processed = 0;
        while (processed < batchSize && processed < residents.size()) {
            if (cursor >= residents.size()) cursor = 0;
            NpcRecord resident = residents.get(cursor++);
            processed++;
            ResidentBehaviorData data = cache.get(resident.id());
            if (data == null) continue;

            ResidentSchedule.Entry activeEntry = planner.findCurrent(
                    data.schedule(), day, minute).orElse(null);
            ResidentDestination.DestinationType destinationType =
                    activeEntry == null
                            ? ResidentDestination.DestinationType.HOME
                            : activeEntry.destinationType();
            ResidentDestination destination = data.destinations().stream()
                    .filter(point -> point.type() == destinationType)
                    .findFirst().orElse(null);
            if (destination == null && destinationType != ResidentDestination.DestinationType.HOME) {
                destination = data.destinations().stream()
                        .filter(point -> point.type() == ResidentDestination.DestinationType.HOME)
                        .findFirst().orElse(null);
            }
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

            if (activeEntry != null
                    && activeEntry.activity() == ResidentSchedule.Activity.WORK
                    && activeEntry.destinationType() == ResidentDestination.DestinationType.WORK
                    && entity.getWorld().equals(targetWorld)
                    && entity.getLocation().distanceSquared(target) <= 2.25) {
                ResidentProfile profile = profileCache.get(resident.id());
                if (profile != null) {
                    workActions.performIfReady(resident.id(), entity, profile, behaviorTick);
                }
            }
        }
        processNeedsBatch(day, minute);
    }

    /** Advances needs once per in-game day, processing only one configured batch per timer tick. */
    private void processNeedsBatch(DayOfWeek day, int minute) {
        int processed = 0;
        while (processed < batchSize && needsCursor < needsQueue.size()) {
            NpcRecord resident = needsQueue.get(needsCursor++);
            processed++;
            ResidentProfile current = profileCache.get(resident.id());
            if (current == null) continue;
            ResidentBehaviorData data = cache.get(resident.id());
            ResidentSchedule.Entry active = data == null ? null
                    : planner.findCurrent(data.schedule(), day, minute).orElse(null);
            ResidentSchedule.Activity activity = active == null
                    ? ResidentSchedule.Activity.REST : active.activity();
            ResidentProfile updated = needsController.advance(current, activity);
            profileCache.put(resident.id(), updated);
            profileRepository.save(updated).whenComplete((ignored, error) -> {
                if (error != null) plugin.getLogger().log(Level.WARNING,
                        "Could not persist daily needs for resident " + resident.id(), error);
            });
        }
        if (needsCursor >= needsQueue.size()) {
            needsQueue = List.of();
            needsCursor = 0;
        }
    }

    /** Reloads schedules, destinations and profession profiles as one bulk snapshot. */
    public void reload() {
        if (!plugin.isEnabled()) return;
        loadAllData().whenComplete((loaded, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not refresh resident behavior cache.", error);
                    return;
                }
                applyLoadedData(loaded);
            });
        });
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        cache.clear();
        profileCache.clear();
        workActions.clear();
        residents = List.of();
        cursor = 0;
        behaviorTick = 0;
        lastNeedsDay = Long.MIN_VALUE;
        needsQueue = List.of();
        needsCursor = 0;
        loading.set(false);
    }

    public int cachedResidentCount() {
        return cache.size();
    }
}
