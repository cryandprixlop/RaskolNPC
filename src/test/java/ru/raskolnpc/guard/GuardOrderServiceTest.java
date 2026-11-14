package ru.raskolnpc.guard;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardOrderRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardOrderServiceTest {
    @Test
    void persistsValidPatrolForRegisteredGuard() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = create(NpcType.GUARD);
        registry.register(guard);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        when(repository.save(any())).thenReturn(CompletableFuture.completedFuture(true));
        GuardOrderService service = new GuardOrderService(registry, repository);
        UUID world = UUID.randomUUID();

        GuardOrder result = service.issue(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(world, 0), point(world, 10)), UUID.randomUUID()).join();

        assertEquals(GuardOrder.Type.PATROL, result.type());
        assertEquals(2, result.waypoints().size());
        verify(repository).save(result);
    }

    @Test
    void rejectsPatrolWithLessThanTwoWaypoints() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = create(NpcType.GUARD);
        registry.register(guard);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrderService service = new GuardOrderService(registry, repository);

        assertThrows(RuntimeException.class, () -> service.issue(guard.id(),
                GuardOrder.Type.PATROL, List.of(point(UUID.randomUUID(), 0)),
                UUID.randomUUID()).join());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMixedWorldPatrolAndNonGuardNpc() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = create(NpcType.GUARD);
        NpcRecord resident = create(NpcType.RESIDENT);
        registry.register(guard);
        registry.register(resident);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrderService service = new GuardOrderService(registry, repository);

        assertThrows(RuntimeException.class, () -> service.issue(guard.id(),
                GuardOrder.Type.PATROL,
                List.of(point(UUID.randomUUID(), 0), point(UUID.randomUUID(), 5)),
                UUID.randomUUID()).join());
        assertThrows(RuntimeException.class, () -> service.issue(resident.id(),
                GuardOrder.Type.HOLD_POSITION, List.of(), UUID.randomUUID()).join());
        verifyNoInteractions(repository);
    }

    @Test
    void clearAndFindAreRestrictedToRegisteredGuards() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord resident = create(NpcType.RESIDENT);
        registry.register(resident);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrderService service = new GuardOrderService(registry, repository);

        assertTrue(service.find(resident.id()).join().isEmpty());
        assertFalse(service.clear(resident.id()).join());
        verifyNoInteractions(repository);
    }

    @Test
    void removesCachedOrdersWhenGuardIsDeletedOrRetyped() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = create(NpcType.GUARD);
        registry.register(guard);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.HOLD_POSITION,
                List.of(), UUID.randomUUID(), 1L);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(order)));
        GuardOrderService service = new GuardOrderService(registry, repository);

        assertEquals(1, service.loadAll().join());
        assertEquals(1, service.snapshot().size());
        registry.unregister(guard.id());

        service.retainRegisteredGuards();

        assertTrue(service.snapshot().isEmpty());
    }

    @Test
    void removesPersistedOrderWhenGuardIsDeletedDuringSave() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = create(NpcType.GUARD);
        registry.register(guard);
        GuardOrderRepository repository = mock(GuardOrderRepository.class);
        CompletableFuture<Boolean> pendingSave = new CompletableFuture<>();
        when(repository.save(any())).thenReturn(pendingSave);
        when(repository.delete(guard.id())).thenReturn(CompletableFuture.completedFuture(true));
        GuardOrderService service = new GuardOrderService(registry, repository);

        CompletableFuture<GuardOrder> issuing = service.issue(guard.id(),
                GuardOrder.Type.HOLD_POSITION, List.of(), UUID.randomUUID());
        registry.unregister(guard.id());
        pendingSave.complete(true);

        assertThrows(RuntimeException.class, issuing::join);
        verify(repository).delete(guard.id());
        assertTrue(service.snapshot().isEmpty());
    }

    private static NpcRecord create(NpcType type) {
        return NpcRecord.create(type, type.name(), null, "town", "nation", "{}");
    }

    private static GuardOrder.Waypoint point(UUID world, double x) {
        return new GuardOrder.Waypoint(world, x, 64, 0, 0, 0);
    }
}
