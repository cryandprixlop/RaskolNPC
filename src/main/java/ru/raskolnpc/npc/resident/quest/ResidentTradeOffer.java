package ru.raskolnpc.npc.resident.quest;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.storage.ResidentBusinessStockBatch;
import ru.raskolnpc.storage.ResidentProductQuality;
import ru.raskolnpc.storage.ResidentProductValuation;

import java.util.List;
import java.util.Objects;

/** Read-only market listing for one material and one product-quality tier. */
public record ResidentTradeOffer(
        NpcId residentId,
        String material,
        ResidentProductQuality quality,
        int availableAmount,
        long unitPriceMinor,
        long totalValueMinor
) {
    public ResidentTradeOffer {
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(quality, "quality");
        material = normalizeMaterial(material);
        if (availableAmount < 1) throw new IllegalArgumentException("Available amount must be positive.");
        if (unitPriceMinor < 0 || totalValueMinor < 0) {
            throw new IllegalArgumentException("Offer prices cannot be negative.");
        }
        if (Math.multiplyExact(unitPriceMinor, (long) availableAmount) != totalValueMinor) {
            throw new IllegalArgumentException("Offer total does not match its unit price and amount.");
        }
    }

    /** Builds an offer only from matching persisted batches; no stock is reserved here. */
    public static ResidentTradeOffer fromBatches(
            NpcId residentId, String material, ResidentProductQuality quality,
            long basePriceMinor, List<ResidentBusinessStockBatch> batches) {
        Objects.requireNonNull(batches, "batches");
        String normalized = normalizeMaterial(material);
        int amount = 0;
        for (ResidentBusinessStockBatch batch : batches) {
            Objects.requireNonNull(batch, "batch");
            if (!batch.material().equals(normalized) || batch.quality() != quality) {
                throw new IllegalArgumentException("Offer batches must match the requested material and quality.");
            }
            amount = Math.addExact(amount, batch.amount());
        }
        if (amount == 0) throw new IllegalArgumentException("Cannot create an offer without stock.");
        long unitPrice = ResidentProductValuation.unitPrice(basePriceMinor, quality);
        long total = Math.multiplyExact(unitPrice, (long) amount);
        return new ResidentTradeOffer(residentId, normalized, quality, amount, unitPrice, total);
    }

    private static String normalizeMaterial(String material) {
        Objects.requireNonNull(material, "material");
        String normalized = material.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("Material must contain 1-128 characters.");
        }
        return normalized;
    }
}
