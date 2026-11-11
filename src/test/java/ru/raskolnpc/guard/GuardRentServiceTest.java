package ru.raskolnpc.guard;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;

import static org.junit.jupiter.api.Assertions.*;

class GuardRentServiceTest {

    @Test
    void updatesExistingMetadataInsteadOfDuplicatingKeys() {
        String json = "{\"overdueDays\":1,\"nextRentAt\":100}";
        String updated = GuardRentService.metadata(json, 2, 200);

        assertEquals(1, updated.split("\"overdueDays\"").length - 1);
        assertEquals(1, updated.split("\"nextRentAt\"").length - 1);
        assertEquals(2.0, GuardRentService.number(updated, "overdueDays", -1));
        assertEquals(200.0, GuardRentService.number(updated, "nextRentAt", -1));
    }

    @Test
    void preservesUnrelatedGuardMetadata() {
        String original = "{\"level\":3,\"dailyRent\":30.0,\"payerTownId\":\"town-uuid\"}";
        String updated = GuardRentService.metadata(original, 2, 1_800_000_000L);

        assertEquals(3.0, GuardRentService.number(updated, "level", -1));
        assertEquals(30.0, GuardRentService.number(updated, "dailyRent", -1));
        assertEquals(2.0, GuardRentService.number(updated, "overdueDays", -1));
        assertEquals(1_800_000_000.0,
                GuardRentService.number(updated, "nextRentAt", -1));
        assertEquals("town-uuid", GuardRentService.string(updated, "payerTownId"));
    }

    @Test
    void returnsFallbackForMissingMetadata() {
        assertEquals(-1.0, GuardRentService.number("{}", "dailyRent", -1));
        assertNull(GuardRentService.string("{}", "payerTownId"));
    }

    @Test
    void paymentIdentifiersAreUuidBacked() {
        NpcId id = NpcId.create();
        assertEquals(id, NpcId.fromString(id.toString()));
    }
}
