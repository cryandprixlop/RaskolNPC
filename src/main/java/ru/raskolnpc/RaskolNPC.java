package ru.raskolnpc;

import org.bukkit.plugin.java.JavaPlugin;
import ru.raskolnpc.core.config.ConfigManager;
import ru.raskolnpc.core.database.DatabaseManager;

import java.util.logging.Level;

public final class RaskolNPC extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;

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

        databaseManager.initialize().whenComplete((ignored, error) -> {
            if (!isEnabled()) {
                return;
            }

            getServer().getScheduler().runTask(this, () -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE,
                            "Database initialization failed. Persistence-dependent features will remain unavailable.",
                            error);
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }

                getLogger().info("RaskolNPC database is ready.");
            });
        });

        getLogger().info("RaskolNPC is starting.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("RaskolNPC has been disabled.");
    }
}
