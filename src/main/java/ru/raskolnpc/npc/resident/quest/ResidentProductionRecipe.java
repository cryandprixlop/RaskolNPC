package ru.raskolnpc.npc.resident.quest;

import org.bukkit.Material;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, validated recipe for one resident profession's shared town stock. */
public record ResidentProductionRecipe(
        String id,
        ResidentProfession profession,
        Map<String, Integer> inputs,
        String outputMaterial,
        int outputAmount,
        int outputCapacity,
        ResidentSkillLevel minimumSkillLevel
) {
    /** Backwards-compatible recipe constructor; existing recipes remain available to apprentices. */
    public ResidentProductionRecipe(String id, ResidentProfession profession, Map<String, Integer> inputs,
            String outputMaterial, int outputAmount, int outputCapacity) {
        this(id, profession, inputs, outputMaterial, outputAmount, outputCapacity,
                ResidentSkillLevel.APPRENTICE);
    }

    public ResidentProductionRecipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profession, "profession");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(outputMaterial, "outputMaterial");
        Objects.requireNonNull(minimumSkillLevel, "minimumSkillLevel");
        id = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Recipe id must contain 1-64 lowercase letters, digits, '.', '_' or '-'.");
        }
        TreeMap<String, Integer> normalized = new TreeMap<>();
        inputs.forEach((materialKey, amount) -> {
            String material = normalizeMaterial(materialKey);
            if (amount == null || amount < 1 || amount > 2304) {
                throw new IllegalArgumentException("Each recipe input amount must be between 1 and 2304.");
            }
            if (normalized.putIfAbsent(material, amount) != null) {
                throw new IllegalArgumentException("Duplicate recipe input material: " + material);
            }
        });
        if (normalized.isEmpty() || normalized.size() > 16) {
            throw new IllegalArgumentException("A recipe must contain between 1 and 16 input materials.");
        }
        inputs = Map.copyOf(normalized);
        outputMaterial = normalizeMaterial(outputMaterial);
        if (outputAmount < 1 || outputAmount > 2304) {
            throw new IllegalArgumentException("Output amount must be between 1 and 2304.");
        }
        if (outputCapacity < outputAmount || outputCapacity > 1_000_000) {
            throw new IllegalArgumentException("Output capacity must be at least output amount and at most 1000000.");
        }
    }

    private static String normalizeMaterial(String key) {
        Objects.requireNonNull(key, "material");
        String normalized = key.trim().toUpperCase(java.util.Locale.ROOT);
        Material material = Material.matchMaterial(normalized);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Unknown or non-item Bukkit material: " + key);
        }
        return material.name();
    }
}
