package ru.raskolnpc.npc.resident;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.entity.NpcEntityManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Handles right-click conversations with resident NPCs. */
public final class ResidentInteractionListener implements Listener {
    private static final long DEFAULT_COOLDOWN_MILLIS = 1_500L;
    private static final int MAX_COOLDOWN_ENTRIES = 4_096;

    private record InteractionKey(UUID playerId, NpcId npcId) {}

    private final Plugin plugin;
    private final NpcEntityManager entities;
    private final NpcRegistry registry;
    private final ResidentProfileService profiles;
    private final ResidentDialogueService dialogues;
    private final long cooldownMillis;
    // Bukkit interaction events are handled on the server thread.
    private final Map<InteractionKey, Long> lastInteractions = new HashMap<>();

    public ResidentInteractionListener(Plugin plugin, NpcEntityManager entities,
            NpcRegistry registry, ResidentProfileService profiles,
            ResidentDialogueService dialogues) {
        this(plugin, entities, registry, profiles, dialogues, DEFAULT_COOLDOWN_MILLIS);
    }

    ResidentInteractionListener(Plugin plugin, NpcEntityManager entities,
            NpcRegistry registry, ResidentProfileService profiles,
            ResidentDialogueService dialogues, long cooldownMillis) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.dialogues = Objects.requireNonNull(dialogues, "dialogues");
        if (cooldownMillis < 0) throw new IllegalArgumentException("Cooldown cannot be negative.");
        this.cooldownMillis = cooldownMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidentInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        NpcId npcId = entities.getNpcId(event.getRightClicked()).orElse(null);
        if (npcId == null) return;
        var record = registry.get(npcId).orElse(null);
        if (record == null || record.type() != NpcType.RESIDENT) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        InteractionKey key = new InteractionKey(player.getUniqueId(), npcId);
        Long previous = lastInteractions.get(key);
        if (previous != null && now - previous < cooldownMillis) return;
        lastInteractions.put(key, now);

        if (lastInteractions.size() > MAX_COOLDOWN_ENTRIES) {
            lastInteractions.entrySet().removeIf(entry ->
                    now - entry.getValue() > Math.max(cooldownMillis * 4, 10_000L));
        }

        String residentName = record.name();
        profiles.find(npcId).whenComplete((profile, error) -> {
            if (!plugin.isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) {
                        plugin.getLogger().warning("Could not load resident dialogue profile for "
                                + npcId + ": " + error.getMessage());
                        player.sendMessage("§7Житель сейчас занят и не может поговорить.");
                        return;
                    }
                    if (profile == null || profile.isEmpty()) {
                        player.sendMessage("§7Житель пока не готов к разговору.");
                        return;
                    }
                    dialogues.speak(player, profile.get(), residentName);
                });
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().warning("Could not schedule resident dialogue: "
                        + schedulingError.getMessage());
            }
        });
    }
}
