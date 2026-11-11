package ru.raskolnpc.npc.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.NpcRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NpcEntityManagerTest {
    private ServerMock server;
    private Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityTagger tagger;
    private NpcEntityProvider provider;
    private NpcRepository repository;
    private NpcEntityManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("manager-test");
        registry = new NpcRegistry();
        tagger = mock(NpcEntityTagger.class);
        provider = mock(NpcEntityProvider.class);
        repository = mock(NpcRepository.class);
        when(repository.update(any(NpcRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        manager = new NpcEntityManager(plugin, provider, tagger, repository, registry);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spawnsEntityAndRejectsDuplicateLiveNpc() {
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Sentinel", null, null, null, null);
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        Location location = new Location(world, 2, 70, 2);
        when(provider.spawn(record, location.clone())).thenReturn(entity);

        Entity spawned = manager.spawn(record, location);

        assertSame(entity, spawned);
        verify(provider).spawn(record, location.clone());
        assertThrows(NpcEntityException.class, () -> manager.spawn(record, location));
        verify(provider, times(1)).spawn(any(NpcRecord.class), any(Location.class));
    }


    @Test
    void clearRemovesTrackedEntitiesFromWorld() {
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Tracked", null, null, null, null);
        Location location = new Location(world, 4, 70, 4);
        Entity entity = world.spawnEntity(location, org.bukkit.entity.EntityType.ZOMBIE);
        when(provider.spawn(record, location.clone())).thenReturn(entity);

        manager.spawn(record, location);
        assertTrue(entity.isValid());

        manager.clear();

        assertFalse(entity.isValid());
    }

    @Test
    void removesEntityByPersistedUuidWithoutScanningWorldEntities() {
        NpcId id = NpcId.create();
        Entity entity = world.spawnEntity(
                new Location(world, 8, 70, 8), org.bukkit.entity.EntityType.ZOMBIE);
        NpcRecord record = NpcRecord.create(
                        NpcType.GUARD, "Persisted sentinel", null, null, null, null)
                .withEntityBinding(entity.getUniqueId(),
                        NpcSpawnLocation.from(entity.getLocation()));
        registry.put(record);
        when(tagger.read(entity)).thenReturn(Optional.of(id));

        assertTrue(manager.remove(id));
        assertFalse(entity.isValid());
        verify(tagger, times(2)).read(entity);
    }

    @Test
    void fallsBackToTaggedEntitySearchWhenPersistedUuidIsStale() {
        NpcId id = NpcId.create();
        Entity entity = world.spawnEntity(
                new Location(world, 12, 70, 12), org.bukkit.entity.EntityType.ZOMBIE);
        NpcRecord record = NpcRecord.create(
                        NpcType.GUARD, "Legacy sentinel", null, null, null, null)
                .withEntityBinding(UUID.randomUUID(),
                        NpcSpawnLocation.from(entity.getLocation()));
        registry.put(record);
        when(tagger.read(entity)).thenReturn(Optional.of(id));

        assertTrue(manager.remove(id));
        assertFalse(entity.isValid());
    }

    @Test
    void persistsUnloadLocationOnlyForTheCurrentEntityBinding() {
        NpcId id = NpcId.create();
        Entity entity = world.spawnEntity(
                new Location(world, 20, 75, 21), org.bukkit.entity.EntityType.ZOMBIE);
        UUID entityUuid = entity.getUniqueId();
        NpcSpawnLocation initial = NpcSpawnLocation.from(entity.getLocation());
        entity.teleport(new Location(world, 31.5, 76.0, -9.5, 90.0f, 0.0f));
        NpcSpawnLocation moved = NpcSpawnLocation.from(entity.getLocation());
        NpcRecord record = new NpcRecord(id, NpcType.GUARD, "Unload guard",
                null, null, null, NpcState.ACTIVE, "{}", null, null,
                entityUuid, initial);
        when(tagger.read(entity)).thenReturn(Optional.of(id));
        registry.put(record);
        CompletableFuture<Boolean> persisted = new CompletableFuture<>();
        when(repository.updateEntityLocationIfBindingMatches(
                id, entityUuid, moved)).thenReturn(persisted);

        manager.untrackAndPersistLocation(entity);

        verify(repository).updateEntityLocationIfBindingMatches(id, entityUuid, moved);
        persisted.complete(true);
        server.getScheduler().performOneTick();

        assertEquals(entityUuid, registry.get(id).orElseThrow().entityUuid());
        assertEquals(moved, registry.get(id).orElseThrow().spawnLocation());
    }

    @Test
    void delayedUnloadCompletionDoesNotOverwriteANewerEntityBinding() {
        NpcId id = NpcId.create();
        Entity entity = world.spawnEntity(
                new Location(world, 22, 75, 23), org.bukkit.entity.EntityType.ZOMBIE);
        UUID oldUuid = entity.getUniqueId();
        UUID newUuid = UUID.randomUUID();
        NpcRecord original = new NpcRecord(id, NpcType.GUARD, "Racing guard",
                null, null, null, NpcState.ACTIVE, "{}", null, null,
                oldUuid, NpcSpawnLocation.from(entity.getLocation()));
        NpcRecord replacement = original.withEntityBinding(newUuid,
                new NpcSpawnLocation(world.getUID(), 80, 70, 80, 0, 0));
        when(tagger.read(entity)).thenReturn(Optional.of(id));
        registry.put(original);
        CompletableFuture<Boolean> persisted = new CompletableFuture<>();
        when(repository.updateEntityLocationIfBindingMatches(
                eq(id), eq(oldUuid), any(NpcSpawnLocation.class))).thenReturn(persisted);

        manager.untrackAndPersistLocation(entity);
        registry.put(replacement);
        persisted.complete(true);
        server.getScheduler().performOneTick();

        assertSame(replacement, registry.get(id).orElseThrow());
        assertEquals(newUuid, registry.get(id).orElseThrow().entityUuid());
    }

    @Test
    void ignoresUnloadFromAnEntityWithAStaleBinding() {
        NpcId id = NpcId.create();
        UUID persistedUuid = UUID.randomUUID();
        Entity entity = world.spawnEntity(
                new Location(world, 24, 75, 25), org.bukkit.entity.EntityType.ZOMBIE);
        NpcRecord record = new NpcRecord(id, NpcType.GUARD, "Stale guard",
                null, null, null, NpcState.ACTIVE, "{}", null, null,
                persistedUuid, NpcSpawnLocation.from(entity.getLocation()));
        when(tagger.read(entity)).thenReturn(Optional.of(id));
        registry.put(record);

        manager.untrackAndPersistLocation(entity);

        verify(repository, never()).updateEntityLocationIfBindingMatches(
                any(), any(), any());
        assertSame(record, registry.get(id).orElseThrow());
    }

    @Test
    void returnsFalseWhenNoEntityIsTrackedForNpc() {
        when(tagger.read(any(Entity.class))).thenReturn(Optional.empty());

        assertFalse(manager.remove(NpcId.create()));
        verifyNoInteractions(provider);
    }
}
