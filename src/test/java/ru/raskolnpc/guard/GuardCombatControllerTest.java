package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.integration.worldguard.WorldGuardProtectionAdapter;
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
    void doesNotAttackTargetOutsideSphericalDetectionRadius() {
        when(target.getLocation()).thenReturn(new Location(world, 11.5, 64, 11.5));
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }


    @Test
    void doesNotAttackDeadRegisteredTarget() {
        when(target.isDead()).thenReturn(true);
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void doesNotAttackRegisteredTargetInAnotherWorld() {
        World otherWorld = server.addSimpleWorld("guard-combat-other-world");
        when(target.getLocation()).thenReturn(new Location(otherWorld, 2.5, 64, 0.5));
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
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
    void damagesPlayerOnlyWhenTownyResolverConfirmsHostileNation() {
        Player enemyPlayer = mock(Player.class);
        when(enemyPlayer.isValid()).thenReturn(true);
        when(enemyPlayer.isDead()).thenReturn(false);
        when(enemyPlayer.getLocation()).thenReturn(new Location(world, 2.5, 64, 0.5));
        when(attacker.getNearbyEntities(12.0, 12.0, 12.0)).thenReturn(List.of(enemyPlayer));

        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0,
                1000L, player -> Optional.of(new RelationshipPolicy.Subject(
                        player.getUniqueId(), "enemy-town", "nation-b")));

        controller.tick();

        verify(enemyPlayer).damage(2.0, attacker);
    }

    @Test
    void ignoresPlayerWhenTownyMembershipCannotBeResolved() {
        Player unknownPlayer = mock(Player.class);
        when(unknownPlayer.isValid()).thenReturn(true);
        when(unknownPlayer.isDead()).thenReturn(false);
        when(unknownPlayer.getLocation()).thenReturn(new Location(world, 2.5, 64, 0.5));
        when(attacker.getNearbyEntities(12.0, 12.0, 12.0)).thenReturn(List.of(unknownPlayer));

        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0,
                1000L, player -> Optional.empty());

        controller.tick();

        verify(unknownPlayer, never()).damage(anyDouble(), any(Entity.class));
    }


    @Test
    void doesNotDamageTargetOutsideHiringNationTerritory() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);
        controller.setTerritoryAccess((location, nationId) ->
                location.getX() < 1.0 && "nation-a".equals(nationId));

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void doesNotDamageWhenGuardIsOutsideHiringNationEvenIfTargetIsInside() {
        when(attacker.getLocation()).thenReturn(new Location(world, 2.5, 64, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);
        controller.setTerritoryAccess((location, nationId) ->
                location.getX() < 1.0 && "nation-a".equals(nationId));

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void damagesTargetWhenBothGuardAndTargetAreInsideHiringNationTerritory() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);
        controller.setTerritoryAccess((location, nationId) ->
                "nation-a".equals(nationId));

        controller.tick();

        verify(target).damage(2.0, attacker);
    }

    @Test
    void failsClosedWhenTerritoryLookupThrows() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);
        controller.setTerritoryAccess((location, nationId) -> {
            throw new IllegalStateException("Towny lookup unavailable");
        });

        assertDoesNotThrow(controller::tick);

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void failsClosedWhenDiplomacyLookupThrowsDuringCombat() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> { throw new IllegalStateException("Diplomacy unavailable"); });
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0, 1000L);

        assertDoesNotThrow(controller::tick);
        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void failsClosedWhenPlayerMembershipResolverThrowsDuringCombat() {
        Player hostilePlayer = mock(Player.class);
        when(hostilePlayer.isValid()).thenReturn(true);
        when(hostilePlayer.isDead()).thenReturn(false);
        when(hostilePlayer.getLocation()).thenReturn(new Location(world, 2.5, 64, 0.5));
        when(attacker.getNearbyEntities(12.0, 12.0, 12.0)).thenReturn(List.of(hostilePlayer));
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0,
                1000L, player -> { throw new IllegalStateException("Towny unavailable"); });

        assertDoesNotThrow(controller::tick);
        verify(hostilePlayer, never()).damage(anyDouble(), any(Entity.class));
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

    @Test
    void requiresWorldGuardPermissionAtBothAttackLocationsWhenEnabled() {
        WorldGuardProtectionAdapter worldGuard = mock(WorldGuardProtectionAdapter.class);
        when(worldGuard.checkMobDamage(any(Location.class)))
                .thenReturn(WorldGuardProtectionAdapter.Decision.ALLOWED);
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, worldGuard, 4, 12.0, 2.0,
                1000L, ru.raskolnpc.integration.PlayerSubjectResolver.none(), true);

        controller.tick();

        verify(target).damage(2.0, attacker);
        verify(worldGuard, times(2)).checkMobDamage(any(Location.class));
    }

    @Test
    void blocksCombatWhenWorldGuardDeniesAttackerLocation() {
        WorldGuardProtectionAdapter worldGuard = mock(WorldGuardProtectionAdapter.class);
        when(worldGuard.checkMobDamage(any(Location.class)))
                .thenReturn(WorldGuardProtectionAdapter.Decision.DENIED);
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, worldGuard, 4, 12.0, 2.0,
                1000L, ru.raskolnpc.integration.PlayerSubjectResolver.none(), true);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
        verify(worldGuard, times(1)).checkMobDamage(any(Location.class));
    }

    @Test
    void blocksCombatWhenWorldGuardReturnsUnknownForTargetLocation() {
        WorldGuardProtectionAdapter worldGuard = mock(WorldGuardProtectionAdapter.class);
        when(worldGuard.checkMobDamage(any(Location.class)))
                .thenReturn(WorldGuardProtectionAdapter.Decision.ALLOWED)
                .thenReturn(WorldGuardProtectionAdapter.Decision.UNKNOWN);
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, worldGuard, 4, 12.0, 2.0,
                1000L, ru.raskolnpc.integration.PlayerSubjectResolver.none(), true);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
        verify(worldGuard, times(2)).checkMobDamage(any(Location.class));
    }

    @Test
    void blocksCombatWhenWorldGuardIsEnabledButAdapterIsMissing() {
        RelationshipPolicy relationships = new RelationshipPolicy(
                (first, second) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 4, 12.0, 2.0,
                1000L, ru.raskolnpc.integration.PlayerSubjectResolver.none(), true);

        controller.tick();

        verify(target, never()).damage(anyDouble(), any(Entity.class));
    }


}
