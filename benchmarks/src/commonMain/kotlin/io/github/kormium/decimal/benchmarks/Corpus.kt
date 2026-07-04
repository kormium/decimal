package io.github.kormium.decimal.benchmarks

import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonspinDecimal
import io.github.kormium.decimal.Decimal
import kotlin.random.Random

/**
 * Deterministic input corpora. Two shapes:
 *
 * - [money]: the ORM/business profile — up to 8 integer digits, 2 fraction digits.
 *   This is what a `NUMERIC(12,2)` column feeds a driver all day.
 * - [large]: 30-34 significant digits, mixed scales — arbitrary-precision territory
 *   where limb-based big integers (ionspin, java.math) are in their element.
 *
 * All corpora are pre-built outside the measured loops; parse benchmarks read [money] /
 * [large] strings, operation benchmarks read the pre-parsed instance lists.
 */
internal object Corpus {
    const val SIZE = 256

    val money: List<String> = Random(42).let { rnd ->
        List(SIZE) {
            val sign = if (rnd.nextInt(4) == 0) "-" else ""
            "$sign${rnd.nextLong(0, 100_000_000)}.${rnd.nextInt(0, 100).toString().padStart(2, '0')}"
        }
    }

    val large: List<String> = Random(43).let { rnd ->
        List(SIZE) {
            val sign = if (rnd.nextBoolean()) "-" else ""
            val digits = (1..30 + rnd.nextInt(5)).map { rnd.nextInt(10) }.joinToString("")
            val cut = 1 + rnd.nextInt(digits.length - 1)
            "$sign${digits.take(cut).trimStart('0').ifEmpty { "0" }}.${digits.drop(cut)}"
        }
    }

    val moneyDecimals: List<Decimal> = money.map(Decimal::parse)
    val largeDecimals: List<Decimal> = large.map(Decimal::parse)
    val moneyIonspin: List<IonspinDecimal> = money.map(IonspinDecimal::parseString)
    val largeIonspin: List<IonspinDecimal> = large.map(IonspinDecimal::parseString)
}
