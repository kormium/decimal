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
 * Internally a significand of up to 18 digits lives in a [Long] (like `java.math`'s
 * `intCompact`), so the money-shaped values that dominate real workloads never touch
 * digit-string arithmetic; longer significands fall back to exact schoolbook string math.
 * The representation is invisible in the API and covered by the same differential oracle.
 *
 * Instances are immutable and safe to share across threads.
 */
public class Decimal private constructor(
    private val negative: Boolean,
    /** Magnitude when it fits 18 digits ([digitsOrNull] == null), else -1. */
    private val compact: Long,
    /** Significand digits (no leading zero, no sign) when the magnitude needs >18 digits. */
    private val digitsOrNull: String?,
    /**
     * Number of significand digits to the right of the decimal point; negative values
     * shift the point right (`12E+3` = significand 12, scale -3). 0 for special values.
     */
    public val scale: Int,
    private val special: Byte,
) : Comparable<Decimal> {

    /**
     * Lazily materialized decimal digits of [compact], for the string-math slow paths.
     * The benign data race is deliberate (the computed value is identical either way) —
     * the same trick `java.math.BigDecimal` uses for its `stringCache`.
     */
    private var digitsCache: String? = null

    private val digits: String
        get() = digitsOrNull ?: digitsCache ?: compact.toString().also { digitsCache = it }

    private inline val isCompact: Boolean get() = digitsOrNull == null

    /** Number of digits in the significand (1 for zero and for special values). */
    public val precision: Int get() = digitsOrNull?.length ?: digitCount(compact)

    /** True for every value except [NaN], [POSITIVE_INFINITY] and [NEGATIVE_INFINITY]. */
    public val isFinite: Boolean get() = special == FINITE

    /** True only for [NaN]. */
    public val isNaN: Boolean get() = special == SP_NAN

    /** True for [POSITIVE_INFINITY] and [NEGATIVE_INFINITY]. */
    public val isInfinite: Boolean get() = special == SP_POS_INF || special == SP_NEG_INF

    /** True for a zero of any scale (`0`, `0.00`, `0E+5`). */
    public val isZero: Boolean get() = special == FINITE && compact == 0L

    /**
     * The sign: -1, 0 or 1. Infinities report ±1; [NaN] has no sign and throws
     * [ArithmeticException].
     */
    public fun signum(): Int = when (special) {
        SP_NAN -> throw ArithmeticException("NaN has no sign")
        SP_POS_INF -> 1
        SP_NEG_INF -> -1
        else -> if (compact == 0L) 0 else if (negative) -1 else 1
    }

    // ---- sign / point movement ----

    /** The negation of this value. */
    public operator fun unaryMinus(): Decimal = when {
        special == SP_NAN -> this
        special == SP_POS_INF -> NEGATIVE_INFINITY
        special == SP_NEG_INF -> POSITIVE_INFINITY
        isZero -> this
        else -> Decimal(!negative, compact, digitsOrNull, scale, FINITE)
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
            return if (isCompact) finiteCompact(negative, compact, newScale.toInt())
            else finite(negative, digits, newScale.toInt())
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
        if (isZero) return if (scale == 0) this else ZERO
        if (isCompact) {
            var c = compact
            var newScale = scale.toLong()
            while (c % 10L == 0L) {
                c /= 10L
                newScale--
            }
            if (c == compact) return this
            checkScale(newScale)
            return finiteCompact(negative, c, newScale.toInt())
        }
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
        if (isZero) return finiteCompact(false, 0L, newScale)
        if (newScale > scale) {
            val grow = newScale.toLong() - scale
            if (isCompact && grow < 19 && compact <= MAX_COMPACT / POW10[grow.toInt()]) {
                return finiteCompact(negative, compact * POW10[grow.toInt()], newScale)
            }
            if (grow > MAX_PLAIN_PAD) throw ArithmeticException("Scale overflow: $newScale")
            return finite(negative, padZerosRight(digits, grow.toInt()), newScale)
        }
        val drop = scale.toLong() - newScale // > 0; may exceed the digit count
        if (isCompact) {
            val q: Long
            val firstDropped: Int
            val restNonZero: Boolean
            if (drop < 19) {
                val p = POW10[drop.toInt()]
                val sub = p / 10L
                val r = compact % p
                q = compact / p
                firstDropped = (r / sub).toInt()
                restNonZero = r % sub != 0L
            } else {
                // Every digit is dropped, behind at least one virtual leading zero.
                q = 0L
                firstDropped = 0
                restNonZero = true // compact != 0 (zero returned above)
            }
            if (firstDropped == 0 && !restNonZero) return finiteCompact(negative, q, newScale)
            val increment = shouldIncrement(roundingMode, negative, '0' + firstDropped, restNonZero, oddLastDigit = q % 2L != 0L)
            return finiteCompact(negative, if (increment) q + 1 else q, newScale)
        }
        val kept = if (drop >= digits.length) "0" else digits.substring(0, digits.length - drop.toInt())
        val firstDropped: Char
        val restNonZero: Boolean
        if (drop > digits.length) {
            // The dropped block is the whole significand preceded by virtual leading zeros.
            firstDropped = '0'
            restNonZero = true
        } else {
            val cut = digits.length - drop.toInt()
            firstDropped = digits[cut]
            restNonZero = hasNonZero(digits, cut + 1)
        }
        if (firstDropped == '0' && !restNonZero) return finite(negative, kept, newScale)
        val increment = shouldIncrement(roundingMode, negative, firstDropped, restNonZero, oddLastDigit = (kept.last() - '0') % 2 == 1)
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
        if (isZero) return other.setScale(s)
        if (other.isZero) return setScale(s)
        val padA = s.toLong() - scale
        val padB = s.toLong() - other.scale
        if (isCompact && other.isCompact) {
            val a = alignedOrNegative(compact, padA)
            val b = alignedOrNegative(other.compact, padB)
            if (a >= 0L && b >= 0L) {
                // |±a ± b| ≤ 2×MAX_COMPACT, far from Long overflow.
                val sum = (if (negative) -a else a) + (if (other.negative) -b else b)
                return finiteCompact(sum < 0L, if (sum < 0L) -sum else sum, s)
            }
        }
        if (padA > MAX_PLAIN_PAD || padB > MAX_PLAIN_PAD) {
            throw ArithmeticException("Scale difference too large to align: $scale vs ${other.scale}")
        }
        val a = padZerosRight(digits, padA.toInt())
        val b = padZerosRight(other.digits, padB.toInt())
        if (negative == other.negative) return finite(negative, DigitMath.add(a, b), s)
        val c = DigitMath.compare(a, b)
        return when {
            c == 0 -> finiteCompact(false, 0L, s)
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
        if (isZero || other.isZero) return finiteCompact(false, 0L, newScale.toInt())
        // Long fast path whenever the product provably fits a Long (both are non-zero here);
        // a 19-digit product falls out of the compact form inside finiteCompact, which is
        // still far cheaper than digit-string multiplication.
        if (isCompact && other.isCompact && compact <= Long.MAX_VALUE / other.compact) {
            return finiteCompact(negative != other.negative, compact * other.compact, newScale.toInt())
        }
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
            return finiteCompact(false, 0L, scale) // finite / ±Infinity
        }
        if (other.isZero) throw ArithmeticException("Division by zero")
        if (isZero) return finiteCompact(false, 0L, scale)
        // this/other × 10^scale = digits × 10^shift / other.digits, with shift folding all scales.
        val shift = scale.toLong() - this.scale + other.scale
        if (shift > MAX_PLAIN_PAD || -shift > MAX_PLAIN_PAD) throw ArithmeticException("Scale overflow: $scale")
        val negResult = negative != other.negative
        if (isCompact && other.isCompact) {
            val n = alignedOrNegative(compact, maxOf(shift, 0L))
            val d = alignedOrNegative(other.compact, maxOf(-shift, 0L))
            if (n >= 0L && d >= 0L) {
                val q = n / d
                val r = n % d
                if (r == 0L) return finiteCompact(negResult, q, scale)
                val increment = when (roundingMode) {
                    RoundingMode.UP -> true
                    RoundingMode.DOWN -> false
                    RoundingMode.CEILING -> !negResult
                    RoundingMode.FLOOR -> negResult
                    // r < MAX_COMPACT ≤ Long.MAX/2, so 2r cannot overflow.
                    RoundingMode.HALF_UP -> 2L * r >= d
                    RoundingMode.HALF_DOWN -> 2L * r > d
                    RoundingMode.HALF_EVEN -> 2L * r > d || (2L * r == d && q % 2L != 0L)
                    RoundingMode.UNNECESSARY -> throw ArithmeticException("Rounding necessary")
                }
                return finiteCompact(negResult, if (increment) q + 1L else q, scale)
            }
        }
        val n = if (shift >= 0) padZerosRight(digits, shift.toInt()) else digits
        val d = if (shift >= 0) other.digits else padZerosRight(other.digits, (-shift).toInt())
        val (q, r) = DigitMath.divide(n, d)
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
        val negResult = negative != other.negative
        if (isCompact && other.isCompact) {
            val n = alignedOrNegative(compact, maxOf(shift, 0L))
            val d = alignedOrNegative(other.compact, maxOf(-shift, 0L))
            if (n >= 0L && d >= 0L) {
                val q = n / d
                if (q == 0L) return zeroWithPreferredScale(preferred)
                return finiteCompact(negResult, q, 0).adjustTowardPreferredScale(preferred)
            }
        }
        val n = if (shift >= 0) padZerosRight(digits, shift.toInt()) else digits
        val d = if (shift >= 0) other.digits else padZerosRight(other.digits, (-shift).toInt())
        val (q, _) = DigitMath.divide(n, d)
        if (q == "0") return zeroWithPreferredScale(preferred)
        return finite(negResult, q, 0).adjustTowardPreferredScale(preferred)
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
        finiteCompact(false, 0L, preferred.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())

    /** Grows toward a positive preferred scale exactly; shrinks only as trailing zeros allow. */
    private fun adjustTowardPreferredScale(preferred: Long): Decimal {
        if (preferred >= scale) {
            val target = preferred.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return setScale(target)
        }
        if (isCompact) {
            var c = compact
            var s = scale.toLong()
            while (s > preferred && c % 10L == 0L) {
                c /= 10L
                s--
            }
            return if (c == compact) this else finiteCompact(negative, c, s.toInt())
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
        if (this === other) return 0
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
        val p1 = precision
        val p2 = other.precision
        val exp = p1.toLong() - scale
        val expO = p2.toLong() - other.scale
        if (exp != expO) return exp.compareTo(expO)
        if (isCompact && other.isCompact) {
            // Same MSD position and both ≤18 digits: align the shorter and compare exactly.
            return if (p1 >= p2) compact.compareTo(other.compact * POW10[p1 - p2])
            else (compact * POW10[p2 - p1]).compareTo(other.compact)
        }
        // Same magnitude order: compare digit-by-digit, the shorter padded with zeros.
        val a = digits
        val b = other.digits
        val shorter = minOf(a.length, b.length)
        for (i in 0 until shorter) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        if (hasNonZero(a, shorter)) return 1
        if (hasNonZero(b, shorter)) return -1
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
        if (isZero) return 0
        // Normalize away trailing zeros so equal values hash equally; the position of the
        // most significant digit (precision - scale) is unchanged by that normalization.
        var h = if (negative) 31 else 17
        if (isCompact) {
            var c = compact
            while (c % 10L == 0L) c /= 10L
            var pow = POW10[digitCount(c) - 1]
            while (pow > 0L) {
                h = h * 31 + ('0' + (c / pow).toInt()).code
                c %= pow
                pow /= 10L
            }
        } else {
            var end = digits.length
            while (end > 1 && digits[end - 1] == '0') end--
            for (i in 0 until end) h = h * 31 + digits[i].code
        }
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
        if (scale == 0) {
            if (!negative) return digits // Long.toString / cached — the hot money-integer case
            return buildString(digits.length + 1) { append('-').append(digits) }
        }
        val ds = digits
        val adjusted = ds.length.toLong() - 1 - scale
        if (scale > 0 && adjusted >= -6) return plainString(ds)
        // Scientific: one digit, optional fraction, explicit exponent sign (java prints E+7).
        val sb = StringBuilder(ds.length + 8)
        if (negative) sb.append('-')
        sb.append(ds[0])
        if (ds.length > 1) sb.append('.').append(ds, 1, ds.length)
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
        val ds = digits
        if (scale == 0) return if (negative) "-$ds" else ds
        if (scale < 0) {
            val shift = -scale.toLong()
            if (shift > MAX_PLAIN_PAD) throw ArithmeticException("toPlainString would need $shift padding zeros")
            val padded = padZerosRight(ds, shift.toInt())
            return if (negative) "-$padded" else padded
        }
        return plainString(ds)
    }

    /** Plain notation with the point inserted; only for `scale > 0`. */
    private fun plainString(ds: String): String {
        val intLen = ds.length - scale
        val size = (if (negative) 1 else 0) + (if (intLen > 0) ds.length + 1 else scale + 2)
        return buildString(size) {
            if (negative) append('-')
            if (intLen > 0) {
                append(ds, 0, intLen).append('.').append(ds, intLen, ds.length)
            } else {
                append("0.")
                repeat(-intLen) { append('0') }
                append(ds)
            }
        }
    }

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
        else -> {
            val sign = if (negative) "-" else ""
            (sign + digits + "E" + (-scale.toLong())).toDouble()
        }
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
        if (isCompact) {
            val whole = when {
                scale == 0 -> compact
                scale > 0 -> if (scale < 19) compact / POW10[scale] else 0L
                else -> {
                    val k = -scale.toLong()
                    if (compact == 0L) 0L
                    else if (k > 18 || compact > Long.MAX_VALUE / POW10[k.toInt()]) {
                        throw ArithmeticException("Value $this does not fit in a Long")
                    } else compact * POW10[k.toInt()]
                }
            }
            return if (negative) -whole else whole
        }
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

    /** The whole part of |this| as a digit string ("0" when |this| < 1); string form only. */
    private fun wholeDigits(): String {
        val intLen = digits.length.toLong() - scale
        return when {
            isZero || intLen <= 0 -> "0"
            scale <= 0 -> {
                if (intLen > MAX_PLAIN_PAD) throw ArithmeticException("Value $this does not fit in a Long")
                padZerosRight(digits, -scale)
            }
            else -> digits.substring(0, intLen.toInt())
        }
    }

    private fun hasFraction(): Boolean {
        if (scale <= 0 || isZero) return false
        if (isCompact) {
            return if (scale < 19) compact % POW10[scale] != 0L else true
        }
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

        /** 10^0 .. 10^18. */
        private val POW10 = LongArray(19).also { p ->
            p[0] = 1L
            for (i in 1..18) p[i] = p[i - 1] * 10L
        }

        /** The largest magnitude the compact form holds: 18 nines. */
        private val MAX_COMPACT = POW10[18] - 1L

        public val ZERO: Decimal = Decimal(false, 0L, null, 0, FINITE)
        public val ONE: Decimal = Decimal(false, 1L, null, 0, FINITE)
        public val TEN: Decimal = Decimal(false, 10L, null, 0, FINITE)

        /** Not-a-number: what PostgreSQL's `numeric` reports as `NaN`. `NaN == NaN` is true here. */
        public val NaN: Decimal = Decimal(false, 0L, null, 0, SP_NAN)
        public val POSITIVE_INFINITY: Decimal = Decimal(false, 0L, null, 0, SP_POS_INF)
        public val NEGATIVE_INFINITY: Decimal = Decimal(true, 0L, null, 0, SP_NEG_INF)

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
            // Fast scan: accumulate up to 18 significant digits straight into a Long.
            // Anything longer restarts on the string path below; anything malformed
            // throws from either path with identical rules.
            val n = text.length
            if (n == 0) throw NumberFormatException("Empty decimal string")
            var i = 0
            var negative = false
            when (text[0]) {
                '-' -> { negative = true; i = 1 }
                '+' -> i = 1
            }
            var mag = 0L
            var significant = 0
            var fracLen = 0L
            var seenDot = false
            var seenDigit = false
            while (i < n) {
                val c = text[i]
                when {
                    c in '0'..'9' -> {
                        seenDigit = true
                        if (mag == 0L && c == '0') {
                            // Leading zero: contributes to the fraction length only.
                        } else {
                            if (significant == 18) return parseSlow(text)
                            mag = mag * 10L + (c - '0')
                            significant++
                        }
                        if (seenDot) fracLen++
                    }
                    c == '.' && !seenDot -> seenDot = true
                    c == 'e' || c == 'E' -> break
                    else -> throw NumberFormatException("Malformed decimal: \"$text\"")
                }
                i++
            }
            if (!seenDigit) throw NumberFormatException("Malformed decimal: \"$text\"")
            val exponent = parseExponent(text, i, n)
            val scale = fracLen - exponent
            if (scale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw NumberFormatException("Exponent overflow: \"$text\"")
            }
            return finiteCompact(negative, mag, scale.toInt())
        }

        /** The >18-digit tail of [parse]: exact string significand. Same grammar, same errors. */
        private fun parseSlow(text: String): Decimal {
            var i = 0
            val n = text.length
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
            val exponent = parseExponent(text, i, n)
            val scale = fracLen - exponent
            if (scale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw NumberFormatException("Exponent overflow: \"$text\"")
            }
            return finite(negative, DigitMath.stripLeadingZeros(significand.toString()), scale.toInt())
        }

        /** Parses the optional exponent block starting at [i] (at 'e'/'E' or the end). */
        private fun parseExponent(text: String, start: Int, n: Int): Long {
            var i = start
            if (i >= n) return 0L
            i++ // consume 'e' / 'E'
            var expNegative = false
            if (i < n && (text[i] == '-' || text[i] == '+')) {
                expNegative = text[i] == '-'
                i++
            }
            if (i >= n) throw NumberFormatException("Malformed decimal: \"$text\"")
            var exponent = 0L
            while (i < n) {
                val c = text[i]
                if (c !in '0'..'9') throw NumberFormatException("Malformed decimal: \"$text\"")
                exponent = exponent * 10L + (c - '0')
                // The Int check below rejects anything oversized; just keep the accumulator
                // itself from overflowing on absurdly long exponents.
                if (exponent > OVERSIZED_EXPONENT) throw NumberFormatException("Exponent overflow: \"$text\"")
                i++
            }
            if (expNegative) exponent = -exponent
            // Like java: the exponent itself must fit in an Int, independent of the scale.
            if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw NumberFormatException("Exponent overflow: \"$text\"")
            }
            return exponent
        }

        /** The [Decimal] `unscaled × 10^-scale`: `of(1250, 2)` is `12.50`. */
        public fun of(unscaled: Long, scale: Int): Decimal {
            if (unscaled == 0L) return if (scale == 0) ZERO else Decimal(false, 0L, null, scale, FINITE)
            if (unscaled != Long.MIN_VALUE) {
                val mag = if (unscaled < 0L) -unscaled else unscaled
                if (mag <= MAX_COMPACT) return Decimal(unscaled < 0L, mag, null, scale, FINITE)
            }
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

        /** Number of decimal digits in [v] (1 for 0); [v] must be in `0..MAX_COMPACT`. */
        private fun digitCount(v: Long): Int {
            var count = 1
            while (count < 19 && v >= POW10[count]) count++
            return count
        }

        /** `magnitude × 10^pad` when it stays ≤ [MAX_COMPACT]; -1 when it would not. */
        private fun alignedOrNegative(magnitude: Long, pad: Long): Long {
            if (pad == 0L) return magnitude
            if (pad > 17L || magnitude > MAX_COMPACT / POW10[pad.toInt()]) return -1L
            return magnitude * POW10[pad.toInt()]
        }

        /** The shared rounding-increment decision; [firstDropped] is the MSD of the dropped block. */
        private fun shouldIncrement(
            mode: RoundingMode,
            negative: Boolean,
            firstDropped: Char,
            restNonZero: Boolean,
            oddLastDigit: Boolean,
        ): Boolean = when (mode) {
            RoundingMode.UP -> true
            RoundingMode.DOWN -> false
            RoundingMode.CEILING -> !negative
            RoundingMode.FLOOR -> negative
            RoundingMode.HALF_UP -> firstDropped >= '5'
            RoundingMode.HALF_DOWN -> firstDropped > '5' || (firstDropped == '5' && restNonZero)
            RoundingMode.HALF_EVEN -> firstDropped > '5' || (firstDropped == '5' && (restNonZero || oddLastDigit))
            RoundingMode.UNNECESSARY -> throw ArithmeticException("Rounding necessary")
        }

        /** Normalizing constructor from a digit string (compacts when ≤18 digits). */
        private fun finite(negative: Boolean, digits: String, scale: Int): Decimal {
            val d = DigitMath.stripLeadingZeros(digits)
            if (d.length <= 18) {
                var mag = 0L
                for (c in d) mag = mag * 10L + (c - '0')
                return finiteCompact(negative, mag, scale)
            }
            return Decimal(negative, -1L, d, scale, FINITE)
        }

        /** Normalizing constructor from a compact magnitude (falls back past 18 digits). */
        private fun finiteCompact(negative: Boolean, magnitude: Long, scale: Int): Decimal {
            if (magnitude == 0L) return if (scale == 0) ZERO else Decimal(false, 0L, null, scale, FINITE)
            if (magnitude <= MAX_COMPACT) return Decimal(negative, magnitude, null, scale, FINITE)
            return Decimal(negative, -1L, magnitude.toString(), scale, FINITE)
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
