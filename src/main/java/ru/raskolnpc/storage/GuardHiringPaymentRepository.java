package ru.raskolnpc.storage;

import ru.raskolnpc.guard.model.GuardHiringPayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GuardHiringPaymentRepository {
    CompletableFuture<Boolean> create(GuardHiringPayment payment);
    CompletableFuture<Boolean> attachNpcId(String paymentId, String npcId);
    CompletableFuture<Boolean> transition(String paymentId, String expectedStatus,
                                           String newStatus, String reason);
    CompletableFuture<Optional<GuardHiringPayment>> findById(String paymentId);
    CompletableFuture<List<GuardHiringPayment>> findReviewRequired();
    CompletableFuture<Integer> moveUnfinishedToReview(String reason);
    CompletableFuture<Boolean> resolveReview(String paymentId, String resolution, UUID administratorUuid);
}
