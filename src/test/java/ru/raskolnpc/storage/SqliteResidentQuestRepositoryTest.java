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
    void businessStockBatchesAreReadInDeterministicFifoOrder() {
        NpcRecord resident = createResident();
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resident_business_stock_batches(
                        batch_id, business_id, profession, material, amount,
                        quality, created_at, source_type, source_ref
                    ) VALUES (?, 'town:town', 'MINER', 'IRON_INGOT', ?, ?, ?, 'TEST', NULL)
                    """)) {
                insert.setString(1, "later-batch");
                insert.setInt(2, 7);
                insert.setString(3, "FINE");
                insert.setLong(4, 200);
                insert.executeUpdate();
                insert.setString(1, "earlier-batch");
                insert.setInt(2, 3);
                insert.setString(3, "STANDARD");
                insert.setLong(4, 100);
                insert.executeUpdate();
            }
            return null;
        }).join();

        List<ResidentBusinessStockBatch> batches =
                quests.findBusinessStockBatches(resident.id(), "IRON_INGOT").join();

        assertEquals(2, batches.size());
        assertEquals("earlier-batch", batches.get(0).batchId());
        assertEquals(ResidentProductQuality.STANDARD, batches.get(0).quality());
        assertEquals(3, batches.get(0).amount());
        assertEquals("later-batch", batches.get(1).batchId());
        assertEquals(ResidentProductQuality.FINE, batches.get(1).quality());
        assertEquals(7, batches.get(1).amount());
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
    void businessStockTransfersAtomicallyBetweenProfessionsInSameTown() {
        NpcRecord miner = createResident(ResidentProfession.MINER, "town");
        NpcRecord blacksmith = createResident(ResidentProfession.BLACKSMITH, "town");
        deliverStock(miner, "miner_iron_delivery", "IRON_INGOT", 32, 96);

        assertTrue(quests.transferBusinessStock(
                miner.id(), blacksmith.id(), "IRON_INGOT", 16, 64).join());

        assertEquals(16, quests.getBusinessStock(miner.id(), "IRON_INGOT").join());
        assertEquals(16, quests.getBusinessStock(blacksmith.id(), "IRON_INGOT").join());
    }

    @Test
    void businessStockTransferRejectsDifferentTownsAndSameProfession() {
        NpcRecord miner = createResident(ResidentProfession.MINER, "town");
        NpcRecord otherTownBlacksmith = createResident(ResidentProfession.BLACKSMITH, "other-town");
        NpcRecord secondMiner = createResident(ResidentProfession.MINER, "town");
        deliverStock(miner, "miner_iron_delivery", "IRON_INGOT", 32, 96);

        assertFalse(quests.transferBusinessStock(
                miner.id(), otherTownBlacksmith.id(), "IRON_INGOT", 16, 64).join());
        assertFalse(quests.transferBusinessStock(
                miner.id(), secondMiner.id(), "IRON_INGOT", 16, 64).join());
        assertEquals(32, quests.getBusinessStock(miner.id(), "IRON_INGOT").join());
        assertEquals(0, quests.getBusinessStock(otherTownBlacksmith.id(), "IRON_INGOT").join());
    }

    @Test
    void businessStockTransferRejectsInsufficientSourceAndFullTarget() {
        NpcRecord miner = createResident(ResidentProfession.MINER, "town");
        NpcRecord blacksmith = createResident(ResidentProfession.BLACKSMITH, "town");
        deliverStock(miner, "miner_iron_delivery", "IRON_INGOT", 32, 96);

        assertFalse(quests.transferBusinessStock(
                miner.id(), blacksmith.id(), "IRON_INGOT", 33, 64).join());
        assertEquals(32, quests.getBusinessStock(miner.id(), "IRON_INGOT").join());

        assertTrue(quests.transferBusinessStock(
                miner.id(), blacksmith.id(), "IRON_INGOT", 16, 24).join());
        // A later caller cannot raise the already persisted capacity by passing a larger value.
        assertFalse(quests.transferBusinessStock(
                miner.id(), blacksmith.id(), "IRON_INGOT", 16, 64).join());
        assertEquals(16, quests.getBusinessStock(miner.id(), "IRON_INGOT").join());
        assertEquals(16, quests.getBusinessStock(blacksmith.id(), "IRON_INGOT").join());
    }

    @Test
    void withdrawalConsumesOldestQualityBatchesFirst() {
        NpcRecord resident = createResident(ResidentProfession.MINER, "town");
        deliverStock(resident, "iron_delivery", "IRON_INGOT", 32, 64);
        replaceStockBatches(resident, "IRON_INGOT",
                List.of(new BatchSeed("old-fine", 10, "FINE", 100),
                        new BatchSeed("new-masterwork", 22, "MASTERWORK", 200)));

        assertTrue(quests.withdrawBusinessStock(resident.id(), "IRON_INGOT", 12).join());

        List<ResidentBusinessStockBatch> remaining =
                quests.findBusinessStockBatches(resident.id(), "IRON_INGOT").join();
        assertEquals(1, remaining.size());
        assertEquals("new-masterwork", remaining.get(0).batchId());
        assertEquals(20, remaining.get(0).amount());
        assertEquals(20, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
    }

    @Test
    void professionTransferPreservesQualityDistributionAcrossBatches() {
        NpcRecord miner = createResident(ResidentProfession.MINER, "town");
        NpcRecord blacksmith = createResident(ResidentProfession.BLACKSMITH, "town");
        deliverStock(miner, "iron_delivery", "IRON_INGOT", 32, 64);
        replaceStockBatches(miner, "IRON_INGOT",
                List.of(new BatchSeed("old-fine", 8, "FINE", 100),
                        new BatchSeed("new-masterwork", 24, "MASTERWORK", 200)));

        assertTrue(quests.transferBusinessStock(
                miner.id(), blacksmith.id(), "IRON_INGOT", 16, 64).join());

        List<ResidentBusinessStockBatch> source =
                quests.findBusinessStockBatches(miner.id(), "IRON_INGOT").join();
        List<ResidentBusinessStockBatch> target =
                quests.findBusinessStockBatches(blacksmith.id(), "IRON_INGOT").join();
        assertEquals(1, source.size());
        assertEquals(16, source.get(0).amount());
        assertEquals(ResidentProductQuality.MASTERWORK, source.get(0).quality());
        assertEquals(2, target.size());
        assertEquals(8, target.get(0).amount());
        assertEquals(ResidentProductQuality.FINE, target.get(0).quality());
        assertEquals(8, target.get(1).amount());
        assertEquals(ResidentProductQuality.MASTERWORK, target.get(1).quality());
        assertEquals(16, quests.getBusinessStock(miner.id(), "IRON_INGOT").join());
        assertEquals(16, quests.getBusinessStock(blacksmith.id(), "IRON_INGOT").join());
    }

    private record BatchSeed(String id, int amount, String quality, long createdAt) { }

    private void replaceStockBatches(NpcRecord resident, String material, List<BatchSeed> seeds) {
        database.transaction(connection -> {
            try (var delete = connection.prepareStatement("""
                    DELETE FROM resident_business_stock_batches
                    WHERE business_id = 'town:town' AND profession = ? AND material = ?
                    """)) {
                delete.setString(1, residentProfession(resident).name());
                delete.setString(2, material);
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resident_business_stock_batches(
                        batch_id, business_id, profession, material, amount,
                        quality, created_at, source_type, source_ref
                    ) VALUES (?, 'town:town', ?, ?, ?, ?, ?, 'TEST', NULL)
                    """)) {
                for (BatchSeed seed : seeds) {
                    insert.setString(1, seed.id());
                    insert.setString(2, residentProfession(resident).name());
                    insert.setString(3, material);
                    insert.setInt(4, seed.amount());
                    insert.setString(5, seed.quality());
                    insert.setLong(6, seed.createdAt());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        }).join();
    }

    private ResidentProfession residentProfession(NpcRecord resident) {
        return profiles.findByNpcId(resident.id()).join().orElseThrow().profession();
    }

    @Test
    void productionConsumesAllInputsAndCreditsOutputAtomically() {
        NpcRecord blacksmith = createResident(ResidentProfession.BLACKSMITH, "town");
        deliverStock(blacksmith, "iron_delivery", "IRON_INGOT", 12, 64);
        deliverStock(blacksmith, "coal_delivery", "COAL", 8, 64);

        assertTrue(quests.produceBusinessStock(blacksmith.id(),
                java.util.Map.of("IRON_INGOT", 4, "COAL", 2),
                "IRON_SWORD", 2, 16).join());

        assertEquals(8, quests.getBusinessStock(blacksmith.id(), "IRON_INGOT").join());
        assertEquals(6, quests.getBusinessStock(blacksmith.id(), "COAL").join());
        assertEquals(2, quests.getBusinessStock(blacksmith.id(), "IRON_SWORD").join());
        assertEquals(8, quests.findBusinessStockBatches(blacksmith.id(), "IRON_INGOT")
                .join().stream().mapToInt(ResidentBusinessStockBatch::amount).sum());
        assertEquals(6, quests.findBusinessStockBatches(blacksmith.id(), "COAL")
                .join().stream().mapToInt(ResidentBusinessStockBatch::amount).sum());
        List<ResidentBusinessStockBatch> outputBatches =
                quests.findBusinessStockBatches(blacksmith.id(), "IRON_SWORD").join();
        assertEquals(1, outputBatches.size());
        assertEquals(2, outputBatches.get(0).amount());
        assertEquals(ResidentProductQuality.STANDARD, outputBatches.get(0).quality());
    }

    @Test
    void productionRollsBackAllInputsWhenOneIsMissingOrOutputCapacityIsFull() {
        NpcRecord blacksmith = createResident(ResidentProfession.BLACKSMITH, "town");
        deliverStock(blacksmith, "iron_delivery", "IRON_INGOT", 12, 64);
        deliverStock(blacksmith, "coal_delivery", "COAL", 2, 64);
        deliverStock(blacksmith, "sword_delivery", "IRON_SWORD", 15, 16);

        assertFalse(quests.produceBusinessStock(blacksmith.id(),
                java.util.Map.of("IRON_INGOT", 4, "COAL", 3),
                "IRON_SWORD", 1, 16).join());
        assertFalse(quests.produceBusinessStock(blacksmith.id(),
                java.util.Map.of("IRON_INGOT", 4, "COAL", 2),
                "IRON_SWORD", 2, 16).join());

        assertEquals(12, quests.getBusinessStock(blacksmith.id(), "IRON_INGOT").join());
        assertEquals(2, quests.getBusinessStock(blacksmith.id(), "COAL").join());
        assertEquals(15, quests.getBusinessStock(blacksmith.id(), "IRON_SWORD").join());
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
        assertEquals(23, quests.findBusinessStockBatches(resident.id(), "IRON_INGOT")
                .join().stream().mapToInt(ResidentBusinessStockBatch::amount).sum());
        assertEquals(0, quests.consumeBusinessStockDaily(0).join());
        assertThrows(IllegalArgumentException.class,
                () -> quests.consumeBusinessStockDaily(101));
    }

    @Test
    void activeDeliveriesReserveTheRemainingBusinessDemand() {
        NpcRecord resident = createResident();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();

        assertTrue(quests.accept(firstPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 32).join());
        assertFalse(quests.accept(secondPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 32).join());
        assertEquals(0, quests.getBusinessStock(resident.id(), "IRON_INGOT").join());
    }

    @Test
    void abandonedAssignmentReleasesReservedBusinessDemand() {
        NpcRecord resident = createResident();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();

        assertTrue(quests.accept(firstPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 32).join());
        assertTrue(quests.abandon(firstPlayer, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.findActiveForPlayer(firstPlayer).join().isEmpty());
        assertTrue(quests.accept(secondPlayer, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 32).join());
    }

    @Test
    void cannotAbandonSubmissionThatNeedsReview() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 32, 96).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery",
                "IRON_INGOT", 5, 32, 96).join());
        assertTrue(quests.markSubmissionForReview(player, resident.id(),
                "miner_iron_delivery", "uncertain").join());

        assertFalse(quests.abandon(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(1, quests.findReviews(10).join().size());
    }

    private NpcRecord createResident() {
        return createResident(ResidentProfession.MINER, "town");
    }

    private NpcRecord createResident(ResidentProfession profession, String townId) {
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, profession.name(),
                null, townId, "nation", "{}");
        npcs.insert(resident).join();
        profiles.save(new ResidentProfile(resident.id(), 30, profession,
                Set.of(), 75, 75, 75, 75, 75, 75, java.util.Map.of())).join();
        return resident;
    }

    private void deliverStock(NpcRecord resident, String questId,
            String material, int amount, int capacity) {
        UUID player = UUID.randomUUID();
        assertTrue(quests.accept(player, resident.id(), questId, material, amount, capacity).join());
        assertTrue(quests.reserveSubmission(player, resident.id(), questId,
                material, 5, amount, capacity).join());
        assertTrue(quests.completeSubmission(player, resident.id(), questId, 5).join());
    }
}
