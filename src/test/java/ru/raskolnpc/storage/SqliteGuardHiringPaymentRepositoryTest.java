package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardHiringPayment;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteGuardHiringPaymentRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private GuardHiringPaymentRepository payments;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-hiring-test"));
        database = new DatabaseManager(plugin, "hiring-tests.sqlite", 1);
        database.initialize().join();
        payments = new SqliteGuardHiringPaymentRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void storesHiringAttemptAndAllowsSingleStateTransition() {
        GuardHiringPayment payment = payment("hire-1");
        assertTrue(payments.create(payment).join());
        assertFalse(payments.create(payment).join());
        assertTrue(payments.transition("hire-1", "IN_PROGRESS", "DEBIT_CONFIRMED", null).join());
        assertFalse(payments.transition("hire-1", "IN_PROGRESS", "DEBIT_FAILED", "late").join());
        assertEquals("DEBIT_CONFIRMED",
                payments.findById("hire-1").join().orElseThrow().status());
    }

    @Test
    void startupMovesUnfinishedDebitAndRefundAttemptsToManualReview() {
        payments.create(payment("debit-pending")).join();
        payments.create(payment("refund-pending")).join();
        payments.create(payment("complete")).join();
        payments.transition("refund-pending", "IN_PROGRESS", "DEBIT_CONFIRMED", null).join();
        payments.transition("refund-pending", "DEBIT_CONFIRMED", "REFUND_IN_PROGRESS", null).join();
        payments.transition("complete", "IN_PROGRESS", "DEBIT_CONFIRMED", null).join();
        payments.transition("complete", "DEBIT_CONFIRMED", "COMPLETED", null).join();

        assertEquals(2, payments.moveUnfinishedToReview("restart").join());
        assertEquals("REVIEW_REQUIRED",
                payments.findById("debit-pending").join().orElseThrow().status());
        assertEquals("REVIEW_REQUIRED",
                payments.findById("refund-pending").join().orElseThrow().status());
        assertEquals("COMPLETED",
                payments.findById("complete").join().orElseThrow().status());
    }

    @Test
    void administratorResolutionRecordsUuidWithoutCallingEconomy() {
        payments.create(payment("review")).join();
        payments.transition("review", "IN_PROGRESS", "REVIEW_REQUIRED", "unknown result").join();
        UUID admin = UUID.randomUUID();

        assertTrue(payments.resolveReview("review", "DEBIT_CONFIRMED", admin).join());
        assertFalse(payments.resolveReview("review", "DEBIT_FAILED", admin).join());
        GuardHiringPayment resolved = payments.findById("review").join().orElseThrow();
        assertEquals("DEBIT_CONFIRMED", resolved.status());
        assertEquals(admin.toString(), resolved.resolvedBy());
        assertNotNull(resolved.resolvedAt());
    }

    @Test
    void canAttachNpcIdOnlyOnce() {
        payments.create(payment("attach")).join();
        assertTrue(payments.attachNpcId("attach", UUID.randomUUID().toString()).join());
        assertFalse(payments.attachNpcId("attach", UUID.randomUUID().toString()).join());
    }

    private GuardHiringPayment payment(String id) {
        return new GuardHiringPayment(id, null, UUID.randomUUID(), "town-1", "nation-1",
                "Guard", 2, 200.0, 20.0, "IN_PROGRESS", null, null, null);
    }
}
