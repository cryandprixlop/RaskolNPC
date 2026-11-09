package ru.raskolnpc;

import org.bukkit.plugin.java.JavaPlugin;
import ru.raskolnpc.core.config.ConfigManager;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.lifecycle.NpcLifecycleService;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.SqliteNpcRepository;

import java.util.logging.Level;

public final class RaskolNPC extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private NpcRegistry npcRegistry;
    private NpcLifecycleService npcLifecycleService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.databaseManager = new DatabaseManager(
                this,
                configManager.getDatabaseFileName(),
                configManager.getDatabaseWorkerCount()
        );

        this.npcRegistry = new NpcRegistry();
        this.npcLifecycleService = new NpcLifecycleService(
                new SqliteNpcRepository(databaseManager),
                npcRegistry
        );

        databaseManager.initialize()
                .thenCompose(ignored -> npcLifecycleService.loadAll())
                .whenComplete((records, error) -> getServer().getScheduler().runTask(this, () -> {
                    if (!isEnabled()) {
                        return;
                    }

                    if (error != null) {
                        getLogger().log(Level.SEVERE,
                                "RaskolNPC could not initialize persistent NPC data.", error);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }

                    getLogger().info("RaskolNPC database is ready. Loaded "
                            + records.size() + " NPC record(s) into the runtime registry.");
                    getLogger().info("RaskolNPC is enabled.");
                }));

        getLogger().info("RaskolNPC is starting.");
    }

    public NpcLifecycleService getNpcLifecycleService() {
        return npcLifecycleService;
    }

    public NpcRegistry getNpcRegistry() {
        return npcRegistry;
    }

    @Override
    public void onDisable() {
        if (npcLifecycleService != null) {
            npcLifecycleService.clearRuntimeRegistry();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("RaskolNPC has been disabled.");
    }
}
