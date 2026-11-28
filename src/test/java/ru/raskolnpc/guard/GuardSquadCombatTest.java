package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.integration.RelationshipPolicy;
import ru.raskolnpc.integration.PlayerSubjectResolver;
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

class GuardSquadCombatTest {
    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("guard-squad-combat");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void squadMembersFocusTheStableLeadersVerifiedTarget() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord firstGuard = NpcRecord.create(
                NpcType.GUARD, "First guard", null, "town-a", "nation-a", "{}");
        NpcRecord secondGuard = NpcRecord.create(
                NpcType.GUARD, "Second guard", null, "town-a", "nation-a", "{}");
        NpcRecord focusRecord = NpcRecord.create(
                NpcType.RESIDENT, "Focus target", null, "town-b", "nation-b", "{}");
        NpcRecord nearerToFollowerRecord = NpcRecord.create(
                NpcType.RESIDENT, "Follower decoy", null, "town-c", "nation-c", "{}");
        for (NpcRecord record : List.of(
                firstGuard, secondGuard, focusRecord, nearerToFollowerRecord)) {
            registry.register(record);
        }

        List<NpcRecord> orderedGuards = List.of(firstGuard, secondGuard).stream()
                .sorted(java.util.Comparator.comparing(record -> record.id().toString()))
                .toList();
        NpcRecord leader = orderedGuards.getFirst();
        NpcRecord follower = orderedGuards.getLast();

        LivingEntity leaderEntity = mock(LivingEntity.class);
        LivingEntity followerEntity = mock(LivingEntity.class);
        LivingEntity focusTarget = mock(LivingEntity.class);
        LivingEntity followerDecoy = mock(LivingEntity.class);
        Location leaderLocation = new Location(world, 0.5, 64, 0.5);
        Location followerLocation = new Location(world, 0.5, 64, 1.5);
        Location focusLocation = new Location(world, 5.5, 64, 0.5);
        Location decoyLocation = new Location(world, 1.5, 64, 1.5);

        configure(leaderEntity, leaderLocation);
        configure(followerEntity, followerLocation);
        configure(focusTarget, focusLocation);
        configure(followerDecoy, decoyLocation);
        registerActive(registry, firstGuard, leaderEntity);
        registerActive(registry, secondGuard, followerEntity);
        registerActive(registry, focusRecord, focusTarget);
        registerActive(registry, nearerToFollowerRecord, followerDecoy);
        when(leaderEntity.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(focusTarget));
        when(followerEntity.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(followerDecoy, focusTarget));

        NpcEntityManager entities = mock(NpcEntityManager.class);
        when(entities.find(leader.id())).thenReturn(Optional.of(leaderEntity));
        when(entities.find(follower.id())).thenReturn(Optional.of(followerEntity));
        when(entities.getNpcId(focusTarget)).thenReturn(Optional.of(focusRecord.id()));
        when(entities.getNpcId(followerDecoy)).thenReturn(Optional.of(nearerToFollowerRecord.id()));

        GuardSquad squad = new GuardSquad(UUID.randomUUID(), "Alpha", UUID.randomUUID(),
                100L, List.of(follower.id(), leader.id()));
        GuardSquadRepository squadRepository = mock(GuardSquadRepository.class);
        when(squadRepository.findAll())
                .thenReturn(CompletableFuture.completedFuture(List.of(squad)));
        GuardSquadService squads = new GuardSquadService(registry, squadRepository, 10);
        squads.refreshMemberships().join();

        RelationshipPolicy relationships = new RelationshipPolicy(
                (actor, victim) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 10, 12.0, 2.0, 1000L,
                PlayerSubjectResolver.none(), false, () -> false);
        controller.setSquadService(squads);

        controller.tick();

        verify(focusTarget).damage(2.0, leaderEntity);
        verify(focusTarget).damage(2.0, followerEntity);
        verify(followerDecoy, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
    }

    @Test
    void livingFollowerTakesOverTargetSelectionWhenStableLeaderIsUnavailable() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord firstGuard = NpcRecord.create(
                NpcType.GUARD, "First guard", null, "town-a", "nation-a", "{}");
        NpcRecord secondGuard = NpcRecord.create(
                NpcType.GUARD, "Second guard", null, "town-a", "nation-a", "{}");
        NpcRecord nearbyTargetRecord = NpcRecord.create(
                NpcType.RESIDENT, "Nearby hostile", null, "town-b", "nation-b", "{}");
        NpcRecord distantTargetRecord = NpcRecord.create(
                NpcType.RESIDENT, "Other hostile", null, "town-c", "nation-c", "{}");
        for (NpcRecord record : List.of(
                firstGuard, secondGuard, nearbyTargetRecord, distantTargetRecord)) {
            registry.register(record);
        }

        List<NpcRecord> orderedGuards = List.of(firstGuard, secondGuard).stream()
                .sorted(java.util.Comparator.comparing(record -> record.id().toString()))
                .toList();
        NpcRecord leader = orderedGuards.getFirst();
        NpcRecord follower = orderedGuards.getLast();

        LivingEntity unavailableLeader = mock(LivingEntity.class);
        LivingEntity livingFollower = mock(LivingEntity.class);
        LivingEntity nearbyTarget = mock(LivingEntity.class);
        LivingEntity distantTarget = mock(LivingEntity.class);
        Location followerLocation = new Location(world, 0.5, 64, 0.5);
        configure(unavailableLeader, new Location(world, 0.5, 64, 1.5));
        configure(livingFollower, followerLocation);
        configure(nearbyTarget, new Location(world, 2.5, 64, 0.5));
        configure(distantTarget, new Location(world, 7.5, 64, 0.5));
        registerActive(registry, firstGuard, unavailableLeader);
        registerActive(registry, secondGuard, livingFollower);
        registerActive(registry, nearbyTargetRecord, nearbyTarget);
        registerActive(registry, distantTargetRecord, distantTarget);
        when(unavailableLeader.isValid()).thenReturn(false);
        when(livingFollower.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(distantTarget, nearbyTarget));

        NpcEntityManager entities = mock(NpcEntityManager.class);
        when(entities.find(leader.id())).thenReturn(Optional.of(unavailableLeader));
        when(entities.find(follower.id())).thenReturn(Optional.of(livingFollower));
        when(entities.getNpcId(nearbyTarget)).thenReturn(Optional.of(nearbyTargetRecord.id()));
        when(entities.getNpcId(distantTarget)).thenReturn(Optional.of(distantTargetRecord.id()));

        GuardSquad squad = new GuardSquad(UUID.randomUUID(), "Alpha", UUID.randomUUID(),
                100L, List.of(follower.id(), leader.id()));
        GuardSquadRepository squadRepository = mock(GuardSquadRepository.class);
        when(squadRepository.findAll())
                .thenReturn(CompletableFuture.completedFuture(List.of(squad)));
        GuardSquadService squads = new GuardSquadService(registry, squadRepository, 10);
        squads.refreshMemberships().join();

        RelationshipPolicy relationships = new RelationshipPolicy(
                (actor, victim) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 10, 12.0, 2.0, 1000L,
                PlayerSubjectResolver.none(), false, () -> false);
        controller.setSquadService(squads);

        controller.tick();

        verify(nearbyTarget).damage(2.0, livingFollower);
        verify(distantTarget, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
    }

    @Test
    void squadDropsDeadSharedTargetAndSelectsAnotherHostile() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord firstGuard = NpcRecord.create(
                NpcType.GUARD, "First guard", null, "town-a", "nation-a", "{}");
        NpcRecord secondGuard = NpcRecord.create(
                NpcType.GUARD, "Second guard", null, "town-a", "nation-a", "{}");
        NpcRecord firstTargetRecord = NpcRecord.create(
                NpcType.RESIDENT, "First target", null, "town-b", "nation-b", "{}");
        NpcRecord replacementTargetRecord = NpcRecord.create(
                NpcType.RESIDENT, "Replacement target", null, "town-c", "nation-c", "{}");
        for (NpcRecord record : List.of(
                firstGuard, secondGuard, firstTargetRecord, replacementTargetRecord)) {
            registry.register(record);
        }

        List<NpcRecord> orderedGuards = List.of(firstGuard, secondGuard).stream()
                .sorted(java.util.Comparator.comparing(record -> record.id().toString()))
                .toList();
        NpcRecord leader = orderedGuards.getFirst();
        NpcRecord follower = orderedGuards.getLast();

        LivingEntity leaderEntity = mock(LivingEntity.class);
        LivingEntity followerEntity = mock(LivingEntity.class);
        LivingEntity firstTarget = mock(LivingEntity.class);
        LivingEntity replacementTarget = mock(LivingEntity.class);
        configure(leaderEntity, new Location(world, 0.5, 64, 0.5));
        configure(followerEntity, new Location(world, 0.5, 64, 1.5));
        configure(firstTarget, new Location(world, 2.5, 64, 0.5));
        configure(replacementTarget, new Location(world, 4.5, 64, 0.5));
        registerActive(registry, firstGuard, leaderEntity);
        registerActive(registry, secondGuard, followerEntity);
        registerActive(registry, firstTargetRecord, firstTarget);
        registerActive(registry, replacementTargetRecord, replacementTarget);
        when(leaderEntity.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(firstTarget, replacementTarget));
        when(followerEntity.getNearbyEntities(12.0, 12.0, 12.0))
                .thenReturn(List.of(firstTarget, replacementTarget));
        doThrow(new IllegalStateException("simulated transient damage failure"))
                .when(firstTarget).damage(2.0, leaderEntity);
        doThrow(new IllegalStateException("simulated transient damage failure"))
                .when(firstTarget).damage(2.0, followerEntity);

        NpcEntityManager entities = mock(NpcEntityManager.class);
        when(entities.find(leader.id())).thenReturn(Optional.of(leaderEntity));
        when(entities.find(follower.id())).thenReturn(Optional.of(followerEntity));
        when(entities.getNpcId(firstTarget)).thenReturn(Optional.of(firstTargetRecord.id()));
        when(entities.getNpcId(replacementTarget))
                .thenReturn(Optional.of(replacementTargetRecord.id()));

        GuardSquad squad = new GuardSquad(UUID.randomUUID(), "Alpha", UUID.randomUUID(),
                100L, List.of(follower.id(), leader.id()));
        GuardSquadRepository squadRepository = mock(GuardSquadRepository.class);
        when(squadRepository.findAll())
                .thenReturn(CompletableFuture.completedFuture(List.of(squad)));
        GuardSquadService squads = new GuardSquadService(registry, squadRepository, 10);
        squads.refreshMemberships().join();

        RelationshipPolicy relationships = new RelationshipPolicy(
                (actor, victim) -> RelationshipPolicy.DiplomacyRelation.HOSTILE);
        GuardCombatController controller = new GuardCombatController(
                plugin, registry, entities, relationships, null, 10, 12.0, 2.0, 1000L,
                PlayerSubjectResolver.none(), false, () -> false);
        controller.setSquadService(squads);

        controller.tick();
        verify(firstTarget).damage(2.0, leaderEntity);
        verify(firstTarget).damage(2.0, followerEntity);

        when(firstTarget.isDead()).thenReturn(true);
        controller.tick();

        verify(replacementTarget).damage(2.0, leaderEntity);
        verify(replacementTarget).damage(2.0, followerEntity);
    }

    private void registerActive(NpcRegistry registry, NpcRecord record, LivingEntity entity) {
        UUID entityUuid = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(entityUuid);
        registry.put(record.withEntityBinding(entityUuid, null));
    }

    private void configure(LivingEntity entity, Location location) {
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
        when(entity.getLocation()).thenReturn(location);
    }
}
