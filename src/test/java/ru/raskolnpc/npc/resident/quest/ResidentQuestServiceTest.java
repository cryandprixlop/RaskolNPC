package ru.raskolnpc.npc.resident.quest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;
import ru.raskolnpc.npc.resident.ResidentProfileService;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.storage.ResidentQuestRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentQuestServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsOnlyQuestMatchingResidentsProfession() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("resident-quest-service-test"));
        Files.writeString(temporaryDirectory.resolve("resident-quests.yml"), """
                quests:
                  miner_iron_delivery:
                    profession: MINER
                    required-material: IRON_INGOT
                    required-amount: 32
                    reputation-reward: 5
                  farmer_wheat_delivery:
                    profession: FARMER
                    required-material: WHEAT
                    required-amount: 16
                    reputation-reward: 3
                """);

        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Miner",
                null, "town", "nation", "{}");
        NpcRegistry registry = new NpcRegistry();
        registry.register(resident);
        ResidentProfile profile = new ResidentProfile(resident.id(), 30,
                ResidentProfession.MINER, Set.of(), 75, 75, 75, 75, 75, 75, Map.of());
        ResidentProfileService profiles = mock(ResidentProfileService.class);
        when(profiles.find(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));
        ResidentQuestRepository repository = mock(ResidentQuestRepository.class);
        when(repository.accept(any(), eq(resident.id()), eq("miner_iron_delivery"),
                eq("IRON_INGOT"), eq(32), eq(0)))
                .thenReturn(CompletableFuture.completedFuture(true));
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        ResidentQuestService service = new ResidentQuestService(
                plugin, profiles, registry, repository);

        assertEquals(ResidentQuestService.AcceptResult.ACCEPTED,
                service.accept(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(ResidentQuestService.AcceptResult.WRONG_PROFESSION,
                service.accept(player, resident.id(), "farmer_wheat_delivery").join());
        verify(repository, times(1)).accept(playerId, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 0);
    }

    @Test
    void doesNotOfferDeliveryWhenTownBusinessHasEnoughStock() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("resident-quest-demand-test"));
        Files.writeString(temporaryDirectory.resolve("resident-quests.yml"), """
                quests:
                  miner_iron_delivery:
                    profession: MINER
                    required-material: IRON_INGOT
                    required-amount: 32
                    reputation-reward: 5
                    stock-target: 32
                """);

        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Miner",
                null, "town", "nation", "{}");
        NpcRegistry registry = new NpcRegistry();
        registry.register(resident);
        ResidentProfile profile = new ResidentProfile(resident.id(), 30,
                ResidentProfession.MINER, Set.of(), 75, 75, 75, 75, 75, 75, Map.of());
        ResidentProfileService profiles = mock(ResidentProfileService.class);
        when(profiles.find(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));
        ResidentQuestRepository repository = mock(ResidentQuestRepository.class);
        when(repository.getBusinessStock(resident.id(), "IRON_INGOT"))
                .thenReturn(CompletableFuture.completedFuture(32));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        ResidentQuestService service = new ResidentQuestService(
                plugin, profiles, registry, repository);

        assertEquals(ResidentQuestService.AcceptResult.NO_BUSINESS_DEMAND,
                service.accept(player, resident.id(), "miner_iron_delivery").join());
        verify(repository, never()).accept(any(), any(), any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void failedReservationReleaseIsMovedToManualReview() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("resident-quest-release-test"));
        when(plugin.isEnabled()).thenReturn(true);
        Files.writeString(temporaryDirectory.resolve("resident-quests.yml"), """
                quests:
                  miner_iron_delivery:
                    profession: MINER
                    required-material: IRON_INGOT
                    required-amount: 32
                    reputation-reward: 5
                """);

        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Miner",
                null, "town", "nation", "{}");
        NpcRegistry registry = new NpcRegistry();
        registry.register(resident);
        ResidentQuestRepository repository = mock(ResidentQuestRepository.class);
        UUID playerId = UUID.randomUUID();
        when(repository.findActive(playerId, resident.id(), "miner_iron_delivery"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(
                        new ResidentQuest.Assignment(playerId, resident.id(), "miner_iron_delivery",
                                ResidentQuest.AssignmentStatus.ACCEPTED, Instant.now(), null))));
        when(repository.reserveSubmission(playerId, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 5, 32, 0))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repository.releaseSubmission(playerId, resident.id(), "miner_iron_delivery"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        when(repository.markSubmissionForReview(playerId, resident.id(), "miner_iron_delivery",
                "A submission with no inventory change could not release its reservation: database unavailable"))
                .thenReturn(CompletableFuture.completedFuture(true));

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.containsAtLeast(any(ItemStack.class), eq(32))).thenReturn(false);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return mock(BukkitTask.class);
        });

        ResidentQuestService service = new ResidentQuestService(
                plugin, mock(ResidentProfileService.class), registry, repository);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            assertEquals(ResidentQuestService.SubmitResult.REVIEW_REQUIRED,
                    service.submit(player, resident.id(), "miner_iron_delivery").join());
        }

        verify(repository).markSubmissionForReview(playerId, resident.id(),
                "miner_iron_delivery",
                "A submission with no inventory change could not release its reservation: database unavailable");
        verify(inventory, never()).removeItem(any(ItemStack.class));
    }
}
