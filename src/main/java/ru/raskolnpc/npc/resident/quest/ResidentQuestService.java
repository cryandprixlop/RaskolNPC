package ru.raskolnpc.npc.resident.quest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.ResidentProfileService;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.storage.ResidentQuestRepository;
import ru.raskolnpc.storage.ResidentQuestReview;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Coordinates resident quest offers, acceptance and safe resource submissions. */
public final class ResidentQuestService {
    public record BusinessStockEntry(
            String questId, String material, int amount, int target,
            int deliveryAmount, boolean deliveryAvailable) { }

    public enum AcceptResult {
        ACCEPTED, ALREADY_ACTIVE, RESIDENT_NOT_FOUND, QUEST_NOT_FOUND,
        WRONG_PROFESSION, PROFILE_UNAVAILABLE, NO_BUSINESS_DEMAND
    }

    public enum SubmitResult {
        SUBMITTED, NO_ACTIVE_QUEST, SUBMISSION_PENDING, NOT_ENOUGH_ITEMS,
        INVALID_MATERIAL, PLAYER_OFFLINE, REVIEW_REQUIRED, RESIDENT_NOT_FOUND,
        QUEST_NOT_FOUND
    }

    private final Plugin plugin;
    private final ResidentProfileService profiles;
    private final NpcRegistry registry;
    private final ResidentQuestRepository repository;
    private final File catalogFile;
    private volatile ResidentQuestCatalog catalog = new ResidentQuestCatalog(java.util.Map.of());

    public ResidentQuestService(Plugin plugin, ResidentProfileService profiles,
            NpcRegistry registry, ResidentQuestRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data directory.");
        }
        this.catalogFile = new File(folder, "resident-quests.yml");
        if (!catalogFile.exists()) plugin.saveResource("resident-quests.yml", false);
        try {
            reload();
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not load resident-quests.yml.", exception);
        }
    }

    public synchronized void reload() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(catalogFile);
        catalog = ResidentQuestCatalog.from(yaml);
    }

    public List<ResidentQuest> availableFor(ResidentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return catalog.forProfession(profile.profession());
    }

    /** Returns profession quests whose full delivery still fits the town business stock target. */
    public CompletableFuture<List<ResidentQuest>> availableFor(
            ResidentProfile profile, NpcId residentId) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(residentId, "residentId");
        List<ResidentQuest> candidates = catalog.forProfession(profile.profession());
        List<CompletableFuture<Integer>> stockLookups = candidates.stream()
                .map(quest -> quest.stockTarget() == 0
                        ? CompletableFuture.completedFuture(0)
                        : repository.getBusinessStock(residentId, quest.requiredMaterial()))
                .toList();
        return CompletableFuture.allOf(stockLookups.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<ResidentQuest> available = new java.util.ArrayList<>();
                    for (int index = 0; index < candidates.size(); index++) {
                        ResidentQuest quest = candidates.get(index);
                        int stock = stockLookups.get(index).join();
                        if (quest.stockTarget() == 0
                                || (long) stock + quest.requiredAmount() <= quest.stockTarget()) {
                            available.add(quest);
                        }
                    }
                    return List.copyOf(available);
                });
    }

    public CompletableFuture<AcceptResult> accept(Player player, NpcId residentId, String questId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(residentId, "residentId");
        UUID playerId = player.getUniqueId();
        if (registry.get(residentId).filter(record -> record.type() == NpcType.RESIDENT).isEmpty()) {
            return CompletableFuture.completedFuture(AcceptResult.RESIDENT_NOT_FOUND);
        }
        ResidentQuest quest = catalog.find(questId).orElse(null);
        if (quest == null) return CompletableFuture.completedFuture(AcceptResult.QUEST_NOT_FOUND);

        return profiles.find(residentId).thenCompose(profile -> {
            if (profile.isEmpty()) return CompletableFuture.completedFuture(AcceptResult.PROFILE_UNAVAILABLE);
            if (profile.get().profession() != quest.profession()) {
                return CompletableFuture.completedFuture(AcceptResult.WRONG_PROFESSION);
            }
            return availableFor(profile.get(), residentId).thenCompose(available -> {
                if (available.stream().noneMatch(candidate -> candidate.id().equals(quest.id()))) {
                    return CompletableFuture.completedFuture(AcceptResult.NO_BUSINESS_DEMAND);
                }
                return repository.accept(playerId, residentId, quest.id(),
                                quest.requiredMaterial(), quest.requiredAmount(), quest.stockTarget())
                        .thenCompose(accepted -> accepted
                                ? CompletableFuture.completedFuture(AcceptResult.ACCEPTED)
                                : repository.findActive(playerId, residentId, quest.id()).thenApply(active ->
                                        active.isPresent() ? AcceptResult.ALREADY_ACTIVE
                                                : AcceptResult.NO_BUSINESS_DEMAND));
            });
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING,
                    "Could not accept resident quest " + questId + " for " + playerId, error);
            throw new java.util.concurrent.CompletionException(error);
        });
    }

    public CompletableFuture<Boolean> abandon(Player player, NpcId residentId, String questId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(residentId, "residentId");
        if (registry.get(residentId).filter(record -> record.type() == NpcType.RESIDENT).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        if (catalog.find(questId).isEmpty()) return CompletableFuture.completedFuture(false);
        return repository.abandon(player.getUniqueId(), residentId, questId);
    }

    /**
     * Reserves the assignment in SQLite, then checks/removes inventory only on the
     * server thread. SQLite completion and reputation credit share one transaction.
     */
    public CompletableFuture<SubmitResult> submit(Player player, NpcId residentId, String questId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(residentId, "residentId");
        UUID playerId = player.getUniqueId();
        if (registry.get(residentId).filter(record -> record.type() == NpcType.RESIDENT).isEmpty()) {
            return CompletableFuture.completedFuture(SubmitResult.RESIDENT_NOT_FOUND);
        }
        ResidentQuest quest = catalog.find(questId).orElse(null);
        if (quest == null) return CompletableFuture.completedFuture(SubmitResult.QUEST_NOT_FOUND);

        return repository.findActive(playerId, residentId, quest.id()).thenCompose(active -> {
            if (active.isEmpty()) return CompletableFuture.completedFuture(SubmitResult.NO_ACTIVE_QUEST);
            return repository.reserveSubmission(playerId, residentId, quest.id(), quest.requiredMaterial(),
                    quest.reputationReward(), quest.requiredAmount(), quest.stockTarget()).thenCompose(reserved -> {
                if (!reserved) return CompletableFuture.completedFuture(SubmitResult.SUBMISSION_PENDING);
                CompletableFuture<SubmitResult> result = new CompletableFuture<>();
                try {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            removeItemsAndFinalize(player, playerId, residentId, quest, result));
                } catch (RuntimeException schedulingError) {
                    repository.releaseSubmission(playerId, residentId, quest.id())
                            .whenComplete((released, releaseError) -> {
                                if (releaseError == null && Boolean.TRUE.equals(released)) {
                                    result.completeExceptionally(schedulingError);
                                } else {
                                    String reason = "Could not schedule inventory submission and could not "
                                            + "release its reservation: " + messageOf(releaseError);
                                    markForReview(playerId, residentId, quest.id(), reason, result);
                                }
                            });
                }
                return result;
            });
        });
    }

    private void removeItemsAndFinalize(Player player, UUID playerId, NpcId residentId,
            ResidentQuest quest, CompletableFuture<SubmitResult> result) {
        if (!plugin.isEnabled()) {
            markForReview(playerId, residentId, quest.id(),
                    "Plugin disabled before the reserved submission could be processed.", result);
            return;
        }
        if (!player.isOnline()) {
            releaseReservation(playerId, residentId, quest.id(), SubmitResult.PLAYER_OFFLINE, result);
            return;
        }

        Material material = Material.matchMaterial(quest.requiredMaterial());
        if (material == null || !material.isItem() || material.isAir()) {
            releaseReservation(playerId, residentId, quest.id(), SubmitResult.INVALID_MATERIAL, result);
            return;
        }

        try {
            if (!player.getInventory().containsAtLeast(new ItemStack(material), quest.requiredAmount())) {
                releaseReservation(playerId, residentId, quest.id(), SubmitResult.NOT_ENOUGH_ITEMS, result);
                return;
            }
            var leftovers = player.getInventory().removeItem(
                    new ItemStack(material, quest.requiredAmount()));
            if (!leftovers.isEmpty()) {
                markForReview(playerId, residentId, quest.id(),
                        "Inventory removal returned leftovers after the pre-check.", result);
                return;
            }
        } catch (RuntimeException inventoryError) {
            markForReview(playerId, residentId, quest.id(),
                    "Inventory mutation failed; outcome may be partial: " + messageOf(inventoryError), result);
            return;
        }

        repository.completeSubmission(playerId, residentId, quest.id(), quest.reputationReward())
                .whenComplete((completed, error) -> {
                    if (error != null || !Boolean.TRUE.equals(completed)) {
                        String reason = error == null
                                ? "Assignment could not be finalized after inventory removal."
                                : "Database finalization failed after inventory removal: " + messageOf(error);
                        markForReview(playerId, residentId, quest.id(), reason, result);
                    } else {
                        result.complete(SubmitResult.SUBMITTED);
                    }
                });
    }

    /**
     * A failed release must never be reported as an ordinary retryable outcome:
     * the reservation would otherwise remain IN_PROGRESS and block future attempts.
     */
    private void releaseReservation(UUID playerId, NpcId residentId, String questId,
            SubmitResult releasedResult, CompletableFuture<SubmitResult> result) {
        repository.releaseSubmission(playerId, residentId, questId).whenComplete((released, error) -> {
            if (error == null && Boolean.TRUE.equals(released)) {
                result.complete(releasedResult);
                return;
            }
            String reason = "A submission with no inventory change could not release its reservation: "
                    + (error == null ? "reservation was not in IN_PROGRESS." : messageOf(error));
            markForReview(playerId, residentId, questId, reason, result);
        });
    }

    private void markForReview(UUID playerId, NpcId residentId, String questId,
            String reason, CompletableFuture<SubmitResult> result) {
        repository.markSubmissionForReview(playerId, residentId, questId, reason)
                .whenComplete((marked, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not mark resident quest submission for manual review.", error);
                    } else if (!Boolean.TRUE.equals(marked)) {
                        plugin.getLogger().warning(
                                "Resident quest submission could not be moved to REVIEW_REQUIRED for "
                                        + playerId + "/" + residentId + "/" + questId
                                        + "; startup recovery will inspect any remaining IN_PROGRESS record.");
                    }
                    result.complete(SubmitResult.REVIEW_REQUIRED);
                });
    }

    public CompletableFuture<List<ResidentQuest.Assignment>> activeAssignments(UUID playerId) {
        return repository.findActiveForPlayer(Objects.requireNonNull(playerId, "playerId"));
    }

    public CompletableFuture<List<ResidentQuestReview>> reviewRequired(int limit) {
        return repository.findReviews(limit);
    }

    public CompletableFuture<Boolean> resolveReview(UUID playerId, NpcId residentId,
            String questId, boolean complete) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(questId, "questId");
        if (!complete) return repository.resolveReview(playerId, residentId, questId, false, 0);

        return repository.findReview(playerId, residentId, questId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(false);
            ResidentQuestReview review = found.get();
            int fallbackReward;
            if (review.reputationReward() != null) {
                fallbackReward = review.reputationReward();
            } else {
                ResidentQuest definition = catalog.find(questId).orElseThrow(() ->
                        new IllegalStateException("Quest definition is missing; legacy review cannot be safely rewarded."));
                fallbackReward = definition.reputationReward();
            }
            return repository.resolveReview(playerId, residentId, questId, true, fallbackReward);
        });
    }

    public CompletableFuture<List<BusinessStockEntry>> stockReport(NpcId residentId) {
        Objects.requireNonNull(residentId, "residentId");
        if (registry.get(residentId).filter(record -> record.type() == NpcType.RESIDENT).isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Resident not found."));
        }
        return profiles.find(residentId).thenCompose(profile -> {
            if (profile.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Resident profile is unavailable."));
            }
            List<ResidentQuest> definitions = catalog.forProfession(profile.get().profession());
            List<CompletableFuture<Integer>> stocks = definitions.stream()
                    .map(quest -> repository.getBusinessStock(residentId, quest.requiredMaterial()))
                    .toList();
            return CompletableFuture.allOf(stocks.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        List<BusinessStockEntry> report = new java.util.ArrayList<>();
                        for (int index = 0; index < definitions.size(); index++) {
                            ResidentQuest quest = definitions.get(index);
                            int amount = stocks.get(index).join();
                            boolean available = quest.stockTarget() == 0
                                    || (long) amount + quest.requiredAmount() <= quest.stockTarget();
                            report.add(new BusinessStockEntry(quest.id(), quest.requiredMaterial(),
                                    amount, quest.stockTarget(), quest.requiredAmount(), available));
                        }
                        return List.copyOf(report);
                    });
        });
    }

    public CompletableFuture<Integer> businessStock(NpcId residentId, String material) {
        return repository.getBusinessStock(
                Objects.requireNonNull(residentId, "residentId"),
                Objects.requireNonNull(material, "material"));
    }

    /** Used by a future market adapter to reserve goods without allowing negative stock. */
    public CompletableFuture<Boolean> withdrawBusinessStock(
            NpcId residentId, String material, int amount) {
        return repository.withdrawBusinessStock(
                Objects.requireNonNull(residentId, "residentId"),
                Objects.requireNonNull(material, "material"), amount);
    }

    public CompletableFuture<Integer> reputation(UUID playerId) {
        return repository.getReputation(Objects.requireNonNull(playerId, "playerId"));
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "unknown persistence result";
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
