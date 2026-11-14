package ru.raskolnpc;

import org.bukkit.plugin.java.JavaPlugin;
import ru.raskolnpc.command.NpcCommand;
import ru.raskolnpc.command.NpcCommandMessages;
import ru.raskolnpc.command.NpcCommandService;
import ru.raskolnpc.guard.GuardRentService;
import ru.raskolnpc.guard.GuardCombatController;
import ru.raskolnpc.guard.GuardOrderService;
import ru.raskolnpc.guard.GuardOrderController;
import ru.raskolnpc.guard.GuardSquadService;
import ru.raskolnpc.guard.KingdomGuardRosterService;
import ru.raskolnpc.core.config.ConfigManager;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.integration.IntegrationCapabilities;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.integration.PlayerSubjectResolver;
import ru.raskolnpc.integration.towny.TownyPlayerSubjectResolver;
import ru.raskolnpc.integration.towny.TownyGuardTerritoryAccess;
import ru.raskolnpc.integration.CompositeDiplomacy;
import ru.raskolnpc.integration.towny.TownyDiplomacyAdapter;
import ru.raskolnpc.integration.siegewar.SiegeWarDiplomacyAdapter;
import ru.raskolnpc.integration.worldguard.WorldGuardProtectionAdapter;
import ru.raskolnpc.integration.towny.KingdomGuardService;
import ru.raskolnpc.integration.towny.TownyIntegration;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
import ru.raskolnpc.integration.towny.TownyKingdomGeneralService;
import ru.raskolnpc.integration.towny.TownyNpcLimitService;
import ru.raskolnpc.integration.towny.TownyResidentService;
import ru.raskolnpc.integration.towny.TownyTownBankEconomy;
import ru.raskolnpc.npc.entity.NpcEntityListener;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.entity.NpcEntityTagger;
import ru.raskolnpc.npc.entity.PaperNpcEntityProvider;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.lifecycle.NpcRecoveryService;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.ResidentProfileService;
import ru.raskolnpc.npc.resident.ResidentBehaviorService;
import ru.raskolnpc.npc.resident.ResidentDialogueService;
import ru.raskolnpc.npc.resident.ResidentInteractionListener;
import ru.raskolnpc.npc.resident.quest.ResidentQuestService;
import ru.raskolnpc.storage.ResidentQuestRepository;
import ru.raskolnpc.storage.SqliteResidentQuestRepository;
import ru.raskolnpc.npc.resident.ResidentScheduleCommandService;
import ru.raskolnpc.storage.GuardRentPaymentRepository;
import ru.raskolnpc.storage.GuardOrderRepository;
import ru.raskolnpc.storage.GuardSquadRepository;
import ru.raskolnpc.storage.SqliteGuardSquadRepository;
import ru.raskolnpc.storage.SqliteGuardOrderRepository;
import ru.raskolnpc.storage.GuardHiringPaymentRepository;
import ru.raskolnpc.storage.NpcDeletionRepository;
import ru.raskolnpc.storage.NpcRepository;
import ru.raskolnpc.storage.ResidentProfileRepository;
import ru.raskolnpc.storage.ResidentScheduleRepository;
import ru.raskolnpc.storage.SqliteResidentProfileRepository;
import ru.raskolnpc.storage.SqliteResidentScheduleRepository;
import ru.raskolnpc.storage.SqliteGuardRentPaymentRepository;
import ru.raskolnpc.storage.SqliteGuardHiringPaymentRepository;
import ru.raskolnpc.storage.SqliteNpcDeletionRepository;
import ru.raskolnpc.storage.SqliteNpcRepository;

import java.util.Objects;
import java.util.logging.Level;

public final class RaskolNPC extends JavaPlugin {
    private DatabaseManager databaseManager;
    private NpcRegistry npcRegistry;
    private NpcLifecycleService npcLifecycleService;
    private NpcEntityManager npcEntityManager;
    private NpcRecoveryService npcRecoveryService;
    private TownyKingdomGuardAccess townyKingdomGuardAccess;
    private GuardRentService guardRentService;
    private GuardCombatController guardCombatController;
    private GuardOrderController guardOrderController;
    private ResidentProfileService residentProfileService;
    private ResidentBehaviorService residentBehaviorService;
    private RelationshipPolicy relationshipPolicy;
    private WorldGuardProtectionAdapter worldGuardProtectionAdapter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigManager configManager = new ConfigManager(this);
        configManager.load();

        databaseManager = new DatabaseManager(this,
                configManager.getDatabaseFileName(),
                configManager.getDatabaseWorkerCount());

        npcRegistry = new NpcRegistry();
        NpcRepository repository = new SqliteNpcRepository(databaseManager);
        ResidentProfileRepository residentProfiles =
                new SqliteResidentProfileRepository(databaseManager);
        residentProfileService = new ResidentProfileService(residentProfiles);
        NpcDeletionRepository deletionRepository =
                new SqliteNpcDeletionRepository(databaseManager);
        GuardRentPaymentRepository rentPayments =
                new SqliteGuardRentPaymentRepository(databaseManager);
        GuardHiringPaymentRepository hiringPayments =
                new SqliteGuardHiringPaymentRepository(databaseManager);

        int maximumNpcs = Math.max(1, getConfig().getInt("limits.total-npcs", 150));
        npcLifecycleService = new NpcLifecycleService(
                repository, npcRegistry, maximumNpcs, deletionRepository);

        NpcEntityTagger tagger = new NpcEntityTagger(this);
        npcEntityManager = new NpcEntityManager(this,
                new PaperNpcEntityProvider(this, tagger),
                tagger, repository, npcRegistry);
        GuardOrderRepository guardOrders = new SqliteGuardOrderRepository(databaseManager);
        GuardOrderService guardOrderService = new GuardOrderService(npcRegistry, guardOrders);
        GuardSquadRepository guardSquads = new SqliteGuardSquadRepository(databaseManager);
        GuardSquadService guardSquadService = new GuardSquadService(npcRegistry, guardSquads,
                Math.max(2, Math.min(10, getConfig().getInt("guards.squad-max-members", 10))));
        guardOrderController = new GuardOrderController(this, npcRegistry, npcEntityManager,
                guardOrderService,
                Math.max(1, getConfig().getInt("guards.order-batch-size", 6)),
                Math.min(1.5, Math.max(0.1,
                        getConfig().getDouble("guards.movement-step", 0.6))));
        npcRecoveryService = new NpcRecoveryService(
                this, npcRegistry, npcEntityManager, deletionRepository);
        getServer().getPluginManager().registerEvents(
                new NpcEntityListener(npcEntityManager, npcRecoveryService), this);

        ResidentDialogueService residentDialogues = new ResidentDialogueService(this);
        ResidentQuestRepository residentQuestRepository =
                new SqliteResidentQuestRepository(databaseManager);
        ResidentQuestService residentQuests = new ResidentQuestService(
                this, residentProfileService, npcRegistry, residentQuestRepository);
        getServer().getPluginManager().registerEvents(
                new ResidentInteractionListener(this, npcEntityManager, npcRegistry,
                        residentProfileService, residentDialogues, residentQuests), this);

        ResidentScheduleRepository residentSchedules =
                new SqliteResidentScheduleRepository(databaseManager);
        residentBehaviorService = new ResidentBehaviorService(this, npcRegistry,
                npcEntityManager, residentSchedules, residentProfiles,
                Math.max(1, getConfig().getInt("resident-behavior.batch-size", 8)),
                Math.max(1L, getConfig().getLong("resident-behavior.interval-ticks", 10L)),
                getConfig().getDouble("resident-behavior.movement-step", 0.8));
        residentBehaviorService.setBusinessStockRepository(residentQuestRepository);
        residentBehaviorService.setDailyStockConsumptionPercent(Math.max(0, Math.min(100,
                getConfig().getInt("resident-business-stock.daily-consumption-percent", 10))));

        TownyIntegration townyIntegration = new TownyIntegration(this);
        boolean townyEnabled = townyIntegration.isAvailable()
                && getConfig().getBoolean("integrations.towny.enabled", true);
        boolean siegeWarEnabled = getServer().getPluginManager().getPlugin("SiegeWar") != null
                && getServer().getPluginManager().getPlugin("SiegeWar").isEnabled();
        org.bukkit.plugin.Plugin worldGuardPlugin =
                getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardProtectionAdapter = null;
        if (worldGuardPlugin != null && worldGuardPlugin.isEnabled()) {
            try {
                worldGuardProtectionAdapter = new WorldGuardProtectionAdapter();
            } catch (RuntimeException adapterError) {
                getLogger().log(Level.WARNING,
                        "WorldGuard was detected, but its region query adapter could not initialize. "
                                + "NPC combat must fail closed until the adapter is available.",
                        adapterError);
            }
        }
        java.util.List<RelationshipPolicy.Diplomacy> diplomacyProviders = new java.util.ArrayList<>();
        if (townyEnabled) diplomacyProviders.add(new TownyDiplomacyAdapter());
        if (siegeWarEnabled) diplomacyProviders.add(new SiegeWarDiplomacyAdapter());
        relationshipPolicy = new RelationshipPolicy(new CompositeDiplomacy(diplomacyProviders));
        PlayerSubjectResolver playerSubjects = townyEnabled
                ? new TownyPlayerSubjectResolver() : PlayerSubjectResolver.none();
        TownyGuardTerritoryAccess territoryAccess = townyEnabled
                ? new TownyGuardTerritoryAccess() : null;
        if (territoryAccess != null) {
            guardOrderController.setTerritoryAccess(territoryAccess::apply);
        }
        guardOrderController.setPursuitPolicy(relationshipPolicy,
                Math.max(0.0, Math.min(32.0,
                        getConfig().getDouble("guards.combat.max-pursuit-distance", 16.0))),
                playerSubjects);
        guardCombatController = new GuardCombatController(
                this, npcRegistry, npcEntityManager, relationshipPolicy,
                worldGuardProtectionAdapter,
                Math.max(1, getConfig().getInt("guards.combat.batch-size", 4)),
                getConfig().getDouble("guards.combat.detection-radius", 12.0),
                getConfig().getDouble("guards.combat.damage", 2.0),
                getConfig().getLong("guards.combat.attack-cooldown-ms", 1000L),
                playerSubjects);
        if (territoryAccess != null) {
            guardCombatController.setTerritoryAccess(territoryAccess::apply);
        }
        townyKingdomGuardAccess = townyEnabled ? new TownyKingdomGuardAccess() : null;
        TownyResidentService townyResidents = townyEnabled
                ? townyIntegration.residents() : null;
        int residentsPerTown = Math.max(1,
                getConfig().getInt("limits.residents-per-town", 15));
        int guardsPerNation = Math.max(1,
                getConfig().getInt("limits.guards-per-nation", 30));
        NpcCommandService commandService = new NpcCommandService(
                this, npcLifecycleService, npcRegistry, npcEntityManager,
                townyResidents, new TownyNpcLimitService(), residentsPerTown,
                townyKingdomGuardAccess,
                townyEnabled ? new TownyTownBankEconomy() : null,
                townyEnabled ? new KingdomGuardService() : null,
                guardsPerNation,
                getConfig().getDouble("guards.base-hire-cost", 100.0),
                getConfig().getDouble("guards.base-daily-rent", 10.0));
        commandService.setGuardHiringPaymentRepository(hiringPayments);
        commandService.setResidentProfileService(residentProfileService);
        commandService.setResidentBehaviorService(residentBehaviorService);
        if (townyKingdomGuardAccess != null) {
            commandService.setGuardRosterService(
                    new KingdomGuardRosterService(npcRegistry, townyKingdomGuardAccess));
        }
        NpcCommand commandHandler = new NpcCommand(
                this, commandService, new NpcCommandMessages(this));
        commandHandler.setIntegrationCapabilities(new IntegrationCapabilities(
                getServer().getPluginManager(), townyEnabled,
                worldGuardProtectionAdapter != null));
        commandHandler.setGuardOrderService(guardOrderService);
        commandHandler.setGuardSquadService(guardSquadService);
        Objects.requireNonNull(getCommand("raskolnpc"),
                "raskolnpc command is missing from plugin.yml")
                .setExecutor(commandHandler);
        getCommand("raskolnpc").setTabCompleter(commandHandler);
        commandHandler.setResidentScheduleService(new ResidentScheduleCommandService(
                commandService, residentSchedules, residentBehaviorService));
        commandHandler.setResidentDialogueService(residentDialogues);
        commandHandler.setResidentQuestService(residentQuests);
        if (townyEnabled) {
            commandHandler.setKingdomGeneralService(new TownyKingdomGeneralService(
                    getConfig().getString("guards.towny-general-rank", "general")));
        }

        databaseManager.initialize()
                .thenCompose(ignored -> residentQuestRepository.recoverUnfinishedSubmissions())
                .thenCompose(reviewCount -> {
                    if (reviewCount > 0) {
                        getLogger().warning("Resident quest submissions requiring review: " + reviewCount);
                    }
                    return npcLifecycleService.loadAll();
                })
                .thenCompose(records -> residentProfileService.ensureProfiles(records)
                        .thenApply(ignored -> records))
                .whenComplete((records, error) -> {
                    if (!isEnabled()) return;
                    scheduleStartupStep(() -> {
                        if (!isEnabled()) return;
                        if (error != null) {
                            disableAfterStartupFailure(
                                    "RaskolNPC could not initialize persistent NPC data.", error);
                            return;
                        }

                        if (townyEnabled) {
                            guardRentService = new GuardRentService(this, npcRegistry,
                                    npcLifecycleService, npcEntityManager,
                                    new TownyTownBankEconomy(), rentPayments,
                                    getConfig().getInt("guards.maximum-debt-days", 3));
                            commandHandler.setGuardRentService(guardRentService);
                        }

                        java.util.concurrent.CompletableFuture<Void> rentRecovery =
                                guardRentService == null
                                        ? java.util.concurrent.CompletableFuture.completedFuture(null)
                                        : guardRentService.recoverPendingPayments();
                        java.util.concurrent.CompletableFuture<Void> hiringRecovery = rentRecovery
                                .thenCompose(ignored -> hiringPayments.moveUnfinishedToReview(
                                        "Server restarted while guard hiring or refund result was not finalized."))
                                .thenApply(ignored -> null);

                        hiringRecovery.whenComplete((ignored, rentError) -> {
                            if (!isEnabled()) return;
                            scheduleStartupStep(() -> {
                                if (!isEnabled()) return;
                                if (rentError != null) {
                                    disableAfterStartupFailure(
                                            "RaskolNPC could not recover guard rent or hiring payments.",
                                            rentError);
                                    return;
                                }

                                npcRecoveryService.recover().whenComplete((recovered, recoveryError) -> {
                                    if (!isEnabled()) return;
                                    scheduleStartupStep(() -> {
                                        if (!isEnabled()) return;
                                        if (recoveryError != null) {
                                            disableAfterStartupFailure(
                                                    "RaskolNPC could not recover persistent NPC entities.",
                                                    recoveryError);
                                            return;
                                        }
                                        npcLifecycleService.markReady();
                                        residentBehaviorService.start();
                                        guardOrderController.start();
                                        guardCombatController.start();
                                        if (guardRentService != null) {
                                            try {
                                                guardRentService.start();
                                            } catch (RuntimeException startError) {
                                                disableAfterStartupFailure(
                                                        "RaskolNPC could not start guard rent collection.",
                                                        startError);
                                                return;
                                            }
                                        }
                                        getLogger().info("RaskolNPC is enabled. Loaded "
                                                + records.size()
                                                + " NPC record(s); payment and entity recovery completed.");
                                    });
                                });
                            });
                        });
                    });
                });

        getLogger().info("RaskolNPC is starting.");
    }

    private void scheduleStartupStep(Runnable action) {
        if (!isEnabled()) return;
        try {
            getServer().getScheduler().runTask(this, action);
        } catch (RuntimeException schedulingError) {
            disableAfterStartupFailure(
                    "RaskolNPC could not schedule a startup continuation.", schedulingError);
        }
    }

    private void disableAfterStartupFailure(String message, Throwable error) {
        getLogger().log(Level.SEVERE, message, error);
        getServer().getPluginManager().disablePlugin(this);
    }

    public NpcLifecycleService getNpcLifecycleService() {
        return npcLifecycleService;
    }

    public NpcRegistry getNpcRegistry() {
        return npcRegistry;
    }

    public NpcEntityManager getNpcEntityManager() {
        return npcEntityManager;
    }

    /** Returns the WorldGuard mob-damage query adapter when WorldGuard is enabled. */
    public WorldGuardProtectionAdapter getWorldGuardProtectionAdapter() {
        return worldGuardProtectionAdapter;
    }

    /** Returns the provider-neutral relationship policy; unknown relations remain neutral. */
    public RelationshipPolicy getRelationshipPolicy() {
        return relationshipPolicy;
    }

    /** Returns Towny-backed guard authorization when the integration is enabled. */
    public TownyKingdomGuardAccess getTownyKingdomGuardAccess() {
        return townyKingdomGuardAccess;
    }

    @Override
    public void onDisable() {
        if (residentBehaviorService != null) residentBehaviorService.stop();
        if (guardOrderController != null) guardOrderController.stop();
        if (guardCombatController != null) guardCombatController.stop();
        if (guardRentService != null) guardRentService.stop();
        if (npcEntityManager != null && org.bukkit.Bukkit.isPrimaryThread()) {
            npcEntityManager.clear();
        }
        if (npcLifecycleService != null) npcLifecycleService.clearRuntimeRegistry();
        if (databaseManager != null) databaseManager.close();
        getLogger().info("RaskolNPC has been disabled.");
    }
}
