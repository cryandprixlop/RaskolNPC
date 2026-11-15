package ru.raskolnpc.guard;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardSquadRepository;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardSquadServiceTest {
    @Test
    void createsNamedSquadAndDelegatesGuardMembership() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(guard);
        GuardSquadRepository repository = mock(GuardSquadRepository.class);
        UUID creator = UUID.randomUUID();
        AtomicReference<UUID> createdSquadId = new AtomicReference<>();
        when(repository.create(any(), eq("Watch"), eq(creator), anyLong()))
                .thenAnswer(invocation -> {
                    createdSquadId.set(invocation.getArgument(0));
                    return CompletableFuture.completedFuture(true);
                });
        when(repository.addMember(any(), eq(guard.id()), eq(8)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.findAll()).thenAnswer(invocation ->
                CompletableFuture.completedFuture(List.of(new GuardSquad(
                        createdSquadId.get(), "Watch", creator, 100L, List.of(guard.id())))));
        GuardSquadService service = new GuardSquadService(registry, repository, 8);

        UUID squadId = service.create(" Watch ", creator).join();
        assertTrue(service.add(squadId, guard.id()).join());
        verify(repository).addMember(squadId, guard.id(), 8);
    }

    @Test
    void rejectsNonGuardAndInvalidSquadLimitsBeforePersistence() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident", null, "town", "nation", "{}");
        registry.register(resident);
        GuardSquadRepository repository = mock(GuardSquadRepository.class);

        assertThrows(IllegalArgumentException.class,
                () -> new GuardSquadService(registry, repository, 1));
        GuardSquadService service = new GuardSquadService(registry, repository, 10);
        assertThrows(RuntimeException.class,
                () -> service.add(UUID.randomUUID(), resident.id()).join());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBlankOrOversizedSquadNames() {
        GuardSquadRepository repository = mock(GuardSquadRepository.class);
        GuardSquadService service = new GuardSquadService(new NpcRegistry(), repository, 10);
        assertThrows(RuntimeException.class,
                () -> service.create(" ", UUID.randomUUID()).join());
        assertThrows(RuntimeException.class,
                () -> service.create("x".repeat(33), UUID.randomUUID()).join());
        verifyNoInteractions(repository);
    }
}