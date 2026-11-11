package ru.raskolnpc;

import org.bukkit.plugin.java.JavaPlugin;
import ru.raskolnpc.command.NpcCommand;
import ru.raskolnpc.command.NpcCommandMessages;
import ru.raskolnpc.command.NpcCommandService;
import ru.raskolnpc.core.config.ConfigManager;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.integration.towny.TownyIntegration;
import ru.raskolnpc.integration.towny.TownyKingdomGuardAccess;
import ru.raskolnpc.integration.towny.TownyNpcLimitService;
import ru.raskolnpc.integration.towny.TownyResidentService;
import ru.raskolnpc.npc.entity.NpcEntityListener;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.entity.NpcEntityTagger;
import ru.raskolnpc.npc.entity.PaperNpcEntityProvider;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.lifecycle.NpcRecoveryService;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcDeletionRepository;
import ru.raskolnpc.storage.NpcRepository;
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
        NpcCommandService commandService = new NpcCommandService(
                this, npcLifecycleService, npcRegistry, npcEntityManager,
                townyResidents, new TownyNpcLimitService(), residentsPerTown);
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
                    getServer().getScheduler().runTask(this, () -> {
                        if (!isEnabled()) return;
                        if (error != null) {
                            getLogger().log(Level.SEVERE,
                                    "RaskolNPC could not initialize persistent NPC data.", error);
                            getServer().getPluginManager().disablePlugin(this);
                            return;
                        }
                        npcRecoveryService.recover().whenComplete((ignored, recoveryError) -> {
                            if (!isEnabled()) return;
                            getServer().getScheduler().runTask(this, () -> {
                                if (!isEnabled()) return;
                                if (recoveryError != null) {
                                    getLogger().log(Level.SEVERE,
                                            "RaskolNPC could not recover persistent NPC entities.",
                                            recoveryError);
                                    getServer().getPluginManager().disablePlugin(this);
                                    return;
                                }
                                npcLifecycleService.markReady();
                                getLogger().info("RaskolNPC is enabled. Loaded "
                                        + records.size() + " NPC record(s); recovery completed.");
                            });
                        });
                    });
                });

        getLogger().info("RaskolNPC is starting.");
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
        if (npcEntityManager != null && org.bukkit.Bukkit.isPrimaryThread()) {
            npcEntityManager.clear();
        }
        if (npcLifecycleService != null) npcLifecycleService.clearRuntimeRegistry();
        if (databaseManager != null) databaseManager.close();
        getLogger().info("RaskolNPC has been disabled.");
    }
}
