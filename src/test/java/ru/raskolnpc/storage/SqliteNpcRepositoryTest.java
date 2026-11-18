package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteNpcRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository repository;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-npc-repository-test"));
        database = new DatabaseManager(plugin, "npc-tests.sqlite", 1);
        database.initialize().join();
        repository = new SqliteNpcRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void terminalTradeHistoryDoesNotPreventResidentDeletion() {
        NpcRecord resident = createResidentWithProfile("resident-terminal");
        insertTrade(resident.id().toString(), "trade-terminal", "DELIVERED");

        SqliteNpcDeletionRepository deletions = new SqliteNpcDeletionRepository(database);
        assertTrue(deletions.deleteAndQueue(resident.id()).join());
        assertTrue(repository.findById(resident.id()).join().isEmpty());

        database.query(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT resident_npc_id, status
                    FROM resident_trade_transactions WHERE transaction_id = ?
                    """)) {
                query.setString(1, "trade-terminal");
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(resident.id().toString(), result.getString("resident_npc_id"));
                    assertEquals("DELIVERED", result.getString("status"));
                    assertFalse(result.next());
                }
            }
            try (var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM resident_profiles WHERE npc_id = ?")) {
                query.setString(1, resident.id().toString());
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            }
            return null;
        }).join();
    }

    @Test
    void residentDeletionIsBlockedWhileTradeNeedsReconciliation() {
        NpcRecord resident = createResidentWithProfile("resident-review");
        insertTrade(resident.id().toString(), "trade-review", "REVIEW_REQUIRED");

        SqliteNpcDeletionRepository deletions = new SqliteNpcDeletionRepository(database);
        java.util.concurrent.CompletionException error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> deletions.deleteAndQueue(resident.id()).join());
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(cause.getMessage().contains(
                "Cannot delete resident while a trade requires reconciliation: trade-review"));
        assertTrue(repository.findById(resident.id()).join().isPresent());
        assertTrue(deletions.findPending().join().isEmpty());
    }

    private NpcRecord createResidentWithProfile(String name) {
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, name,
                null, "town-1", "nation-1", "{}");
        repository.insert(resident).join();
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
        }).join();
        return resident;
    }

    private void insertTrade(String residentId, String transactionId, String status) {
        database.execute(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resident_trade_transactions(
                        transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                        material, quality, amount, unit_price_minor, total_price_minor,
                        status, created_at, updated_at, business_id, profession
                    ) VALUES (?, ?, ?, ?, 'IRON_INGOT', 'STANDARD', 2, 50, 100, ?, 100, 100,
                              'town:town-1', 'MINER')
                    """)) {
                insert.setString(1, transactionId);
                insert.setString(2, "key-" + transactionId);
                insert.setString(3, UUID.randomUUID().toString());
                insert.setString(4, residentId);
                insert.setString(5, status);
                insert.executeUpdate();
            }
        }).join();
    }

    @Test
    void persistsSpawnLocationBeforeEntityBindingExists() {
        NpcSpawnLocation spawnLocation = new NpcSpawnLocation(
                UUID.randomUUID(), 12.5, 72.0, -8.25, 135.0f, 10.0f);
        NpcRecord creating = NpcRecord.create(NpcType.GUARD, "Interrupted guard",
                UUID.randomUUID(), "town-1", "nation-1", "{\"level\":1}", spawnLocation);

        assertNull(creating.entityUuid());
        repository.insert(creating).join();

        NpcRecord restored = repository.findById(creating.id()).join().orElseThrow();
        assertNull(restored.entityUuid());
        assertEquals(spawnLocation, restored.spawnLocation());
        assertEquals(creating.id(), restored.id());
    }
}
