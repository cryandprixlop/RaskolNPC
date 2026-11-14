package ru.raskolnpc.guard;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.guard.model.GuardSquad;
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
import static org.mockito.Mockito.*;

class GuardSquadOrderTest {
    @Test
    void appliesOneSharedOrderToEveryMemberAndReportsPartialFailure() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord first = guard(registry);
        NpcRecord second = guard(registry);
        UUID squadId = UUID.randomUUID();
        GuardSquad squad = new GuardSquad(squadId, "North Watch", UUID.randomUUID(),
                System.currentTimeMillis(), List.of(first.id(), second.id()));
        GuardSquadRepository squads = mock(GuardSquadRepository.class);
        when(squads.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(squad)));

        GuardOrderRepository orderRepository = mock(GuardOrderRepository.class);
        when(orderRepository.save(any())).thenAnswer(invocation -> {
            GuardOrder order = invocation.getArgument(0);
            return CompletableFuture.completedFuture(order.npcId().equals(first.id()));
        });
        GuardOrderService orders = new GuardOrderService(registry, orderRepository);
        GuardSquadService service = new GuardSquadService(registry, squads, 10);

        GuardSquadService.SquadOrderResult result = service.issueOrder(squadId, orders,
                GuardOrder.Type.HOLD_POSITION, List.of(), UUID.randomUUID()).join();

        assertEquals(2, result.total());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
        verify(orderRepository, times(2)).save(any());
    }

    @Test
    void rejectsUnknownSquadWithoutIssuingAnyOrders() {
        GuardSquadRepository squads = mock(GuardSquadRepository.class);
        when(squads.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        GuardOrderRepository orderRepository = mock(GuardOrderRepository.class);
        GuardSquadService service = new GuardSquadService(new NpcRegistry(), squads, 10);

        assertThrows(RuntimeException.class, () -> service.issueOrder(UUID.randomUUID(),
                new GuardOrderService(new NpcRegistry(), orderRepository),
                GuardOrder.Type.HOLD_POSITION, List.of(), UUID.randomUUID()).join());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void clearsPersistedOrdersForAllMembers() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord first = guard(registry);
        UUID squadId = UUID.randomUUID();
        GuardSquad squad = new GuardSquad(squadId, "Gate", UUID.randomUUID(),
                System.currentTimeMillis(), List.of(first.id()));
        GuardSquadRepository squads = mock(GuardSquadRepository.class);
        when(squads.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(squad)));
        GuardOrderRepository orderRepository = mock(GuardOrderRepository.class);
        when(orderRepository.delete(first.id())).thenReturn(CompletableFuture.completedFuture(true));
        GuardSquadService service = new GuardSquadService(registry, squads, 10);

        GuardSquadService.SquadOrderResult result = service.clearOrders(squadId,
                new GuardOrderService(registry, orderRepository)).join();

        assertEquals(new GuardSquadService.SquadOrderResult(1, 1, 0), result);
        verify(orderRepository).delete(first.id());
    }

    private static NpcRecord guard(NpcRegistry registry) {
        NpcRecord guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(guard);
        return guard;
    }
}
