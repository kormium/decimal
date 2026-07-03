package io.github.kormium.decimal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Curated, platform-independent cases. Expected strings were verified against
 * `java.math.BigDecimal` on JDK 21 (see the differential suite in jvmTest, which
 * checks the same properties on millions of random inputs).
 */
class DecimalGoldenTest {

    // ---- parse / toString round-trip ----

    @Test
    fun parseAndPrint() {
        val cases = listOf(
            // input to canonical toString (java.math.BigDecimal semantics)
            "0" to "0",
            "-0" to "0",
            "+0" to "0",
            "0.00" to "0.00",
            "-0.00" to "0.00",
            "1" to "1",
            "-1" to "-1",
            "+1" to "1",
            "12.50" to "12.50",
            "007.5" to "7.5",
            "1." to "1",
            ".5" to "0.5",
            "-.5" to "-0.5",
            "0.1" to "0.1",
            "0.000001" to "0.000001",
            "0.0000001" to "1E-7",
            "1e5" to "1E+5",
            "1E+5" to "1E+5",
            "12e5" to "1.2E+6",
            "1.23E3" to "1.23E+3",
            "1.23E2" to "123",
            "1.23E1" to "12.3",
            "1.23E-2" to "0.0123",
            "123e-1" to "12.3",
            "0e5" to "0E+5",
            "0e-5" to "0.00000",
            "1000000" to "1000000",
            "9999999999999999999999999999" to "9999999999999999999999999999",
            "-98765.43210" to "-98765.43210",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, Decimal.parse(input).toString(), "parse(\"$input\")")
            // Everything toString produces must re-parse to an equal value.
            assertEquals(Decimal.parse(input), Decimal.parse(Decimal.parse(input).toString()), "round-trip \"$input\"")
        }
    }

    @Test
    fun parseRejectsGarbage() {
        for (bad in listOf("", " ", "1 ", " 1", "+", "-", ".", "+.", "1..2", "1.2.3", "e5", "1e", "1e+", "1,5", "0x1F", "1_000", "Inf", "nan", "--1", "+-1", "1E2147483648", "1E-2147483648")) {
            assertFailsWith<NumberFormatException>("parse(\"$bad\")") { Decimal.parse(bad) }
        }
    }

    @Test
    fun toPlainStringNeverUsesExponent() {
        assertEquals("100", Decimal("1E+2").toPlainString())
        assertEquals("0.0000001", Decimal("1E-7").toPlainString())
        assertEquals("-1200", Decimal("-1.2E+3").toPlainString())
        assertEquals("0", Decimal("0E+5").toPlainString())
        assertEquals("12.50", Decimal("12.50").toPlainString())
    }

    // ---- equality / ordering ----

    @Test
    fun valueEqualityIgnoresScale() {
        assertEquals(Decimal("2.5"), Decimal("2.50"))
        assertEquals(Decimal("2.5").hashCode(), Decimal("2.50").hashCode())
        assertEquals(Decimal("0"), Decimal("0.000"))
        assertEquals(Decimal("0").hashCode(), Decimal("0E+7").hashCode())
        assertEquals(Decimal("600.0"), Decimal("6E+2"))
        assertEquals(Decimal("600.0").hashCode(), Decimal("6E+2").hashCode())
        assertNotEquals(Decimal("2.5"), Decimal("2.51"))
    }

    @Test
    fun ordering() {
        val sorted = listOf("-11", "-2", "-1.5", "-0.01", "0", "0.001", "1", "1.09", "1.1", "2", "10", "10.5").map(Decimal::parse)
        for (i in sorted.indices) {
            for (j in sorted.indices) {
                assertEquals(i.compareTo(j), sorted[i].compareTo(sorted[j]), "${sorted[i]} vs ${sorted[j]}")
            }
        }
    }

    @Test
    fun specialValuesOrderLikeDouble() {
        val order = listOf(Decimal.NEGATIVE_INFINITY, Decimal("-1"), Decimal.ZERO, Decimal("1"), Decimal.POSITIVE_INFINITY, Decimal.NaN)
        for (i in order.indices) {
            for (j in order.indices) {
                assertEquals(i.compareTo(j), order[i].compareTo(order[j]), "$i vs $j")
            }
        }
        assertEquals(Decimal.NaN, Decimal.NaN)
        assertEquals(Decimal.parse("NaN"), Decimal.NaN)
        assertEquals(Decimal.parse("-Infinity"), Decimal.NEGATIVE_INFINITY)
        assertEquals(Decimal.parse("+Infinity"), Decimal.POSITIVE_INFINITY)
        assertTrue(Decimal.NaN.isNaN)
        assertTrue(Decimal.POSITIVE_INFINITY.isInfinite)
        assertTrue(!Decimal.NaN.isFinite)
    }

    // ---- properties ----

    @Test
    fun scalePrecisionSignum() {
        val d = Decimal("-12.50")
        assertEquals(2, d.scale)
        assertEquals(4, d.precision)
        assertEquals(-1, d.signum())
        assertEquals(-3, Decimal("12e3").scale)
        assertEquals(0, Decimal("0.00").signum())
        assertEquals(1, Decimal.POSITIVE_INFINITY.signum())
        assertFailsWith<ArithmeticException> { Decimal.NaN.signum() }
        assertTrue(Decimal("0E-3").isZero)
    }

    @Test
    fun negateAbsMovePointStrip() {
        assertEquals(Decimal("-2.5"), -Decimal("2.5"))
        assertEquals(Decimal("2.5"), Decimal("-2.5").abs())
        assertEquals(Decimal.NEGATIVE_INFINITY, -Decimal.POSITIVE_INFINITY)
        assertEquals("0.025", Decimal("2.5").movePointLeft(2).toString())
        assertEquals("250", Decimal("2.5").movePointRight(2).toString())
        assertEquals("2.5", Decimal("2.5").movePointLeft(2).movePointRight(2).toString())
        // Scale contract: result scale = max(scale + n, 0) even for n == 0 (JDK ≤17 got this
        // wrong; JDK 21+ conforms — a negative scale pads out to plain form).
        assertEquals("100000", Decimal("1E+5").movePointLeft(0).toString())
        assertEquals("100000", Decimal("1E+5").movePointRight(0).toString())
        assertEquals("2.5", Decimal("2.5").movePointLeft(0).toString())
        assertEquals("6E+2", Decimal("600.0").stripTrailingZeros().toString())
        assertEquals("0", Decimal("0.000").stripTrailingZeros().toString())
        assertEquals("1.5", Decimal("1.50").stripTrailingZeros().toString())
    }

    // ---- conversions ----

    @Test
    fun conversions() {
        assertEquals(12L, Decimal("12.99").toLong())
        assertEquals(-12L, Decimal("-12.99").toLong())
        assertEquals(0L, Decimal("0.5").toLong())
        assertEquals(Long.MAX_VALUE, Decimal("9223372036854775807").toLong())
        assertEquals(Long.MIN_VALUE, Decimal("-9223372036854775808").toLong())
        assertFailsWith<ArithmeticException> { Decimal("9223372036854775808").toLong() }
        assertFailsWith<ArithmeticException> { Decimal("-9223372036854775809").toLong() }
        assertFailsWith<ArithmeticException> { Decimal("12.5").toLongExact() }
        assertEquals(12L, Decimal("12.00").toLongExact())
        assertEquals(1200L, Decimal("1.2E3").toLongExact())
        assertFailsWith<ArithmeticException> { Decimal("2147483648").toInt() }
        assertEquals(-2147483648, Decimal("-2147483648").toIntExact())
        assertFailsWith<ArithmeticException> { Decimal.NaN.toLong() }

        assertEquals(12.5, Decimal("12.5").toDouble())
        assertEquals(-0.001, Decimal("-1E-3").toDouble())
        assertTrue(Decimal.NaN.toDouble().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Decimal.POSITIVE_INFINITY.toDouble())
        assertEquals(Double.POSITIVE_INFINITY, Decimal("1E+400").toDouble())
    }

    @Test
    fun factories() {
        assertEquals(Decimal("12.50"), Decimal.of(1250, 2))
        assertEquals("12.50", Decimal.of(1250, 2).toString())
        assertEquals(Decimal("-9223372036854775808"), Decimal.of(Long.MIN_VALUE))
        assertEquals(Decimal("42"), 42.toDecimal())
        assertEquals(Decimal("42"), 42L.toDecimal())
        assertEquals(Decimal("0.1"), 0.1.toDecimal())
        assertEquals(Decimal("1"), 1.0.toDecimal()) // canonical across platforms
        assertEquals(Decimal.NaN, Double.NaN.toDecimal())
        assertEquals(Decimal("12.5"), "12.5".toDecimal())
    }

    // ---- arithmetic ----

    @Test
    fun addition() {
        assertEquals("3.55", (Decimal("1.25") + Decimal("2.30")).toString())
        assertEquals("0.05", (Decimal("2.35") - Decimal("2.30")).toString())
        assertEquals("-1.05", (Decimal("1.25") - Decimal("2.30")).toString())
        assertEquals("0.00", (Decimal("1.25") - Decimal("1.25")).toString()) // scale = max, like java
        assertEquals("10000000000000000000", (Decimal("9999999999999999999") + Decimal("1")).toString())
        assertEquals("1.0000000001", (Decimal("1") + Decimal("1E-10")).toString())
        assertEquals("124.45", (Decimal("125") + Decimal("-0.55")).toString())
    }

    @Test
    fun multiplication() {
        assertEquals("7.50", (Decimal("2.5") * Decimal("3.0")).toString())
        assertEquals("0.000006", (Decimal("0.002") * Decimal("0.003")).toString())
        assertEquals("-7.50", (Decimal("-2.5") * Decimal("3.0")).toString())
        assertEquals("0.00", (Decimal("0.0") * Decimal("5.0")).toString()) // scale 1+1, like java
        assertEquals(
            "99999999999999999999999999980000000000000000000000000001",
            (Decimal("9999999999999999999999999999") * Decimal("9999999999999999999999999999")).toString(),
        )
    }

    @Test
    fun specialValueArithmetic() {
        assertEquals(Decimal.NaN, Decimal.NaN + Decimal.ONE)
        assertEquals(Decimal.NaN, Decimal.POSITIVE_INFINITY + Decimal.NEGATIVE_INFINITY)
        assertEquals(Decimal.POSITIVE_INFINITY, Decimal.POSITIVE_INFINITY + Decimal.POSITIVE_INFINITY)
        assertEquals(Decimal.POSITIVE_INFINITY, Decimal.POSITIVE_INFINITY + Decimal("42"))
        assertEquals(Decimal.NEGATIVE_INFINITY, Decimal("42") - Decimal.POSITIVE_INFINITY)
        assertEquals(Decimal.NaN, Decimal.POSITIVE_INFINITY * Decimal.ZERO)
        assertEquals(Decimal.NEGATIVE_INFINITY, Decimal.POSITIVE_INFINITY * Decimal("-2"))
        assertEquals(Decimal.POSITIVE_INFINITY, Decimal.NEGATIVE_INFINITY * Decimal.NEGATIVE_INFINITY)
        assertEquals(Decimal.NaN, Decimal.NaN * Decimal.NaN)
    }

    // ---- setScale rounding: the java.math.RoundingMode javadoc table ----

    @Test
    fun roundingTable() {
        // input -> results for UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN (setScale(0)),
        // null result = UNNECESSARY would throw (checked separately).
        val table = mapOf(
            "5.5" to listOf("6", "5", "6", "5", "6", "5", "6"),
            "2.5" to listOf("3", "2", "3", "2", "3", "2", "2"),
            "1.6" to listOf("2", "1", "2", "1", "2", "2", "2"),
            "1.1" to listOf("2", "1", "2", "1", "1", "1", "1"),
            "1.0" to listOf("1", "1", "1", "1", "1", "1", "1"),
            "-1.0" to listOf("-1", "-1", "-1", "-1", "-1", "-1", "-1"),
            "-1.1" to listOf("-2", "-1", "-1", "-2", "-1", "-1", "-1"),
            "-1.6" to listOf("-2", "-1", "-1", "-2", "-2", "-2", "-2"),
            "-2.5" to listOf("-3", "-2", "-2", "-3", "-3", "-2", "-2"),
            "-5.5" to listOf("-6", "-5", "-5", "-6", "-6", "-5", "-6"),
        )
        val modes = listOf(
            RoundingMode.UP, RoundingMode.DOWN, RoundingMode.CEILING, RoundingMode.FLOOR,
            RoundingMode.HALF_UP, RoundingMode.HALF_DOWN, RoundingMode.HALF_EVEN,
        )
        for ((input, expected) in table) {
            for ((mode, want) in modes.zip(expected)) {
                assertEquals(want, Decimal(input).setScale(0, mode).toString(), "$input setScale(0, $mode)")
            }
            if (!input.endsWith(".0")) {
                assertFailsWith<ArithmeticException>("$input UNNECESSARY") { Decimal(input).setScale(0) }
            } else {
                assertEquals(input.dropLast(2), Decimal(input).setScale(0).toString())
            }
        }
    }

    @Test
    fun setScaleEdges() {
        assertEquals("12.500", Decimal("12.5").setScale(3).toString())
        assertEquals("0.000", Decimal("0").setScale(3).toString())
        assertEquals("1", Decimal("0.5").setScale(0, RoundingMode.HALF_UP).toString())
        assertEquals("0", Decimal("0.05").setScale(0, RoundingMode.HALF_UP).toString())
        assertEquals("0.1", Decimal("0.05").setScale(1, RoundingMode.HALF_UP).toString())
        assertEquals("1.00", Decimal("0.999").setScale(2, RoundingMode.HALF_UP).toString())
        assertEquals("1E+2", Decimal("99").setScale(-2, RoundingMode.UP).toString())
        assertEquals("0E+2", Decimal("49").setScale(-2, RoundingMode.HALF_UP).toString())
        assertEquals(Decimal.NaN, Decimal.NaN.setScale(2, RoundingMode.HALF_UP))
    }
}
