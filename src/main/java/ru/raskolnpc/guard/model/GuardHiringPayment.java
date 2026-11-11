package ru.raskolnpc.guard.model;

import java.util.Objects;
import java.util.UUID;

public record GuardHiringPayment(
        String paymentId,
        String npcId,
        UUID ownerUuid,
        String townId,
        String nationId,
        String guardName,
        int level,
        double hireCost,
        double dailyRent,
        String status,
        String failureReason,
        String resolvedBy,
        String resolvedAt
) {
    public GuardHiringPayment {
        if (paymentId == null || paymentId.isBlank()) throw new IllegalArgumentException("Payment ID is required.");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        if (townId == null || townId.isBlank()) throw new IllegalArgumentException("Town ID is required.");
        if (nationId == null || nationId.isBlank()) throw new IllegalArgumentException("Nation ID is required.");
        if (guardName == null || guardName.isBlank() || guardName.length() > 64) throw new IllegalArgumentException("Invalid guard name.");
        if (level < 1 || level > 10) throw new IllegalArgumentException("Guard level must be between 1 and 10.");
        if (!Double.isFinite(hireCost) || hireCost < 0 || !Double.isFinite(dailyRent) || dailyRent < 0) {
            throw new IllegalArgumentException("Guard costs must be finite and non-negative.");
        }
        Objects.requireNonNull(status, "status");
    }
}
