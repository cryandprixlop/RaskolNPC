package ru.raskolnpc.npc.resident;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentWorkActionControllerTest {
    @Test
    void allowsOnlyOneWorkActionWithinConfiguredInterval() {
        ResidentWorkActionController controller = new ResidentWorkActionController();
        controller.setActionIntervalTicks(40L);
        Villager villager = mock(Villager.class);
        World world = mock(World.class);
        Location location = mock(Location.class);
        when(villager.isValid()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenReturn(location);
        when(location.add(0.0, 1.0, 0.0)).thenReturn(location);
        ResidentProfile profile = new ResidentProfile(NpcId.create(), 30,
                ResidentProfession.BLACKSMITH, Set.of(), 75, 75, 75, 75, 75, 75, Map.of());

        assertTrue(controller.performIfReady(profile.npcId(), villager, profile, 100L));
        assertFalse(controller.performIfReady(profile.npcId(), villager, profile, 139L));
        assertTrue(controller.performIfReady(profile.npcId(), villager, profile, 140L));
    }

    @Test
    void rejectsInvalidWorkIntervals() {
        ResidentWorkActionController controller = new ResidentWorkActionController();

        assertThrows(IllegalArgumentException.class, () -> controller.setActionIntervalTicks(0L));
        assertThrows(IllegalArgumentException.class, () -> controller.setActionIntervalTicks(24_001L));
    }
}
