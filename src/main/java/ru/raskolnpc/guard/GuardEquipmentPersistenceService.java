package ru.raskolnpc.guard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.storage.GuardEquipmentRepository;
import java.io.*;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Durable equipment snapshots for guards whose Paper entity is recreated after death. */
public final class GuardEquipmentPersistenceService {
    private static final int SLOT_COUNT = 6;
    private final Plugin plugin;
    private final NpcRegistry registry;
    private final NpcEntityManager entities;
    private final GuardEquipmentRepository repository;
    private final Map<NpcId, CompletableFuture<Void>> saveTails = new ConcurrentHashMap<>();

    public GuardEquipmentPersistenceService(Plugin plugin, NpcRegistry registry,
            NpcEntityManager entities, GuardEquipmentRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CompletableFuture<Void> save(Entity entity) {
        NpcId id = entities.getNpcId(entity).orElse(null);
        if (id == null || registry.get(id).map(r -> r.type() != NpcType.GUARD).orElse(true)
                || !(entity instanceof LivingEntity living)) return CompletableFuture.completedFuture(null);
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) return CompletableFuture.completedFuture(null);
        final byte[] snapshot;
        try {
            // Bukkit ItemStack serialization must happen on the server thread.
            snapshot = encode(equipment);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        // DatabaseManager may use several workers. Serialize writes per NPC so an older
        // wear snapshot can never overtake and overwrite the final death snapshot.
        return saveTails.compute(id, (ignored, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((nothing, previousError) -> null);
            CompletableFuture<Void> next = ready.thenCompose(nothing -> repository.save(id, snapshot));
            next.whenComplete((nothing, error) -> saveTails.remove(id, next));
            return next;
        });
    }

    public CompletableFuture<Void> restore(NpcId id, Entity entity) {
        return repository.find(id).thenCompose(snapshot -> {
            if (snapshot.isEmpty()) return CompletableFuture.completedFuture(null);
            final ItemStack[] slots;
            try { slots = decode(snapshot.get()); }
            catch (IOException | RuntimeException exception) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Invalid saved equipment for guard " + id, exception));
            }
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!entity.isValid() || !(entity instanceof LivingEntity living)
                                || living.getEquipment() == null) {
                            throw new IllegalStateException("Recovered guard entity is unavailable: " + id);
                        }
                        EntityEquipment equipment = living.getEquipment();
                        equipment.setItemInMainHand(slots[0]);
                        equipment.setItemInOffHand(slots[1]);
                        equipment.setHelmet(slots[2]);
                        equipment.setChestplate(slots[3]);
                        equipment.setLeggings(slots[4]);
                        equipment.setBoots(slots[5]);
                        result.complete(null);
                    } catch (RuntimeException exception) { result.completeExceptionally(exception); }
                });
            } catch (RuntimeException exception) { result.completeExceptionally(exception); }
            return result;
        });
    }

    private static byte[] encode(EntityEquipment e) throws IOException {
        ItemStack[] items = {e.getItemInMainHand(), e.getItemInOffHand(), e.getHelmet(),
                e.getChestplate(), e.getLeggings(), e.getBoots()};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(SLOT_COUNT);
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir()) out.writeInt(-1);
                else {
                    byte[] data = item.serializeAsBytes();
                    out.writeInt(data.length);
                    out.write(data);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static ItemStack[] decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != SLOT_COUNT) throw new IOException("Invalid slot count");
            ItemStack[] items = new ItemStack[SLOT_COUNT];
            for (int i = 0; i < SLOT_COUNT; i++) {
                int length = in.readInt();
                if (length == -1) continue;
                if (length <= 0 || length > 1_048_576) throw new IOException("Invalid item length");
                byte[] data = in.readNBytes(length);
                if (data.length != length) throw new EOFException("Truncated item data");
                items[i] = ItemStack.deserializeBytes(data);
            }
            if (in.available() != 0) throw new IOException("Trailing equipment data");
            return items;
        }
    }
}
