package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardOrderRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuardOrderControllerTest {
    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private Entity entity;
    private GuardOrderService orders;
    private NpcRecord guard;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("guard-order-controller");
        world.loadChunk(0, 0);
        registry = new NpcRegistry();
        guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}")
                .withEntityBinding(UUID.randomUUID(),
                        NpcSpawnLocation.from(new Location(world, 0.5, 64, 0.5)));
        registry.put(guard);

        entity = mock(Entity.class);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        entities = mock(NpcEntityManager.class);
        when(entities.find(guard.id())).thenReturn(Optional.of(entity));
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        orders = new GuardOrderService(registry, repository);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void patrolMovesInSmallStepsTowardLoadedWaypoint() {
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(5.5), point(10.5)), UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        orders = new GuardOrderService(registry, repository);
        assertEquals(1, orders.loadAll().join());

        GuardOrderController controller =
                new GuardOrderController(plugin, registry, entities, orders, 4, 0.6);
        controller.tick();

        verify(entity).teleport((Location) argThat((Location location) ->
                location.getX() > 0.5 && location.getX() <= 1.11
                        && location.getWorld().getUID().equals(world.getUID())));
    }

    @Test
    void doesNotForceLoadDestinationChunk() {
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.DEFEND,
                List.of(new GuardOrder.Waypoint(world.getUID(), 40.5, 64, 0.5, 0, 0)),
                UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        GuardOrderController controller =
                new GuardOrderController(plugin, registry, entities, orders, 4, 0.6);
        controller.tick();

        assertFalse(world.isChunkLoaded(2, 0));
        verify(entity, never()).teleport((Location) any(Location.class));
    }

    @Test
    void holdPositionDoesNotMoveGuard() {
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.HOLD_POSITION,
                List.of(), UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        GuardOrderController controller =
                new GuardOrderController(plugin, registry, entities, orders, 4, 0.6);
        controller.tick();

        verify(entity, never()).teleport(any(Location.class));
    }

    private GuardOrder.Waypoint point(double x) {
        return new GuardOrder.Waypoint(world.getUID(), x, 64, 0.5, 0, 0);
    }
}
