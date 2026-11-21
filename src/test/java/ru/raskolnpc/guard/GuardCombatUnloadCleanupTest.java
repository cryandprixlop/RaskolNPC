package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardSquadRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

class GuardCombatUnloadCleanupTest {
    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("guard-unload-cleanup");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void unloadingGuardClearsItsAttackCooldownBeforeItIsLoadedAgain() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord guard = NpcRecord.create(
                NpcType.GUARD, "Guard", null, "town-a", "nation-a", "{}");
        NpcRecord hostile = NpcRecord.create(
                NpcType.GUARD, "Hostile", null, "town-b", "nation-b", "{}");
        registry.register(guard);
        registry.register(hostile);

        LivingEntity firstEntity = mock(LivingEntity.class);
        LivingEntity reloadedEntity = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        configure(firstEntity, new Location(world, 0.5, 64, 0.5));
        configure(reloadedEntity, new Location(world, 0.5, 64, 0.5));
        configure(target, new Location(world, 2.5, 64, 0.5));
        when(firstEntity.getNearbyEntities(12.0, 12.0, 12.0)).thenReturn(List.of(target));
        when(reloadedEntity.getNearbyEntities(12.0, 12.0, 12.0)).thenReturn(List.of(target));

        NpcEntityManager entities = mock(NpcEntityManager.class);
        when(entities.find(guard.id())).thenReturn(Optional.of(firstEntity), Optional.of(reloadedEntity));
        when(entities.getNpcId(target)).thenReturn(Optional.of(hostile.id()));
        when(entities.getNpcId(firstEntity)).thenReturn(Optional.of(guard.id()));

        RelationshipPolicy relationships = new RelationshipPolicy(
                (actor, victim) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 10, 12.0, 2.0, 10_000L);

        controller.tick();
        verify(target, times(1)).damage(2.0, firstEntity);

        controller.onEntitiesUnload(List.of(firstEntity));
        when(entities.getNpcId(reloadedEntity)).thenReturn(Optional.of(guard.id()));
        controller.tick();

        verify(target, times(1)).damage(2.0, reloadedEntity);
    }

    @Test
    void unloadingSharedTargetMakesSquadSelectAnotherHostile() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord leader = NpcRecord.create(
                NpcType.GUARD, "Leader", null, "town-a", "nation-a", "{}");
        NpcRecord follower = NpcRecord.create(
                NpcType.GUARD, "Follower", null, "town-a", "nation-a", "{}");
        NpcRecord firstHostile = NpcRecord.create(
                NpcType.GUARD, "First hostile", null, "town-b", "nation-b", "{}");
        NpcRecord nextHostile = NpcRecord.create(
                NpcType.GUARD, "Next hostile", null, "town-c", "nation-c", "{}");
        for (NpcRecord record : List.of(leader, follower, firstHostile, nextHostile)) {
            registry.register(record);
        }

        LivingEntity leaderEntity = mock(LivingEntity.class);
        LivingEntity followerEntity = mock(LivingEntity.class);
        LivingEntity firstTarget = mock(LivingEntity.class);
        LivingEntity nextTarget = mock(LivingEntity.class);
        configure(leaderEntity, new Location(world, 0.5, 64, 0.5));
        configure(followerEntity, new Location(world, 0.5, 64, 1.5));
        configure(firstTarget, new Location(world, 2.5, 64, 0.5));
        configure(nextTarget, new Location(world, 4.5, 64, 0.5));
        when(leaderEntity.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(firstTarget, nextTarget));
        when(followerEntity.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(firstTarget, nextTarget));
        doThrow(new IllegalStateException("simulated transient damage failure"))
                .when(firstTarget).damage(2.0, leaderEntity);
        doThrow(new IllegalStateException("simulated transient damage failure"))
                .when(firstTarget).damage(2.0, followerEntity);

        NpcEntityManager entities = mock(NpcEntityManager.class);
        when(entities.find(leader.id())).thenReturn(Optional.of(leaderEntity));
        when(entities.find(follower.id())).thenReturn(Optional.of(followerEntity));
        when(entities.getNpcId(firstTarget)).thenReturn(Optional.of(firstHostile.id()));
        when(entities.getNpcId(nextTarget)).thenReturn(Optional.of(nextHostile.id()));

        GuardSquad squad = new GuardSquad(UUID.randomUUID(), "Alpha", UUID.randomUUID(),
                100L, List.of(leader.id(), follower.id()));
        GuardSquadRepository repository = mock(GuardSquadRepository.class);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(squad)));
        GuardSquadService squads = new GuardSquadService(registry, repository, 10);
        squads.refreshMemberships().join();
        RelationshipPolicy relationships = new RelationshipPolicy(
                (actor, victim) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 10, 12.0, 2.0, 1000L);
        controller.setSquadService(squads);

        controller.tick();
        verify(firstTarget).damage(2.0, leaderEntity);
        verify(firstTarget).damage(2.0, followerEntity);

        controller.onEntitiesUnload(List.of(firstTarget));
        controller.tick();

        verify(nextTarget).damage(2.0, leaderEntity);
        verify(nextTarget).damage(2.0, followerEntity);
    }

    private void configure(LivingEntity entity, Location location) {
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
        when(entity.getLocation()).thenReturn(location);
    }
}
