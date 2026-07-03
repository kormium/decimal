package io.github.kormium.decimal

import java.math.BigDecimal

/**
 * This value as a [java.math.BigDecimal], preserving scale exactly.
 * Throws [ArithmeticException] for [Decimal.NaN] and the infinities —
 * `java.math.BigDecimal` has no special values.
 */
public fun Decimal.toJavaBigDecimal(): BigDecimal {
    if (!isFinite) throw ArithmeticException("$this cannot be represented as java.math.BigDecimal")
    return BigDecimal(toString())
}

/** This value as a [Decimal], preserving scale exactly. */
public fun BigDecimal.toKormiumDecimal(): Decimal = Decimal.parse(toString())

/** Maps this mode onto its `java.math.RoundingMode` namesake. */
public fun RoundingMode.toJavaRoundingMode(): java.math.RoundingMode = when (this) {
    RoundingMode.UP -> java.math.RoundingMode.UP
    RoundingMode.DOWN -> java.math.RoundingMode.DOWN
    RoundingMode.CEILING -> java.math.RoundingMode.CEILING
    RoundingMode.FLOOR -> java.math.RoundingMode.FLOOR
    RoundingMode.HALF_UP -> java.math.RoundingMode.HALF_UP
    RoundingMode.HALF_DOWN -> java.math.RoundingMode.HALF_DOWN
    RoundingMode.HALF_EVEN -> java.math.RoundingMode.HALF_EVEN
    RoundingMode.UNNECESSARY -> java.math.RoundingMode.UNNECESSARY
}
