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
    void selectsPresentationParticleForEachProfession() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        assertEquals(Particle.COMPOSTER, controller.particleFor(ResidentProfession.FARMER));
        assertEquals(Particle.CRIT, controller.particleFor(ResidentProfession.MINER));
        assertEquals(Particle.CRIT, controller.particleFor(ResidentProfession.LUMBERJACK));
        assertEquals(Particle.BUBBLE, controller.particleFor(ResidentProfession.FISHERMAN));
        assertEquals(Particle.FLAME, controller.particleFor(ResidentProfession.BLACKSMITH));
        assertEquals(Particle.HAPPY_VILLAGER, controller.particleFor(ResidentProfession.CRAFTSMAN));
        assertEquals(Particle.HAPPY_VILLAGER, controller.particleFor(ResidentProfession.MERCHANT));
        assertEquals(Particle.CRIT, controller.particleFor(ResidentProfession.BUILDER));
        assertEquals(Particle.SMOKE, controller.particleFor(ResidentProfession.COOK));
        assertEquals(Particle.ENCHANT, controller.particleFor(ResidentProfession.TEACHER));
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

    private static ResidentProfile profile(ResidentProfession profession) {
        return new ResidentProfile(NpcId.create(), 30, profession, Set.of(),
                75, 75, 75, 75, 75, 75, Map.of());
    }
}
