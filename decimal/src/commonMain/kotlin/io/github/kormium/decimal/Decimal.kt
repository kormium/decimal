package io.github.kormium.decimal

/**
 * An immutable arbitrary-precision decimal number for Kotlin Multiplatform.
 *
 * A finite [Decimal] is `sign × significand × 10^-scale`, where the significand is an
 * arbitrary-length digit sequence and [scale] says how many of those digits sit to the
 * right of the decimal point (negative scale shifts the point further right: `12E+3`
 * has scale `-3`). Trailing zeros are preserved — `1.50` keeps scale 2 — so a value
 * round-trips [parse] → [toString] exactly.
 *
 * Semantics follow `java.math.BigDecimal` wherever both define an operation
 * ([parse]/[toString]/[toPlainString] grammar and formatting, [scale]/[precision],
 * arithmetic scale rules, [setScale] rounding), with two deliberate divergences:
 *
 * - **[equals] compares numeric value**, not representation: `Decimal("2.5") == Decimal("2.50")`
 *   (`java.math.BigDecimal.equals` is scale-sensitive, a classic map-key trap). [compareTo]
 *   is consistent with [equals].
 * - **The special values [NaN], [POSITIVE_INFINITY] and [NEGATIVE_INFINITY] exist** (SQL
 *   backends emit them — PostgreSQL `numeric` accepts all three). Their ordering follows
 *   [Double.compareTo]: `-Infinity < finite < Infinity < NaN`, and `NaN == NaN`. Arithmetic
 *   propagates them IEEE-754-style. Conversions that cannot represent them throw
 *   [ArithmeticException].
 *
 * Instances are immutable and safe to share across threads.
 */
public class Decimal private constructor(
    private val negative: Boolean,
    /** Significand digits: `"0"` or digits with no leading zero and no sign. */
    private val digits: String,
    /**
     * Number of significand digits to the right of the decimal point; negative values
     * shift the point right (`12E+3` = significand 12, scale -3). 0 for special values.
     */
    public val scale: Int,
    private val special: Byte,
) : Comparable<Decimal> {

    /** Number of digits in the significand (1 for zero and for special values). */
    public val precision: Int get() = digits.length

    /** True for every value except [NaN], [POSITIVE_INFINITY] and [NEGATIVE_INFINITY]. */
    public val isFinite: Boolean get() = special == FINITE

    /** True only for [NaN]. */
    public val isNaN: Boolean get() = special == SP_NAN

    /** True for [POSITIVE_INFINITY] and [NEGATIVE_INFINITY]. */
    public val isInfinite: Boolean get() = special == SP_POS_INF || special == SP_NEG_INF

    /** True for a zero of any scale (`0`, `0.00`, `0E+5`). */
    public val isZero: Boolean get() = special == FINITE && digits == "0"

    /**
     * The sign: -1, 0 or 1. Infinities report ±1; [NaN] has no sign and throws
     * [ArithmeticException].
     */
    public fun signum(): Int = when (special) {
        SP_NAN -> throw ArithmeticException("NaN has no sign")
        SP_POS_INF -> 1
        SP_NEG_INF -> -1
        else -> if (digits == "0") 0 else if (negative) -1 else 1
    }

    // ---- sign / point movement ----

    /** The negation of this value. */
    public operator fun unaryMinus(): Decimal = when (special) {
        SP_NAN -> this
        SP_POS_INF -> NEGATIVE_INFINITY
        SP_NEG_INF -> POSITIVE_INFINITY
        else -> finite(!negative, digits, scale)
    }

    /** This value, kept as-is (present for symmetry with [unaryMinus]). */
    public operator fun unaryPlus(): Decimal = this

    /** The absolute value. */
    public fun abs(): Decimal = if (negative || special == SP_NEG_INF) -this else this

    /**
     * Moves the decimal point [n] places to the left (equivalent to dividing by 10^n
     * exactly). The result's scale is `max(scale + n, 0)`, matching `java.math.BigDecimal`.
     */
    public fun movePointLeft(n: Int): Decimal {
        if (special != FINITE) return this
        // Per the java.math.BigDecimal contract, the result scale is max(scale + n, 0) — even
        // for n == 0: a negative scale pads out to plain form. (JDK ≤17 short-circuited n == 0,
        // violating its own javadoc; JDK 21+ conforms. We follow the documented behavior, so
        // the differential oracle must run on JDK 21+.)
        val newScale = scale.toLong() + n
        if (newScale >= 0) {
            checkScale(newScale)
            return finite(negative, digits, newScale.toInt())
        }
        if (-newScale > MAX_PLAIN_PAD) throw ArithmeticException("Scale overflow: $newScale")
        return finite(negative, padZerosRight(digits, (-newScale).toInt()), 0)
    }

    /** Moves the decimal point [n] places to the right; see [movePointLeft] for the scale rule. */
    public fun movePointRight(n: Int): Decimal {
        if (special != FINITE) return this
        if (n == Int.MIN_VALUE) throw ArithmeticException("Scale overflow")
        return movePointLeft(-n)
    }

    /**
     * The numerically equal value with all trailing zeros removed from the significand
     * (`600.0` → `6E+2`, `0.00` → `0`).
     */
    public fun stripTrailingZeros(): Decimal {
        if (special != FINITE) return this
        if (digits == "0") return if (scale == 0) this else finite(false, "0", 0)
        var end = digits.length
        var newScale = scale.toLong()
        while (end > 1 && digits[end - 1] == '0') {
            end--
            newScale--
        }
        if (end == digits.length) return this
        checkScale(newScale)
        return finite(negative, digits.substring(0, end), newScale.toInt())
    }

    /**
     * This value with the given [scale]. Growing the scale is always exact; shrinking it
     * discards digits, resolved by [roundingMode] — [RoundingMode.UNNECESSARY] (the default)
     * throws [ArithmeticException] if any discarded digit is non-zero. Special values are
     * returned unchanged.
     */
    public fun setScale(newScale: Int, roundingMode: RoundingMode = RoundingMode.UNNECESSARY): Decimal {
        if (special != FINITE || newScale == scale) return this
        if (newScale > scale) {
            if (digits == "0") return finite(false, "0", newScale)
            return finite(negative, padZerosRight(digits, newScale - scale), newScale)
        }
        val drop = scale.toLong() - newScale // > 0; may exceed digits.length
        val kept = if (drop >= digits.length) "0" else digits.substring(0, digits.length - drop.toInt())
        val firstDropped: Char
        val restNonZero: Boolean
        if (drop > digits.length) {
            // The dropped block is the whole significand preceded by virtual leading zeros.
            firstDropped = '0'
            restNonZero = digits != "0"
        } else {
            val cut = digits.length - drop.toInt()
            firstDropped = digits[cut]
            restNonZero = hasNonZero(digits, cut + 1)
        }
        val droppedNonZero = firstDropped != '0' || restNonZero
        if (!droppedNonZero) return finite(negative, kept, newScale)
        val increment = when (roundingMode) {
            RoundingMode.UP -> true
            RoundingMode.DOWN -> false
            RoundingMode.CEILING -> !negative
            RoundingMode.FLOOR -> negative
            RoundingMode.HALF_UP -> firstDropped >= '5'
            RoundingMode.HALF_DOWN -> firstDropped > '5' || (firstDropped == '5' && restNonZero)
            RoundingMode.HALF_EVEN ->
                firstDropped > '5' || (firstDropped == '5' && (restNonZero || (kept.last() - '0') % 2 == 1))
            RoundingMode.UNNECESSARY -> throw ArithmeticException("Rounding necessary")
        }
        return finite(negative, if (increment) DigitMath.increment(kept) else kept, newScale)
    }

    // ---- arithmetic (exact; see setScale for rounding) ----

    /**
     * The exact sum. The result scale is `max(this.scale, other.scale)` (java rule).
     * Special values combine IEEE-754-style: `NaN` propagates; `Infinity + (-Infinity)` is [NaN].
     */
    public operator fun plus(other: Decimal): Decimal {
        if (special != FINITE || other.special != FINITE) {
            if (isNaN || other.isNaN) return NaN
            if (isInfinite && other.isInfinite) return if (special == other.special) this else NaN
            return if (isInfinite) this else other
        }
        val s = maxOf(scale, other.scale)
        val padA = s.toLong() - scale
        val padB = s.toLong() - other.scale
        if (padA > MAX_PLAIN_PAD || padB > MAX_PLAIN_PAD) {
            throw ArithmeticException("Scale difference too large to align: $scale vs ${other.scale}")
        }
        val a = padZerosRight(digits, padA.toInt())
        val b = padZerosRight(other.digits, padB.toInt())
        if (negative == other.negative) return finite(negative, DigitMath.add(a, b), s)
        val c = DigitMath.compare(a, b)
        return when {
            c == 0 -> finite(false, "0", s)
            c > 0 -> finite(negative, DigitMath.subtract(a, b), s)
            else -> finite(other.negative, DigitMath.subtract(b, a), s)
        }
    }

    /** The exact difference; see [plus] for the scale rule and special-value semantics. */
    public operator fun minus(other: Decimal): Decimal = this + (-other)

    /**
     * The exact product. The result scale is `this.scale + other.scale` (java rule).
     * `Infinity × 0` is [NaN]; other special combinations propagate signs IEEE-754-style.
     */
    public operator fun times(other: Decimal): Decimal {
        if (special != FINITE || other.special != FINITE) {
            if (isNaN || other.isNaN) return NaN
            if (isZero || other.isZero) return NaN // 0 × Infinity
            val neg = (signum() < 0) != (other.signum() < 0)
            return if (neg) NEGATIVE_INFINITY else POSITIVE_INFINITY
        }
        val newScale = scale.toLong() + other.scale
        checkScale(newScale)
        return finite(negative != other.negative, DigitMath.multiply(digits, other.digits), newScale.toInt())
    }

    /**
     * `this / other`, rounded to [scale] with [roundingMode] — both are always explicit
     * because most quotients (`1/3`) have no exact decimal representation; there is
     * deliberately no `/` operator that would pick them silently.
     *
     * Division by a zero [other] throws [ArithmeticException] (like `java.math.BigDecimal`,
     * unlike [Double] — silent infinities hide bugs in money code). `NaN` propagates;
     * `Infinity / finite` is infinite, `finite / Infinity` is zero, `Infinity / Infinity` is [NaN].
     */
    public fun div(other: Decimal, scale: Int, roundingMode: RoundingMode): Decimal {
        if (special != FINITE || other.special != FINITE) {
            if (isNaN || other.isNaN) return NaN
            if (other.isZero) throw ArithmeticException("Division by zero")
            if (isInfinite && other.isInfinite) return NaN
            if (isInfinite) {
                return if ((special == SP_NEG_INF) != (other.signum() < 0)) NEGATIVE_INFINITY else POSITIVE_INFINITY
            }
            return finite(false, "0", scale) // finite / ±Infinity
        }
        if (other.isZero) throw ArithmeticException("Division by zero")
        if (isZero) return finite(false, "0", scale)
        // this/other × 10^scale = digits × 10^shift / other.digits, with shift folding all scales.
        val shift = scale.toLong() - this.scale + other.scale
        if (shift > MAX_PLAIN_PAD || -shift > MAX_PLAIN_PAD) throw ArithmeticException("Scale overflow: $scale")
        val n = if (shift >= 0) padZerosRight(digits, shift.toInt()) else digits
        val d = if (shift >= 0) other.digits else padZerosRight(other.digits, (-shift).toInt())
        val (q, r) = DigitMath.divide(n, d)
        val negResult = negative != other.negative
        if (r == "0") return finite(negResult, q, scale)
        val increment = when (roundingMode) {
            RoundingMode.UP -> true
            RoundingMode.DOWN -> false
            RoundingMode.CEILING -> !negResult
            RoundingMode.FLOOR -> negResult
            RoundingMode.HALF_UP -> DigitMath.compare(DigitMath.add(r, r), d) >= 0
            RoundingMode.HALF_DOWN -> DigitMath.compare(DigitMath.add(r, r), d) > 0
            RoundingMode.HALF_EVEN -> {
                val c = DigitMath.compare(DigitMath.add(r, r), d)
                c > 0 || (c == 0 && (q.last() - '0') % 2 == 1)
            }
            RoundingMode.UNNECESSARY -> throw ArithmeticException("Rounding necessary")
        }
        return finite(negResult, if (increment) DigitMath.increment(q) else q, scale)
    }

    /**
     * The integer part of `this / other` (an exact operation — no rounding mode needed).
     * The result's scale follows `java.math.BigDecimal.divideToIntegralValue`'s preferred
     * scale, `this.scale - other.scale`, so far as trailing zeros allow.
     */
    public fun divideToIntegral(other: Decimal): Decimal {
        if (special != FINITE || other.special != FINITE) {
            if (isNaN || other.isNaN) return NaN
            if (other.isZero) throw ArithmeticException("Division by zero")
            if (isInfinite && other.isInfinite) return NaN
            if (isInfinite) {
                return if ((special == SP_NEG_INF) != (other.signum() < 0)) NEGATIVE_INFINITY else POSITIVE_INFINITY
            }
            return ZERO // finite / ±Infinity
        }
        if (other.isZero) throw ArithmeticException("Division by zero")
        val preferred = scale.toLong() - other.scale
        if (isZero) return zeroWithPreferredScale(preferred)
        // |this| / |other| = digits × 10^(other.scale - scale) / other.digits, integer part.
        val shift = other.scale.toLong() - scale
        if (shift > MAX_PLAIN_PAD || -shift > MAX_PLAIN_PAD) throw ArithmeticException("Scale overflow")
        val n = if (shift >= 0) padZerosRight(digits, shift.toInt()) else digits
        val d = if (shift >= 0) other.digits else padZerosRight(other.digits, (-shift).toInt())
        val (q, _) = DigitMath.divide(n, d)
        if (q == "0") return zeroWithPreferredScale(preferred)
        val integral = finite(negative != other.negative, q, 0)
        return integral.adjustTowardPreferredScale(preferred)
    }

    /**
     * The remainder: `this - divideToIntegral(other) × other` (exact, so the operator is
     * safe — no hidden rounding). The sign follows this dividend, like `java.math`'s
     * `remainder` and Kotlin's `Long.rem`. Throws [ArithmeticException] on a zero [other];
     * `finite % Infinity` is `this`, any other non-finite operand yields [NaN].
     */
    public operator fun rem(other: Decimal): Decimal {
        if (special != FINITE || other.special != FINITE) {
            if (other.isZero) throw ArithmeticException("Division by zero")
            if (special == FINITE && other.isInfinite) return this
            return NaN
        }
        if (other.isZero) throw ArithmeticException("Division by zero")
        return this - divideToIntegral(other) * other
    }

    private fun zeroWithPreferredScale(preferred: Long): Decimal =
        finite(false, "0", preferred.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())

    /** Grows toward a positive preferred scale exactly; shrinks only as trailing zeros allow. */
    private fun adjustTowardPreferredScale(preferred: Long): Decimal {
        if (preferred >= scale) {
            val target = preferred.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return setScale(target)
        }
        var end = digits.length
        var s = scale.toLong()
        while (end > 1 && s > preferred && digits[end - 1] == '0') {
            end--
            s--
        }
        return if (end == digits.length) this else finite(negative, digits.substring(0, end), s.toInt())
    }

    // ---- comparison / equality ----

    /**
     * Numeric order. Total over special values, matching [Double.compareTo]:
     * `-Infinity < finite < Infinity < NaN` (and `NaN` compares equal to itself).
     */
    override fun compareTo(other: Decimal): Int {
        val r = rank()
        val ro = other.rank()
        if (r != ro) return r.compareTo(ro)
        if (special != FINITE) return 0
        val sa = signum()
        val sb = other.signum()
        if (sa != sb) return sa.compareTo(sb)
        if (sa == 0) return 0
        return compareMagnitude(other) * sa
    }

    private fun rank(): Int = when (special) {
        SP_NEG_INF -> -1
        SP_POS_INF -> 1
        SP_NAN -> 2
        else -> 0
    }

    /** Compares |this| with |other|; both finite and non-zero. */
    private fun compareMagnitude(other: Decimal): Int {
        // Position of the most significant digit relative to the decimal point.
        val exp = precision.toLong() - scale
        val expO = other.precision.toLong() - other.scale
        if (exp != expO) return exp.compareTo(expO)
        // Same magnitude order: compare digit-by-digit, the shorter padded with zeros.
        val shorter = minOf(digits.length, other.digits.length)
        for (i in 0 until shorter) {
            if (digits[i] != other.digits[i]) return digits[i].compareTo(other.digits[i])
        }
        if (hasNonZero(digits, shorter)) return 1
        if (hasNonZero(other.digits, shorter)) return -1
        return 0
    }

    /**
     * Value equality: `Decimal("2.5") == Decimal("2.50")`, `NaN == NaN`. Consistent with
     * [compareTo]. (Note this deliberately differs from `java.math.BigDecimal.equals`,
     * which is scale-sensitive.)
     */
    override fun equals(other: Any?): Boolean = other is Decimal && compareTo(other) == 0

    override fun hashCode(): Int {
        when (special) {
            SP_NAN -> return -1
            SP_POS_INF -> return -2
            SP_NEG_INF -> return -3
            else -> {}
        }
        if (digits == "0") return 0
        // Normalize away trailing zeros so equal values hash equally; the position of the
        // most significant digit (precision - scale) is unchanged by that normalization.
        var end = digits.length
        while (end > 1 && digits[end - 1] == '0') end--
        var h = if (negative) 31 else 17
        for (i in 0 until end) h = h * 31 + digits[i].code
        return h * 31 + (precision.toLong() - scale).hashCode()
    }

    // ---- formatting ----

    /**
     * Canonical text form, character-compatible with `java.math.BigDecimal.toString` for
     * finite values: plain notation while the exponent stays in java's window, scientific
     * (`1.23E+7`) outside it. Special values print as `NaN`, `Infinity`, `-Infinity`.
     * [parse] accepts every string this produces.
     */
    override fun toString(): String {
        when (special) {
            SP_NAN -> return "NaN"
            SP_POS_INF -> return "Infinity"
            SP_NEG_INF -> return "-Infinity"
            else -> {}
        }
        if (scale == 0) return signPrefix() + digits
        val adjusted = precision.toLong() - 1 - scale
        if (scale > 0 && adjusted >= -6) return signPrefix() + plainBody()
        // Scientific: one digit, optional fraction, explicit exponent sign (java prints E+7).
        val sb = StringBuilder(signPrefix())
        sb.append(digits[0])
        if (digits.length > 1) sb.append('.').append(digits, 1, digits.length)
        sb.append('E')
        if (adjusted >= 0) sb.append('+')
        sb.append(adjusted)
        return sb.toString()
    }

    /**
     * Plain notation with no exponent, like `java.math.BigDecimal.toPlainString`
     * (`1E+6` → `"1000000"`). Throws [ArithmeticException] for special values.
     */
    public fun toPlainString(): String {
        requireFinite("toPlainString")
        if (scale == 0) return signPrefix() + digits
        if (scale < 0) {
            val shift = -scale.toLong()
            if (shift > MAX_PLAIN_PAD) throw ArithmeticException("toPlainString would need $shift padding zeros")
            return signPrefix() + padZerosRight(digits, shift.toInt())
        }
        return signPrefix() + plainBody()
    }

    /** Digits with the point inserted; only for `scale > 0`. */
    private fun plainBody(): String {
        val intLen = digits.length - scale
        return if (intLen > 0) {
            buildString(digits.length + 1) {
                append(digits, 0, intLen).append('.').append(digits, intLen, digits.length)
            }
        } else {
            buildString(scale + 2) {
                append("0.")
                repeat(-intLen) { append('0') }
                append(digits)
            }
        }
    }

    private fun signPrefix(): String = if (negative) "-" else ""

    // ---- conversions ----

    /**
     * The nearest [Double] (correctly rounded by the platform's decimal-to-binary parser).
     * Special values map to their [Double] counterparts. Magnitudes beyond `Double.MAX_VALUE`
     * overflow to infinity, like `java.math.BigDecimal.doubleValue`.
     */
    public fun toDouble(): Double = when (special) {
        SP_NAN -> Double.NaN
        SP_POS_INF -> Double.POSITIVE_INFINITY
        SP_NEG_INF -> Double.NEGATIVE_INFINITY
        else -> (signPrefix() + digits + "E" + (-scale.toLong())).toDouble()
    }

    /** The nearest [Float]; see [toDouble]. */
    public fun toFloat(): Float = toDouble().toFloat()

    /**
     * The whole part of this value as a [Long] (the fraction is truncated toward zero).
     * Throws [ArithmeticException] if the whole part does not fit in a [Long] or the value
     * is not finite. (Deliberately safer than `java.math.BigDecimal.longValue`, which
     * silently keeps the low 64 bits; for a no-fraction guarantee use [toLongExact].)
     */
    public fun toLong(): Long {
        requireFinite("toLong")
        val whole = wholeDigits()
        var acc = 0L // accumulate negated to reach Long.MIN_VALUE
        for (c in whole) {
            val d = c - '0'
            if (acc < (Long.MIN_VALUE + d) / 10) throw ArithmeticException("Value $this does not fit in a Long")
            acc = acc * 10 - d
        }
        if (!negative) {
            if (acc == Long.MIN_VALUE) throw ArithmeticException("Value $this does not fit in a Long")
            return -acc
        }
        return acc
    }

    /** Like [toLong], but also throws [ArithmeticException] if the fraction is non-zero. */
    public fun toLongExact(): Long {
        requireFinite("toLongExact")
        if (hasFraction()) throw ArithmeticException("Value $this has a non-zero fraction")
        return toLong()
    }

    /** The whole part as an [Int]; throws [ArithmeticException] when out of range or not finite. */
    public fun toInt(): Int {
        val l = toLong()
        if (l !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw ArithmeticException("Value $this does not fit in an Int")
        }
        return l.toInt()
    }

    /** Like [toInt], but also throws [ArithmeticException] if the fraction is non-zero. */
    public fun toIntExact(): Int {
        requireFinite("toIntExact")
        if (hasFraction()) throw ArithmeticException("Value $this has a non-zero fraction")
        return toInt()
    }

    /** The whole part of |this| as a digit string ("0" when |this| < 1). */
    private fun wholeDigits(): String {
        val intLen = digits.length.toLong() - scale
        return when {
            digits == "0" || intLen <= 0 -> "0"
            scale <= 0 -> {
                if (intLen > MAX_PLAIN_PAD) throw ArithmeticException("Value $this does not fit in a Long")
                padZerosRight(digits, -scale)
            }
            else -> digits.substring(0, intLen.toInt())
        }
    }

    private fun hasFraction(): Boolean {
        if (scale <= 0 || digits == "0") return false
        val cut = (digits.length - scale).coerceAtLeast(0)
        return hasNonZero(digits, cut)
    }

    private fun requireFinite(operation: String) {
        if (special != FINITE) throw ArithmeticException("$operation is undefined for $this")
    }

    public companion object {
        private const val FINITE: Byte = 0
        private const val SP_NAN: Byte = 1
        private const val SP_POS_INF: Byte = 2
        private const val SP_NEG_INF: Byte = 3

        /** toPlainString/toLong padding guard: beyond this many zeros the request is a bug. */
        private const val MAX_PLAIN_PAD = 1_000_000L

        public val ZERO: Decimal = Decimal(false, "0", 0, FINITE)
        public val ONE: Decimal = Decimal(false, "1", 0, FINITE)
        public val TEN: Decimal = Decimal(false, "10", 0, FINITE)

        /** Not-a-number: what PostgreSQL's `numeric` reports as `NaN`. `NaN == NaN` is true here. */
        public val NaN: Decimal = Decimal(false, "0", 0, SP_NAN)
        public val POSITIVE_INFINITY: Decimal = Decimal(false, "0", 0, SP_POS_INF)
        public val NEGATIVE_INFINITY: Decimal = Decimal(true, "0", 0, SP_NEG_INF)

        /** Shorthand for [parse]: `Decimal("12.50")`. */
        public operator fun invoke(value: String): Decimal = parse(value)

        /**
         * Parses decimal text. The grammar is exactly `java.math.BigDecimal(String)` —
         * optional sign, digits with an optional point (`"12"`, `"12."`, `".5"`), optional
         * exponent (`"1.2E-7"`) — plus the three special forms `"NaN"`, `"Infinity"`
         * (optionally signed). No whitespace is accepted. Throws [NumberFormatException]
         * on anything else.
         */
        public fun parse(text: String): Decimal {
            when (text) {
                "NaN" -> return NaN
                "Infinity", "+Infinity" -> return POSITIVE_INFINITY
                "-Infinity" -> return NEGATIVE_INFINITY
            }
            var i = 0
            val n = text.length
            if (n == 0) throw NumberFormatException("Empty decimal string")
            var negative = false
            when (text[0]) {
                '-' -> { negative = true; i = 1 }
                '+' -> i = 1
            }
            val significand = StringBuilder(n - i)
            var fracLen = 0L
            var seenDot = false
            var seenDigit = false
            while (i < n) {
                val c = text[i]
                when {
                    c in '0'..'9' -> {
                        seenDigit = true
                        significand.append(c)
                        if (seenDot) fracLen++
                    }
                    c == '.' && !seenDot -> seenDot = true
                    c == 'e' || c == 'E' -> break
                    else -> throw NumberFormatException("Malformed decimal: \"$text\"")
                }
                i++
            }
            if (!seenDigit) throw NumberFormatException("Malformed decimal: \"$text\"")
            var exponent = 0L
            if (i < n) { // at 'e' / 'E'
                i++
                var expNegative = false
                if (i < n && (text[i] == '-' || text[i] == '+')) {
                    expNegative = text[i] == '-'
                    i++
                }
                if (i >= n) throw NumberFormatException("Malformed decimal: \"$text\"")
                while (i < n) {
                    val c = text[i]
                    if (c !in '0'..'9') throw NumberFormatException("Malformed decimal: \"$text\"")
                    exponent = exponent * 10 + (c - '0')
                    // The scale check below rejects anything out of Int range; just keep
                    // the accumulator itself from overflowing on absurdly long exponents.
                    if (exponent > OVERSIZED_EXPONENT) throw NumberFormatException("Exponent overflow: \"$text\"")
                    i++
                }
                if (expNegative) exponent = -exponent
                // Like java: the exponent itself must fit in an Int, independent of the scale.
                if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    throw NumberFormatException("Exponent overflow: \"$text\"")
                }
            }
            val scale = fracLen - exponent
            if (scale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw NumberFormatException("Exponent overflow: \"$text\"")
            }
            return finite(negative, DigitMath.stripLeadingZeros(significand.toString()), scale.toInt())
        }

        /** The [Decimal] `unscaled × 10^-scale`: `of(1250, 2)` is `12.50`. */
        public fun of(unscaled: Long, scale: Int): Decimal {
            if (unscaled == 0L) return if (scale == 0) ZERO else Decimal(false, "0", scale, FINITE)
            return finite(unscaled < 0, unsignedDigits(unscaled), scale)
        }

        /** The [Decimal] equal to this whole number. */
        public fun of(value: Long): Decimal = of(value, 0)

        /** The [Decimal] equal to this whole number. */
        public fun of(value: Int): Decimal = of(value.toLong(), 0)

        /**
         * The [Decimal] holding [value]'s shortest decimal representation
         * (`0.1` → `0.1`, not the exact binary expansion `0.1000000000000000055511…`),
         * normalized with [stripTrailingZeros] so the result is identical on every platform.
         * `NaN` and infinities map to the [Decimal] special values.
         */
        public fun fromDouble(value: Double): Decimal = when {
            value.isNaN() -> NaN
            value == Double.POSITIVE_INFINITY -> POSITIVE_INFINITY
            value == Double.NEGATIVE_INFINITY -> NEGATIVE_INFINITY
            else -> parse(value.toString()).stripTrailingZeros()
        }

        private const val OVERSIZED_EXPONENT = 1L shl 40

        /** Digits of |value| without allocating for the sign; handles Long.MIN_VALUE. */
        private fun unsignedDigits(value: Long): String {
            var v = value // keep negative: |Long.MIN_VALUE| overflows
            if (v > 0) v = -v
            val sb = StringBuilder(19)
            while (v != 0L) {
                sb.append('0' - (v % 10).toInt())
                v /= 10
            }
            return sb.reverse().toString()
        }

        /** Normalizing constructor for finite values. */
        private fun finite(negative: Boolean, digits: String, scale: Int): Decimal {
            val d = DigitMath.stripLeadingZeros(digits)
            if (d == "0") return if (scale == 0) ZERO else Decimal(false, "0", scale, FINITE)
            return Decimal(negative, d, scale, FINITE)
        }

        private fun checkScale(scale: Long) {
            if (scale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw ArithmeticException("Scale overflow: $scale")
            }
        }

        private fun padZerosRight(digits: String, count: Int): String {
            if (count == 0 || digits == "0") return digits
            return buildString(digits.length + count) {
                append(digits)
                repeat(count) { append('0') }
            }
        }

        private fun hasNonZero(s: String, from: Int): Boolean {
            for (i in from.coerceAtLeast(0) until s.length) if (s[i] != '0') return true
            return false
        }
    }
}

/** Parses this string as a [Decimal]; see [Decimal.parse]. */
public fun String.toDecimal(): Decimal = Decimal.parse(this)

/** This whole number as a [Decimal]. */
public fun Long.toDecimal(): Decimal = Decimal.of(this)

/** This whole number as a [Decimal]. */
public fun Int.toDecimal(): Decimal = Decimal.of(this)

/** This value's shortest decimal representation as a [Decimal]; see [Decimal.fromDouble]. */
public fun Double.toDecimal(): Decimal = Decimal.fromDouble(this)
