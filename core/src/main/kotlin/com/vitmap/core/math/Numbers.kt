package com.vitmap.core.math

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

object NumberFormatter {
    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '-' to '⁻',
    )

    /**
     * Formats with up to [significant] significant digits, switching to scientific notation
     * (with superscript exponent) for very large or very small magnitudes.
     */
    fun format(value: Double, significant: Int = 10, scientific: Boolean = false, grouping: Boolean = true): String {
        if (value.isNaN()) return "—"
        if (value.isInfinite()) return if (value > 0) "∞" else "−∞"
        if (value == 0.0) return "0"
        val mag = abs(value)
        val useSci = scientific || mag >= 1e15 || mag < 1e-6
        val bd = BigDecimal(value).round(MathContext(significant.coerceIn(1, 17), RoundingMode.HALF_EVEN))
        if (useSci) {
            val plain = bd.stripTrailingZeros()
            val exp = plain.precision() - plain.scale() - 1
            val mantissa = plain.movePointLeft(exp).stripTrailingZeros().toPlainString()
            val expStr = exp.toString().map { SUPERSCRIPT[it] ?: it }.joinToString("")
            return "$mantissa×10$expStr"
        }
        val plain = bd.stripTrailingZeros().toPlainString()
        if (!grouping) return plain
        val negative = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val intPart = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }

    /** Plain machine-readable form for copying (no grouping, no superscripts). */
    fun plain(value: Double, significant: Int = 12): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == 0.0) return "0"
        val bd = BigDecimal(value).round(MathContext(significant, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val mag = abs(value)
        return if (mag >= 1e15 || mag < 1e-6) bd.toString().replace("E+", "e").replace("E", "e") else bd.toPlainString()
    }
}

/**
 * Tiny arithmetic expression evaluator so the converter accepts input like "12*3.5" or "2^10".
 * Supports + - * / ^ %, parentheses, unary minus, constants (pi, e) and common functions.
 */
object Expression {
    fun evaluate(input: String): Double? {
        val text = input.replace(",", "").replace("×", "*").replace("÷", "/").replace("−", "-").trim()
        if (text.isEmpty()) return null
        return try {
            val p = Parser(text)
            val v = p.parseExpression()
            p.skipSpaces()
            if (p.pos != text.length) null else v
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipSpaces() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return s.getOrNull(pos)
        }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= parseFactor() }
                    '/' -> { pos++; v /= parseFactor() }
                    '%' -> { pos++; v %= parseFactor() }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            if (peek() == '^') {
                pos++
                return Math.pow(base, parseFactor())
            }
            return base
        }

        private fun parseUnary(): Double {
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            val c = peek() ?: throw IllegalArgumentException("Unexpected end")
            if (c == '(') {
                pos++
                val v = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("Missing )")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E') && pos + 1 < s.length &&
                    (s[pos + 1].isDigit() || ((s[pos + 1] == '-' || s[pos + 1] == '+') && pos + 2 < s.length && s[pos + 2].isDigit()))
                ) {
                    pos += 2
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number")
            }
            if (c.isLetter()) {
                val start = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val name = s.substring(start, pos).lowercase()
                when (name) {
                    "pi", "π" -> return Math.PI
                    "e" -> return Math.E
                }
                if (peek() != '(') throw IllegalArgumentException("Unknown name $name")
                pos++
                val arg = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("Missing )")
                pos++
                return when (name) {
                    "sqrt" -> kotlin.math.sqrt(arg)
                    "cbrt" -> Math.cbrt(arg)
                    "sin" -> kotlin.math.sin(arg)
                    "cos" -> kotlin.math.cos(arg)
                    "tan" -> kotlin.math.tan(arg)
                    "ln" -> kotlin.math.ln(arg)
                    "log" -> kotlin.math.log10(arg)
                    "abs" -> abs(arg)
                    "exp" -> kotlin.math.exp(arg)
                    else -> throw IllegalArgumentException("Unknown function $name")
                }
            }
            if (c == 'π') { pos++; return Math.PI }
            throw IllegalArgumentException("Unexpected '$c'")
        }
    }
}
