package ru.raskolnpc.npc.resident.quest;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import static org.junit.jupiter.api.Assertions.*;

class ResidentQuestCatalogTest {
    @Test
    void loadsAndFiltersQuestsByProfession() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("quests.miner_iron.profession", "MINER");
        yaml.set("quests.miner_iron.required-material", "IRON_INGOT");
        yaml.set("quests.miner_iron.required-amount", 32);
        yaml.set("quests.miner_iron.reputation-reward", 5);
        yaml.set("quests.miner_iron.stock-target", 96);
        yaml.set("quests.farmer_wheat.profession", "FARMER");
        yaml.set("quests.farmer_wheat.required-material", "WHEAT");
        yaml.set("quests.farmer_wheat.required-amount", 16);
        yaml.set("quests.farmer_wheat.reputation-reward", 3);

        ResidentQuestCatalog catalog = ResidentQuestCatalog.from(yaml);

        assertEquals(2, catalog.all().size());
        assertEquals("IRON_INGOT", catalog.forProfession(ResidentProfession.MINER)
                .getFirst().requiredMaterial());
        assertEquals(96, catalog.forProfession(ResidentProfession.MINER)
                .getFirst().stockTarget());
        assertEquals("farmer_wheat", catalog.forProfession(ResidentProfession.FARMER)
                .getFirst().id());
        assertTrue(catalog.forProfession(ResidentProfession.COOK).isEmpty());
    }

    @Test
    void rejectsStockTargetSmallerThanDelivery() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentQuest(
                "miner_iron", ResidentProfession.MINER, "IRON_INGOT", 32, 5, 16));
    }

    @Test
    void shippedQuestCatalogCoversAllTenProfessionsWithStockTargets() throws Exception {
        try (var stream = getClass().getResourceAsStream("/resident-quests.yml")) {
            assertNotNull(stream);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            ResidentQuestCatalog catalog = ResidentQuestCatalog.from(yaml);

            assertEquals(10, catalog.all().size());
            assertEquals(java.util.EnumSet.allOf(ResidentProfession.class),
                    catalog.all().stream().map(ResidentQuest::profession)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(catalog.all().stream().allMatch(quest -> quest.stockTarget()
                    >= quest.requiredAmount()));
        }
    }

    @Test
    void rejectsInvalidQuestDefinition() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("quests.bad.profession", "NOT_A_PROFESSION");
        yaml.set("quests.bad.required-material", "IRON_INGOT");
        yaml.set("quests.bad.required-amount", 1);
        yaml.set("quests.bad.reputation-reward", 1);

        assertThrows(IllegalArgumentException.class, () -> ResidentQuestCatalog.from(yaml));
    }

    @Test
    void rejectsMultipleStockGatedQuestsForTheSameBusinessMaterial() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("quests.miner_iron_small.profession", "MINER");
        yaml.set("quests.miner_iron_small.required-material", "IRON_INGOT");
        yaml.set("quests.miner_iron_small.required-amount", 16);
        yaml.set("quests.miner_iron_small.reputation-reward", 2);
        yaml.set("quests.miner_iron_small.stock-target", 64);
        yaml.set("quests.miner_iron_large.profession", "MINER");
        yaml.set("quests.miner_iron_large.required-material", "IRON_INGOT");
        yaml.set("quests.miner_iron_large.required-amount", 32);
        yaml.set("quests.miner_iron_large.reputation-reward", 5);
        yaml.set("quests.miner_iron_large.stock-target", 96);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ResidentQuestCatalog.from(yaml));

        assertTrue(error.getMessage().contains("profession/material pair"));
    }

    @Test
    void allowsDifferentMaterialsForTheSameProfession() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("quests.miner_iron.profession", "MINER");
        yaml.set("quests.miner_iron.required-material", "IRON_INGOT");
        yaml.set("quests.miner_iron.required-amount", 16);
        yaml.set("quests.miner_iron.reputation-reward", 2);
        yaml.set("quests.miner_iron.stock-target", 64);
        yaml.set("quests.miner_coal.profession", "MINER");
        yaml.set("quests.miner_coal.required-material", "COAL");
        yaml.set("quests.miner_coal.required-amount", 16);
        yaml.set("quests.miner_coal.reputation-reward", 2);
        yaml.set("quests.miner_coal.stock-target", 64);

        ResidentQuestCatalog catalog = ResidentQuestCatalog.from(yaml);

        assertEquals(2, catalog.forProfession(ResidentProfession.MINER).size());
    }

}
