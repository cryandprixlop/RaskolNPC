package ru.raskolnpc.npc.resident;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.quest.ResidentQuest;
import ru.raskolnpc.npc.resident.quest.ResidentQuestService;
import ru.raskolnpc.storage.ResidentQuestReview;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Handles right-click conversations with resident NPCs. */
public final class ResidentInteractionListener implements Listener {
    private static final long DEFAULT_COOLDOWN_MILLIS = 1_500L;
    private static final int MAX_COOLDOWN_ENTRIES = 4_096;
    private static final int REVIEW_LOOKUP_LIMIT = 100;

    private record InteractionKey(UUID playerId, NpcId npcId) {}
    private record ProfileAndReputation(
            java.util.Optional<ResidentProfile> profile, int reputation) {}
    private record ProfileAndAssignments(
            java.util.Optional<ResidentProfile> profile, int reputation,
            List<ResidentQuest.Assignment> assignments) {}
    private record ConversationContext(
            ResidentProfile profile,
            int reputation,
            List<ResidentQuest.Assignment> activeAssignments,
            List<ResidentQuestReview> reviews,
            List<ResidentQuest> availableQuests
    ) {}

    private final Plugin plugin;
    private final NpcEntityManager entities;
    private final NpcRegistry registry;
    private final ResidentProfileService profiles;
    private final ResidentDialogueService dialogues;
    private final ResidentQuestService quests;
    private final long cooldownMillis;
    // Bukkit interaction events are handled on the server thread.
    private final Map<InteractionKey, Long> lastInteractions = new HashMap<>();

    public ResidentInteractionListener(Plugin plugin, NpcEntityManager entities,
            NpcRegistry registry, ResidentProfileService profiles,
            ResidentDialogueService dialogues, ResidentQuestService quests) {
        this(plugin, entities, registry, profiles, dialogues, quests, DEFAULT_COOLDOWN_MILLIS);
    }

    ResidentInteractionListener(Plugin plugin, NpcEntityManager entities,
            NpcRegistry registry, ResidentProfileService profiles,
            ResidentDialogueService dialogues, ResidentQuestService quests, long cooldownMillis) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.dialogues = Objects.requireNonNull(dialogues, "dialogues");
        this.quests = Objects.requireNonNull(quests, "quests");
        if (cooldownMillis < 0) throw new IllegalArgumentException("Cooldown cannot be negative.");
        this.cooldownMillis = cooldownMillis;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResidentInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        NpcId npcId = entities.getNpcId(event.getRightClicked()).orElse(null);
        if (npcId == null) return;
        var record = registry.get(npcId).orElse(null);
        if (record == null || record.type() != NpcType.RESIDENT) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        InteractionKey key = new InteractionKey(playerId, npcId);
        Long previous = lastInteractions.get(key);
        if (previous != null && now - previous < cooldownMillis) return;
        lastInteractions.put(key, now);

        if (lastInteractions.size() > MAX_COOLDOWN_ENTRIES) {
            lastInteractions.entrySet().removeIf(entry ->
                    now - entry.getValue() > Math.max(cooldownMillis * 4, 10_000L));
        }

        String residentName = record.name();
        CompletableFuture<ConversationContext> context = profiles.find(npcId)
                .thenCombine(quests.reputation(playerId), ProfileAndReputation::new)
                .thenCombine(quests.activeAssignments(playerId), (profileAndReputation, assignments) ->
                        new ProfileAndAssignments(profileAndReputation.profile(),
                                profileAndReputation.reputation(), assignments))
                .thenCombine(quests.reviewRequired(REVIEW_LOOKUP_LIMIT),
                        (profileAndAssignments, reviews) -> {
                            if (profileAndAssignments.profile() == null
                                    || profileAndAssignments.profile().isEmpty()) {
                                throw new IllegalStateException("Resident profile is unavailable.");
                            }
                            return new ConversationContext(profileAndAssignments.profile().get(),
                                    profileAndAssignments.reputation(),
                                    profileAndAssignments.assignments(), reviews, List.of());
                        })
                .thenCompose(conversationContext -> quests.availableFor(conversationContext.profile(), npcId)
                        .thenApply(available -> new ConversationContext(conversationContext.profile(),
                                conversationContext.reputation(), conversationContext.activeAssignments(),
                                conversationContext.reviews(), available)));

        context.whenComplete((conversation, error) -> {
            if (!plugin.isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !player.isOnline()) return;
                    if (error != null) {
                        plugin.getLogger().warning("Could not load resident conversation context for "
                                + npcId + ": " + rootMessage(error));
                        player.sendMessage("§7Житель сейчас занят и не может поговорить.");
                        return;
                    }
                    dialogues.speak(player, conversation.profile(), residentName,
                            conversation.reputation());
                    sendQuestActions(player, npcId, residentName, conversation);
                });
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().warning("Could not schedule resident dialogue: "
                        + schedulingError.getMessage());
            }
        });
    }

    private void sendQuestActions(Player player, NpcId residentId, String residentName,
            ConversationContext context) {
        for (ResidentQuest quest : context.availableQuests()) {
            boolean active = context.activeAssignments().stream().anyMatch(assignment ->
                    assignment.residentId().equals(residentId)
                            && assignment.questId().equals(quest.id()));
            boolean underReview = context.reviews().stream().anyMatch(review ->
                    review.playerId().equals(player.getUniqueId())
                            && review.residentId().equals(residentId)
                            && review.questId().equals(quest.id()));

            Component prefix = Component.text("[" + residentName + "] ");
            if (underReview) {
                player.sendMessage(prefix.append(Component.text(
                        "  » Сдача этого поручения ожидает проверки администрации.")));
            } else if (active) {
                Component submit = Component.text("  » Сдать ресурсы по поручению")
                        .clickEvent(ClickEvent.runCommand("/raskolnpc resident quest submit "
                                + residentId + " " + quest.id()));
                player.sendMessage(prefix.append(submit));
            } else {
                Component offer = Component.text("  » Взять поручение: ")
                        .append(Component.text(quest.requiredAmount() + " "
                                + quest.requiredMaterial() + " (награда: "
                                + quest.reputationReward() + " репутации)")
                                .clickEvent(ClickEvent.runCommand("/raskolnpc resident quest accept "
                                        + residentId + " " + quest.id())));
                player.sendMessage(prefix.append(offer));
            }
        }
    }


    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
