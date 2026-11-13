package ru.raskolnpc.npc.resident;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Immutable dialogue text catalog loaded from resident-dialogues.yml. */
public final class ResidentDialogueCatalog {
    private final List<String> greetings;
    private final Map<ResidentProfession, List<String>> professions;
    private final Map<ResidentProfile.ResidentTrait, List<String>> traits;

    public ResidentDialogueCatalog(List<String> greetings,
            Map<ResidentProfession, List<String>> professions,
            Map<ResidentProfile.ResidentTrait, List<String>> traits) {
        this.greetings = immutableLines(greetings);
        this.professions = immutableMap(professions);
        this.traits = immutableMap(traits);
    }

    public static ResidentDialogueCatalog from(YamlConfiguration yaml) {
        List<String> greetings = yaml.getStringList("greetings");
        Map<ResidentProfession, List<String>> professions = new EnumMap<>(ResidentProfession.class);
        ConfigurationSection professionSection = yaml.getConfigurationSection("professions");
        if (professionSection != null) {
            for (String key : professionSection.getKeys(false)) {
                try {
                    professions.put(ResidentProfession.valueOf(key.toUpperCase(java.util.Locale.ROOT)),
                            professionSection.getStringList(key));
                } catch (IllegalArgumentException ignored) {
                    // Unknown profession keys are ignored for forward compatibility.
                }
            }
        }
        Map<ResidentProfile.ResidentTrait, List<String>> traits =
                new EnumMap<>(ResidentProfile.ResidentTrait.class);
        ConfigurationSection traitSection = yaml.getConfigurationSection("traits");
        if (traitSection != null) {
            for (String key : traitSection.getKeys(false)) {
                try {
                    traits.put(ResidentProfile.ResidentTrait.valueOf(key.toUpperCase(java.util.Locale.ROOT)),
                            traitSection.getStringList(key));
                } catch (IllegalArgumentException ignored) {
                    // Unknown trait keys are ignored for forward compatibility.
                }
            }
        }
        return new ResidentDialogueCatalog(greetings, professions, traits);
    }

    public List<String> linesFor(ResidentProfile profile) {
        List<String> result = new ArrayList<>(3);
        choose(greetings).ifPresent(result::add);
        for (ResidentProfile.ResidentTrait trait : profile.traits()) {
            List<String> lines = traits.get(trait);
            if (lines != null && !lines.isEmpty()) {
                choose(lines).ifPresent(result::add);
                break;
            }
        }
        choose(professions.get(profile.profession())).ifPresent(result::add);
        return List.copyOf(result);
    }

    private static java.util.Optional<String> choose(List<String> lines) {
        if (lines == null || lines.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
    }

    private static List<String> immutableLines(List<String> lines) {
        return lines == null ? List.of() : lines.stream()
                .filter(line -> line != null && !line.isBlank()).toList();
    }

    private static <K> Map<K, List<String>> immutableMap(Map<K, List<String>> source) {
        Map<K, List<String>> result = new java.util.HashMap<>();
        source.forEach((key, value) -> result.put(key, immutableLines(value)));
        return Map.copyOf(result);
    }
}
