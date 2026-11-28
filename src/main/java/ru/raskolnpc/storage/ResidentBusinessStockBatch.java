package ru.raskolnpc.storage;

import java.util.Objects;

/** One remaining quantity of a material whose quality and provenance never change. */
public record ResidentBusinessStockBatch(
        String batchId,
        String businessId,
        String profession,
        String material,
        int amount,
        ResidentProductQuality quality,
        long createdAt,
        String sourceType,
        String sourceRef
) {
    public ResidentBusinessStockBatch {
        batchId = requireText(batchId, "batchId");
        businessId = requireText(businessId, "businessId");
        profession = requireText(profession, "profession");
        material = requireText(material, "material");
        Objects.requireNonNull(quality, "quality");
        sourceType = requireText(sourceType, "sourceType");
        if (amount < 1) throw new IllegalArgumentException("Batch amount must be positive.");
        if (createdAt < 0) throw new IllegalArgumentException("Batch creation time cannot be negative.");
        if (sourceRef != null && sourceRef.length() > 128) {
            throw new IllegalArgumentException("Batch source reference cannot exceed 128 characters.");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1-128 characters.");
        }
        return normalized;
    }
}
