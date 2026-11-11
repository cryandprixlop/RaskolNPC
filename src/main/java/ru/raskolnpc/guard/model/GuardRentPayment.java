package ru.raskolnpc.guard.model;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Objects;

public record GuardRentPayment(
        String paymentId,
        NpcId npcId,
        long rentDueAt,
        String townId,
        double amount,
        GuardRentPaymentStatus status,
        int targetDebtDays,
        long targetNextRentAt,
        String failureReason,
        String resolvedBy,
        String resolvedAt
) {
    public GuardRentPayment {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID must not be blank.");
        }
        Objects.requireNonNull(npcId, "npcId");
        if (rentDueAt <= 0) {
            throw new IllegalArgumentException("Rent due timestamp must be positive.");
        }
        if (townId == null || townId.isBlank()) {
            throw new IllegalArgumentException("Town ID must not be blank.");
        }
        if (!Double.isFinite(amount) || amount < 0.0) {
            throw new IllegalArgumentException("Payment amount must be finite and non-negative.");
        }
        Objects.requireNonNull(status, "status");
        if (targetDebtDays < 0 || targetDebtDays > 3) {
            throw new IllegalArgumentException("Target debt days must be between 0 and 3.");
        }
        if (targetNextRentAt <= 0) {
            throw new IllegalArgumentException("Next rent timestamp must be positive.");
        }
    }
}
