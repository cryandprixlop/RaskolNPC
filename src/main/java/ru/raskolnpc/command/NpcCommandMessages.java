package ru.raskolnpc.command;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

public final class NpcCommandMessages {
    private final Plugin plugin;

    public NpcCommandMessages(Plugin plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSender sender, String key, String fallback) {
        String message = plugin.getConfig().getString("messages." + key, fallback);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    public void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
