package ru.raskolnpc.storage;

import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GuardRentPaymentRepository {

    CompletableFuture<Boolean> createIfAbsent(GuardRentPayment payment);

    CompletableFuture<Optional<GuardRentPayment>> findById(String paymentId);

    CompletableFuture<Optional<GuardRentPayment>> findByDue(NpcId npcId, long rentDueAt);

    CompletableFuture<List<GuardRentPayment>> findByStatus(GuardRentPaymentStatus status);

    CompletableFuture<Boolean> markReviewRequired(String paymentId, String reason);

    CompletableFuture<Boolean> markPaid(String paymentId);

    CompletableFuture<Boolean> markFailed(String paymentId, String reason);

    CompletableFuture<Integer> moveUnfinishedToReview(String reason);

    CompletableFuture<Boolean> applyPaid(String paymentId, NpcRecord updatedRecord);

    CompletableFuture<Boolean> applyDebt(String paymentId, NpcRecord updatedRecord);

    CompletableFuture<Boolean> applyDebtAndQueueDeletion(String paymentId, NpcId npcId);

    CompletableFuture<Boolean> resolveReview(
            String paymentId,
            GuardRentPaymentStatus resolution,
            UUID administratorUuid
    );
}
