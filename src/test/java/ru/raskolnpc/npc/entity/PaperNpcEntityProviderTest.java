package ru.raskolnpc.npc.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import static org.junit.jupiter.api.Assertions.*;

class PaperNpcEntityProviderTest {
    private ServerMock server;
    private Plugin plugin;
    private World world;
    private NpcEntityTagger tagger;
    private PaperNpcEntityProvider provider;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("npc-test");
        tagger = new NpcEntityTagger(plugin);
        provider = new PaperNpcEntityProvider(plugin, tagger);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spawnsEachNpcTypeAsItsConfiguredPaperEntity() {
        assertSpawn(NpcType.RESIDENT, EntityType.VILLAGER);
        assertSpawn(NpcType.GUARD, EntityType.IRON_GOLEM);
        assertSpawn(NpcType.COMPANION, EntityType.WOLF);
    }

    @Test
    void spawnedMobIsPersistentNamedAndTagged() {
        NpcRecord record = NpcRecord.create(NpcType.RESIDENT, "Mira", null, null, null, null);

        Entity entity = provider.spawn(record, new Location(world, 4, 70, 8));

        assertEquals("Mira", entity.getCustomName());
        assertTrue(entity.isCustomNameVisible());
        assertTrue(entity.isPersistent());
        assertEquals(record.id(), tagger.read(entity).orElseThrow());
        assertTrue(entity instanceof Mob);
        assertFalse(((Mob) entity).hasAI());
    }

    private void assertSpawn(NpcType npcType, EntityType expectedType) {
        NpcRecord record = NpcRecord.create(npcType, "Test NPC", null, null, null, null);

        Entity entity = provider.spawn(record, new Location(world, 0, 65, 0));

        assertEquals(expectedType, entity.getType());
        assertEquals(record.id(), tagger.read(entity).orElseThrow());
    }
}
