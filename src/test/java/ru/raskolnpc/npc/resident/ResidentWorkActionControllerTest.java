package ru.raskolnpc.npc.resident;

import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResidentWorkActionControllerTest {
    @Test
    void performsWorkPresentationOnlyWhenIntervalHasElapsed() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        NpcId id = NpcId.create();
        Villager villager = mock(Villager.class);
        World world = mock(World.class);
        when(villager.isValid()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);

        assertTrue(controller.performIfReady(id, villager, profile(ResidentProfession.FARMER), 10));
        assertFalse(controller.performIfReady(id, villager, profile(ResidentProfession.FARMER), 69));
        assertTrue(controller.performIfReady(id, villager, profile(ResidentProfession.FARMER), 70));

        verify(villager, times(2)).swingMainHand();
        verify(world, times(2)).spawnParticle(eq(Particle.COMPOSTER), any(), eq(2),
                eq(0.15), eq(0.2), eq(0.15), eq(0.01));
    }

    @Test
    void selectsPresentationParticleForEveryProfession() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        World world = mock(World.class);
        Villager villager = mock(Villager.class);
        when(villager.isValid()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);

        Map<ResidentProfession, Particle> expected = Map.ofEntries(
                Map.entry(ResidentProfession.FARMER, Particle.COMPOSTER),
                Map.entry(ResidentProfession.MINER, Particle.CRIT),
                Map.entry(ResidentProfession.LUMBERJACK, Particle.CRIT),
                Map.entry(ResidentProfession.FISHERMAN, Particle.BUBBLE),
                Map.entry(ResidentProfession.BLACKSMITH, Particle.FLAME),
                Map.entry(ResidentProfession.CRAFTSMAN, Particle.HAPPY_VILLAGER),
                Map.entry(ResidentProfession.MERCHANT, Particle.HAPPY_VILLAGER),
                Map.entry(ResidentProfession.BUILDER, Particle.CRIT),
                Map.entry(ResidentProfession.COOK, Particle.SMOKE),
                Map.entry(ResidentProfession.TEACHER, Particle.ENCHANT));

        long tick = 0;
        for (var entry : expected.entrySet()) {
            assertTrue(controller.performIfReady(NpcId.create(), villager,
                    profile(entry.getKey()), tick++));
        }
        verify(world, times(expected.size())).spawnParticle(any(Particle.class), any(), eq(2),
                eq(0.15), eq(0.2), eq(0.15), eq(0.01));
    }

    @Test
    void rejectsInvalidIntervalsAndAllowsConfiguredBounds() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        assertThrows(IllegalArgumentException.class, () -> controller.setActionIntervalTicks(0));
        assertThrows(IllegalArgumentException.class, () -> controller.setActionIntervalTicks(24_001));
        assertDoesNotThrow(() -> controller.setActionIntervalTicks(1));
        assertDoesNotThrow(() -> controller.setActionIntervalTicks(24_000));
    }

    @Test
    void ignoresInvalidEntitiesAndClearsPerResidentCooldowns() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        NpcId id = NpcId.create();
        Villager villager = mock(Villager.class);
        when(villager.isValid()).thenReturn(false);

        assertFalse(controller.performIfReady(id, villager, profile(ResidentProfession.FARMER), 10));
        verify(villager, never()).swingMainHand();

        when(villager.isValid()).thenReturn(true);
        when(villager.getWorld()).thenReturn(mock(World.class));
        assertTrue(controller.performIfReady(id, villager, profile(ResidentProfession.FARMER), 10));
        controller.clear();
        assertTrue(controller.performIfReady(id, villager, profile(ResidentProfession.FARMER), 11));
    }


    @Test
    void respectsExplicitlyConfiguredWorkInterval() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        controller.setActionIntervalTicks(40L);
        Villager villager = mock(Villager.class);
        World world = mock(World.class);
        when(villager.isValid()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        ResidentProfile profile = profile(ResidentProfession.BLACKSMITH);

        assertTrue(controller.performIfReady(profile.npcId(), villager, profile, 100L));
        assertFalse(controller.performIfReady(profile.npcId(), villager, profile, 139L));
        assertTrue(controller.performIfReady(profile.npcId(), villager, profile, 140L));
    }

    private static ResidentProfile profile(ResidentProfession profession) {
        return new ResidentProfile(NpcId.create(), 30, profession, Set.of(),
                75, 75, 75, 75, 75, 75, Map.of());
    }
}
