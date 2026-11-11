package ru.raskolnpc.guard;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GuardRentServiceTest {
    @Test
    void storesDebtAndNextPaymentWithoutDroppingOtherGuardMetadata() {
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
    void resetsDebtAfterSuccessfulPayment() {
        String overdue = "{\"overdueDays\":2,\"nextRentAt\":100}";
        String paid = GuardRentService.metadata(overdue, 0, 86_500);

        assertEquals(0.0, GuardRentService.number(paid, "overdueDays", -1));
        assertEquals(86_500.0, GuardRentService.number(paid, "nextRentAt", -1));
    }

    @Test
    void updatesExistingMetadataInsteadOfDuplicatingKeys() {
        String json = "{\"overdueDays\":1,\"nextRentAt\":100}";
        String updated = GuardRentService.metadata(json, 2, 200);

        assertEquals(1, updated.split("\"overdueDays\"").length - 1);
        assertEquals(1, updated.split("\"nextRentAt\"").length - 1);
        assertEquals(2.0, GuardRentService.number(updated, "overdueDays", -1));
    }

    @Test
    void returnsFallbackForMissingMetadata() {
        assertEquals(-1.0, GuardRentService.number("{}", "dailyRent", -1));
        assertNull(GuardRentService.string("{}", "payerTownId"));
    }
    @Test
    void inactiveGuardRemainsInactiveAfterRecoveryDecision() {
        NpcRecord guard = new NpcRecord(NpcId.create(), NpcType.GUARD, "Gatekeeper",
                null, "town", "nation", NpcState.INACTIVE, "{}",
                Instant.now().toString(), Instant.now().toString(), null, null);
        assertFalse(GuardRentService.shouldGuardBeActive(guard));
        assertTrue(GuardRentService.shouldGuardBeActive(
                GuardRentService.copy(guard, NpcState.ACTIVE, "{}")));
    }

}
