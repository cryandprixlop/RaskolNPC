package ru.raskolnpc.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public final class ConfigManager {

    private final JavaPlugin plugin;
    private FileConfiguration databaseConfig;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        saveResourceIfMissing("database.yml");
        saveResourceIfMissing("messages.yml");

        File databaseFile = new File(plugin.getDataFolder(), "database.yml");
        this.databaseConfig = YamlConfiguration.loadConfiguration(databaseFile);

        validateDatabaseConfig();
    }

    public String getDatabaseFileName() {
        return databaseConfig.getString("sqlite.file", "raskolnpc.db");
    }

    public int getDatabaseWorkerCount() {
        return Math.max(1, Math.min(4,
                databaseConfig.getInt("async.worker-threads", 2)));
    }

    private void validateDatabaseConfig() {
        String fileName = getDatabaseFileName();
        if (fileName == null || fileName.isBlank()
                || new File(fileName).isAbsolute()
                || fileName.contains("..")) {
            throw new IllegalArgumentException(
                    "database.yml: sqlite.file must be a safe relative file name.");
        }
    }

    private void saveResourceIfMissing(String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (file.exists()) {
            return;
        }

        try {
            plugin.saveResource(resourceName, false);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Missing bundled resource: " + resourceName, exception);
            throw exception;
        }
    }
}
