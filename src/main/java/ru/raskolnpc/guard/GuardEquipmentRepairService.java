package ru.raskolnpc.guard;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Repairs loaded guard equipment in exchange for tier-appropriate materials.
 * All inventory and entity mutations are performed synchronously on the server thread.
 */
public final class GuardEquipmentRepairService {
    private static final double REPAIR_DISTANCE_SQUARED = 36.0;

    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final GuardEquipmentPersistenceService persistenceService;

    public GuardEquipmentRepairService(NpcRegistry registry, NpcEntityManager entities) {
        this(registry, entities, null);
    }

    public GuardEquipmentRepairService(NpcRegistry registry, NpcEntityManager entities,
                                       GuardEquipmentPersistenceService persistenceService) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.persistenceService = persistenceService;
    }

    public RepairResult repair(Player player, NpcId guardId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(guardId, "guardId");
        requireMainThread();

        NpcRecord record = registry.get(guardId).orElse(null);
        if (record == null || record.type() != NpcType.GUARD) {
            return RepairResult.failure("Страж не найден.");
        }
        Optional<Entity> entityOptional = entities.find(guardId);
        if (entityOptional.isEmpty() || !(entityOptional.get() instanceof LivingEntity guard)) {
            return RepairResult.failure("Страж должен быть загружен в мире.");
        }
        if (!guard.getWorld().equals(player.getWorld())
                || guard.getLocation().distanceSquared(player.getLocation()) > REPAIR_DISTANCE_SQUARED) {
            return RepairResult.failure("Подойдите к стражу на расстояние не более 6 блоков.");
        }

        EntityEquipment equipment = guard.getEquipment();
        if (equipment == null) return RepairResult.failure("У стража нет доступной экипировки.");

        ItemStack[] slots = {
                equipment.getItemInMainHand(),
                equipment.getItemInOffHand(),
                equipment.getHelmet(),
                equipment.getChestplate(),
                equipment.getLeggings(),
                equipment.getBoots()
        };
        Map<Material, Integer> required = new EnumMap<>(Material.class);
        int damagedItems = 0;
        int unsupportedItems = 0;
        int restoredDurability = 0;

        for (ItemStack item : slots) {
            if (!isDamaged(item)) continue;
            Material ingredient = repairIngredient(item.getType());
            if (ingredient == null) {
                unsupportedItems++;
                continue;
            }
            int maximum = item.getType().getMaxDurability();
            int damage = ((Damageable) item.getItemMeta()).getDamage();
            int units = Math.max(1, (int) Math.ceil((double) damage * 4.0 / maximum));
            required.merge(ingredient, units, Integer::sum);
            damagedItems++;
            restoredDurability += damage;
        }

        if (damagedItems == 0) {
            return new RepairResult(false, unsupportedItems > 0
                            ? "Нет повреждённых предметов с поддерживаемым рецептом ремонта."
                            : "Экипировка не нуждается в ремонте.",
                    0, 0, Map.of(), unsupportedItems);
        }
        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            if (!player.getInventory().containsAtLeast(
                    new ItemStack(entry.getKey()), entry.getValue())) {
                return new RepairResult(false,
                        "Недостаточно материалов: требуется " + entry.getValue()
                                + " × " + entry.getKey() + ".",
                        0, 0, Map.of(), unsupportedItems);
            }
        }

        // The complete cost is validated before any item or equipment is changed.
        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            player.getInventory().removeItem(new ItemStack(entry.getKey(), entry.getValue()));
        }

        repairSlot(equipment, Slot.MAIN_HAND);
        repairSlot(equipment, Slot.OFF_HAND);
        repairSlot(equipment, Slot.HELMET);
        repairSlot(equipment, Slot.CHESTPLATE);
        repairSlot(equipment, Slot.LEGGINGS);
        repairSlot(equipment, Slot.BOOTS);
        if (persistenceService != null) {
            persistenceService.save(guard).exceptionally(error -> {
                org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not persist repaired guard equipment.", error);
                return null;
            });
        }

        return new RepairResult(true, "Экипировка стража отремонтирована.",
                damagedItems, restoredDurability, Map.copyOf(required), unsupportedItems);
    }

    private static boolean isDamaged(ItemStack item) {
        return item != null && !item.getType().isAir()
                && item.getType().getMaxDurability() > 0
                && item.getItemMeta() instanceof Damageable damageable
                && damageable.getDamage() > 0;
    }

    private static Material repairIngredient(Material material) {
        String name = material.name();
        if (name.startsWith("LEATHER_")) return Material.LEATHER;
        if (name.startsWith("CHAINMAIL_") || name.startsWith("IRON_")
                || material == Material.IRON_SWORD || material == Material.IRON_PICKAXE
                || material == Material.IRON_AXE || material == Material.IRON_SHOVEL
                || material == Material.IRON_HOE) return Material.IRON_INGOT;
        if (name.startsWith("GOLDEN_") || name.startsWith("GOLD_")
                || material == Material.GOLDEN_SWORD || material == Material.GOLDEN_PICKAXE
                || material == Material.GOLDEN_AXE || material == Material.GOLDEN_SHOVEL
                || material == Material.GOLDEN_HOE) return Material.GOLD_INGOT;
        if (name.startsWith("DIAMOND_") || material == Material.DIAMOND_SWORD
                || material == Material.DIAMOND_PICKAXE || material == Material.DIAMOND_AXE
                || material == Material.DIAMOND_SHOVEL || material == Material.DIAMOND_HOE) {
            return Material.DIAMOND;
        }
        if (name.startsWith("NETHERITE_")) return Material.NETHERITE_INGOT;
        if (name.startsWith("WOODEN_") || name.startsWith("STONE_")
                || material == Material.BOW || material == Material.CROSSBOW
                || material == Material.SHIELD) return Material.STICK;
        if (material == Material.ELYTRA) return Material.PHANTOM_MEMBRANE;
        if (material == Material.TURTLE_HELMET) return Material.TURTLE_SCUTE;
        return null;
    }

    private static void repairSlot(EntityEquipment equipment, Slot slot) {
        ItemStack item = switch (slot) {
            case MAIN_HAND -> equipment.getItemInMainHand();
            case OFF_HAND -> equipment.getItemInOffHand();
            case HELMET -> equipment.getHelmet();
            case CHESTPLATE -> equipment.getChestplate();
            case LEGGINGS -> equipment.getLeggings();
            case BOOTS -> equipment.getBoots();
        };
        if (!isDamaged(item) || repairIngredient(item.getType()) == null) return;
        ItemStack repaired = item.clone();
        Damageable meta = (Damageable) repaired.getItemMeta();
        meta.setDamage(0);
        repaired.setItemMeta(meta);
        switch (slot) {
            case MAIN_HAND -> equipment.setItemInMainHand(repaired);
            case OFF_HAND -> equipment.setItemInOffHand(repaired);
            case HELMET -> equipment.setHelmet(repaired);
            case CHESTPLATE -> equipment.setChestplate(repaired);
            case LEGGINGS -> equipment.setLeggings(repaired);
            case BOOTS -> equipment.setBoots(repaired);
        }
    }

    private static void requireMainThread() {
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Guard equipment repair must run on the server thread.");
        }
    }

    private enum Slot { MAIN_HAND, OFF_HAND, HELMET, CHESTPLATE, LEGGINGS, BOOTS }

    public record RepairResult(boolean repaired, String message, int repairedItems,
                               int restoredDurability, Map<Material, Integer> materialsConsumed,
                               int unsupportedItems) {
        public RepairResult {
            materialsConsumed = Map.copyOf(materialsConsumed);
        }

        private static RepairResult failure(String message) {
            return new RepairResult(false, message, 0, 0, Map.of(), 0);
        }
    }
}
