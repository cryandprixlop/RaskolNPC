package ru.raskolnpc.npc.lifecycle;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcDeletionRepository;
import ru.raskolnpc.storage.NpcRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NpcLifecycleCapacityTest {

    @Test
    void allowsExactlyTheConfiguredNumberOfPendingAndRegisteredNpcs() {
        NpcRepository repository = mock(NpcRepository.class);
        NpcRegistry registry = new NpcRegistry();
        List<CompletableFuture<Void>> inserts = new ArrayList<>();
        when(repository.insert(any(NpcRecord.class))).thenAnswer(invocation -> {
            CompletableFuture<Void> insert = new CompletableFuture<>();
            inserts.add(insert);
            return insert;
        });
        NpcLifecycleService lifecycle = lifecycle(repository, registry, 150);

        List<CompletableFuture<NpcRecord>> creations = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            creations.add(lifecycle.create(NpcType.RESIDENT, "Resident " + i,
                    null, null, null, "{}"));
        }

        assertEquals(150, inserts.size());
        assertEquals(0, registry.size());
        assertTrue(lifecycle.create(NpcType.RESIDENT, "Overflow",
                null, null, null, "{}").isCompletedExceptionally());

        for (CompletableFuture<Void> insert : inserts) insert.complete(null);
        for (CompletableFuture<NpcRecord> creation : creations) assertNotNull(creation.join());
        assertEquals(150, registry.size());
    }

    @Test
    void releasesGlobalCapacityWhenPersistenceFails() {
        NpcRepository repository = mock(NpcRepository.class);
        NpcRegistry registry = new NpcRegistry();
        CompletableFuture<Void> failedInsert = new CompletableFuture<>();
        when(repository.insert(any(NpcRecord.class)))
                .thenReturn(failedInsert)
                .thenReturn(CompletableFuture.completedFuture(null));
        NpcLifecycleService lifecycle = lifecycle(repository, registry, 1);

        CompletableFuture<NpcRecord> failed = lifecycle.create(NpcType.RESIDENT,
                "First", null, null, null, "{}");
        assertTrue(lifecycle.create(NpcType.RESIDENT, "Blocked",
                null, null, null, "{}").isCompletedExceptionally());

        failedInsert.completeExceptionally(new IllegalStateException("database unavailable"));
        assertThrows(RuntimeException.class, failed::join);

        NpcRecord recovered = lifecycle.create(NpcType.RESIDENT,
                "Second", null, null, null, "{}").join();
        assertNotNull(recovered);
        assertEquals(1, registry.size());
    }

    private NpcLifecycleService lifecycle(NpcRepository repository,
                                          NpcRegistry registry, int maximum) {
        NpcLifecycleService lifecycle = new NpcLifecycleService(repository, registry,
                maximum, mock(NpcDeletionRepository.class));
        lifecycle.markReady();
        return lifecycle;
    }
}
