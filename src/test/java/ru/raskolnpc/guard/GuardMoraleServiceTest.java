package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.guard.model.GuardCondition;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardConditionRepository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardMoraleServiceTest {
    private ServerMock server;
    private Plugin plugin;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private GuardConditionRepository repository;
    private NpcRecord guard;
    private LivingEntity guardEntity;
    private GuardMoraleService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        registry = new NpcRegistry();
        guard = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(guard);
        guardEntity = mock(LivingEntity.class);
        entities = mock(NpcEntityManager.class);
        when(entities.getNpcId(guardEntity)).thenReturn(java.util.Optional.of(guard.id()));
        repository = mock(GuardConditionRepository.class);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(repository.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        service = new GuardMoraleService(plugin, registry, entities, repository);
        service.setRetreatThresholds(20, 35);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void restoresRetreatStateFromPersistedLowMorale() {
        GuardCondition persisted = new GuardCondition(guard.id(), 12, 1000, 2000);
        when(repository.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(persisted)));

        service.load().join();

        assertEquals(12, service.morale(guard.id()));
        assertTrue(service.isRetreating(guard.id()));
    }

    @Test
    void incomingDamageLowersMoraleAndRoutesGuardAtConfiguredThreshold() {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(guardEntity);
        when(event.getFinalDamage()).thenReturn(40.0);

        service.onGuardDamaged(event);

        assertEquals(20, service.morale(guard.id()));
        assertTrue(service.isRetreating(guard.id()));
        verify(repository).save(argThat(condition ->
                condition.npcId().equals(guard.id()) && condition.morale() == 20));
    }

    @Test
    void retreatThresholdsMustHaveAValidHysteresisRange() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setRetreatThresholds(35, 35));
        assertThrows(IllegalArgumentException.class,
                () -> service.setRetreatThresholds(-1, 35));
        assertThrows(IllegalArgumentException.class,
                () -> service.setRetreatThresholds(20, 101));
    }

    @Test
    void nearbySameNationGuardsLoseMoraleWhenAnAllyDies() {
        NpcRecord ally = NpcRecord.create(NpcType.GUARD, "Ally", null, "town", "nation", "{}");
        registry.register(ally);
        LivingEntity allyEntity = mock(LivingEntity.class);
        when(entities.find(ally.id())).thenReturn(java.util.Optional.of(allyEntity));
        when(allyEntity.isValid()).thenReturn(true);
        when(allyEntity.isDead()).thenReturn(false);
        when(allyEntity.getLocation()).thenReturn(new Location(server.addSimpleWorld("world"), 1, 64, 1));
        when(guardEntity.getLocation()).thenReturn(new Location(server.getWorld("world"), 0, 64, 0));

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(guardEntity);

        service.onGuardDeath(event);

        assertEquals(92, service.morale(ally.id()));
        verify(repository).save(argThat(condition ->
                condition.npcId().equals(ally.id()) && condition.morale() == 92));
    }

    @Test
    void alliedDeathDoesNotAffectGuardsWithoutKnownNation() {
        NpcRecord unknownNationGuard = NpcRecord.create(
                NpcType.GUARD, "Unknown", null, "town", null, "{}");
        registry.register(unknownNationGuard);
        LivingEntity unknownEntity = mock(LivingEntity.class);
        when(entities.find(unknownNationGuard.id())).thenReturn(java.util.Optional.of(unknownEntity));
        when(unknownEntity.isValid()).thenReturn(true);
        when(unknownEntity.isDead()).thenReturn(false);
        when(unknownEntity.getLocation()).thenReturn(new Location(server.addSimpleWorld("world"), 1, 64, 1));
        when(guardEntity.getLocation()).thenReturn(new Location(server.getWorld("world"), 0, 64, 0));

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(guardEntity);

        service.onGuardDeath(event);

        assertEquals(100, service.morale(unknownNationGuard.id()));
    }

}
