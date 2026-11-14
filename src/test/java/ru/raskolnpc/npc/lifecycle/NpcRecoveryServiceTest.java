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

    @Test
    void doesNotRecoverNpcWhileItsDeletionIsPending() {
        Location savedLocation = new Location(world, 160.5, 70, 160.5);
        NpcRecord record = recordAt(savedLocation);
        registry.put(record);
        when(deletions.findPending())
                .thenReturn(CompletableFuture.completedFuture(List.of(record.id())));

        CompletableFuture<Void> startup = recovery.recover();
        server.getScheduler().performOneTick();
        assertDoesNotThrow(startup::join);

        world.loadChunk(10, 10);
        recovery.onEntitiesLoad(world.getChunkAt(10, 10), List.of());
        server.getScheduler().performTicks(3);

        verifyNoInteractions(provider);
        verify(deletions, never()).complete(record.id());
    }

    @Test
    void skipsPendingDeletionLookupForUnrelatedChunk() {
        NpcRecord record = recordAt(new Location(world, 160.5, 70, 160.5));
        registry.put(record);

        CompletableFuture<Void> startup = recovery.recover();
        server.getScheduler().performOneTick();
        assertDoesNotThrow(startup::join);
        clearInvocations(deletions);

        recovery.onEntitiesLoad(world.getChunkAt(0, 0), List.of());

        verify(deletions, never()).findPending();
    }

    @Test
    void doesNotKeepUnloadedNpcInActiveEntityTracking() {
        Entity loadedEntity = createTrackedEntity();
        NpcRecord record = recordFor(loadedEntity);
        registry.put(record);
        when(tagger.read(loadedEntity)).thenReturn(Optional.of(record.id()));

        entities.trackLoaded(loadedEntity);
        assertEquals(loadedEntity, entities.find(record.id()).orElseThrow());

        assertTrue(world.unloadChunk(0, 0));
        recovery.onEntitiesUnload(List.of(loadedEntity));

        assertTrue(entities.find(record.id()).isEmpty());
        assertEquals(loadedEntity.getUniqueId(),
                registry.get(record.id()).orElseThrow().entityUuid());
        verifyNoInteractions(provider);
    }

    @Test
    void tracksReloadedNpcWithoutSpawningDuplicateEntity() {
        Entity loadedEntity = createTrackedEntity();
        NpcRecord record = recordFor(loadedEntity);
        registry.put(record);
        when(tagger.read(loadedEntity)).thenReturn(Optional.of(record.id()));

        entities.trackLoaded(loadedEntity);
        assertEquals(loadedEntity, entities.find(record.id()).orElseThrow());

        assertTrue(world.unloadChunk(0, 0));
        recovery.onEntitiesUnload(List.of(loadedEntity));
        assertTrue(entities.find(record.id()).isEmpty());

        world.loadChunk(0, 0);
        world.addEntity(loadedEntity);
        assertSame(loadedEntity, world.getEntity(loadedEntity.getUniqueId()));
        recovery.recover();
        server.getScheduler().performOneTick();

        recovery.onEntitiesLoad(world.getChunkAt(0, 0), List.of(loadedEntity));
        server.getScheduler().performTicks(3);

        assertEquals(loadedEntity, entities.find(record.id()).orElseThrow());
        verifyNoInteractions(provider);
        verify(repository, never()).update(any(NpcRecord.class));
    }

    @Test
    void restoresNpcWhenItsSpawnChunkLoadsAfterRecoveryStarts() {
        Location savedLocation = new Location(world, 160.5, 70, 160.5);
        NpcRecord record = recordAt(savedLocation);
        registry.put(record);

        CompletableFuture<Void> startup = recovery.recover();
        server.getScheduler().performOneTick();
        assertDoesNotThrow(startup::join);
        verifyNoInteractions(provider);

        Entity restoredEntity = mock(Entity.class);
        UUID restoredEntityId = UUID.randomUUID();
        when(restoredEntity.getUniqueId()).thenReturn(restoredEntityId);
        when(provider.spawn(eq(record), any(Location.class))).thenReturn(restoredEntity);

        world.loadChunk(10, 10);
        assertTrue(world.isChunkLoaded(10, 10));
        recovery.onEntitiesLoad(world.getChunkAt(10, 10), List.of());
        server.getScheduler().performTicks(4);

        verify(provider).spawn(eq(record), argThat(location ->
                location.getWorld().getUID().equals(world.getUID())
                        && location.getX() == savedLocation.getX()
                        && location.getY() == savedLocation.getY()
                        && location.getZ() == savedLocation.getZ()));
        verify(repository).update(argThat(updated ->
                updated.id().equals(record.id())
                        && restoredEntityId.equals(updated.entityUuid())));
        assertEquals(restoredEntityId, registry.get(record.id()).orElseThrow().entityUuid());
    }

    @Test
    void restoresActiveNpcAfterItsBoundEntityDies() {
        Location savedLocation = new Location(world, 6.5, 70, 6.5);
        Entity deadEntity = mock(Entity.class);
        UUID deadEntityId = UUID.randomUUID();
        when(deadEntity.getUniqueId()).thenReturn(deadEntityId);
        NpcRecord record = recordAt(savedLocation).withEntityBinding(
                deadEntityId, NpcSpawnLocation.from(savedLocation));
        registry.put(record);
        when(tagger.read(deadEntity)).thenReturn(Optional.of(record.id()));

        Entity restoredEntity = world.spawnEntity(
                savedLocation, org.bukkit.entity.EntityType.ZOMBIE);
        when(provider.spawn(eq(record), any(Location.class))).thenReturn(restoredEntity);

        recovery.onEntityDeath(deadEntity);
        server.getScheduler().performTicks(4);

        verify(provider).spawn(eq(record), argThat(location ->
                location.getWorld().getUID().equals(world.getUID())
                        && location.getX() == savedLocation.getX()
                        && location.getY() == savedLocation.getY()
                        && location.getZ() == savedLocation.getZ()));
        assertEquals(restoredEntity.getUniqueId(),
                registry.get(record.id()).orElseThrow().entityUuid());
        assertEquals(restoredEntity, entities.find(record.id()).orElseThrow());
    }

    @Test
    void doesNotRestoreActiveNpcWhenItsDeletionIsPendingAfterDeath() {
        Location savedLocation = new Location(world, 6.5, 70, 6.5);
        Entity deadEntity = mock(Entity.class);
        UUID deadEntityId = UUID.randomUUID();
        when(deadEntity.getUniqueId()).thenReturn(deadEntityId);
        NpcRecord record = recordAt(savedLocation).withEntityBinding(
                deadEntityId, NpcSpawnLocation.from(savedLocation));
        registry.put(record);
        when(tagger.read(deadEntity)).thenReturn(Optional.of(record.id()));
        when(deletions.findPending()).thenReturn(
                CompletableFuture.completedFuture(List.of(record.id())));

        recovery.onEntityDeath(deadEntity);
        server.getScheduler().performTicks(3);

        verifyNoInteractions(provider);
        verify(deletions, never()).complete(record.id());
    }

    private Entity createTrackedEntity() {
        world.loadChunk(0, 0);
        Entity entity = world.createEntity(new Location(world, 2, 65, 2), Entity.class);
        world.addEntity(entity);
        when(entity.isValid()).thenReturn(true);
        return entity;
    }

    private NpcRecord recordFor(Entity entity) {
        return NpcRecord.create(
                        NpcType.GUARD, "Recovery sentinel", null, null, null, null)
                .withEntityBinding(entity.getUniqueId(),
                        NpcSpawnLocation.from(entity.getLocation()));
    }

    private NpcRecord recordAt(Location location) {
        return NpcRecord.create(NpcType.GUARD, "Recovery sentinel",
                null, null, null, null, NpcSpawnLocation.from(location));
    }
}
