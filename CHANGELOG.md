# Changelog

All notable changes are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Division: `div(other, scale, roundingMode)` (both always explicit — deliberately no `/`
  operator), `divideToIntegral` (java preferred-scale semantics) and the exact `%` operator
  (sign follows the dividend). Division by zero always throws, even for special values.
- Mixed-operand `+`, `-`, `×` with `Int` and `Long` in both directions, and `div`/`rem`
  overloads (`Double` is deliberately excluded — binary-float noise must be converted
  explicitly).
- Benchmarks module (kotlinx-benchmark/JMH): `Decimal` vs `java.math.BigDecimal` vs
  ionspin bignum, money-profile and 30-digit corpora, JVM + native.
- `Decimal`: an immutable arbitrary-precision decimal value type in pure common Kotlin.
  `java.math.BigDecimal`-compatible parse/toString/toPlainString grammar and formatting,
  `scale`/`precision`/`signum`, `stripTrailingZeros`, `movePointLeft`/`movePointRight`,
  conversions (`toDouble`/`toFloat`/`toLong`/`toInt` + `…Exact` variants), factories
  (`parse`, `of(unscaled, scale)`, `of(Long/Int)`, `fromDouble`) and `String`/`Long`/`Int`/
  `Double` `toDecimal()` extensions.
- Exact arithmetic: `plus`, `minus`, `times` (java scale rules) and `setScale` with all
  eight `RoundingMode`s.
- Value-based `equals`/`hashCode` consistent with `compareTo` (`2.5 == 2.50`) — a deliberate
  divergence from `java.math.BigDecimal`'s scale-sensitive equality.
- Special values `NaN`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY` (what PostgreSQL `numeric`
  can emit), ordered and propagated like `Double`.
- JVM interop: `toJavaBigDecimal()`, `BigDecimal.toKormiumDecimal()`, `RoundingMode.toJavaRoundingMode()`.
- Differential test suite against `java.math.BigDecimal` (seeded, 200k random cases per
  suite), parser fuzzing, cross-platform golden suite, `explicitApi` + binary-compatibility
  validation (JVM + klib).
