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
    void returnsFalseWhenNoEntityIsTrackedForNpc() {
        when(tagger.read(any(Entity.class))).thenReturn(Optional.empty());

        assertFalse(manager.remove(NpcId.create()));
        verifyNoInteractions(provider);
    }
}
