# decimal — agent guide

Arbitrary-precision decimal value type for Kotlin Multiplatform. Package
`io.github.kormium.decimal`, single artifact `io.github.kormium:decimal`. Semantics are
`java.math.BigDecimal` except: `equals` is value-based (`2.5 == 2.50`), and `NaN`/`±Infinity`
exist (ordered like `Double`). No division until 0.2.0.

## Idiomatic use

```kotlin
import io.github.kormium.decimal.*

// Construct
val a = Decimal("12.50")            // parse; java grammar + "NaN"/"Infinity"
val b = Decimal.of(1250, 2)         // unscaled × 10⁻ˢᶜᵃˡᵉ = 12.50
val c = 0.1.toDecimal()             // shortest double repr → 0.1 (not 0.1000000000000000055…)

// Arithmetic is exact; round explicitly
val sum = a + b                     // scale = max(scales)
val product = a * b                 // scale = sum of scales
val money = product.setScale(2, RoundingMode.HALF_EVEN)

// Compare / print
a == Decimal("12.5")                // true (value equality)
a.toPlainString()                   // "12.50" — never scientific notation
a.toString()                        // java.math.BigDecimal-identical formatting

// Convert
a.toDouble()                        // correctly rounded
a.toLongExact()                     // throws on fraction or overflow — prefer over toLong()
a.toJavaBigDecimal()                // JVM only, lossless both ways
```

## Rules

- Never construct from binary floating point unless you mean it: `0.1f.toDouble().toDecimal()`
  carries float noise. Parse strings or use `of(unscaled, scale)` for exact values.
- `setScale` with `RoundingMode.UNNECESSARY` (the default) throws if rounding would occur —
  that's the point; pass an explicit mode when you expect to round.
- Special values: check `isFinite` before `toLong`/`toPlainString`/`toJavaBigDecimal` if the
  value can be `NaN`/`Infinity` (e.g. read from PostgreSQL `numeric`).

## Project layout

- `decimal/src/commonMain` — the whole implementation (pure Kotlin, zero dependencies):
  `Decimal.kt`, `DigitMath.kt` (schoolbook digit-string arithmetic), `RoundingMode.kt`.
- `decimal/src/jvmTest/.../DifferentialTest.kt` — the oracle: every change must keep it green;
  it checks agreement with `java.math.BigDecimal` on 200k seeded random cases per suite.
  Reproduce a failure with `-Ddecimal.differential.seed=<printed seed>`.
- `decimal/src/commonTest/.../DecimalGoldenTest.kt` — curated cases, runs on every platform.
- `decimal/api/` — ABI dumps (binary-compatibility-validator). API changes require
  `./gradlew apiDump` and a review of the diff.

## Commands

- `./gradlew :decimal:jvmTest` — golden + oracle (the fast, high-signal loop)
- `./gradlew :decimal:macosArm64Test :decimal:jsNodeTest :decimal:wasmJsNodeTest :decimal:wasmWasiNodeTest` — cross-platform
- `./gradlew apiCheck` / `./gradlew apiDump` — ABI guard
