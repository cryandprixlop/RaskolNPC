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
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcDeletionRepository;
import ru.raskolnpc.storage.NpcRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

class NpcRecoveryIntegrationTest {
    private ServerMock server;
    private Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityProvider provider;
    private NpcRepository repository;
    private NpcDeletionRepository deletions;
    private NpcEntityManager entities;
    private NpcRecoveryService recovery;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("recovery-integration");
        registry = new NpcRegistry();
        provider = mock(NpcEntityProvider.class);
        NpcEntityTagger tagger = mock(NpcEntityTagger.class);
        repository = mock(NpcRepository.class);
        deletions = mock(NpcDeletionRepository.class);

        when(repository.update(any(NpcRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(deletions.findPending())
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        entities = new NpcEntityManager(plugin, provider, tagger, repository, registry);
        recovery = new NpcRecoveryService(plugin, registry, entities, deletions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void restoresNpcAtPersistedLocationWhenItsChunkIsAlreadyLoaded() {
        Location savedLocation = new Location(world, 4.5, 72, 9.5, 90, 0);
        NpcRecord record = NpcRecord.create(
                NpcType.GUARD, "Restored guard", null, null, null, null)
                .withEntityBinding(UUID.randomUUID(), NpcSpawnLocation.from(savedLocation));
        registry.put(record);

        Entity restoredEntity = mock(Entity.class);
        UUID restoredEntityId = UUID.randomUUID();
        when(restoredEntity.getUniqueId()).thenReturn(restoredEntityId);
        when(provider.spawn(eq(record), any(Location.class))).thenReturn(restoredEntity);

        world.loadChunk(0, 0);
        assertTrue(world.isChunkLoaded(0, 0), "Test setup must load chunk 0,0");
        assertEquals(world.getUID(), savedLocation.getWorld().getUID());
        CompletableFuture<Void> result = recovery.recover();
        server.getScheduler().performTicks(4);

        assertTrue(result.isDone());
        assertDoesNotThrow(result::join);
        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);
        verify(provider).spawn(eq(record), locationCaptor.capture());
        Location actualLocation = locationCaptor.getValue();
        assertNotNull(actualLocation.getWorld(), "Recovery must resolve the saved world");
        assertEquals(world.getUID(), actualLocation.getWorld().getUID());
        assertEquals(savedLocation.getX(), actualLocation.getX());
        assertEquals(savedLocation.getY(), actualLocation.getY());
        assertEquals(savedLocation.getZ(), actualLocation.getZ());
        verify(repository).update(argThat(updated ->
                updated.id().equals(record.id())
                        && restoredEntityId.equals(updated.entityUuid())));
        assertEquals(restoredEntityId, registry.get(record.id()).orElseThrow().entityUuid());
    }


    @Test
    void restoresNpcCreatedBeforeEntityWasSpawned() {
        Location savedLocation = new Location(world, 5.5, 72, 9.5, 45, 0);
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Interrupted guard",
                null, null, null, null, NpcSpawnLocation.from(savedLocation))
                .withState(ru.raskolnpc.npc.model.NpcState.ACTIVE);
        assertNull(record.entityUuid());
        assertEquals(ru.raskolnpc.npc.model.NpcState.ACTIVE, record.state());
        registry.put(record);

        Entity restoredEntity = mock(Entity.class);
        UUID restoredEntityId = UUID.randomUUID();
        when(restoredEntity.getUniqueId()).thenReturn(restoredEntityId);
        when(provider.spawn(eq(record), any(Location.class))).thenReturn(restoredEntity);

        world.loadChunk(0, 0);
        CompletableFuture<Void> result = recovery.recover();
        server.getScheduler().performTicks(4);

        assertTrue(result.isDone());
        assertDoesNotThrow(result::join);
        verify(provider).spawn(eq(record), argThat(location ->
                location.getWorld().getUID().equals(world.getUID())
                        && location.getX() == savedLocation.getX()
                        && location.getY() == savedLocation.getY()
                        && location.getZ() == savedLocation.getZ()));
        verify(repository).update(argThat(updated ->
                updated.id().equals(record.id())
                        && restoredEntityId.equals(updated.entityUuid())));
    }

    @Test
    void doesNotAttemptRestoreWhenSavedWorldIsUnavailable() {
        NpcRecord record = NpcRecord.create(
                NpcType.GUARD, "Missing world guard", null, null, null, null)
                .withEntityBinding(UUID.randomUUID(),
                        new NpcSpawnLocation(UUID.randomUUID(), 0, 70, 0, 0, 0));
        registry.put(record);

        CompletableFuture<Void> result = recovery.recover();
        server.getScheduler().performOneTick();

        assertTrue(result.isDone());
        assertDoesNotThrow(result::join);
        verifyNoInteractions(provider, repository);
    }
}
