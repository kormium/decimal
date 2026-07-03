# decimal

[![CI](https://github.com/kormium/decimal/actions/workflows/ci.yml/badge.svg)](https://github.com/kormium/decimal/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

An arbitrary-precision decimal **value type** for Kotlin Multiplatform. Zero dependencies,
`java.math.BigDecimal`-compatible semantics, verified against `java.math.BigDecimal` as a
differential-testing oracle on millions of random inputs per CI run.

Kotlin has no multiplatform `BigDecimal` ([KT-20912](https://youtrack.jetbrains.com/issue/KT-20912),
open since 2017, absent from the current roadmap). This library fills exactly that gap for the
most common need: **carrying exact decimal values** — money, quantities, rates — across
platforms, parsing and printing them, comparing them, and doing exact arithmetic on them.

```kotlin
import io.github.kormium.decimal.*

val price = Decimal("19.99")
val qty = Decimal.of(3)
val total = price * qty                      // 59.97 — exact, scale rules like java.math
val rounded = total.setScale(1, RoundingMode.HALF_EVEN) // 60.0

Decimal("2.50") == Decimal("2.5")            // true — value equality
Decimal("0.1") + Decimal("0.2") == Decimal("0.3") // true — no binary float drama

price.toPlainString()                        // "19.99"
price.toDouble()                             // 19.99 (correctly rounded)
```

Not published to Maven Central yet — v0.1.0 is imminent:

```kotlin
dependencies {
    implementation("io.github.kormium:decimal:0.1.0")
}
```

## Semantics: `java.math.BigDecimal`, with two deliberate changes

Everything both sides define behaves identically, character-for-character — that is a tested
invariant, not an aspiration (see [Quality](#quality)): the `parse`/`toString`/`toPlainString`
grammar and formatting, `scale`/`precision`/`signum`, arithmetic result scales (`a + b` →
`max(sa, sb)`, `a * b` → `sa + sb`), all eight `RoundingMode`s, `stripTrailingZeros`,
`movePointLeft/Right`, `toDouble`.

The two deliberate divergences:

| | `java.math.BigDecimal` | `Decimal` |
|---|---|---|
| `equals` | scale-sensitive: `2.5 != 2.50` (the classic map-key trap) | **value-based**: `2.5 == 2.50`, consistent with `compareTo` |
| `NaN` / `±Infinity` | not representable | first-class values — SQL backends emit them (PostgreSQL `numeric`); ordering and arithmetic follow `Double` |

Plus one safety upgrade: `toLong()`/`toInt()` truncate the fraction but **throw on overflow**
instead of silently keeping the low bits like `longValue()`. `toLongExact()`/`toIntExact()`
additionally reject non-zero fractions, like their java namesakes.

On the JVM, `toJavaBigDecimal()` / `BigDecimal.toKormiumDecimal()` convert losslessly
(scale preserved), so arithmetic-heavy JVM code can hop over and back freely.

## What it is not (yet)

- **Division lands in 0.2.0** (with explicit scale + rounding mode). Addition, subtraction,
  multiplication and `setScale` rounding are in since 0.1.0.
- No `MathContext`-style precision propagation — deliberately out of scope; results are exact
  and you round explicitly with `setScale`.
- Need heavy arbitrary-precision *math* (pow, sqrt, trig)? Use
  [ionspin/kotlin-multiplatform-bignum](https://github.com/ionspin/kotlin-multiplatform-bignum) —
  this library is a value type, not a calculator.

## Quality

The type is deliberately *finite*: no hidden state, inputs and outputs are decimal text.
That makes it ideal for **differential testing**, and that is the core of the test suite:

- **Oracle tests (JVM)**: every operation is checked against `java.math.BigDecimal` on
  randomly generated inputs — 200 000 cases per suite per CI run, seeded and reproducible
  (`-Ddecimal.differential.seed=...`). Parsing, formatting, comparison, hashing, arithmetic,
  rounding, conversions — all must agree with the JDK exactly.
- **Parser fuzzing**: random garbage must be accepted/rejected in exact agreement with
  `java.math.BigDecimal(String)`.
- **Golden suite (all platforms)**: the curated cases (including the full
  `java.math.RoundingMode` javadoc table) run on JVM, JS, Wasm and native, catching any
  platform divergence.
- **ABI stability**: `explicitApi()` + [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
  with klib validation — the public API surface is dumped in [`decimal/api`](decimal/api) and
  checked on every commit.

## Supported targets

JVM (11+), JS, WasmJS, WasmWASI, and all Kotlin/Native tiers: Linux (x64, arm64),
macOS (x64, arm64), iOS, tvOS, watchOS, Windows (mingwX64), Android Native. Pure common
Kotlin — one implementation, no expect/actual, no platform delegation.

## Roadmap

- **0.2.0** — division (`div(other, scale, roundingMode)`, `rem`, `divideToIntegral`),
  benchmarks vs ionspin, `kotlinx-serialization` support module.
- Long-significand fast path (compact `Long` representation for ≤18 digits) — API-invisible,
  benchmark-driven.
- API reference (Dokka) on GitHub Pages.

## Origin

Built for [Kormium](https://github.com/kormium/kormium) (a Kotlin Multiplatform ORM) to keep
third-party 0.x types out of its stable public API — and released standalone because the gap
is everyone's, not just an ORM's. Kormium uses `Decimal` as the carrier for SQL
`NUMERIC`/`DECIMAL` columns.

## License

[Apache 2.0](LICENSE)
