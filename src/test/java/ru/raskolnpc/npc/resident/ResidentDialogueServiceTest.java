package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResidentDialogueServiceTest {
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
    void emptyCatalogReturnsNoLinesSoServiceCanUseFallback() {
        ResidentDialogueCatalog catalog = new ResidentDialogueCatalog(List.of(), Map.of(), Map.of());
        assertTrue(catalog.linesFor(profile(EnumSet.noneOf(
                ResidentProfile.ResidentTrait.class))).isEmpty());
    }

    private static ResidentProfile profile(java.util.Set<ResidentProfile.ResidentTrait> traits) {
        Map<ResidentProfession, ResidentSkillLevel> skills = new EnumMap<>(ResidentProfession.class);
        skills.put(ResidentProfession.MINER, ResidentSkillLevel.APPRENTICE);
        return new ResidentProfile(NpcId.create(), 28, ResidentProfession.MINER, traits,
                75, 75, 75, 75, 75, 75, skills);
    }
}
