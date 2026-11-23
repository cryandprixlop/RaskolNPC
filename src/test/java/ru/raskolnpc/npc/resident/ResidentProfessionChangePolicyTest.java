package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResidentProfessionChangePolicyTest {
    private final ResidentProfessionChangePolicy policy = new ResidentProfessionChangePolicy();

    @Test
    void curiousResidentWithHealthyNeedsCanRequestChangeAfterCooldown() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                80, 75, 75, 70, ResidentProfession.FARMER);

        var decision = policy.evaluate(profile, 20, 13);

        assertTrue(decision.canRequestChange());
        assertEquals(ResidentProfessionChangePolicy.Status.READY, decision.status());
        assertEquals(ResidentProfession.values().length - 1,
                decision.suggestedProfessions().size());
        assertFalse(decision.suggestedProfessions().contains(ResidentProfession.FARMER));
    }

    @Test
    void residentWhoHasNeverChangedProfessionHasNoInitialCooldown() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                80, 75, 75, 70, ResidentProfession.FARMER);

        var decision = policy.evaluate(profile, 0, -1);

        assertTrue(decision.canRequestChange());
        assertEquals(ResidentProfession.values().length - 1,
                decision.suggestedProfessions().size());
    }

    @Test
    void hardworkingResidentCanRequestChange() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.HARD_WORKING),
                75, 70, 70, 65, ResidentProfession.MINER);

        assertTrue(policy.evaluate(profile, 7, 0).canRequestChange());
    }

    @Test
    void residentMustRecoverBeforeRequestingChange() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                69, 75, 75, 70, ResidentProfession.FARMER);

        var decision = policy.evaluate(profile, 10, 0);

        assertEquals(ResidentProfessionChangePolicy.Status.NEEDS_RECOVERY, decision.status());
        assertFalse(decision.canRequestChange());
        assertTrue(decision.suggestedProfessions().isEmpty());
    }

    @Test
    void residentWithoutMotivatingTraitDoesNotRequestChange() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.FRIENDLY),
                80, 75, 75, 70, ResidentProfession.FARMER);

        assertEquals(ResidentProfessionChangePolicy.Status.NO_MOTIVATION,
                policy.evaluate(profile, 10, 0).status());
    }

    @Test
    void cooldownBlocksRepeatedRequestsAndReportsRemainingDays() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                80, 75, 75, 70, ResidentProfession.FARMER);

        var decision = policy.evaluate(profile, 15, 10);

        assertEquals(ResidentProfessionChangePolicy.Status.COOLDOWN, decision.status());
        assertEquals(2, decision.daysUntilAvailable());
        assertFalse(decision.canRequestChange());
    }

    @Test
    void recentResidentRequestAlsoStartsSevenDayCooldown() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                80, 75, 75, 70, ResidentProfession.FARMER);

        var decision = policy.evaluate(profile, 15, -1, 10);

        assertEquals(ResidentProfessionChangePolicy.Status.COOLDOWN, decision.status());
        assertEquals(2, decision.daysUntilAvailable());
        assertFalse(decision.canRequestChange());
    }

    @Test
    void futureLastChangeDayFailsClosed() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                80, 75, 75, 70, ResidentProfession.FARMER);

        var decision = policy.evaluate(profile, 10, 12);

        assertEquals(ResidentProfessionChangePolicy.Status.COOLDOWN, decision.status());
        assertEquals(7, decision.daysUntilAvailable());
    }

    @Test
    void rejectsNegativeWorldDay() {
        ResidentProfile profile = profile(EnumSet.of(ResidentProfile.ResidentTrait.CURIOUS),
                80, 75, 75, 70, ResidentProfession.FARMER);

        assertThrows(IllegalArgumentException.class,
                () -> policy.evaluate(profile, -1, 0));
    }

    private static ResidentProfile profile(
            java.util.Set<ResidentProfile.ResidentTrait> traits,
            int mood, int hunger, int energy, int rest, ResidentProfession profession) {
        return new ResidentProfile(NpcId.create(), 30, profession, traits,
                hunger, energy, rest, 75, 75, mood, Map.of());
    }
}
