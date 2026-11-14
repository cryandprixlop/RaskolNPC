package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResidentNeedsControllerTest {
    private final ResidentNeedsController controller = new ResidentNeedsController();

    @Test
    void workGraduallyConsumesHungerEnergyAndRest() {
        ResidentProfile updated = controller.advance(profile(75, 75, 75, 75),
                ResidentSchedule.Activity.WORK);

        assertEquals(73, updated.hunger());
        assertEquals(69, updated.energy());
        assertEquals(70, updated.rest());
        assertEquals(73, updated.social());
    }

    @Test
    void sleepRestoresEnergyAndRest() {
        ResidentProfile updated = controller.advance(profile(75, 40, 35, 75),
                ResidentSchedule.Activity.SLEEP);

        assertEquals(52, updated.energy());
        assertEquals(50, updated.rest());
        assertEquals(72, updated.social());
    }

    @Test
    void eatingRestoresHungerAndSocializingImprovesSocialNeed() {
        ResidentProfile fed = controller.advance(profile(40, 75, 75, 75),
                ResidentSchedule.Activity.EAT);
        ResidentProfile socialized = controller.advance(profile(75, 75, 75, 40),
                ResidentSchedule.Activity.SOCIALIZE);

        assertEquals(56, fed.hunger());
        assertEquals(50, socialized.social());
    }

    @Test
    void needsAndMoodRemainWithinZeroToOneHundred() {
        ResidentProfile exhausted = controller.advance(profile(0, 0, 0, 0),
                ResidentSchedule.Activity.WORK);
        ResidentProfile rested = controller.advance(profile(100, 100, 100, 100),
                ResidentSchedule.Activity.SLEEP);

        assertEquals(0, exhausted.hunger());
        assertEquals(0, exhausted.energy());
        assertEquals(0, exhausted.rest());
        assertEquals(15, exhausted.mood());
        assertEquals(100, rested.energy());
        assertEquals(100, rested.rest());
        assertTrue(rested.mood() <= 100);
    }

    private static ResidentProfile profile(int hunger, int energy, int rest, int social) {
        return new ResidentProfile(NpcId.create(), 30, ResidentProfession.MINER, Set.of(),
                hunger, energy, rest, social, 75, 75, Map.of());
    }
}
