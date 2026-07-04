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

## Division

Division is the one operation whose exact result usually does not exist (`1/3`), so
`Decimal` makes the two decisions it forces — target scale and rounding — explicit,
and deliberately ships **no `/` operator** that would pick them silently:

```kotlin
val unitPrice = total.div(quantity, scale = 2, roundingMode = RoundingMode.HALF_EVEN)
val boxes = items.divideToIntegral(perBox)   // exact: integer part of the quotient
val left = items % perBox                    // exact: remainder, sign follows the dividend
```

Division by zero always throws `ArithmeticException` — like `java.math`, unlike `Double`:
a silent `Infinity` in money code is a bug that got away.

## Serialization

The core is zero-dependency by design, so it ships no `kotlinx-serialization` support —
because the serializer you'd want is five lines, and string form is the only
representation that survives every JSON parser's number handling:

```kotlin
object DecimalSerializer : KSerializer<Decimal> {
    override val descriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Decimal) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = Decimal.parse(decoder.decodeString())
}
```

Register it per-field (`@Serializable(with = DecimalSerializer::class)`), per-file, or
contextually — standard `kotlinx-serialization` mechanics.

## What it is not (yet)

- No `MathContext`-style precision propagation — deliberately out of scope; results are exact
  and you round explicitly with `setScale` (or per-division).
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

## Performance

Significands of up to 18 digits live in a `Long` (like `java.math`'s `intCompact`), so the
money-shaped values that dominate real workloads never touch string arithmetic. Measured
with the [benchmarks module](benchmarks) (JMH on the JVM, kotlinx-benchmark's native
harness) on an Apple-silicon MacBook, JDK 21, Kotlin 2.4.0 — **µs per sweep of 256 values**,
lower is better. "Money" is a `NUMERIC(12,2)`-shaped corpus; "large" is 30-34 digits.

| JVM | `Decimal` | `java.math` | ionspin 0.3.10 |
|---|---:|---:|---:|
| parse (money) | 6.8 | 7.3 | 151.7 |
| toString (money) | 7.5 | 0.2 | 132.3 |
| compareTo (money) | 2.2 | 0.6 | 28.1 |
| plus (money) | 1.9 | 1.8 | 94.9 |
| times (money) | 54.0 | 5.1 | 66.5 |
| parse (large) | 31.4 | 47.5 | 573.1 |
| times (large) | 675.0 | 9.6 | 193.6 |

| Native (macosArm64) | `Decimal` | ionspin 0.3.10 |
|---|---:|---:|
| parse (money) | 22.0 | 550.2 |
| toString (money) | 24.9 | 766.4 |
| compareTo (money) | 8.2 | 76.8 |
| plus (money) | 10.1 | 279.2 |
| times (money) | 105.2 | 195.8 |
| parse (large) | 99.3 | 1 561.9 |
| times (large) | 903.0 | 495.8 |

Read it honestly: on the money profile `Decimal` beats ionspin by 2-50× everywhere and
matches `java.math` on parse and addition; `java.math` keeps a large lead on `toString`
(a direct char-buffer writer — on the roadmap) and on multiplication (products beyond 18
digits leave our `Long` path). On large-number **multiplication** ionspin's limb-based big
integers win by design — heavy arbitrary-precision math is exactly the case where you
should use ionspin instead (see above).

## Supported targets

JVM (11+), JS, WasmJS, WasmWASI, and all Kotlin/Native tiers: Linux (x64, arm64),
macOS (x64, arm64), iOS, tvOS, watchOS, Windows (mingwX64), Android Native. Pure common
Kotlin — one implementation, no expect/actual, no platform delegation.

## Roadmap

- Performance, benchmark-driven and API-invisible: direct char-buffer `toString` for the
  compact form; a base-10⁹ two-limb product for 19-38-digit results (closes most of the
  `times` gap); resuming the parse fast-scan where it bailed instead of re-scanning.
- API reference (Dokka) on GitHub Pages.
- Golden-corpus differential testing on the non-JVM platforms (corpus generated from the
  JVM oracle).

## Origin

Built for [Kormium](https://github.com/kormium/kormium) (a Kotlin Multiplatform ORM) to keep
third-party 0.x types out of its stable public API — and released standalone because the gap
is everyone's, not just an ORM's. Kormium uses `Decimal` as the carrier for SQL
`NUMERIC`/`DECIMAL` columns.

## License

[Apache 2.0](LICENSE)
