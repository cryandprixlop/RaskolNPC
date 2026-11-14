package ru.raskolnpc.npc.resident.quest;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.ResidentProfileService;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.storage.ResidentQuestRepository;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Coordinates resident quest offers and persisted quest acceptance. */
public final class ResidentQuestService {
    public enum AcceptResult {
        ACCEPTED,
        ALREADY_ACTIVE,
        RESIDENT_NOT_FOUND,
        QUEST_NOT_FOUND,
        WRONG_PROFESSION,
        PROFILE_UNAVAILABLE
    }

    private final Plugin plugin;
    private final ResidentProfileService profiles;
    private final NpcRegistry registry;
    private final ResidentQuestRepository repository;
    private final File catalogFile;
    private volatile ResidentQuestCatalog catalog = new ResidentQuestCatalog(java.util.Map.of());

    public ResidentQuestService(Plugin plugin, ResidentProfileService profiles,
            NpcRegistry registry, ResidentQuestRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data directory.");
        }
        this.catalogFile = new File(folder, "resident-quests.yml");
        if (!catalogFile.exists()) plugin.saveResource("resident-quests.yml", false);
        try {
            reload();
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not load resident-quests.yml.", exception);
        }
    }

    /** Reloads and validates the full catalog before publishing it. */
    public synchronized void reload() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(catalogFile);
        ResidentQuestCatalog loaded = ResidentQuestCatalog.from(yaml);
        catalog = loaded;
    }

    public List<ResidentQuest> availableFor(ResidentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return catalog.forProfession(profile.profession());
    }

    public CompletableFuture<AcceptResult> accept(Player player, NpcId residentId, String questId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(residentId, "residentId");
        if (registry.get(residentId).filter(record -> record.type() == NpcType.RESIDENT).isEmpty()) {
            return CompletableFuture.completedFuture(AcceptResult.RESIDENT_NOT_FOUND);
        }
        ResidentQuest quest = catalog.find(questId).orElse(null);
        if (quest == null) return CompletableFuture.completedFuture(AcceptResult.QUEST_NOT_FOUND);

        return profiles.find(residentId).thenCompose(profile -> {
            if (profile.isEmpty()) {
                return CompletableFuture.completedFuture(AcceptResult.PROFILE_UNAVAILABLE);
            }
            if (profile.get().profession() != quest.profession()) {
                return CompletableFuture.completedFuture(AcceptResult.WRONG_PROFESSION);
            }
            return repository.accept(player.getUniqueId(), residentId, quest.id())
                    .thenApply(accepted -> accepted
                            ? AcceptResult.ACCEPTED : AcceptResult.ALREADY_ACTIVE);
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING,
                    "Could not accept resident quest " + questId + " for " + player.getUniqueId(),
                    error);
            throw new java.util.concurrent.CompletionException(error);
        });
    }
}
