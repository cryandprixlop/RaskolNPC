package ru.raskolnpc.npc.resident.quest;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import static org.junit.jupiter.api.Assertions.*;

class ResidentProductionCatalogTest {
    @Test
    void loadsRecipesAndFiltersByProfession() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                recipes:
                  sword:
                    profession: BLACKSMITH
                    inputs:
                      IRON_INGOT: 4
                      COAL: 2
                    output:
                      material: IRON_SWORD
                      amount: 1
                      capacity: 32
                  bread:
                    profession: COOK
                    inputs:
                      WHEAT: 3
                    output:
                      material: BREAD
                      amount: 1
                      capacity: 48
                """));

        ResidentProductionCatalog catalog = ResidentProductionCatalog.from(yaml);

        assertEquals(2, catalog.all().size());
        assertEquals("IRON_SWORD", catalog.forProfession(ResidentProfession.BLACKSMITH)
                .getFirst().outputMaterial());
        assertEquals(2, catalog.forProfession(ResidentProfession.BLACKSMITH)
                .getFirst().inputs().size());
        assertTrue(catalog.forProfession(ResidentProfession.MINER).isEmpty());
    }

    @Test
    void rejectsMissingInputsAndInvalidMaterials() {
        YamlConfiguration missingInputs = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                recipes:
                  bad:
                    profession: BLACKSMITH
                    output:
                      material: IRON_SWORD
                      amount: 1
                      capacity: 16
                """));
        assertThrows(IllegalArgumentException.class,
                () -> ResidentProductionCatalog.from(missingInputs));

        YamlConfiguration invalidMaterial = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                recipes:
                  bad:
                    profession: BLACKSMITH
                    inputs:
                      NOT_A_MATERIAL: 1
                    output:
                      material: IRON_SWORD
                      amount: 1
                      capacity: 16
                """));
        assertThrows(IllegalArgumentException.class,
                () -> ResidentProductionCatalog.from(invalidMaterial));
    }

    @Test
    void rejectsCapacityBelowOutputAndNonPositiveInput() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentProductionRecipe(
                "bad", ResidentProfession.BLACKSMITH, java.util.Map.of("IRON_INGOT", 4),
                "IRON_SWORD", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResidentProductionRecipe(
                "bad", ResidentProfession.BLACKSMITH, java.util.Map.of("IRON_INGOT", 0),
                "IRON_SWORD", 1, 16));
    }
}
