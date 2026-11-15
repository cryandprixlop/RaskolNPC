package ru.raskolnpc.storage;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Converts a material's configured base price (in the economy's smallest
 * currency unit) into a quality-adjusted price without floating-point drift.
 */
public final class ResidentProductValuation {
    private static final long BASIS = 10_000L;

    private ResidentProductValuation() { }

    /** Returns the unit price in minor currency units, rounded half up. */
    public static long unitPrice(long basePriceMinor, ResidentProductQuality quality) {
        if (basePriceMinor < 0) {
            throw new IllegalArgumentException("Base price cannot be negative.");
        }
        Objects.requireNonNull(quality, "quality");
        long multiplier = multiplierBasisPoints(quality);
        BigInteger numerator = BigInteger.valueOf(basePriceMinor)
                .multiply(BigInteger.valueOf(multiplier))
                .add(BigInteger.valueOf(BASIS / 2));
        return numerator.divide(BigInteger.valueOf(BASIS)).longValueExact();
    }

    /** Returns the total listing value in minor currency units. */
    public static long totalPrice(long basePriceMinor, int amount, ResidentProductQuality quality) {
        if (amount < 1) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        return Math.multiplyExact(unitPrice(basePriceMinor, quality), (long) amount);
    }

    /** Multipliers are fixed-point values: 10,000 basis points equals 100%. */
    public static long multiplierBasisPoints(ResidentProductQuality quality) {
        return switch (Objects.requireNonNull(quality, "quality")) {
            case BASIC -> 8_000L;
            case STANDARD -> 10_000L;
            case FINE -> 12_500L;
            case MASTERWORK -> 17_500L;
        };
    }
}
