package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Skeleton;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardEquipmentRepository;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuardEquipmentPersistenceServiceTest {
    private ServerMock server;
    private Plugin plugin;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private GuardEquipmentRepository repository;
    private NpcRecord guardRecord;
    private Skeleton guard;
    private GuardEquipmentPersistenceService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        registry = new NpcRegistry();
        entities = mock(NpcEntityManager.class);
        repository = mock(GuardEquipmentRepository.class);
        guardRecord = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(guardRecord);
        var world = server.addSimpleWorld("equipment-persistence");
        guard = world.spawn(new Location(world, 0, 64, 0), Skeleton.class);
        when(entities.getNpcId(guard)).thenReturn(Optional.of(guardRecord.id()));
        service = new GuardEquipmentPersistenceService(plugin, registry, entities, repository);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void serializesWritesPerGuardSoOlderWearSnapshotCannotOverwriteDeathSnapshot() {
        CompletableFuture<Void> firstWrite = new CompletableFuture<>();
        CompletableFuture<Void> secondWrite = CompletableFuture.completedFuture(null);
        when(repository.save(eq(guardRecord.id()), any(byte[].class)))
                .thenReturn(firstWrite, secondWrite);

        guard.getEquipment().setItemInMainHand(damagedSword(4));
        CompletableFuture<Void> first = service.save(guard);

        guard.getEquipment().setItemInMainHand(damagedSword(9));
        CompletableFuture<Void> second = service.save(guard);

        verify(repository, times(1)).save(eq(guardRecord.id()), any(byte[].class));
        assertFalse(second.isDone(), "Newer snapshot must wait for the previous write.");

        firstWrite.complete(null);
        assertDoesNotThrow(second::join);
        verify(repository, times(2)).save(eq(guardRecord.id()), any(byte[].class));
    }

    @Test
    void doesNotPersistEquipmentForResidents() {
        NpcRecord resident = NpcRecord.create(
                NpcType.RESIDENT, "Resident", null, "town", "nation", "{}");
        registry.register(resident);
        Skeleton entity = guard.getWorld().spawn(
                new Location(guard.getWorld(), 2, 64, 0), Skeleton.class);
        when(entities.getNpcId(entity)).thenReturn(Optional.of(resident.id()));

        assertDoesNotThrow(() -> service.save(entity).join());
        verifyNoInteractions(repository);
    }

    private static ItemStack damagedSword(int damage) {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        Damageable meta = (Damageable) sword.getItemMeta();
        meta.setDamage(damage);
        sword.setItemMeta(meta);
        return sword;
    }
}
