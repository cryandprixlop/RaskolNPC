package ru.raskolnpc.npc.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperNpcEntityProviderTest {
    private Plugin plugin;
    private World world;
    private NpcEntityTagger tagger;
    private PaperNpcEntityProvider provider;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("RaskolNPC");
        world = mock(World.class);
        tagger = mock(NpcEntityTagger.class);
        provider = new PaperNpcEntityProvider(plugin, tagger);
    }

    @Test
    void spawnsEachNpcTypeAsItsConfiguredPaperEntity() {
        assertSpawn(NpcType.RESIDENT, EntityType.VILLAGER);
        assertSpawn(NpcType.GUARD, EntityType.IRON_GOLEM);
        assertSpawn(NpcType.COMPANION, EntityType.WOLF);
    }

    @Test
    void spawnedMobIsPersistentNamedAndTaggedAndHasAiDisabled() {
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Mira", null, null, null, null);
        Mob entity = mock(Mob.class);
        Location location = new Location(world, 4, 70, 8);
        when(world.spawnEntity(location, EntityType.IRON_GOLEM, false)).thenReturn(entity);

        Entity spawned = provider.spawn(record, location);

        assertSame(entity, spawned);
        verify(entity).setCustomName("Mira");
        verify(entity).setCustomNameVisible(true);
        verify(entity).setPersistent(true);
        verify(entity).setRemoveWhenFarAway(false);
        verify(entity).setAI(false);
        verify(tagger).tag(entity, record.id());
    }

    @Test
    void rejectsLocationWithoutWorld() {
        NpcRecord record = NpcRecord.create(NpcType.RESIDENT, "Mira", null, null, null, null);
        assertThrows(NpcEntityException.class,
                () -> provider.spawn(record, new Location(null, 0, 64, 0)));
        verifyNoInteractions(world, tagger);
    }

    private void assertSpawn(NpcType npcType, EntityType expectedType) {
        NpcRecord record = NpcRecord.create(npcType, "Test NPC", null, null, null, null);
        Entity entity = mock(Entity.class);
        Location location = new Location(world, 0, 65, 0);
        when(world.spawnEntity(location, expectedType, false)).thenReturn(entity);

        Entity spawned = provider.spawn(record, location);

        assertSame(entity, spawned);
        verify(world).spawnEntity(location, expectedType, false);
        verify(tagger).tag(entity, record.id());
    }
}
