package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class GuardCombatControllerTest {
    private ServerMock server;
    private Plugin plugin;
    private World world;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private LivingEntity attacker;
    private LivingEntity target;
    private NpcRecord guard;
    private NpcRecord enemy;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("guard-combat");
        registry = new NpcRegistry();
        guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town-a", "nation-a", "{}");
        enemy = NpcRecord.create(NpcType.GUARD, "Enemy", null, "town-b", "nation-b", "{}");
        registry.register(guard);
        registry.register(enemy);

        attacker = mock(LivingEntity.class);
        target = mock(LivingEntity.class);
        when(attacker.isValid()).thenReturn(true);
        when(attacker.isDead()).thenReturn(false);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(attacker.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 2.5, 64, 0.5));
        when(attacker.getNearbyEntities(12.0, 12.0, 12.0)).thenReturn(List.of(target));

        entities = mock(NpcEntityManager.class);
        when(entities.find(guard.id())).thenReturn(Optional.of(attacker));
        when(entities.getNpcId(target)).thenReturn(Optional.of(enemy.id()));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void damagesOnlyRegisteredNpcWithVerifiedHostileDiplomacy() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        controller.tick();

        verify(target).damage(2.0, attacker);
    }

    @Test
    void ignoresNeutralAndUnknownDiplomacy() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.UNKNOWN);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void ignoresEntitiesWithoutRaskolNpcIdentity() {
        when(entities.getNpcId(target)).thenReturn(Optional.empty());
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        assertDoesNotThrow(controller::tick);

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }
}
