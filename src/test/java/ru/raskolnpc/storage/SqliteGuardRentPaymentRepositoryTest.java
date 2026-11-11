package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteGuardRentPaymentRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    private DatabaseManager database;
    private GuardRentPaymentRepository payments;
    private SqliteNpcRepository npcs;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-test"));

        database = new DatabaseManager(plugin, "rent-tests.sqlite", 1);
        database.initialize().join();
        payments = new SqliteGuardRentPaymentRepository(database);
        npcs = new SqliteNpcRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void uniqueNpcAndDueTimestampPreventsDuplicatePayment() {
        NpcRecord guard = insertGuard();
        GuardRentPayment first = payment("payment-1", guard.id(), 1_800_000_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        GuardRentPayment duplicate = payment("payment-2", first.npcId(), first.rentDueAt(),
                GuardRentPaymentStatus.IN_PROGRESS);

        assertTrue(payments.createIfAbsent(first).join());
        assertFalse(payments.createIfAbsent(duplicate).join());
        assertEquals("payment-1",
                payments.findByDue(first.npcId(), first.rentDueAt()).join().orElseThrow().paymentId());
    }

    @Test
    void startupRecoveryMovesOnlyUnfinishedPaymentsToManualReview() {
        GuardRentPayment unfinished = payment("unfinished", insertGuard().id(), 1_800_000_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        GuardRentPayment paid = payment("paid", insertGuard().id(), 1_800_000_001L,
                GuardRentPaymentStatus.PAID);
        GuardRentPayment failed = payment("failed", insertGuard().id(), 1_800_000_002L,
                GuardRentPaymentStatus.FAILED);

        payments.createIfAbsent(unfinished).join();
        payments.createIfAbsent(paid).join();
        payments.createIfAbsent(failed).join();

        assertEquals(1, payments.moveUnfinishedToReview("restart").join());
        assertEquals(GuardRentPaymentStatus.REVIEW_REQUIRED,
                payments.findById("unfinished").join().orElseThrow().status());
        assertEquals(GuardRentPaymentStatus.PAID,
                payments.findById("paid").join().orElseThrow().status());
        assertEquals(GuardRentPaymentStatus.FAILED,
                payments.findById("failed").join().orElseThrow().status());
    }

    @Test
    void paymentCanLeaveInProgressOnlyOnce() {
        GuardRentPayment payment = payment("transition", insertGuard().id(), 1_800_000_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        payments.createIfAbsent(payment).join();

        assertTrue(payments.markPaid(payment.paymentId()).join());
        assertFalse(payments.markFailed(payment.paymentId(), "late false result").join());
        assertFalse(payments.markReviewRequired(payment.paymentId(), "late ambiguity").join());
        assertEquals(GuardRentPaymentStatus.PAID,
                payments.findById(payment.paymentId()).join().orElseThrow().status());
    }

    @Test
    void paidApplicationAtomicallyUpdatesNpcAndLedger() {
        NpcRecord original = insertGuard();
        GuardRentPayment payment = payment("apply-paid", original.id(), 1_800_000_000L,
                GuardRentPaymentStatus.PAID);
        payments.createIfAbsent(payment).join();
        NpcRecord updated = original.withState(NpcState.ACTIVE);

        assertTrue(payments.applyPaid(payment.paymentId(), updated).join());
        assertEquals(NpcState.ACTIVE, npcs.findById(original.id()).join().orElseThrow().state());
        assertEquals(GuardRentPaymentStatus.PAID_APPLIED,
                payments.findById(payment.paymentId()).join().orElseThrow().status());
    }

    @Test
    void failedApplicationRollsBackNpcUpdateWhenPaymentStateDoesNotMatch() {
        NpcRecord original = insertGuard();
        GuardRentPayment payment = payment("wrong-state", original.id(), 1_800_000_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        payments.createIfAbsent(payment).join();
        NpcRecord attemptedUpdate = original.withState(NpcState.INACTIVE);

        assertThrows(CompletionException.class,
                () -> payments.applyPaid(payment.paymentId(), attemptedUpdate).join());
        assertEquals(original.state(), npcs.findById(original.id()).join().orElseThrow().state());
        assertEquals(GuardRentPaymentStatus.IN_PROGRESS,
                payments.findById(payment.paymentId()).join().orElseThrow().status());
    }

    @Test
    void thirdConfirmedFailureQueuesDeletionAndKeepsPaymentHistory() {
        NpcRecord original = insertGuard();
        GuardRentPayment payment = payment("third-failure", original.id(), 1_800_000_000L,
                GuardRentPaymentStatus.FAILED);
        payments.createIfAbsent(payment).join();

        assertTrue(payments.applyDebtAndQueueDeletion(payment.paymentId(), original.id()).join());
        assertTrue(npcs.findById(original.id()).join().isEmpty());
        assertEquals(GuardRentPaymentStatus.DEBT_APPLIED,
                payments.findById(payment.paymentId()).join().orElseThrow().status());
        assertEquals(1, pendingDeletionCount(original.id()));
    }

    @Test
    void deletionAndQueueAreRolledBackIfPaymentCannotBeApplied() {
        NpcRecord original = insertGuard();
        GuardRentPayment payment = payment("not-failed", original.id(), 1_800_000_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        payments.createIfAbsent(payment).join();

        assertThrows(CompletionException.class,
                () -> payments.applyDebtAndQueueDeletion(payment.paymentId(), original.id()).join());
        assertTrue(npcs.findById(original.id()).join().isPresent());
        assertEquals(0, pendingDeletionCount(original.id()));
        assertEquals(GuardRentPaymentStatus.IN_PROGRESS,
                payments.findById(payment.paymentId()).join().orElseThrow().status());
    }

    @Test
    void administratorCanResolveOnlyReviewPaymentAndResolutionIsAudited() {
        GuardRentPayment payment = payment("manual-review", insertGuard().id(), 1_800_000_000L,
                GuardRentPaymentStatus.REVIEW_REQUIRED);
        payments.createIfAbsent(payment).join();
        UUID administrator = UUID.randomUUID();

        assertTrue(payments.resolveReview(payment.paymentId(),
                GuardRentPaymentStatus.PAID, administrator).join());
        GuardRentPayment resolved = payments.findById(payment.paymentId()).join().orElseThrow();
        assertEquals(GuardRentPaymentStatus.PAID, resolved.status());
        assertEquals(administrator.toString(), resolved.resolvedBy());
        assertNotNull(resolved.resolvedAt());
        assertFalse(payments.resolveReview(payment.paymentId(),
                GuardRentPaymentStatus.FAILED, UUID.randomUUID()).join());
    }

    @Test
    void reviewResolutionRejectsNonFinalStatuses() {
        assertThrows(CompletionException.class, () -> payments.resolveReview(
                "any-id", GuardRentPaymentStatus.IN_PROGRESS, UUID.randomUUID()).join());
    }

    @Test
    void paymentCreationIsRejectedAfterGuardWasDeleted() {
        NpcRecord guard = insertGuard();
        SqliteNpcDeletionRepository deletions = new SqliteNpcDeletionRepository(database);
        assertTrue(deletions.deleteAndQueue(guard.id()).join());

        GuardRentPayment payment = payment("after-deletion", guard.id(), 1_800_300_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        assertFalse(payments.createIfAbsent(payment).join());
        assertTrue(payments.findById(payment.paymentId()).join().isEmpty());
    }

    @Test
    void paymentCreatedBeforeDeletionPreventsGuardDeletion() {
        NpcRecord guard = insertGuard();
        GuardRentPayment payment = payment("before-deletion", guard.id(), 1_800_400_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        assertTrue(payments.createIfAbsent(payment).join());

        CompletionException error = assertThrows(CompletionException.class,
                () -> new SqliteNpcDeletionRepository(database)
                        .deleteAndQueue(guard.id()).join());
        assertTrue(rootMessage(error).contains(payment.paymentId()));
        assertTrue(npcs.findById(guard.id()).join().isPresent());
        assertEquals(0, pendingDeletionCount(guard.id()));
    }

    @Test
    void paymentCreationIsRejectedForResidentEvenWhenNpcExists() {
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Test resident",
                null, "town-test", null, "{}");
        npcs.insert(resident).join();

        GuardRentPayment payment = payment("resident-payment", resident.id(), 1_800_500_000L,
                GuardRentPaymentStatus.IN_PROGRESS);
        assertFalse(payments.createIfAbsent(payment).join());
        assertTrue(payments.findById(payment.paymentId()).join().isEmpty());
    }

    @Test
    void ordinaryDeletionIsBlockedForEveryUnresolvedRentStatus() {
        GuardRentPaymentStatus[] blockedStatuses = {
                GuardRentPaymentStatus.IN_PROGRESS,
                GuardRentPaymentStatus.PAID,
                GuardRentPaymentStatus.FAILED,
                GuardRentPaymentStatus.REVIEW_REQUIRED
        };

        long dueAt = 1_800_100_000L;
        for (GuardRentPaymentStatus status : blockedStatuses) {
            NpcRecord guard = insertGuard();
            String paymentId = "blocked-" + status.name().toLowerCase();
            payments.createIfAbsent(payment(paymentId, guard.id(), dueAt++, status)).join();

            CompletionException error = assertThrows(CompletionException.class,
                    () -> new SqliteNpcDeletionRepository(database)
                            .deleteAndQueue(guard.id()).join());
            assertTrue(rootMessage(error).contains(paymentId));
            assertTrue(npcs.findById(guard.id()).join().isPresent());
            assertEquals(0, pendingDeletionCount(guard.id()));
        }
    }

    @Test
    void ordinaryDeletionIsAllowedAfterRentPaymentWasApplied() {
        GuardRentPaymentStatus[] completedStatuses = {
                GuardRentPaymentStatus.PAID_APPLIED,
                GuardRentPaymentStatus.DEBT_APPLIED
        };

        long dueAt = 1_800_200_000L;
        for (GuardRentPaymentStatus status : completedStatuses) {
            NpcRecord guard = insertGuard();
            payments.createIfAbsent(payment("completed-" + status.name().toLowerCase(),
                    guard.id(), dueAt++, status)).join();

            assertTrue(new SqliteNpcDeletionRepository(database)
                    .deleteAndQueue(guard.id()).join());
            assertTrue(npcs.findById(guard.id()).join().isEmpty());
            assertEquals(1, pendingDeletionCount(guard.id()));
        }
    }

    @Test
    void ordinaryDeletionOfNonGuardIsNotBlockedByGuardRentLedger() {
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Test resident",
                null, "town-test", null, "{}");
        npcs.insert(resident).join();

        assertTrue(new SqliteNpcDeletionRepository(database)
                .deleteAndQueue(resident.id()).join());
        assertTrue(npcs.findById(resident.id()).join().isEmpty());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private NpcRecord insertGuard() {
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Test guard",
                null, "town-test", null, "{\"level\":1}");
        npcs.insert(record).join();
        return record;
    }

    private static GuardRentPayment payment(
            String id, NpcId npcId, long dueAt, GuardRentPaymentStatus status
    ) {
        return new GuardRentPayment(id, npcId, dueAt, "town-test", 25.0,
                status, 1, dueAt + 86_400, null, null, null);
    }

    private int pendingDeletionCount(NpcId npcId) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM pending_npc_deletions WHERE npc_id = ?")) {
                statement.setString(1, npcId.toString());
                try (var result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }).join();
    }
}
