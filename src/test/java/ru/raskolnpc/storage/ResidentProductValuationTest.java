package ru.raskolnpc.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResidentProductValuationTest {
    @Test
    void appliesDeterministicQualityMultipliersToUnitPrice() {
        assertEquals(80, ResidentProductValuation.unitPrice(100, ResidentProductQuality.BASIC));
        assertEquals(100, ResidentProductValuation.unitPrice(100, ResidentProductQuality.STANDARD));
        assertEquals(125, ResidentProductValuation.unitPrice(100, ResidentProductQuality.FINE));
        assertEquals(175, ResidentProductValuation.unitPrice(100, ResidentProductQuality.MASTERWORK));
    }

    @Test
    void roundsHalfUpInMinorCurrencyUnits() {
        assertEquals(1, ResidentProductValuation.unitPrice(1, ResidentProductQuality.FINE));
        assertEquals(2, ResidentProductValuation.unitPrice(1, ResidentProductQuality.MASTERWORK));
    }

    @Test
    void calculatesBulkValueWithoutFloatingPointArithmetic() {
        assertEquals(2_500, ResidentProductValuation.totalPrice(
                100, 20, ResidentProductQuality.FINE));
    }

    @Test
    void rejectsInvalidPricesAmountsAndOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> ResidentProductValuation.unitPrice(-1, ResidentProductQuality.STANDARD));
        assertThrows(IllegalArgumentException.class,
                () -> ResidentProductValuation.totalPrice(100, 0, ResidentProductQuality.STANDARD));
        assertThrows(ArithmeticException.class,
                () -> ResidentProductValuation.totalPrice(Long.MAX_VALUE, 2, ResidentProductQuality.STANDARD));
        assertThrows(NullPointerException.class,
                () -> ResidentProductValuation.unitPrice(100, null));
    }
}
