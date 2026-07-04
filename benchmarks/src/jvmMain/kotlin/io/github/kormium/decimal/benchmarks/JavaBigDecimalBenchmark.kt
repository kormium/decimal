package io.github.kormium.decimal.benchmarks

import java.math.BigDecimal
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * The JVM baseline: the same sweeps as [MoneyParseBenchmark] & co., over
 * `java.math.BigDecimal`. 25 years of JIT-friendly tuning — the number to beat is
 * ionspin's, the number to respect is this one.
 */
@State(Scope.Benchmark)
class JavaBigDecimalBenchmark {
    private val money: List<BigDecimal> = Corpus.money.map(::BigDecimal)
    private val large: List<BigDecimal> = Corpus.large.map(::BigDecimal)

    @Benchmark
    fun moneyParse(bh: Blackhole) {
        for (s in Corpus.money) bh.consume(BigDecimal(s))
    }

    @Benchmark
    fun largeParse(bh: Blackhole) {
        for (s in Corpus.large) bh.consume(BigDecimal(s))
    }

    @Benchmark
    fun moneyToString(bh: Blackhole) {
        for (d in money) bh.consume(d.toString())
    }

    @Benchmark
    fun moneyCompare(bh: Blackhole) {
        for (i in money.indices) bh.consume(money[i].compareTo(money[(i + 1) % money.size]))
    }

    @Benchmark
    fun moneyAdd(bh: Blackhole) {
        for (i in money.indices) bh.consume(money[i].add(money[(i + 1) % money.size]))
    }

    @Benchmark
    fun moneyMultiply(bh: Blackhole) {
        for (i in money.indices) bh.consume(money[i].multiply(money[(i + 1) % money.size]))
    }

    @Benchmark
    fun largeMultiply(bh: Blackhole) {
        for (i in large.indices) bh.consume(large[i].multiply(large[(i + 1) % large.size]))
    }
}
