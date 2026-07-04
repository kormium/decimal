package io.github.kormium.decimal

// Mixed-operand arithmetic with the exact integer types, both directions: `price * 3`,
// `3 * price`, `total + 1L`. Deliberately NOT provided for Double/Float — a binary float
// carries representation noise, and letting it into exact arithmetic silently defeats the
// point of a decimal type. Convert explicitly (`0.1.toDecimal()`) when you mean it.

/** `this + other`, exact; see [Decimal.plus]. */
public operator fun Decimal.plus(other: Int): Decimal = this + Decimal.of(other)

/** `this + other`, exact; see [Decimal.plus]. */
public operator fun Decimal.plus(other: Long): Decimal = this + Decimal.of(other)

/** `this - other`, exact; see [Decimal.minus]. */
public operator fun Decimal.minus(other: Int): Decimal = this - Decimal.of(other)

/** `this - other`, exact; see [Decimal.minus]. */
public operator fun Decimal.minus(other: Long): Decimal = this - Decimal.of(other)

/** `this × other`, exact; see [Decimal.times]. */
public operator fun Decimal.times(other: Int): Decimal = this * Decimal.of(other)

/** `this × other`, exact; see [Decimal.times]. */
public operator fun Decimal.times(other: Long): Decimal = this * Decimal.of(other)

/** `this / other` rounded to [scale] with [roundingMode]; see [Decimal.div]. */
public fun Decimal.div(other: Int, scale: Int, roundingMode: RoundingMode): Decimal =
    div(Decimal.of(other), scale, roundingMode)

/** `this / other` rounded to [scale] with [roundingMode]; see [Decimal.div]. */
public fun Decimal.div(other: Long, scale: Int, roundingMode: RoundingMode): Decimal =
    div(Decimal.of(other), scale, roundingMode)

/** `this % other`, exact; see [Decimal.rem]. */
public operator fun Decimal.rem(other: Int): Decimal = this % Decimal.of(other)

/** `this % other`, exact; see [Decimal.rem]. */
public operator fun Decimal.rem(other: Long): Decimal = this % Decimal.of(other)

/** `this + other`, exact; see [Decimal.plus]. */
public operator fun Int.plus(other: Decimal): Decimal = Decimal.of(this) + other

/** `this + other`, exact; see [Decimal.plus]. */
public operator fun Long.plus(other: Decimal): Decimal = Decimal.of(this) + other

/** `this - other`, exact; see [Decimal.minus]. */
public operator fun Int.minus(other: Decimal): Decimal = Decimal.of(this) - other

/** `this - other`, exact; see [Decimal.minus]. */
public operator fun Long.minus(other: Decimal): Decimal = Decimal.of(this) - other

/** `this × other`, exact; see [Decimal.times]. */
public operator fun Int.times(other: Decimal): Decimal = Decimal.of(this) * other

/** `this × other`, exact; see [Decimal.times]. */
public operator fun Long.times(other: Decimal): Decimal = Decimal.of(this) * other
