package ru.raskolnpc;

import org.bukkit.plugin.java.JavaPlugin;
import ru.raskolnpc.command.NpcCommand;
import ru.raskolnpc.command.NpcCommandMessages;
import ru.raskolnpc.command.NpcCommandService;
import ru.raskolnpc.guard.GuardRentService;
import ru.raskolnpc.core.config.ConfigManager;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.integration.towny.KingdomGuardService;
import ru.raskolnpc.integration.towny.TownyIntegration;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
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
import ru.raskolnpc.storage.GuardRentPaymentRepository;
import ru.raskolnpc.storage.GuardHiringPaymentRepository;
import ru.raskolnpc.storage.NpcDeletionRepository;
import ru.raskolnpc.storage.NpcRepository;
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
        npcRecoveryService = new NpcRecoveryService(
                this, npcRegistry, npcEntityManager, deletionRepository);
        getServer().getPluginManager().registerEvents(
                new NpcEntityListener(npcEntityManager, npcRecoveryService), this);

        TownyIntegration townyIntegration = new TownyIntegration(this);
        boolean townyEnabled = townyIntegration.isAvailable()
                && getConfig().getBoolean("integrations.towny.enabled", true);
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
        NpcCommand commandHandler = new NpcCommand(
                this, commandService, new NpcCommandMessages(this));
        Objects.requireNonNull(getCommand("raskolnpc"),
                "raskolnpc command is missing from plugin.yml")
                .setExecutor(commandHandler);
        getCommand("raskolnpc").setTabCompleter(commandHandler);

        databaseManager.initialize()
                .thenCompose(ignored -> npcLifecycleService.loadAll())
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

    /** Returns Towny-backed guard authorization when the integration is enabled. */
    public TownyKingdomGuardAccess getTownyKingdomGuardAccess() {
        return townyKingdomGuardAccess;
    }

    @Override
    public void onDisable() {
        if (guardRentService != null) guardRentService.stop();
        if (npcEntityManager != null && org.bukkit.Bukkit.isPrimaryThread()) {
            npcEntityManager.clear();
        }
        if (npcLifecycleService != null) npcLifecycleService.clearRuntimeRegistry();
        if (databaseManager != null) databaseManager.close();
        getLogger().info("RaskolNPC has been disabled.");
    }
}
