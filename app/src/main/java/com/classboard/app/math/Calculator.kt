package com.classboard.app.math

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/** C printf-like "%.<p>g" formatting (trailing zeros removed), as Qt's QString::number(v,'g',p). */
fun formatG(v: Double, p: Int): String {
    if (v.isNaN()) return "nan"
    if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
    if (v == 0.0) return "0"
    val bd = BigDecimal(v).round(MathContext(p, RoundingMode.HALF_EVEN))
    val exp = bd.precision() - bd.scale() - 1
    return if (exp < -4 || exp >= p) {
        val unscaled = bd.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
        val mant = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
        val sign = if (bd.signum() < 0) "-" else ""
        val es = if (exp < 0) "-" else "+"
        val ed = abs(exp).toString().padStart(2, '0')
        "$sign${mant}e$es$ed"
    } else {
        var s = bd.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        s
    }
}

/** The classroom calculator: evaluation, Ans, history and school-style formatting. */
class Calculator {
    enum class AngleUnit { Degrees, Radians }

    data class Result(val ok: Boolean, val value: Double = 0.0, val text: String = "", val error: String = "")
    data class Entry(val expression: String, val result: String, val value: Double)

    var angleUnit = AngleUnit.Degrees
    var ans = 0.0
        private set
    val history = ArrayList<Entry>()
    var input = ""
    var showingResult = false

    fun evaluate(expression: String): Result {
        var text = expression.trim()
        while (text.endsWith("=")) text = text.dropLast(1)
        text = text.trim()
        if (text.isEmpty()) return Result(false, error = "Type a calculation")
        if (Regex("[×÷*/·]\\s*[×÷*/·]").containsMatchIn(text)) return Result(false, error = "Two operators in a row")
        var inp = replaceAns(text, "(" + toInput(ans) + ")")
        val open = inp.count { it == '(' } - inp.count { it == ')' }
        if (open > 0) inp += ")".repeat(open)
        val (e, err) = Expression.compileWithError(inp, angleUnit == AngleUnit.Degrees)
        if (!e.isValid) return Result(false, error = err)
        if (e.variables.isNotEmpty()) return Result(false, error = "“${e.variables.first()}” is not a number or a function the calculator knows")
        val v = e.evaluate(DoubleArray(0))
        if (v.isNaN()) return Result(false, error = "Not defined (for example √ of a negative number, 0 ÷ 0 or tan 90°)")
        if (v.isInfinite()) return Result(false, error = "Too large, or division by zero")
        val value = if (v == 0.0) 0.0 else v
        return Result(true, value, format(value))
    }

    fun calculate(expression: String): Result {
        val r = evaluate(expression)
        if (!r.ok) return r
        var shown = expression.trim()
        while (shown.endsWith("=")) shown = shown.dropLast(1)
        history.add(0, Entry(substituteAns(shown.trim(), ans), r.text, r.value))
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        ans = r.value
        return r
    }

    companion object {
        const val MAX_HISTORY = 20
        private const val SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹"
        private val ansRe = Regex("\\bans\\b", RegexOption.IGNORE_CASE)

        fun replaceAns(expression: String, replacement: String) = expression.replace(ansRe, Regex.escapeReplacement(replacement))

        private fun superscript(digits: String): String {
            val sb = StringBuilder()
            for (c in digits) {
                if (c.isDigit()) sb.append(SUP[c - '0']) else if (c == '-') sb.append('⁻')
            }
            return sb.toString()
        }

        fun format(value: Double): String {
            if (value == 0.0 || value.isNaN()) return "0"
            var s = if (abs(value) < 1e15 && value == floor(value)) value.toLong().toString() else formatG(value, 12)
            val e = s.indexOf('e')
            if (e >= 0) {
                var exponent = s.substring(e + 1).replace("+", "")
                while (exponent.startsWith("0") || exponent.startsWith("-0"))
                    exponent = if (exponent.startsWith("-")) "-" + exponent.substring(2) else exponent.substring(1)
                s = s.substring(0, e) + " × 10" + superscript(exponent)
            }
            return s.replace('-', '−')
        }

        fun toInput(value: Double) = formatG(value, 15).replace('e', 'E')

        fun substituteAns(expression: String, ans: Double): String {
            val shown = format(ans)
            return replaceAns(expression, if (ans < 0) "($shown)" else shown)
        }

        private fun matchingParen(s: String, open: Int): Int {
            var depth = 0
            for (i in open until s.length) {
                if (s[i] == '(') depth++ else if (s[i] == ')' && --depth == 0) return i
            }
            return -1
        }

        private val latexFunctions = listOf("asin", "acos", "atan", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "sin", "cos", "tan", "ln", "log", "exp")

        private fun operandAt(s: String, ii: IntArray): String {
            var i = ii[0]
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) { ii[0] = i; return "" }
            if (s[i] == '(') {
                val close = matchingParen(s, i)
                val end = if (close < 0) s.length else close
                val inner = s.substring(i + 1, end)
                ii[0] = if (close < 0) s.length else close + 1
                return latexOf(inner)
            }
            val start = i
            if (s[i].isDigit() || s[i] == '.') {
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            } else if (s[i] == 'π') {
                i++
            } else if (s[i].isLetter()) {
                while (i < s.length && s[i].isLetter() && s[i] != 'π') i++
            } else i++
            ii[0] = i
            return latexOf(s.substring(start, i))
        }

        fun latexOf(s: String): String {
            val out = StringBuilder()
            val ii = intArrayOf(0)
            while (ii[0] < s.length) {
                val i = ii[0]
                val c = s[i]
                when {
                    c.isWhitespace() -> ii[0]++
                    c.isDigit() || c == '.' -> {
                        var j = i
                        while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                        if (j + 1 < s.length && s[j] == 'E') {
                            var k = j + 1
                            if (k < s.length && (s[k] == '+' || s[k] == '-')) k++
                            val es = k
                            while (k < s.length && s[k].isDigit()) k++
                            if (k > es) {
                                val exponent = s.substring(j + 1, k).replace("+", "")
                                out.append(s.substring(i, j)).append(" \\times 10^{").append(exponent).append('}')
                                ii[0] = k
                                continue
                            }
                        }
                        out.append(s.substring(i, j))
                        ii[0] = j
                    }
                    c == 'π' -> { out.append("\\pi "); ii[0]++ }
                    c.isLetter() -> {
                        var j = i
                        while (j < s.length && s[j].isLetter() && s[j] != 'π') j++
                        val word = s.substring(i, j)
                        val lower = word.lowercase()
                        ii[0] = j
                        when (lower) {
                            "sqrt" -> out.append("\\sqrt{").append(operandAt(s, ii)).append('}')
                            "cbrt" -> out.append("\\sqrt[3]{").append(operandAt(s, ii)).append('}')
                            "pi" -> out.append("\\pi ")
                            else -> {
                                val f = latexFunctions.firstOrNull { lower.startsWith(it) }
                                if (f != null) {
                                    val name = if (f.startsWith("a") && !f.startsWith("arc")) "arc" + f.substring(1) else f
                                    out.append('\\').append(name).append(' ')
                                    ii[0] = i + f.length
                                } else out.append(word)
                            }
                        }
                    }
                    c == '√' -> { ii[0]++; out.append("\\sqrt{").append(operandAt(s, ii)).append('}') }
                    c == '^' -> { ii[0]++; out.append("^{").append(operandAt(s, ii)).append('}') }
                    c == '²' -> { out.append("^{2}"); ii[0]++ }
                    c == '³' -> { out.append("^{3}"); ii[0]++ }
                    c == '*' || c == '×' || c == '·' -> { out.append(" \\times "); ii[0]++ }
                    c == '/' || c == '÷' -> { out.append(" \\div "); ii[0]++ }
                    c == '-' || c == '−' -> { out.append(" - "); ii[0]++ }
                    c == '+' -> { out.append(" + "); ii[0]++ }
                    c == '%' -> { out.append("\\%"); ii[0]++ }
                    c == '(' -> {
                        val close = matchingParen(s, i)
                        val end = if (close < 0) s.length else close
                        out.append("\\left(").append(latexOf(s.substring(i + 1, end))).append("\\right)")
                        ii[0] = if (close < 0) s.length else close + 1
                    }
                    c == ')' -> ii[0]++
                    else -> { out.append(c); ii[0]++ }
                }
            }
            return out.toString().trim().replace(Regex("\\s+"), " ")
        }

        fun toLatex(expression: String, result: String): String {
            var value = result.replace('−', '-')
            val times = value.indexOf(" × 10")
            if (times >= 0) {
                val sb = StringBuilder()
                for (c in value.substring(times + 5)) {
                    val d = SUP.indexOf(c)
                    if (d >= 0) sb.append(d) else if (c == '⁻') sb.append('-')
                }
                value = value.substring(0, times) + " \\times 10^{" + sb + "}"
            }
            return latexOf(expression) + " = " + value
        }
    }
}
