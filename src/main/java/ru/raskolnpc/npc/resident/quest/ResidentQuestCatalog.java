package ru.raskolnpc.npc.resident.quest;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.raskolnpc.npc.resident.model.ResidentProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Read-only in-memory catalog; YAML is parsed once during plugin startup/reload. */
public final class ResidentQuestCatalog {
    private final Map<String, ResidentQuest> quests;

    public ResidentQuestCatalog(Map<String, ResidentQuest> quests) {
        Objects.requireNonNull(quests, "quests");
        Map<String, ResidentQuest> copy = new HashMap<>();
        quests.forEach((key, quest) -> {
            Objects.requireNonNull(quest, "quest");
            String normalized = normalizeId(key);
            if (!normalized.equals(quest.id())) {
                throw new IllegalArgumentException("Catalog key must match quest id: " + key);
            }
            if (copy.putIfAbsent(normalized, quest) != null) {
                throw new IllegalArgumentException("Duplicate resident quest id: " + normalized);
            }
        });
        this.quests = Map.copyOf(copy);
    }

    public static ResidentQuestCatalog from(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml, "yaml");
        ConfigurationSection section = yaml.getConfigurationSection("quests");
        if (section == null) return new ResidentQuestCatalog(Map.of());

        Map<String, ResidentQuest> loaded = new HashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection questSection = section.getConfigurationSection(key);
            if (questSection == null) continue;
            try {
                String professionKey = questSection.getString("profession", "");
                ResidentProfile.ResidentProfession profession =
                        ResidentProfile.ResidentProfession.valueOf(
                                professionKey.trim().toUpperCase(Locale.ROOT));
                ResidentQuest quest = new ResidentQuest(
                        key,
                        profession,
                        questSection.getString("required-material", ""),
                        questSection.getInt("required-amount", 0),
                        questSection.getInt("reputation-reward", 0)
                );
                if (loaded.putIfAbsent(quest.id(), quest) != null) {
                    throw new IllegalArgumentException("Duplicate quest id: " + quest.id());
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Invalid resident quest '" + key + "': " + exception.getMessage(), exception);
            }
        }
        return new ResidentQuestCatalog(loaded);
    }

    public Optional<ResidentQuest> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(quests.get(normalizeId(id)));
    }

    public List<ResidentQuest> forProfession(ResidentProfile.ResidentProfession profession) {
        Objects.requireNonNull(profession, "profession");
        List<ResidentQuest> result = new ArrayList<>();
        for (ResidentQuest quest : quests.values()) {
            if (quest.profession() == profession) result.add(quest);
        }
        result.sort(java.util.Comparator.comparing(ResidentQuest::id));
        return List.copyOf(result);
    }

    public List<ResidentQuest> all() {
        return quests.values().stream()
                .sorted(java.util.Comparator.comparing(ResidentQuest::id))
                .toList();
    }

    private static String normalizeId(String id) {
        return Objects.requireNonNull(id, "id").trim().toLowerCase(Locale.ROOT);
    }
}
