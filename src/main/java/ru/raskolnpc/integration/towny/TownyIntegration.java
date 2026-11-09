package ru.raskolnpc.integration.towny;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class TownyIntegration {
    private final Plugin townyPlugin;
    private final TownyResidentService residentService;

    public TownyIntegration(JavaPlugin plugin) {
        PluginManager manager = plugin.getServer().getPluginManager();
        this.townyPlugin = manager.getPlugin("Towny");
        if (townyPlugin != null && townyPlugin.isEnabled()) {
            this.residentService = new TownyResidentService();
            plugin.getLogger().info("Towny integration enabled ("
                    + townyPlugin.getDescription().getVersion() + ").");
        } else {
            this.residentService = null;
            plugin.getLogger().info("Towny is not installed; RaskolNPC will run in standalone mode.");
        }
    }

    public boolean isAvailable() {
        return townyPlugin != null && townyPlugin.isEnabled() && residentService != null;
    }

    public TownyResidentService residents() {
        if (!isAvailable()) {
            throw new TownyIntegrationException("Towny integration is not available.");
        }
        return residentService;
    }
}
