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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NpcEntityManagerTest {
    private ServerMock server;
    private Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityTagger tagger;
    private NpcEntityManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("manager-test");
        registry = new NpcRegistry();
        tagger = new NpcEntityTagger(plugin);
        NpcRepository repository = new CompletedNpcRepository();
        manager = new NpcEntityManager(plugin,
                new PaperNpcEntityProvider(plugin, tagger), tagger, repository, registry);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spawnsTracksFindsAndRemovesNpcEntity() {
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Sentinel", null, null, null, null);
        registry.register(record);

        Entity spawned = manager.spawn(record, new Location(world, 2, 70, 2));

        assertTrue(spawned.isValid());
        assertEquals(spawned, manager.find(record.id()).orElseThrow());
        assertEquals(record.id(), manager.getNpcId(spawned).orElseThrow());
        assertTrue(manager.remove(record.id()));
        assertFalse(spawned.isValid());
        assertTrue(manager.find(record.id()).isEmpty());
    }

    @Test
    void returnsFalseWhenNoEntityIsTrackedForNpc() {
        assertFalse(manager.remove(NpcId.create()));
    }

    private static final class CompletedNpcRepository implements NpcRepository {
        @Override
        public CompletableFuture<Void> insert(NpcRecord record) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<NpcRecord>> findById(NpcId id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<NpcRecord>> findAll() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Boolean> update(NpcRecord record) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> updateState(NpcId id, ru.raskolnpc.npc.model.NpcState state) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> delete(NpcId id) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
