package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class ResidentMovementControllerTest {
    @Test
    void triesASafeSideStepWhenDirectPathIsBlocked() {
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        Block blocked = mock(Block.class);
        when(blocked.isPassable()).thenReturn(false);
        when(blocked.getType()).thenReturn(Material.AIR);
        Block air = mock(Block.class);
        when(air.isPassable()).thenReturn(true);
        when(air.getType()).thenReturn(Material.AIR);
        Block floor = mock(Block.class);
        when(floor.getType()).thenReturn(Material.STONE);

        when(world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
            Location location = invocation.getArgument(0);
            return location.getZ() == 0.0 && location.getX() > 0.5 ? blocked : air;
        });
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            return y < 64 ? floor : air;
        });

        Entity entity = mock(Entity.class);
        when(entity.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(entity.teleport(any(Location.class))).thenReturn(true);
        Location destination = new Location(world, 5, 64, 0);

        assertTrue(new ResidentMovementController(0.8).moveTowards(entity, destination));

        ArgumentCaptor<Location> movedTo = ArgumentCaptor.forClass(Location.class);
        verify(entity).teleport(movedTo.capture());
        assertNotEquals(0.0, movedTo.getValue().getZ(), 0.0001);
    }

    @Test
    void neverInspectsBlocksInAnUnloadedChunk() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        Entity entity = mock(Entity.class);
        when(entity.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        assertFalse(new ResidentMovementController(0.8).moveTowards(
                entity, new Location(world, 5, 64, 0)));
        verify(world, never()).getBlockAt(any(Location.class));
        verify(entity, never()).teleport(any(Location.class));
    }

    @Test
    void acceptsBoundedPositiveMovementStep() {
        assertDoesNotThrow(() -> new ResidentMovementController(0.8));
    }

    @Test
    void rejectsNonPositiveOrExcessiveMovementStep() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentMovementController(0));
        assertThrows(IllegalArgumentException.class, () -> new ResidentMovementController(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new ResidentMovementController(1.6));
    }
}
