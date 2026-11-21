package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardOrderRepository;
import ru.raskolnpc.storage.GuardSquadRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuardSquadFormationTest {
    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("guard-squad-formation");
        world.loadChunk(0, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadsStableWedgeSlotsAndKeepsRallyDestinationsUnchanged() {
        NpcId first = id(1);
        NpcId second = id(2);
        NpcId third = id(3);
        NpcId fourth = id(4);
        GuardSquad squad = new GuardSquad(UUID.randomUUID(), "Watch", UUID.randomUUID(),
                100L, List.of(fourth, second, first, third));
        GuardSquadRepository repository = mock(GuardSquadRepository.class);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(squad)));
        GuardSquadService service = new GuardSquadService(new NpcRegistry(), repository, 10);

        service.refreshMemberships().join();

        Location shared = new Location(world, 10.5, 64, 10.5, 0, 0);
        assertLocation(service.formationTarget(first, shared, GuardOrder.Type.PATROL), 10.5, 10.5);
        assertLocation(service.formationTarget(second, shared, GuardOrder.Type.PATROL), 10.5, 9.0);
        assertLocation(service.formationTarget(third, shared, GuardOrder.Type.PATROL), 9.5, 7.5);
        assertLocation(service.formationTarget(fourth, shared, GuardOrder.Type.PATROL), 11.5, 7.5);
        assertLocation(service.formationTarget(second, shared, GuardOrder.Type.RALLY), 10.5, 10.5);
        assertEquals(10.5, shared.getX(), 0.001, "formation must not mutate the shared destination");
        assertEquals(10.5, shared.getZ(), 0.001);
    }

    @Test
    void movementControllerUsesTheCachedSquadFormationTarget() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(guard);
        LivingEntity entity = mock(LivingEntity.class);
        Location origin = new Location(world, 0.5, 64, 0.5);
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
        when(entity.getLocation()).thenReturn(origin);
        NpcEntityManager entities = mock(NpcEntityManager.class);
        when(entities.find(guard.id())).thenReturn(Optional.of(entity));

        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(new GuardOrder.Waypoint(world.getUID(), 0.5, 64, 8.5, 0, 0)),
                UUID.randomUUID(), 100L);
        GuardOrderRepository orderRepository = mock(GuardOrderRepository.class);
        when(orderRepository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        GuardOrderService orders = new GuardOrderService(registry, orderRepository);
        orders.loadAll().join();

        GuardSquadService squads = mock(GuardSquadService.class);
        when(squads.formationTarget(eq(guard.id()), any(Location.class), eq(GuardOrder.Type.PATROL)))
                .thenReturn(new Location(world, 0.5, 64, 8.5));
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6);
        controller.setSquadService(squads);

        controller.tick();

        verify(squads).formationTarget(eq(guard.id()), any(Location.class), eq(GuardOrder.Type.PATROL));
        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(entity).teleport(destination.capture());
        assertTrue(destination.getValue().getZ() > origin.getZ());
        assertEquals(origin.getX(), destination.getValue().getX(), 0.001);
    }

    private NpcId id(long leastSignificantBits) {
        return new NpcId(new UUID(0L, leastSignificantBits));
    }

    private void assertLocation(Location actual, double x, double z) {
        assertEquals(x, actual.getX(), 0.001);
        assertEquals(z, actual.getZ(), 0.001);
    }
}
