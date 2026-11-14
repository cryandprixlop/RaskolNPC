package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteResidentQuestRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcs;
    private SqliteResidentProfileRepository profiles;
    private SqliteResidentQuestRepository quests;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("resident-quest-test"));
        database = new DatabaseManager(plugin, "resident-quests.sqlite", 1);
        database.initialize().join();
        npcs = new SqliteNpcRepository(database);
        profiles = new SqliteResidentProfileRepository(database);
        quests = new SqliteResidentQuestRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void persistsAcceptanceAndPreventsDuplicateActiveAssignment() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join());
        assertFalse(quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join());
        assertEquals(1, quests.findActiveForPlayer(player).join().size());
        assertTrue(quests.findActive(player, resident.id(), "miner_iron_delivery").join().isPresent());
    }

    @Test
    void completionIsOneWayAndCannotBeRepeated() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();

        assertTrue(quests.complete(player, resident.id(), "miner_iron_delivery").join());
        assertFalse(quests.complete(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.findActiveForPlayer(player).join().isEmpty());
    }

    @Test
    void submissionCreditsReputationExactlyOnce() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();

        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertFalse(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertFalse(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(5, quests.getReputation(player).join());
        assertTrue(quests.findActiveForPlayer(player).join().isEmpty());
    }

    @Test
    void repeatingCompletedQuestAllowsNewSubmissionAndCreditsReputationAgain() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(5, quests.getReputation(player).join());

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(10, quests.getReputation(player).join());
    }

    @Test
    void onlyOneConcurrentSubmissionCanReserveAnActiveAssignment() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();

        CompletableFuture<Boolean> first =
                quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96);
        CompletableFuture<Boolean> second =
                quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96);
        CompletableFuture.allOf(first, second).join();

        assertEquals(1, List.of(first.join(), second.join()).stream()
                .filter(Boolean::booleanValue).count());
    }

    @Test
    void releasingReservationAllowsRetryWithoutCreditingReputation() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();

        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.releaseSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertFalse(quests.releaseSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertEquals(0, quests.getReputation(player).join());
    }

    @Test
    void uncertainSubmissionCanBeMarkedForReviewAndCannotBeRetried() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());

        assertTrue(quests.markSubmissionForReview(player, resident.id(),
                "miner_iron_delivery", "test failure").join());
        assertFalse(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertFalse(quests.releaseSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(0, quests.getReputation(player).join());
    }

    @Test
    void startupRecoveryMovesInProgressSubmissionToReviewAndPreventsReplay() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());

        assertEquals(1, quests.recoverUnfinishedSubmissions().join());
        assertFalse(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertEquals(0, quests.getReputation(player).join());
        assertEquals(1, quests.recoverUnfinishedSubmissions().join());
    }

    @Test
    void startupRecoveryDoesNotChangeCompletedSubmissions() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());

        assertEquals(0, quests.recoverUnfinishedSubmissions().join());
        assertEquals(5, quests.getReputation(player).join());

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
    }

    @Test
    void reviewQueueContainsReasonAndOriginalRewardSnapshot() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 7, 32, 96).join());
        assertTrue(quests.markSubmissionForReview(player, resident.id(),
                "miner_iron_delivery", "inventory/database boundary").join());

        List<ResidentQuestReview> reviews = quests.findReviews(20).join();

        assertEquals(1, reviews.size());
        assertEquals(player, reviews.getFirst().playerId());
        assertEquals(resident.id(), reviews.getFirst().residentId());
        assertEquals("miner_iron_delivery", reviews.getFirst().questId());
        assertEquals("inventory/database boundary", reviews.getFirst().reason());
        assertEquals(7, reviews.getFirst().reputationReward());
    }

    @Test
    void resolvingReviewAsCompleteCreditsSnapshotExactlyOnce() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 7, 32, 96).join());
        assertTrue(quests.markSubmissionForReview(player, resident.id(),
                "miner_iron_delivery", "uncertain result").join());

        assertTrue(quests.resolveReview(player, resident.id(),
                "miner_iron_delivery", true, 99).join());
        assertFalse(quests.resolveReview(player, resident.id(),
                "miner_iron_delivery", true, 99).join());
        assertEquals(7, quests.getReputation(player).join());
        assertTrue(quests.findActiveForPlayer(player).join().isEmpty());
        assertTrue(quests.findReviews(20).join().isEmpty());
    }

    @Test
    void resolvingReviewAsRetryAllowsResubmissionWithoutReward() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 32, 96).join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.markSubmissionForReview(player, resident.id(), "miner_iron_delivery",
                "uncertain result").join());

        assertTrue(quests.resolveReview(player, resident.id(),
                "miner_iron_delivery", false, 0).join());
        assertEquals(0, quests.getReputation(player).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery", "IRON_INGOT", 5, 32, 96).join());
    }

    @Test
    void deliveredResourcesIncreaseSharedTownBusinessStockAndSuppressFurtherDemand() {
        NpcRecord resident = createResident();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();

        assertEquals(0, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
        assertTrue(quests.accept(firstPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 32).join());
        assertTrue(quests.reserveSubmission(firstPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 5, 32, 32).join());
        assertTrue(quests.completeSubmission(firstPlayer, resident.id(),
                "miner_iron_delivery", 5).join());

        assertEquals(32, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
        assertFalse(quests.accept(secondPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 32).join());

        NpcRecord secondMiner = NpcRecord.create(NpcType.RESIDENT, "Second Miner",
                null, "town", "nation", "{}");
        npcs.insert(secondMiner).join();
        profiles.save(new ResidentProfile(secondMiner.id(), 35, ResidentProfession.MINER,
                Set.of(), 75, 75, 75, 75, 75, 75, java.util.Map.of())).join();
        assertEquals(32, quests.getBusinessStock(secondMiner.id(), "IRON_INGOT").join());
    }

    @Test
    void businessStockNeverExceedsConfiguredTarget() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 40).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 5, 32, 40).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(32, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
    }

    @Test
    void businessStockWithdrawalIsAtomicAndCannotBecomeNegative() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 96).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());

        assertTrue(quests.withdrawBusinessStock(resident.id(), "IRON_INGOT", 16).join());
        assertFalse(quests.withdrawBusinessStock(resident.id(), "IRON_INGOT", 17).join());
        assertEquals(16, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
    }

    @Test
    void dailyBusinessConsumptionReopensDemandWithoutNegativeStock() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 96).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());

        assertEquals(1, quests.consumeBusinessStockDaily(10).join());
        assertEquals(23, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
        assertEquals(0, quests.consumeBusinessStockDaily(0).join());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> quests.consumeBusinessStockDaily(101).join());
    }

    private NpcRecord createResident() {
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Miner",
                null, "town", "nation", "{}");
        npcs.insert(resident).join();
        profiles.save(new ResidentProfile(resident.id(), 30, ResidentProfession.MINER,
                Set.of(), 75, 75, 75, 75, 75, 75, java.util.Map.of())).join();
        return resident;
    }
}
