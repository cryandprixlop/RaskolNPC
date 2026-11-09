package ru.raskolnpc.npc.entity;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.npc.model.NpcId;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcEntityTaggerTest {
    private ServerMock server;
    private NpcEntityTagger tagger;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        tagger = new NpcEntityTagger(MockBukkit.createMockPlugin());
        world = server.addSimpleWorld("tagger-test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void writesAndReadsNpcIdentifierFromPersistentData() {
        Entity entity = world.spawnEntity(world.getSpawnLocation(), EntityType.VILLAGER);
        NpcId id = new NpcId(UUID.randomUUID());

        tagger.tag(entity, id);

        assertEquals(id, tagger.read(entity).orElseThrow());
        assertTrue(tagger.isNpc(entity));
        assertTrue(entity.isPersistent());
    }

    @Test
    void returnsEmptyForEntityWithoutNpcTag() {
        Entity entity = world.spawnEntity(world.getSpawnLocation(), EntityType.WOLF);

        assertTrue(tagger.read(entity).isEmpty());
        assertFalse(tagger.isNpc(entity));
    }
}
