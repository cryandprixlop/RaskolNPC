package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
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
import java.util.concurrent.CompletableFuture;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardEquipmentWearServiceTest {
    private ServerMock server;
    private Plugin plugin;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private NpcRecord guardRecord;
    private Skeleton guard;
    private GuardEquipmentWearService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        registry = new NpcRegistry();
        entities = mock(NpcEntityManager.class);
        guardRecord = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(guardRecord);
        var world = server.addSimpleWorld("equipment-wear");
        guard = world.spawn(new Location(world, 0, 64, 0), Skeleton.class);
        when(entities.getNpcId(guard)).thenReturn(Optional.of(guardRecord.id()));
        service = new GuardEquipmentWearService(plugin, registry, entities);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void wearsWeaponAfterDirectGuardAttack() {
        EntityEquipment equipment = guard.getEquipment();
        equipment.setItemInMainHand(new ItemStack(Material.IRON_SWORD));

        service.onGuardAttack(new EntityDamageByEntityEvent(
                guard, mock(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0));

        ItemStack weapon = equipment.getItemInMainHand();
        assertInstanceOf(Damageable.class, weapon.getItemMeta());
        assertEquals(1, ((Damageable) weapon.getItemMeta()).getDamage());
    }

    @Test
    void wearsAllEquippedArmorPiecesWhenGuardTakesDamage() {
        EntityEquipment equipment = guard.getEquipment();
        equipment.setHelmet(new ItemStack(Material.IRON_HELMET));
        equipment.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        equipment.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        equipment.setBoots(new ItemStack(Material.IRON_BOOTS));

        service.onGuardDamaged(new EntityDamageEvent(
                guard, EntityDamageEvent.DamageCause.FALL, 4.0));

        assertEquals(1, damage(equipment.getHelmet()));
        assertEquals(1, damage(equipment.getChestplate()));
        assertEquals(1, damage(equipment.getLeggings()));
        assertEquals(1, damage(equipment.getBoots()));
    }

    @Test
    void breaksEquipmentAtMaximumDurability() {
        EntityEquipment equipment = guard.getEquipment();
        ItemStack sword = new ItemStack(Material.WOODEN_SWORD);
        Damageable meta = (Damageable) sword.getItemMeta();
        meta.setDamage(Material.WOODEN_SWORD.getMaxDurability() - 1);
        sword.setItemMeta(meta);
        equipment.setItemInMainHand(sword);

        service.onGuardAttack(new EntityDamageByEntityEvent(
                guard, mock(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0));

        assertTrue(equipment.getItemInMainHand().getType().isAir());
    }

    @Test
    void doesNotWearNonGuardEntitiesOrWhenDisabled() {
        NpcRecord resident = NpcRecord.create(
                NpcType.RESIDENT, "Resident", null, "town", "nation", "{}");
        registry.register(resident);
        Skeleton other = guard.getWorld().spawn(new Location(guard.getWorld(), 2, 64, 0), Skeleton.class);
        when(entities.getNpcId(other)).thenReturn(Optional.of(resident.id()));
        other.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));

        service.onGuardAttack(new EntityDamageByEntityEvent(
                other, mock(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0));
        assertEquals(0, damage(other.getEquipment().getItemInMainHand()));

        service.setWearPerHit(0, 0);
        service.onGuardDamaged(new EntityDamageEvent(
                guard, EntityDamageEvent.DamageCause.FALL, 4.0));
        assertEquals(0, damage(guard.getEquipment().getHelmet()));
    }


    @Test
    void persistsWeaponWearAfterDamageIsApplied() {
        GuardEquipmentRepository repository = mock(GuardEquipmentRepository.class);
        when(repository.save(eq(guardRecord.id()), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        GuardEquipmentPersistenceService persistence =
                new GuardEquipmentPersistenceService(plugin, registry, entities, repository);
        service.setPersistenceService(persistence);
        guard.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));

        service.onGuardAttack(new EntityDamageByEntityEvent(
                guard, mock(Entity.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0));

        verify(repository).save(eq(guardRecord.id()), any(byte[].class));
    }

    private static int damage(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof Damageable damageable)) return 0;
        return damageable.getDamage();
    }
}
