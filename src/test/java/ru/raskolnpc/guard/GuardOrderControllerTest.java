package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.integration.RelationshipPolicy;
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
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuardOrderControllerTest {
    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private LivingEntity entity;
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

        entity = mock(LivingEntity.class);
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
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

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(entity).teleport(destination.capture());
        assertTrue(destination.getValue().getX() > 0.5);
        assertTrue(destination.getValue().getX() <= 1.11);
        assertEquals(world.getUID(), destination.getValue().getWorld().getUID());
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

    @Test
    void sidestepsAroundASingleBlockedMovementStep() {
        world.getBlockAt(1, 64, 0).setType(Material.STONE);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(5.5), point(10.5)), UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        GuardOrderController controller =
                new GuardOrderController(plugin, registry, entities, orders, 4, 0.6);
        controller.tick();

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(entity).teleport(destination.capture());
        assertEquals(0.5, destination.getValue().getX(), 0.001);
        assertTrue(destination.getValue().getZ() > 0.5);
    }

    @Test
    void pursuesOnlyVerifiedHostileRegisteredNpcWithinConfiguredRadius() {
        LivingEntity guardEntity = mock(LivingEntity.class);
        LivingEntity hostileEntity = mock(LivingEntity.class);
        NpcRecord hostile = NpcRecord.create(
                NpcType.GUARD, "Hostile", null, "enemy-town", "enemy-nation", "{}");
        registry.register(hostile);

        Location origin = new Location(world, 0.5, 64, 0.5);
        when(guardEntity.isValid()).thenReturn(true);
        when(guardEntity.getLocation()).thenReturn(origin);
        when(guardEntity.getNearbyEntities(8.0, 8.0, 8.0))
                .thenReturn(List.of(hostileEntity));
        when(hostileEntity.isValid()).thenReturn(true);
        when(hostileEntity.isDead()).thenReturn(false);
        when(hostileEntity.getWorld()).thenReturn(world);
        when(hostileEntity.getLocation()).thenReturn(new Location(world, 5.5, 64, 0.5));
        when(entities.find(guard.id())).thenReturn(Optional.of(guardEntity));
        when(entities.getNpcId(hostileEntity)).thenReturn(Optional.of(hostile.id()));

        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder hold = new GuardOrder(guard.id(), GuardOrder.Type.HOLD_POSITION,
                List.of(), UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(hold)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        RelationshipPolicy policy = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6, policy, 8.0);
        controller.tick();

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(guardEntity).teleport(destination.capture());
        assertTrue(destination.getValue().getX() > origin.getX());
        assertTrue(destination.getValue().getX() <= 1.11);
    }

    @Test
    void pursuesVerifiedHostileNpcEvenWhenGuardHasNoAssignedOrder() {
        LivingEntity guardEntity = mock(LivingEntity.class);
        LivingEntity hostileEntity = mock(LivingEntity.class);
        NpcRecord hostile = NpcRecord.create(
                NpcType.GUARD, "Hostile", null, "enemy-town", "enemy-nation", "{}");
        registry.register(hostile);

        Location origin = new Location(world, 0.5, 64, 0.5);
        when(guardEntity.isValid()).thenReturn(true);
        when(guardEntity.getLocation()).thenReturn(origin);
        when(guardEntity.getNearbyEntities(8.0, 8.0, 8.0))
                .thenReturn(List.of(hostileEntity));
        when(hostileEntity.isValid()).thenReturn(true);
        when(hostileEntity.isDead()).thenReturn(false);
        when(hostileEntity.getWorld()).thenReturn(world);
        when(hostileEntity.getLocation()).thenReturn(new Location(world, 5.5, 64, 0.5));
        when(entities.find(guard.id())).thenReturn(Optional.of(guardEntity));
        when(entities.getNpcId(hostileEntity)).thenReturn(Optional.of(hostile.id()));

        RelationshipPolicy policy = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6, policy, 8.0);
        controller.tick();

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(guardEntity).teleport(destination.capture());
        assertTrue(destination.getValue().getX() > origin.getX());
        assertTrue(destination.getValue().getX() <= 1.11);
    }

    @Test
    void doesNotPursueUnknownOrUnregisteredTargets() {
        LivingEntity guardEntity = mock(LivingEntity.class);
        LivingEntity unknownEntity = mock(LivingEntity.class);
        Location origin = new Location(world, 0.5, 64, 0.5);
        when(guardEntity.isValid()).thenReturn(true);
        when(guardEntity.getLocation()).thenReturn(origin);
        when(guardEntity.getNearbyEntities(8.0, 8.0, 8.0))
                .thenReturn(List.of(unknownEntity));
        when(unknownEntity.isValid()).thenReturn(true);
        when(unknownEntity.isDead()).thenReturn(false);
        when(unknownEntity.getWorld()).thenReturn(world);
        when(unknownEntity.getLocation()).thenReturn(new Location(world, 5.5, 64, 0.5));
        when(entities.find(guard.id())).thenReturn(Optional.of(guardEntity));
        when(entities.getNpcId(unknownEntity)).thenReturn(Optional.empty());

        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder patrol = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(new GuardOrder.Waypoint(world.getUID(), 0.5, 64, 5.5, 0, 0),
                        new GuardOrder.Waypoint(world.getUID(), 0.5, 64, 10.5, 0, 0)),
                UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(patrol)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        RelationshipPolicy policy = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6, policy, 8.0);
        controller.tick();

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(guardEntity).teleport(destination.capture());
        assertEquals(origin.getX(), destination.getValue().getX(), 0.001);
        assertTrue(destination.getValue().getZ() > origin.getZ());
    }


    @Test
    void pursuesPlayerOnlyWhenTownySubjectIsVerifiedHostile() {
        LivingEntity guardEntity = mock(LivingEntity.class);
        Player enemyPlayer = mock(Player.class);
        Location origin = new Location(world, 0.5, 64, 0.5);
        when(guardEntity.isValid()).thenReturn(true);
        when(guardEntity.isDead()).thenReturn(false);
        when(guardEntity.getLocation()).thenReturn(origin);
        when(guardEntity.getNearbyEntities(8.0, 8.0, 8.0)).thenReturn(List.of(enemyPlayer));
        when(enemyPlayer.isValid()).thenReturn(true);
        when(enemyPlayer.isDead()).thenReturn(false);
        when(enemyPlayer.getWorld()).thenReturn(world);
        when(enemyPlayer.getLocation()).thenReturn(new Location(world, 5.5, 64, 0.5));
        when(entities.find(guard.id())).thenReturn(Optional.of(guardEntity));

        RelationshipPolicy policy = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6);
        controller.setPursuitPolicy(policy, 8.0, player ->
                Optional.of(new RelationshipPolicy.Subject(
                        player.getUniqueId(), "enemy-town", "enemy-nation")));
        controller.tick();

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(guardEntity).teleport(destination.capture());
        assertTrue(destination.getValue().getX() > origin.getX());
    }

    @Test
    void resumesDurablePatrolWhenTargetLosesHostileStatus() {
        LivingEntity guardEntity = mock(LivingEntity.class);
        LivingEntity hostileEntity = mock(LivingEntity.class);
        NpcRecord hostile = NpcRecord.create(
                NpcType.GUARD, "Hostile", null, "enemy-town", "enemy-nation", "{}");
        registry.register(hostile);

        Location origin = new Location(world, 0.5, 64, 0.5);
        when(guardEntity.isValid()).thenReturn(true);
        when(guardEntity.getLocation()).thenReturn(origin);
        when(guardEntity.getNearbyEntities(8.0, 8.0, 8.0))
                .thenReturn(List.of(hostileEntity));
        when(hostileEntity.isValid()).thenReturn(true);
        when(hostileEntity.isDead()).thenReturn(false);
        when(hostileEntity.getWorld()).thenReturn(world);
        when(hostileEntity.getLocation()).thenReturn(new Location(world, 5.5, 64, 0.5));
        when(entities.find(guard.id())).thenReturn(Optional.of(guardEntity));
        when(entities.getNpcId(hostileEntity)).thenReturn(Optional.of(hostile.id()));

        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder patrol = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(new GuardOrder.Waypoint(world.getUID(), 0.5, 64, 5.5, 0, 0),
                        new GuardOrder.Waypoint(world.getUID(), 0.5, 64, 10.5, 0, 0)),
                UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(patrol)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        java.util.concurrent.atomic.AtomicReference<RelationshipPolicy.DiplomacyRelation> relation =
                new java.util.concurrent.atomic.AtomicReference<>(
                        RelationshipPolicy.DiplomacyRelation.HOSTILE);
        RelationshipPolicy policy = new RelationshipPolicy((first, second) -> relation.get());
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6, policy, 8.0);

        controller.tick();
        relation.set(RelationshipPolicy.DiplomacyRelation.NEUTRAL);
        controller.tick();

        ArgumentCaptor<Location> destinations = ArgumentCaptor.forClass(Location.class);
        verify(guardEntity, times(2)).teleport(destinations.capture());
        assertTrue(destinations.getAllValues().get(0).getX() > origin.getX(),
                "first movement should pursue the hostile target");
        assertTrue(destinations.getAllValues().get(1).getZ() > origin.getZ(),
                "second movement should resume the persisted patrol order");
    }

    @Test
    void doesNotPursueDeadRegisteredTarget() {
        LivingEntity guardEntity = mock(LivingEntity.class);
        LivingEntity deadEntity = mock(LivingEntity.class);
        NpcRecord hostile = NpcRecord.create(
                NpcType.GUARD, "Dead target", null, "enemy-town", "enemy-nation", "{}");
        registry.register(hostile);

        Location origin = new Location(world, 0.5, 64, 0.5);
        when(guardEntity.isValid()).thenReturn(true);
        when(guardEntity.getLocation()).thenReturn(origin);
        when(guardEntity.getNearbyEntities(8.0, 8.0, 8.0))
                .thenReturn(List.of(deadEntity));
        when(deadEntity.isValid()).thenReturn(true);
        when(deadEntity.isDead()).thenReturn(true);
        when(deadEntity.getWorld()).thenReturn(world);
        when(deadEntity.getLocation()).thenReturn(new Location(world, 5.5, 64, 0.5));
        when(entities.find(guard.id())).thenReturn(Optional.of(guardEntity));
        when(entities.getNpcId(deadEntity)).thenReturn(Optional.of(hostile.id()));

        RelationshipPolicy policy = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardOrderController controller = new GuardOrderController(
                plugin, registry, entities, orders, 4, 0.6, policy, 8.0);
        controller.tick();

        verify(guardEntity, never()).teleport(any(Location.class));
    }


    @Test
    void failsClosedWhenTerritoryLookupThrowsDuringMovement() {
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(5.5), point(10.5)), UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        orders = new GuardOrderService(registry, repository);
        orders.loadAll().join();

        GuardOrderController controller =
                new GuardOrderController(plugin, registry, entities, orders, 4, 0.6);
        controller.setTerritoryAccess((location, nationId) -> {
            throw new IllegalStateException("Towny lookup unavailable");
        });

        assertDoesNotThrow(controller::tick);
        verify(entity, never()).teleport(any(Location.class));
    }

    @Test
    void deadGuardDoesNotMoveOrPursue() {
        when(entity.isDead()).thenReturn(true);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder patrol = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(5.5), point(10.5)), UUID.randomUUID(), 100L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(patrol)));
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
