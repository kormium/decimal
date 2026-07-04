package io.github.kormium.decimal.benchmarks

import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonspinDecimal
import io.github.kormium.decimal.Decimal
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * Decimal vs ionspin bignum on every platform. Each invocation sweeps the whole
 * [Corpus.SIZE]-entry corpus, so a reported time is "per 256 values".
 */
@State(Scope.Benchmark)
class MoneyParseBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        for (s in Corpus.money) bh.consume(Decimal.parse(s))
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        for (s in Corpus.money) bh.consume(IonspinDecimal.parseString(s))
    }
}

@State(Scope.Benchmark)
class LargeParseBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        for (s in Corpus.large) bh.consume(Decimal.parse(s))
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        for (s in Corpus.large) bh.consume(IonspinDecimal.parseString(s))
    }
}

@State(Scope.Benchmark)
class MoneyToStringBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        for (d in Corpus.moneyDecimals) bh.consume(d.toString())
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        for (d in Corpus.moneyIonspin) bh.consume(d.toString())
    }
}

@State(Scope.Benchmark)
class MoneyCompareBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        val list = Corpus.moneyDecimals
        for (i in list.indices) bh.consume(list[i].compareTo(list[(i + 1) % list.size]))
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        val list = Corpus.moneyIonspin
        for (i in list.indices) bh.consume(list[i].compareTo(list[(i + 1) % list.size]))
    }
}

@State(Scope.Benchmark)
class MoneyAddBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        val list = Corpus.moneyDecimals
        for (i in list.indices) bh.consume(list[i] + list[(i + 1) % list.size])
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        val list = Corpus.moneyIonspin
        for (i in list.indices) bh.consume(list[i] + list[(i + 1) % list.size])
    }
}

@State(Scope.Benchmark)
class MoneyMultiplyBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        val list = Corpus.moneyDecimals
        for (i in list.indices) bh.consume(list[i] * list[(i + 1) % list.size])
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        val list = Corpus.moneyIonspin
        for (i in list.indices) bh.consume(list[i] * list[(i + 1) % list.size])
    }
}

@State(Scope.Benchmark)
class LargeMultiplyBenchmark {
    @Benchmark
    fun kormium(bh: Blackhole) {
        val list = Corpus.largeDecimals
        for (i in list.indices) bh.consume(list[i] * list[(i + 1) % list.size])
    }

    @Benchmark
    fun ionspin(bh: Blackhole) {
        val list = Corpus.largeIonspin
        for (i in list.indices) bh.consume(list[i] * list[(i + 1) % list.size])
    }
}
