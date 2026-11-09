package ru.raskolnpc.command;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

public final class NpcCommandMessages {
    private final YamlConfiguration messages;

    public NpcCommandMessages(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String key, String fallback) {
        String message = messages.getString(key, fallback);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    public void sendRaw(CommandSender sender, String message) {
        String prefix = messages.getString("prefix", "");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + " " + message));
    }
}
