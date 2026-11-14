package ru.raskolnpc.npc.resident;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ResidentDialogueServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void catalogSelectsGreetingTraitAndProfessionLines() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.FRIENDLY));
        ResidentDialogueCatalog catalog = new ResidentDialogueCatalog(
                List.of("Приветствую!"),
                Map.of(ResidentProfession.MINER, List.of("В шахте сегодня много работы.")),
                Map.of(ResidentProfile.ResidentTrait.FRIENDLY, List.of("Рад помочь соседям.")));

        List<String> lines = catalog.linesFor(profile);
        assertEquals(3, lines.size());
        assertTrue(lines.contains("Приветствую!"));
        assertTrue(lines.contains("Рад помочь соседям."));
        assertTrue(lines.contains("В шахте сегодня много работы."));
    }

    @Test
    void emptyCatalogReturnsFallbackGreeting() throws Exception {
        Path file = tempDir.resolve("resident-dialogues.yml");
        Files.writeString(file, "greetings: []\ntraits: {}\nprofessions: {}\n");
        ResidentDialogueService service = new ResidentDialogueService(mock(Plugin.class), file.toFile());

        List<String> lines = service.createLines(profile(EnumSet.noneOf(
                ResidentProfile.ResidentTrait.class)));
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(line -> line.contains("путник")
                || line.contains("Добрый день") || line.contains("Приветствую")));
    }

    @Test
    void reloadReplacesCatalogAfterValidFileChange() throws Exception {
        Path file = tempDir.resolve("resident-dialogues.yml");
        Files.writeString(file, "greetings:\n  - '&aПервый текст'\ntraits: {}\nprofessions: {}\n");
        ResidentDialogueService service = new ResidentDialogueService(mock(Plugin.class), file.toFile());

        assertEquals(1, service.createLines(profile(EnumSet.noneOf(
                ResidentProfile.ResidentTrait.class))).size());
        assertTrue(service.createLines(profile(EnumSet.noneOf(
                ResidentProfile.ResidentTrait.class))).get(0).contains("Первый текст"));

        Files.writeString(file, "greetings:\n  - '&bОбновлённый текст'\ntraits: {}\nprofessions: {}\n");
        service.reload();

        assertTrue(service.createLines(profile(EnumSet.noneOf(
                ResidentProfile.ResidentTrait.class))).get(0).contains("Обновлённый текст"));
    }

    @Test
    void failedReloadKeepsPreviouslyActiveCatalog() throws Exception {
        Path file = tempDir.resolve("resident-dialogues.yml");
        Files.writeString(file, "greetings:\n  - 'Рабочий текст'\ntraits: {}\nprofessions: {}\n");
        ResidentDialogueService service = new ResidentDialogueService(mock(Plugin.class), file.toFile());

        Files.writeString(file, "greetings: [\n  - broken\n");
        assertThrows(Exception.class, service::reload);

        List<String> activeLines = service.createLines(profile(EnumSet.noneOf(
                ResidentProfile.ResidentTrait.class)));
        assertEquals(1, activeLines.size());
        assertTrue(activeLines.get(0).contains("Рабочий текст"));
    }

    @Test
    void reputationSelectsHighestReachedDialogueTier() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("""
                greetings:
                  - "Привет."
                reputation:
                  "0":
                    - "Мы пока не знакомы."
                  "10":
                    - "Спасибо за помощь."
                  "25":
                    - "Тебя уважают в городе."
                traits: {}
                professions: {}
                """);
        ResidentDialogueCatalog catalog = ResidentDialogueCatalog.from(yaml);
        ResidentProfile profile = profile(EnumSet.noneOf(ResidentProfile.ResidentTrait.class));

        assertTrue(catalog.linesFor(profile, 0).contains("Мы пока не знакомы."));
        assertTrue(catalog.linesFor(profile, 14).contains("Спасибо за помощь."));
        assertTrue(catalog.linesFor(profile, 25).contains("Тебя уважают в городе."));
        assertTrue(catalog.linesFor(profile, 100).contains("Тебя уважают в городе."));
    }

    @Test
    void lowNeedsSelectContextualDialogueWithoutChangingHealthyResidents() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("""
                greetings:
                  - "Привет."
                needs:
                  LOW_HUNGER:
                    - "Я давно не ел, на рынке сейчас мало еды."
                  LOW_ENERGY:
                    - "Сегодня я очень устал."
                traits: {}
                professions: {}
                """);
        ResidentDialogueCatalog catalog = ResidentDialogueCatalog.from(yaml);
        ResidentProfile healthy = profile(EnumSet.noneOf(ResidentProfile.ResidentTrait.class));
        ResidentProfile tiredAndHungry = new ResidentProfile(
                healthy.npcId(), healthy.age(), healthy.profession(), healthy.traits(),
                20, 25, 25, 75, 75, 44, healthy.skills());

        assertTrue(catalog.linesFor(tiredAndHungry).contains(
                "Я давно не ел, на рынке сейчас мало еды."));
        assertFalse(catalog.linesFor(healthy).contains(
                "Я давно не ел, на рынке сейчас мало еды."));
    }

    @Test
    void needDialogueIsOptionalForExistingCatalogs() {
        ResidentDialogueCatalog catalog = new ResidentDialogueCatalog(
                List.of("Привет."), Map.of(), Map.of());
        ResidentProfile profile = new ResidentProfile(
                NpcId.create(), 28, ResidentProfession.MINER, EnumSet.noneOf(
                        ResidentProfile.ResidentTrait.class),
                10, 10, 10, 10, 10, 10, Map.of());

        assertEquals(List.of("Привет."), catalog.linesFor(profile));
    }

    @Test
    void reputationDialogueTiersAreOptionalForLegacyCatalogs() {
        ResidentDialogueCatalog catalog = new ResidentDialogueCatalog(
                List.of("Привет."), Map.of(), Map.of());
        ResidentProfile profile = profile(EnumSet.noneOf(ResidentProfile.ResidentTrait.class));

        assertEquals(List.of("Привет."), catalog.linesFor(profile, 100));
    }

    private static ResidentProfile profile(java.util.Set<ResidentProfile.ResidentTrait> traits) {
        Map<ResidentProfession, ResidentSkillLevel> skills = new EnumMap<>(ResidentProfession.class);
        skills.put(ResidentProfession.MINER, ResidentSkillLevel.APPRENTICE);
        return new ResidentProfile(NpcId.create(), 28, ResidentProfession.MINER, traits,
                75, 75, 75, 75, 75, 75, skills);
    }
}
