package ru.raskolnpc.npc.resident;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/** Immutable dialogue text catalog loaded from resident-dialogues.yml. */
public final class ResidentDialogueCatalog {
    private final List<String> greetings;
    private final Map<ResidentProfession, List<String>> professions;
    private final Map<ResidentProfile.ResidentTrait, List<String>> traits;
    private final NavigableMap<Integer, List<String>> reputationLines;
    private final Map<String, List<String>> needLines;

    public ResidentDialogueCatalog(List<String> greetings,
            Map<ResidentProfession, List<String>> professions,
            Map<ResidentProfile.ResidentTrait, List<String>> traits) {
        this(greetings, professions, traits, Map.of(), Map.of());
    }

    public ResidentDialogueCatalog(List<String> greetings,
            Map<ResidentProfession, List<String>> professions,
            Map<ResidentProfile.ResidentTrait, List<String>> traits,
            Map<Integer, List<String>> reputationLines) {
        this(greetings, professions, traits, reputationLines, Map.of());
    }

    public ResidentDialogueCatalog(List<String> greetings,
            Map<ResidentProfession, List<String>> professions,
            Map<ResidentProfile.ResidentTrait, List<String>> traits,
            Map<Integer, List<String>> reputationLines,
            Map<String, List<String>> needLines) {
        this.greetings = immutableLines(greetings);
        this.professions = immutableMap(professions);
        this.traits = immutableMap(traits);
        NavigableMap<Integer, List<String>> reputationCopy = new TreeMap<>();
        Objects.requireNonNull(reputationLines, "reputationLines").forEach(
                (threshold, lines) -> reputationCopy.put(
                        Objects.requireNonNull(threshold, "reputation threshold"),
                        immutableLines(lines)));
        this.reputationLines = Collections.unmodifiableNavigableMap(reputationCopy);
        Map<String, List<String>> needsCopy = new java.util.HashMap<>();
        Objects.requireNonNull(needLines, "needLines").forEach((key, lines) ->
                needsCopy.put(Objects.requireNonNull(key, "need key").toUpperCase(java.util.Locale.ROOT),
                        immutableLines(lines)));
        this.needLines = Map.copyOf(needsCopy);
    }

    public static ResidentDialogueCatalog from(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml, "yaml");
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
        Map<Integer, List<String>> reputation = new TreeMap<>();
        ConfigurationSection reputationSection = yaml.getConfigurationSection("reputation");
        if (reputationSection != null) {
            for (String key : reputationSection.getKeys(false)) {
                try {
                    int threshold = Integer.parseInt(key);
                    if (threshold < 0) continue;
                    reputation.put(threshold, reputationSection.getStringList(key));
                } catch (NumberFormatException ignored) {
                    // Invalid thresholds are ignored; valid tiers remain available.
                }
            }
        }
        Map<String, List<String>> needs = new java.util.HashMap<>();
        ConfigurationSection needsSection = yaml.getConfigurationSection("needs");
        if (needsSection != null) {
            for (String key : needsSection.getKeys(false)) {
                needs.put(key.toUpperCase(java.util.Locale.ROOT), needsSection.getStringList(key));
            }
        }
        return new ResidentDialogueCatalog(greetings, professions, traits, reputation, needs);
    }

    public List<String> linesFor(ResidentProfile profile) {
        return linesFor(profile, 0);
    }

    /** Adds the highest reputation tier whose threshold the player has reached. */
    public List<String> linesFor(ResidentProfile profile, int reputation) {
        Objects.requireNonNull(profile, "profile");
        List<String> result = new ArrayList<>(4);
        choose(greetings).ifPresent(result::add);
        for (ResidentProfile.ResidentTrait trait : profile.traits()) {
            List<String> lines = traits.get(trait);
            if (lines != null && !lines.isEmpty()) {
                choose(lines).ifPresent(result::add);
                break;
            }
        }
        Map.Entry<Integer, List<String>> reputationTier = reputationLines.floorEntry(
                Math.max(0, reputation));
        if (reputationTier != null) choose(reputationTier.getValue()).ifPresent(result::add);
        String needKey = needKey(profile);
        if (needKey != null) choose(needLines.get(needKey)).ifPresent(result::add);
        choose(professions.get(profile.profession())).ifPresent(result::add);
        return List.copyOf(result);
    }

    private static String needKey(ResidentProfile profile) {
        if (profile.hunger() <= 30) return "LOW_HUNGER";
        if (profile.energy() <= 30) return "LOW_ENERGY";
        if (profile.rest() <= 30) return "LOW_REST";
        if (profile.social() <= 30) return "LOW_SOCIAL";
        if (profile.housingSatisfaction() <= 30) return "LOW_HOUSING";
        if (profile.mood() <= 30) return "LOW_MOOD";
        return null;
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
        Objects.requireNonNull(source, "source").forEach((key, value) ->
                result.put(key, immutableLines(value)));
        return Map.copyOf(result);
    }
}
