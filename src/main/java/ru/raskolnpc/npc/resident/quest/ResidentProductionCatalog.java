package ru.raskolnpc.npc.resident.quest;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validated immutable production recipe catalog loaded from production-recipes.yml. */
public final class ResidentProductionCatalog {
    private final Map<String, ResidentProductionRecipe> recipes;

    public ResidentProductionCatalog(Map<String, ResidentProductionRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        Map<String, ResidentProductionRecipe> copy = new HashMap<>();
        recipes.forEach((key, recipe) -> {
            Objects.requireNonNull(recipe, "recipe");
            String id = normalizeId(key);
            if (!id.equals(recipe.id())) {
                throw new IllegalArgumentException("Catalog key must match recipe id: " + key);
            }
            if (copy.putIfAbsent(id, recipe) != null) {
                throw new IllegalArgumentException("Duplicate production recipe id: " + id);
            }
        });
        this.recipes = Map.copyOf(copy);
    }

    public static ResidentProductionCatalog from(YamlConfiguration yaml) {
        Objects.requireNonNull(yaml, "yaml");
        ConfigurationSection section = yaml.getConfigurationSection("recipes");
        if (section == null) return new ResidentProductionCatalog(Map.of());
        Map<String, ResidentProductionRecipe> loaded = new HashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection recipe = section.getConfigurationSection(key);
            if (recipe == null) {
                throw new IllegalArgumentException("Production recipe '" + key + "' must be a section.");
            }
            try {
                ResidentProfession profession = ResidentProfession.valueOf(
                        recipe.getString("profession", "").trim().toUpperCase(Locale.ROOT));
                ConfigurationSection inputSection = recipe.getConfigurationSection("inputs");
                if (inputSection == null) {
                    throw new IllegalArgumentException("Missing inputs section.");
                }
                Map<String, Integer> inputs = new HashMap<>();
                for (String material : inputSection.getKeys(false)) {
                    Object raw = inputSection.get(material);
                    if (!(raw instanceof Number number)
                            || number.doubleValue() != number.intValue()) {
                        throw new IllegalArgumentException("Input amount for " + material + " must be an integer.");
                    }
                    inputs.put(material, number.intValue());
                }
                ResidentProductionRecipe parsed = new ResidentProductionRecipe(
                        key, profession, inputs,
                        recipe.getString("output.material", ""),
                        requiredInteger(recipe, "output.amount"),
                        requiredInteger(recipe, "output.capacity"));
                if (loaded.putIfAbsent(parsed.id(), parsed) != null) {
                    throw new IllegalArgumentException("Duplicate recipe id: " + parsed.id());
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "Invalid production recipe '" + key + "': " + error.getMessage(), error);
            }
        }
        return new ResidentProductionCatalog(loaded);
    }

    public Optional<ResidentProductionRecipe> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(recipes.get(normalizeId(id)));
    }

    public java.util.List<ResidentProductionRecipe> forProfession(ResidentProfession profession) {
        Objects.requireNonNull(profession, "profession");
        return recipes.values().stream()
                .filter(recipe -> recipe.profession() == profession)
                .sorted(java.util.Comparator.comparing(ResidentProductionRecipe::id))
                .toList();
    }

    public java.util.List<ResidentProductionRecipe> all() {
        return recipes.values().stream()
                .sorted(java.util.Comparator.comparing(ResidentProductionRecipe::id))
                .toList();
    }

    private static int requiredInteger(ConfigurationSection section, String path) {
        Object raw = section.get(path);
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(path + " must be an integer.");
        }
        return number.intValue();
    }

    private static String normalizeId(String id) {
        return Objects.requireNonNull(id, "id").trim().toLowerCase(Locale.ROOT);
    }
}
