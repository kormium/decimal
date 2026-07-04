package io.github.kormium.decimal

/**
 * Exact schoolbook arithmetic on non-negative decimal digit strings.
 *
 * A "digit string" here is `"0"` or a sequence of ASCII digits with no leading zero and
 * no sign. Keeping the significand as a digit string (rather than a binary big integer)
 * makes every operation O(n) or O(n²) in the digit count with no base conversion — the
 * right trade-off for a value type whose inputs and outputs are decimal text.
 */
internal object DigitMath {

    /** Compares two digit strings by numeric magnitude. */
    fun compare(a: String, b: String): Int {
        if (a.length != b.length) return if (a.length < b.length) -1 else 1
        return a.compareTo(b)
    }

    /** Drops leading zeros, keeping at least one digit. */
    fun stripLeadingZeros(s: String): String {
        var i = 0
        while (i < s.length - 1 && s[i] == '0') i++
        return if (i == 0) s else s.substring(i)
    }

    /** `a + b` of two digit strings. */
    fun add(a: String, b: String): String {
        val long = if (a.length >= b.length) a else b
        val short = if (a.length >= b.length) b else a
        val out = CharArray(long.length + 1)
        var carry = 0
        var li = long.length - 1
        var si = short.length - 1
        var oi = out.size - 1
        while (li >= 0) {
            val sum = (long[li] - '0') + (if (si >= 0) short[si] - '0' else 0) + carry
            out[oi] = '0' + (sum % 10)
            carry = sum / 10
            li--; si--; oi--
        }
        return if (carry == 0) out.concatToString(1) else {
            out[0] = '0' + carry
            out.concatToString()
        }
    }

    /** `a - b` of two digit strings; requires `a >= b`. Result has leading zeros stripped. */
    fun subtract(a: String, b: String): String {
        val out = CharArray(a.length)
        var borrow = 0
        var ai = a.length - 1
        var bi = b.length - 1
        while (ai >= 0) {
            var diff = (a[ai] - '0') - (if (bi >= 0) b[bi] - '0' else 0) - borrow
            if (diff < 0) {
                diff += 10
                borrow = 1
            } else {
                borrow = 0
            }
            out[ai] = '0' + diff
            ai--; bi--
        }
        return stripLeadingZeros(out.concatToString())
    }

    /** `a * b` of two digit strings. */
    fun multiply(a: String, b: String): String {
        if (a == "0" || b == "0") return "0"
        val product = IntArray(a.length + b.length)
        for (ai in a.length - 1 downTo 0) {
            val da = a[ai] - '0'
            var carry = 0
            for (bi in b.length - 1 downTo 0) {
                val pos = ai + bi + 1
                val sum = product[pos] + da * (b[bi] - '0') + carry
                product[pos] = sum % 10
                carry = sum / 10
            }
            product[ai] += carry
        }
        val sb = StringBuilder(product.size)
        for (d in product) sb.append('0' + d)
        return stripLeadingZeros(sb.toString())
    }

    /** `a + 1` of a digit string. */
    fun increment(a: String): String = add(a, "1")

    /**
     * Integer division of two digit strings: quotient and remainder. Schoolbook long
     * division — one pass over [n]'s digits, at most 9 subtractions of [d] per position.
     */
    fun divide(n: String, d: String): Pair<String, String> {
        require(d != "0") { "Division by zero" }
        if (compare(n, d) < 0) return "0" to n
        val quotient = StringBuilder(n.length)
        var rem = ""
        for (c in n) {
            var acc = stripLeadingZeros(rem + c)
            var q = 0
            while (compare(acc, d) >= 0) {
                acc = subtract(acc, d)
                q++
            }
            quotient.append('0' + q)
            rem = if (acc == "0") "" else acc
        }
        return stripLeadingZeros(quotient.toString()) to (rem.ifEmpty { "0" })
    }
}
