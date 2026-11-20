package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteResidentTradeRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcs;
    private SqliteResidentTradeRepository trades;
    private NpcRecord resident;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-trade-repository-test"));
        database = new DatabaseManager(plugin, "trade-tests.sqlite", 1);
        database.initialize().join();
        npcs = new SqliteNpcRepository(database);
        trades = new SqliteResidentTradeRepository(database);
        resident = NpcRecord.create(NpcType.RESIDENT, "Trade resident",
                null, "town-1", "nation-1", "{}");
        npcs.insert(resident).join();
        database.execute(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resident_profiles(
                        npc_id, age, profession, hunger, energy, rest,
                        social, housing_satisfaction, mood
                    ) VALUES (?, 30, 'MINER', 75, 75, 75, 75, 75, 75)
                    """)) {
                insert.setString(1, resident.id().toString());
                insert.executeUpdate();
            }
            try (var stock = connection.prepareStatement("""
                    INSERT INTO resident_business_stock(
                        business_id, profession, material, amount, capacity, updated_at
                    ) VALUES ('town:town-1', 'MINER', 'IRON_INGOT', 10, 100, ?)
                    """)) {
                stock.setLong(1, System.currentTimeMillis());
                stock.executeUpdate();
            }
            try (var batch = connection.prepareStatement("""
                    INSERT INTO resident_business_stock_batches(
                        batch_id, business_id, profession, material, amount,
                        quality, created_at, source_type, source_ref
                    ) VALUES ('batch-fine', 'town:town-1', 'MINER', 'IRON_INGOT',
                              10, 'FINE', 1, 'TEST', NULL)
                    """)) {
                batch.executeUpdate();
            }
            return null;
        }).join();
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void cancelRestoresAggregateStockAndOriginalQualityExactlyOnce() {
        ResidentTradeTransaction trade = reserve("cancel-key");
        assertEquals(7, stockAmount());
        assertEquals(7, batchAmount("FINE"));

        assertTrue(trades.cancelAndRelease(trade.transactionId(), "buyer cancelled").join());
        assertFalse(trades.cancelAndRelease(trade.transactionId(), "duplicate retry").join());

        assertEquals(10, stockAmount());
        assertEquals(10, batchAmount("FINE"));
        assertEquals(0, batchAmount("STANDARD"));
        assertEquals(ResidentTradeTransaction.Status.CANCELLED,
                trades.find(trade.transactionId()).join().orElseThrow().status());
    }

    @Test
    void reviewResolutionRestoresStockAndQualityOnlyOnFirstDecision() {
        ResidentTradeTransaction trade = reserve("review-key");
        assertTrue(trades.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.REVIEW_REQUIRED, null, "uncertain outcome").join());

        assertTrue(trades.resolveReview(trade.transactionId(),
                ResidentTradeRepository.ReviewResolution.CANCELLED_UNPAID, "provider confirms no charge").join());
        assertFalse(trades.resolveReview(trade.transactionId(),
                ResidentTradeRepository.ReviewResolution.CANCELLED_UNPAID, "duplicate admin action").join());

        assertEquals(10, stockAmount());
        assertEquals(10, batchAmount("FINE"));
        assertEquals(ResidentTradeTransaction.Status.CANCELLED,
                trades.find(trade.transactionId()).join().orElseThrow().status());
    }

    @Test
    void cancellationRestoresReservedStockEvenAfterBusinessCapacityWasReduced() {
        ResidentTradeTransaction trade = reserve("capacity-shrink-key");
        database.execute(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE resident_business_stock
                    SET capacity = 7
                    WHERE business_id = 'town:town-1' AND profession = 'MINER'
                      AND material = 'IRON_INGOT'
                    """)) {
                assertEquals(1, update.executeUpdate());
            }
            return null;
        }).join();

        assertTrue(trades.cancelAndRelease(trade.transactionId(), "release reservation").join());
        assertEquals(10, stockAmount());
        assertEquals(10, batchAmount("FINE"));
        assertEquals(7, stockCapacity());
    }

    @Test
    void restartRecoveryMovesEveryUnfinishedTradeToReviewAndIsIdempotent() {
        ResidentTradeTransaction trade = reserve("recovery-key");
        assertTrue(trades.transition(trade.transactionId(),
                ResidentTradeTransaction.Status.STOCK_RESERVED,
                ResidentTradeTransaction.Status.PAYMENT_PENDING, null, null).join());

        assertEquals(1, trades.recoverUnfinished().join());
        assertEquals(0, trades.recoverUnfinished().join());
        assertEquals(ResidentTradeTransaction.Status.REVIEW_REQUIRED,
                trades.find(trade.transactionId()).join().orElseThrow().status());
        assertEquals(7, stockAmount());
        assertEquals(7, batchAmount("FINE"));
    }

    private ResidentTradeTransaction reserve(String key) {
        return trades.reserve(key, UUID.randomUUID(), resident.id(), "IRON_INGOT",
                ResidentProductQuality.FINE, 3, 125).join().orElseThrow();
    }

    private int stockAmount() {
        return database.query(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT amount FROM resident_business_stock
                    WHERE business_id = 'town:town-1' AND profession = 'MINER'
                      AND material = 'IRON_INGOT'
                    """);
                 var result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }).join();
    }

    private int stockCapacity() {
        return database.query(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT capacity FROM resident_business_stock
                    WHERE business_id = 'town:town-1' AND profession = 'MINER'
                      AND material = 'IRON_INGOT'
                    """);
                 var result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }).join();
    }

    private int batchAmount(String quality) {
        return database.query(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT COALESCE(SUM(amount), 0) FROM resident_business_stock_batches
                    WHERE business_id = 'town:town-1' AND profession = 'MINER'
                      AND material = 'IRON_INGOT' AND quality = ?
                    """)) {
                query.setString(1, quality);
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            }
        }).join();
    }
}
