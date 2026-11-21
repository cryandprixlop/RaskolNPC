package ru.raskolnpc.guard;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Skeleton;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardEquipmentRepairServiceTest {
    private ServerMock server;
    private NpcRegistry registry;
    private NpcEntityManager entities;
    private NpcRecord record;
    private Skeleton guard;
    private org.bukkit.entity.Player player;
    private GuardEquipmentRepairService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var world = server.addSimpleWorld("equipment-repair");
        player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        guard = world.spawn(new Location(world, 2, 64, 0), Skeleton.class);
        registry = new NpcRegistry();
        record = NpcRecord.create(NpcType.GUARD, "Guard", null, "town", "nation", "{}");
        registry.register(record);
        entities = mock(NpcEntityManager.class);
        when(entities.find(record.id())).thenReturn(Optional.of(guard));
        service = new GuardEquipmentRepairService(registry, entities);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void repairsDamagedIronWeaponAndArmorForTierMaterials() {
        ItemStack sword = damaged(Material.IRON_SWORD, 100);
        guard.getEquipment().setItemInMainHand(sword);
        guard.getEquipment().setHelmet(damaged(Material.IRON_HELMET, 100));
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 5));

        var result = service.repair(player, record.id());

        assertTrue(result.repaired());
        assertEquals(2, result.repairedItems());
        assertEquals(5, result.materialsConsumed().get(Material.IRON_INGOT));
        assertEquals(0, damage(guard.getEquipment().getItemInMainHand()));
        assertEquals(0, damage(guard.getEquipment().getHelmet()));
        assertEquals(0, player.getInventory().all(Material.IRON_INGOT).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
    }

    @Test
    void doesNotConsumeAnyMaterialsWhenCostIsInsufficient() {
        guard.getEquipment().setHelmet(damaged(Material.IRON_HELMET, 100));
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));

        var result = service.repair(player, record.id());

        assertFalse(result.repaired());
        assertEquals(100, damage(guard.getEquipment().getHelmet()));
        assertEquals(2, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void refusesRepairWhenGuardIsTooFarAway() {
        player.teleport(new Location(guard.getWorld(), 30, 64, 0));
        guard.getEquipment().setHelmet(damaged(Material.IRON_HELMET, 50));
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 8));

        var result = service.repair(player, record.id());

        assertFalse(result.repaired());
        assertEquals(50, damage(guard.getEquipment().getHelmet()));
    }

    @Test
    void refusesRepairForNonGuardRecord() {
        var resident = NpcRecord.create(NpcType.RESIDENT, "Resident", null, "town", "nation", "{}");
        registry.register(resident);
        when(entities.find(resident.id())).thenReturn(Optional.of(guard));

        var result = service.repair(player, resident.id());

        assertFalse(result.repaired());
        assertEquals("Страж не найден.", result.message());
    }

    private static ItemStack damaged(Material material, int damage) {
        ItemStack item = new ItemStack(material);
        Damageable meta = (Damageable) item.getItemMeta();
        meta.setDamage(damage);
        item.setItemMeta(meta);
        return item;
    }

    private static int damage(ItemStack item) {
        return item != null && item.getItemMeta() instanceof Damageable meta ? meta.getDamage() : 0;
    }
}
