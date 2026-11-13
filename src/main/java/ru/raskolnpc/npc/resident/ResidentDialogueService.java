package ru.raskolnpc.npc.resident;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.resident.model.ResidentProfile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/** Formats and sends short, profile-driven resident conversations. */
public final class ResidentDialogueService {
    private static final List<String> FALLBACK_GREETINGS = List.of(
            "Здравствуй, путник.", "Добрый день. Рад встрече.",
            "Приветствую тебя в нашем городе.");

    private final Plugin plugin;
    private final File file;
    private volatile ResidentDialogueCatalog catalog =
            new ResidentDialogueCatalog(List.of(), java.util.Map.of(), java.util.Map.of());

    public ResidentDialogueService(Plugin plugin) {
        this(plugin, prepareFile(plugin));
    }

    ResidentDialogueService(Plugin plugin, File file) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = Objects.requireNonNull(file, "file");
        try {
            reload();
        } catch (IOException | InvalidConfigurationException exception) {
            this.plugin.getLogger().log(Level.WARNING,
                    "Could not load resident-dialogues.yml; fallback dialogue will be used.",
                    exception);
        }
    }

    private static File prepareFile(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + dataFolder);
        }
        File file = new File(dataFolder, "resident-dialogues.yml");
        if (!file.exists()) plugin.saveResource("resident-dialogues.yml", false);
        return file;
    }

    /**
     * Loads and validates the complete YAML file before replacing the active catalog.
     * If parsing fails, the previously active catalog remains untouched.
     */
    public synchronized void reload() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        ResidentDialogueCatalog loaded = ResidentDialogueCatalog.from(yaml);
        catalog = loaded;
    }

    public List<String> createLines(ResidentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        ResidentDialogueCatalog snapshot = catalog;
        List<String> selected = snapshot.linesFor(profile);
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
