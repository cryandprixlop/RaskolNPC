package ru.raskolnpc.npc.resident.quest;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.storage.ResidentBusinessStockBatch;
import ru.raskolnpc.storage.ResidentProductQuality;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResidentTradeOfferTest {
    @Test
    void aggregatesOnlyMatchingQualityAndCalculatesPriceInMinorUnits() {
        NpcId resident = NpcId.create();
        List<ResidentBusinessStockBatch> batches = List.of(
                batch("fine-1", 3, ResidentProductQuality.FINE),
                batch("fine-2", 2, ResidentProductQuality.FINE));

        ResidentTradeOffer offer = ResidentTradeOffer.fromBatches(
                resident, "iron_ingot", ResidentProductQuality.FINE, 100, batches);

        assertEquals(resident, offer.residentId());
        assertEquals("IRON_INGOT", offer.material());
        assertEquals(ResidentProductQuality.FINE, offer.quality());
        assertEquals(5, offer.availableAmount());
        assertEquals(125, offer.unitPriceMinor());
        assertEquals(625, offer.totalValueMinor());
    }

    @Test
    void rejectsMixedQualityOrMaterialBatches() {
        assertThrows(IllegalArgumentException.class, () -> ResidentTradeOffer.fromBatches(
                NpcId.create(), "IRON_INGOT", ResidentProductQuality.FINE, 100,
                List.of(batch("standard", 1, ResidentProductQuality.STANDARD))));
    }

    @Test
    void rejectsEmptyOffersAndInconsistentTotals() {
        assertThrows(IllegalArgumentException.class, () -> ResidentTradeOffer.fromBatches(
                NpcId.create(), "IRON_INGOT", ResidentProductQuality.FINE, 100, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResidentTradeOffer(
                NpcId.create(), "IRON_INGOT", ResidentProductQuality.FINE, 2, 125, 200));
    }

    private static ResidentBusinessStockBatch batch(
            String id, int amount, ResidentProductQuality quality) {
        return new ResidentBusinessStockBatch(id, "business", "MINER", "IRON_INGOT",
                amount, quality, 1, "TEST", null);
    }
}
