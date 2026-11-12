package ru.raskolnpc.npc.resident.model;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResidentProfileTest {
    @Test
    void rejectsAgeAndNeedValuesOutsideSupportedRanges() {
        assertThrows(IllegalArgumentException.class, () -> profile(0, 50));
        assertThrows(IllegalArgumentException.class, () -> profile(121, 50));
        assertThrows(IllegalArgumentException.class, () -> profile(25, 101));
        assertThrows(IllegalArgumentException.class, () -> profile(25, -1));
    }

    @Test
    void copiesTraitsAndSkillsAndProvidesApprenticeForCurrentProfession() {
        EnumSet<ResidentProfile.ResidentTrait> traits =
                EnumSet.of(ResidentProfile.ResidentTrait.FRIENDLY);
        EnumMap<ResidentProfession, ResidentSkillLevel> skills =
                new EnumMap<>(ResidentProfession.class);
        ResidentProfile profile = new ResidentProfile(NpcId.create(), 25,
                ResidentProfession.FARMER, traits, 50, 50, 50, 50, 50, 50, skills);

        traits.clear();
        skills.put(ResidentProfession.MINER, ResidentSkillLevel.MASTER);

        assertTrue(profile.traits().contains(ResidentProfile.ResidentTrait.FRIENDLY));
        assertEquals(ResidentSkillLevel.APPRENTICE, profile.currentSkillLevel());
        assertThrows(UnsupportedOperationException.class,
                () -> profile.skills().put(ResidentProfession.MINER, ResidentSkillLevel.NOVICE));
    }

    private static ResidentProfile profile(int age, int hunger) {
        return new ResidentProfile(NpcId.create(), age, ResidentProfession.FARMER,
                SetHolder.NO_TRAITS, hunger, 50, 50, 50, 50, 50, Map.of());
    }

    private static final class SetHolder {
        private static final java.util.Set<ResidentProfile.ResidentTrait> NO_TRAITS = java.util.Set.of();
    }
}
