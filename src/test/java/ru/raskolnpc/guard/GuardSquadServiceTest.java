package ru.raskolnpc.guard;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardSquadRepository;

import java.util.UUID;
import java.util.Optional;
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
    @Test
    void refreshReplacesOldMembershipAndElectsNextStableLeader() {
        NpcRegistry registry = new NpcRegistry();
        NpcId first = NpcId.fromString("00000000-0000-0000-0000-000000000001");
        NpcId second = NpcId.fromString("00000000-0000-0000-0000-000000000002");
        NpcId third = NpcId.fromString("00000000-0000-0000-0000-000000000003");
        for (NpcId id : List.of(first, second, third)) {
            registry.register(new NpcRecord(id, NpcType.GUARD, "Guard", null, "town", "nation", "{}"));
        }
        UUID squadId = UUID.randomUUID();
        GuardSquad before = new GuardSquad(
                squadId, "Watch", UUID.randomUUID(), 100L, List.of(second, first, third));
        GuardSquad after = new GuardSquad(
                squadId, "Watch", before.createdBy(), 100L, List.of(third, second));
        AtomicReference<List<GuardSquad>> persisted = new AtomicReference<>(List.of(before));
        GuardSquadRepository repository = mock(GuardSquadRepository.class);
        when(repository.findAll()).thenAnswer(invocation ->
                CompletableFuture.completedFuture(persisted.get()));
        GuardSquadService service = new GuardSquadService(registry, repository, 10);

        service.refreshMemberships().join();
        assertEquals(Optional.of(first), service.leaderFor(second));
        assertEquals(Optional.of(squadId), service.squadIdFor(first));

        persisted.set(List.of(after));
        service.refreshMemberships().join();

        assertEquals(Optional.empty(), service.squadIdFor(first));
        assertEquals(Optional.of(second), service.leaderFor(third));
        assertEquals(Optional.of(squadId), service.squadIdFor(second));
    }

    @Test
    void failedMembershipRefreshKeepsLastKnownGoodSnapshot() {
        NpcRegistry registry = new NpcRegistry();
        NpcId guard = NpcId.fromString("00000000-0000-0000-0000-000000000011");
        registry.register(new NpcRecord(
                guard, NpcType.GUARD, "Guard", null, "town", "nation", "{}"));
        GuardSquad squad = new GuardSquad(
                UUID.randomUUID(), "Watch", UUID.randomUUID(), 100L, List.of(guard));
        GuardSquadRepository repository = mock(GuardSquadRepository.class);
        when(repository.findAll()).thenReturn(
                CompletableFuture.completedFuture(List.of(squad)),
                CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        GuardSquadService service = new GuardSquadService(registry, repository, 10);

        service.refreshMemberships().join();
        assertEquals(Optional.of(squad.id()), service.squadIdFor(guard));

        assertThrows(RuntimeException.class, () -> service.refreshMemberships().join());
        assertEquals(Optional.of(squad.id()), service.squadIdFor(guard));
    }

}