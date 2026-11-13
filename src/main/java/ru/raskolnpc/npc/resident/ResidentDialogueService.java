package ru.raskolnpc.npc.resident;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.resident.model.ResidentProfile;

import java.io.File;
import java.util.List;
import java.util.Objects;

/** Formats and sends short, profile-driven resident conversations. */
public final class ResidentDialogueService {
    private static final List<String> FALLBACK_GREETINGS = List.of(
            "Здравствуй, путник.", "Добрый день. Рад встрече.",
            "Приветствую тебя в нашем городе.");

    private final ResidentDialogueCatalog catalog;

    public ResidentDialogueService(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        File file = new File(plugin.getDataFolder(), "resident-dialogues.yml");
        if (!file.exists()) plugin.saveResource("resident-dialogues.yml", false);
        this.catalog = ResidentDialogueCatalog.from(YamlConfiguration.loadConfiguration(file));
    }

    public List<String> createLines(ResidentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<String> selected = catalog.linesFor(profile);
        if (selected.isEmpty()) selected = FALLBACK_GREETINGS;
        return selected.stream()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .toList();
    }

    public void speak(org.bukkit.entity.Player player, ResidentProfile profile, String residentName) {
        String name = residentName == null || residentName.isBlank() ? "Житель" : residentName;
        for (String line : createLines(profile)) {
            player.sendMessage(ChatColor.GRAY + "[" + ChatColor.GOLD + name
                    + ChatColor.GRAY + "] " + line);
        }
    }
}
