package ru.raskolnpc;

import org.bukkit.plugin.java.JavaPlugin;
import ru.raskolnpc.core.config.ConfigManager;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.entity.NpcEntityListener;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.entity.NpcEntityTagger;
import ru.raskolnpc.npc.entity.PaperNpcEntityProvider;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcRepository;
import ru.raskolnpc.storage.SqliteNpcRepository;

import java.util.logging.Level;

public final class RaskolNPC extends JavaPlugin {
    private DatabaseManager databaseManager;
    private NpcRegistry npcRegistry;
    private NpcLifecycleService npcLifecycleService;
    private NpcEntityManager npcEntityManager;

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
        npcLifecycleService = new NpcLifecycleService(repository, npcRegistry);

        NpcEntityTagger tagger = new NpcEntityTagger(this);
        npcEntityManager = new NpcEntityManager(this,
                new PaperNpcEntityProvider(this, tagger),
                tagger, repository, npcRegistry);
        getServer().getPluginManager().registerEvents(
                new NpcEntityListener(npcEntityManager), this);

        databaseManager.initialize()
                .thenCompose(ignored -> npcLifecycleService.loadAll())
                .whenComplete((records, error) -> getServer().getScheduler().runTask(this, () -> {
                    if (!isEnabled()) return;
                    if (error != null) {
                        getLogger().log(Level.SEVERE,
                                "RaskolNPC could not initialize persistent NPC data.", error);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    npcEntityManager.trackExistingLoadedWorlds();
                    getLogger().info("RaskolNPC is enabled. Loaded " + records.size()
                            + " NPC record(s); tracked physical entities in loaded chunks.");
                }));

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
