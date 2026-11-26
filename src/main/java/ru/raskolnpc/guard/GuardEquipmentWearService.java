package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.Objects;

/**
 * Applies native Minecraft item durability to guard weapons and armor.
 *
 * Changed equipment snapshots are persisted to SQLite so durability survives
 * entity death and recreation as well as chunk unloads and server restarts.
 */
public final class GuardEquipmentWearService implements Listener {
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private int weaponWearPerHit = 1;
    private int armorWearPerHit = 1;
    private boolean listenerRegistered;
    private GuardEquipmentPersistenceService persistenceService;

    public GuardEquipmentWearService(Plugin plugin, NpcRegistry registry,
                                     NpcEntityManager entities) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
    }

    public void setPersistenceService(GuardEquipmentPersistenceService service) {
        requireMainThread();
        this.persistenceService = Objects.requireNonNull(service, "service");
    }

    public void setWearPerHit(int weaponWearPerHit, int armorWearPerHit) {
        requireMainThread();
        if (weaponWearPerHit < 0 || weaponWearPerHit > 100
                || armorWearPerHit < 0 || armorWearPerHit > 100) {
            throw new IllegalArgumentException("Equipment wear per hit must be between 0 and 100.");
        }
        this.weaponWearPerHit = weaponWearPerHit;
        this.armorWearPerHit = armorWearPerHit;
    }

    public void start() {
        requireMainThread();
        if (listenerRegistered) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listenerRegistered = true;
    }

    public void stop() {
        if (!listenerRegistered) return;
        HandlerList.unregisterAll(this);
        listenerRegistered = false;
    }

    /** A direct guard melee hit wears its main-hand weapon after damage is resolved. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onGuardAttack(EntityDamageByEntityEvent event) {
        requireMainThread();
        if (weaponWearPerHit == 0
                || !(event.getDamager() instanceof LivingEntity attacker)) return;
        NpcId guardId = entities.getNpcId(attacker).orElse(null);
        if (!isGuard(guardId)) return;
        EntityEquipment equipment = attacker.getEquipment();
        if (equipment == null) return;
        ItemStack weapon = equipment.getItemInMainHand();
        ItemStack worn = applyWear(weapon, weaponWearPerHit);
        if (worn != weapon) {
            equipment.setItemInMainHand(worn);
            persist(attacker);
        }
    }

    /** Every qualifying hit wears each equipped, damageable armor piece once. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onGuardDamaged(EntityDamageEvent event) {
        requireMainThread();
        if (armorWearPerHit == 0
                || !(event.getEntity() instanceof LivingEntity guard)) return;
        NpcId guardId = entities.getNpcId(guard).orElse(null);
        if (!isGuard(guardId)) return;
        EntityEquipment equipment = guard.getEquipment();
        if (equipment == null) return;

        ItemStack[] armor = equipment.getArmorContents();
        boolean changed = false;
        for (int index = 0; index < armor.length; index++) {
            ItemStack current = armor[index];
            ItemStack worn = applyWear(current, armorWearPerHit);
            if (worn != current) {
                armor[index] = worn;
                changed = true;
            }
        }
        if (changed) {
            equipment.setArmorContents(armor);
            persist(guard);
        }
    }

    private void persist(Entity entity) {
        if (persistenceService == null) return;
        persistenceService.save(entity).exceptionally(error -> {
            if (plugin.isEnabled()) plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not persist worn guard equipment.", error);
            return null;
        });
    }

    private boolean isGuard(NpcId id) {
        if (id == null) return false;
        NpcRecord record = registry.get(id).orElse(null);
        return record != null && record.type() == NpcType.GUARD;
    }

    private static ItemStack applyWear(ItemStack item, int amount) {
        if (item == null || item.getType().isAir() || amount <= 0
                || item.getType().getMaxDurability() <= 0) return item;
        ItemStack updated = item.clone();
        if (!(updated.getItemMeta() instanceof Damageable damageable)) return item;

        int maximum = updated.getType().getMaxDurability();
        int nextDamage = damageable.getDamage() + amount;
        if (nextDamage >= maximum) return new ItemStack(Material.AIR);
        damageable.setDamage(nextDamage);
        updated.setItemMeta(damageable);
        return updated;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Guard equipment must be accessed on the server thread.");
        }
    }
}
