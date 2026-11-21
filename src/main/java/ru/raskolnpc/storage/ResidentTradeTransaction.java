package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;

import java.util.Objects;
import java.util.UUID;

/** Durable journal entry for one resident market purchase. */
public record ResidentTradeTransaction(
        String transactionId,
        String idempotencyKey,
        UUID buyerId,
        NpcId residentId,
        String material,
        ResidentProductQuality quality,
        int amount,
        long unitPriceMinor,
        long totalPriceMinor,
        Status status,
        String providerReference,
        String lastError,
        long createdAt,
        long updatedAt
) {
    public enum Status {
        STOCK_RESERVED,
        PAYMENT_PENDING,
        PAID,
        DELIVERED,
        CANCELLED,
        REFUND_PENDING,
        REFUNDED,
        REVIEW_REQUIRED
    }

    public ResidentTradeTransaction {
        transactionId = requireText(transactionId, "transactionId", 64);
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 128);
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(status, "status");
        material = requireText(material, "material", 64).toUpperCase(java.util.Locale.ROOT);
        if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("Invalid trade amount.");
        if (unitPriceMinor < 0 || totalPriceMinor < 0
                || Math.multiplyExact(unitPriceMinor, (long) amount) != totalPriceMinor) {
            throw new IllegalArgumentException("Invalid trade price.");
        }
        if (createdAt < 1 || updatedAt < createdAt) throw new IllegalArgumentException("Invalid timestamps.");
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1-" + maxLength + " characters.");
        }
        return normalized;
    }
}
