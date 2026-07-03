package io.github.kormium.decimal

/**
 * How to round a value when a result must be shortened to a given [Decimal.scale]
 * (see [Decimal.setScale]). The modes mirror `java.math.RoundingMode` name-for-name,
 * so JVM developers can transfer intuition (and code) directly.
 *
 * In the descriptions below, "discarded fraction" is the part of the value that does
 * not fit into the requested scale.
 */
public enum class RoundingMode {
    /** Round away from zero: any non-zero discarded fraction increments the result's magnitude. */
    UP,

    /** Round toward zero: the discarded fraction is simply dropped (truncation). */
    DOWN,

    /** Round toward positive infinity: increments iff the value is positive and the discarded fraction is non-zero. */
    CEILING,

    /** Round toward negative infinity: increments iff the value is negative and the discarded fraction is non-zero. */
    FLOOR,

    /** Round to nearest neighbor; ties (discarded fraction exactly one half) round away from zero. */
    HALF_UP,

    /** Round to nearest neighbor; ties round toward zero. */
    HALF_DOWN,

    /**
     * Round to nearest neighbor; ties round to the neighbor with an even last digit
     * ("banker's rounding" — statistically bias-free over many roundings).
     */
    HALF_EVEN,

    /**
     * Assert that no rounding is necessary: if the discarded fraction is non-zero,
     * an [ArithmeticException] is thrown instead of rounding.
     */
    UNNECESSARY,
}
