package ru.raskolnpc.npc.lifecycle;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.entity.NpcEntityProvider;
import ru.raskolnpc.npc.entity.NpcEntityTagger;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcDeletionRepository;
import ru.raskolnpc.storage.NpcRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NpcRecoveryServiceTest {
    private ServerMock server;
    private Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private NpcEntityProvider provider;
    private NpcEntityTagger tagger;
    private NpcRepository repository;
    private NpcDeletionRepository deletions;
    private NpcRecoveryService recovery;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("recovery-test");
        registry = new NpcRegistry();
        provider = mock(NpcEntityProvider.class);
        tagger = mock(NpcEntityTagger.class);
        repository = mock(NpcRepository.class);
        deletions = mock(NpcDeletionRepository.class);
        when(repository.update(any(NpcRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(deletions.findPending())
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(deletions.complete(any(NpcId.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        entities = new NpcEntityManager(plugin, provider, tagger, repository, registry);
        recovery = new NpcRecoveryService(plugin, registry, entities, deletions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void doesNotSpawnNpcWhenItsSpawnChunkIsNotLoaded() {
        NpcRecord record = recordAt(new Location(world, 160, 70, 160));
        registry.put(record);

        CompletableFuture<Void> result = recovery.recover();
        server.getScheduler().performOneTick();

        assertTrue(result.isDone());
        assertDoesNotThrow(result::join);
        verifyNoInteractions(provider);
    }

    @Test
    void removesPendingNpcWhenItsChunkLoadsAfterRecoveryStarts() {
        NpcId id = NpcId.create();
        Entity deletedEntity = mock(Entity.class);
        when(deletedEntity.isValid()).thenReturn(true);
        when(deletedEntity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(tagger.read(deletedEntity)).thenReturn(Optional.of(id));
        when(deletions.findPending())
                .thenReturn(CompletableFuture.completedFuture(List.of(id)));

        CompletableFuture<Void> startup = recovery.recover();
        server.getScheduler().performOneTick();
        assertDoesNotThrow(startup::join);

        recovery.onEntitiesLoad(world.getChunkAt(0, 0), List.of(deletedEntity));
        server.getScheduler().performOneTick();

        verify(deletedEntity).remove();
        verify(deletions).complete(id);
    }

    private NpcRecord recordAt(Location location) {
        NpcRecord record = NpcRecord.create(
                NpcType.GUARD, "Recovery sentinel", null, null, null, null);
        return record.withEntityBinding(UUID.randomUUID(), NpcSpawnLocation.from(location));
    }
}
