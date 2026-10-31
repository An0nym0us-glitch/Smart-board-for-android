package com.classboard.app.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * A compiled real-valued expression such as "a*sin(bx + c)" or "2x² − 3x + 1": + − * / ^,
 * unary minus, implicit multiplication, |x|, functions, constants (pi, π, e), Unicode operators,
 * postfix factorial and percent. Unknown multi-letter identifiers split into single-letter
 * variables ("abx" = a·b·x). Compiled into a small stack program for fast evaluation.
 */
class Expression private constructor(
    private val program: Program?,
    val source: String,
    val variables: List<String>,
) {
    val isValid get() = program != null

    fun slotOf(name: String) = variables.indexOf(name)
    fun usesVariable(name: String) = variables.contains(name)

    fun evaluate(values: DoubleArray): Double {
        val p = program ?: return Double.NaN
        val stack = DoubleArray(p.maxDepth + 2)
        var sp = 0
        for (k in p.ops.indices) {
            when (p.ops[k]) {
                OP_CONST -> stack[sp++] = p.values[k]
                OP_VAR -> { val i = p.idx[k]; stack[sp++] = if (i < values.size) values[i] else 0.0 }
                OP_NEG -> stack[sp - 1] = -stack[sp - 1]
                OP_ADD -> { sp--; stack[sp - 1] += stack[sp] }
                OP_SUB -> { sp--; stack[sp - 1] -= stack[sp] }
                OP_MUL -> { sp--; stack[sp - 1] *= stack[sp] }
                OP_DIV -> { sp--; stack[sp - 1] /= stack[sp] }
                OP_POW -> { sp--; stack[sp - 1] = power(stack[sp - 1], stack[sp]) }
                OP_CALL1 -> stack[sp - 1] = call1(p.fns[k], stack[sp - 1], p.degrees)
                OP_CALL2 -> {
                    sp--
                    stack[sp - 1] = if (p.fns[k] == Fn.Min) min(stack[sp - 1], stack[sp]) else max(stack[sp - 1], stack[sp])
                }
            }
        }
        return if (sp == 1) stack[0] else Double.NaN
    }

    fun evaluate(values: Map<String, Double>): Double =
        evaluate(DoubleArray(variables.size) { values[variables[it]] ?: 0.0 })

    // ------------------------------------------------------------------------------ internals

    private enum class Fn(val n: String, val arity: Int) {
        Sin("sin", 1), Cos("cos", 1), Tan("tan", 1), Cot("cot", 1), Sec("sec", 1), Csc("csc", 1),
        Asin("asin", 1), Acos("acos", 1), Atan("atan", 1), Sinh("sinh", 1), Cosh("cosh", 1), Tanh("tanh", 1),
        Sqrt("sqrt", 1), Cbrt("cbrt", 1), Abs("abs", 1), Ln("ln", 1), Log("log", 1), Exp("exp", 1),
        Floor("floor", 1), Ceil("ceil", 1), Round("round", 1), Sign("sign", 1), Min("min", 2), Max("max", 2), Fact("fact", 1);
    }

    private class Program(
        val ops: IntArray, val fns: Array<Fn?>, val idx: IntArray, val values: DoubleArray,
        val maxDepth: Int, val degrees: Boolean,
    )

    private enum class Tok { Number, Ident, Plus, Minus, Star, Slash, Caret, LParen, RParen, Comma, Bar, Root, Bang, Percent, End }

    private class Token(val type: Tok, val number: Double = 0.0, val text: String = "")

    private class Node(val kind: Kind, val value: Double = 0.0, val name: String = "", val fn: Fn? = null, val args: List<Node> = emptyList()) {
        enum class Kind { Num, Var, Neg, Add, Sub, Mul, Div, Pow, Call }
    }

    private class ParseError(msg: String) : Exception(msg)

    private class Parser(val tokens: List<Token>) {
        var pos = 0
        var absDepth = 0

        fun peek() = tokens[min(pos, tokens.size - 1)]
        fun take() = tokens[min(pos++, tokens.size - 1)]

        fun parse(): Node {
            if (tokens.size <= 1) throw ParseError("Empty expression")
            val n = parseExpr()
            if (peek().type != Tok.End) throw ParseError(if (peek().type == Tok.RParen) "Unexpected ')'" else "Unexpected input")
            return n
        }

        fun startsPrimary(t: Token) = when (t.type) {
            Tok.Number, Tok.Ident, Tok.LParen, Tok.Root -> true
            Tok.Bar -> absDepth == 0
            else -> false
        }

        fun parseExpr(): Node {
            var left = parseTerm()
            while (peek().type == Tok.Plus || peek().type == Tok.Minus) {
                val op = take().type
                val right = parseTerm()
                left = Node(if (op == Tok.Plus) Node.Kind.Add else Node.Kind.Sub, args = listOf(left, right))
            }
            return left
        }

        fun parseTerm(): Node {
            var left = parseUnary()
            while (true) {
                val t = peek().type
                if (t == Tok.Star || t == Tok.Slash) {
                    take()
                    val right = parseUnary()
                    left = Node(if (t == Tok.Star) Node.Kind.Mul else Node.Kind.Div, args = listOf(left, right))
                } else if (startsPrimary(peek())) {
                    val right = parsePower()
                    left = Node(Node.Kind.Mul, args = listOf(left, right))
                } else break
            }
            return left
        }

        fun parseUnary(): Node {
            if (peek().type == Tok.Minus) {
                take()
                return Node(Node.Kind.Neg, args = listOf(parseUnary()))
            }
            if (peek().type == Tok.Plus) {
                take(); return parseUnary()
            }
            return parsePower()
        }

        fun parsePostfix(operand0: Node): Node {
            var operand = operand0
            while (peek().type == Tok.Bang || peek().type == Tok.Percent) {
                operand = if (take().type == Tok.Bang) Node(Node.Kind.Call, fn = Fn.Fact, args = listOf(operand))
                else Node(Node.Kind.Div, args = listOf(operand, Node(Node.Kind.Num, 100.0)))
            }
            return operand
        }

        fun parsePower(): Node {
            val base = parsePostfix(parsePrimary())
            if (peek().type == Tok.Caret) {
                take()
                val e = parseUnary()
                return Node(Node.Kind.Pow, args = listOf(base, e))
            }
            return base
        }

        fun parseArguments(arity: Int): List<Node> {
            val args = ArrayList<Node>()
            take()
            if (peek().type != Tok.RParen) {
                while (true) {
                    args.add(parseExpr())
                    if (peek().type == Tok.Comma) { take(); continue }
                    break
                }
            }
            if (peek().type != Tok.RParen) throw ParseError("Missing ')'")
            take()
            if (args.size != arity) throw ParseError("Wrong number of arguments")
            return args
        }

        fun applyFunction(f: Fn, rest: List<String>): Node {
            if (rest.isNotEmpty()) {
                if (f.arity != 1) throw ParseError("'${f.n}' needs ${f.arity} arguments")
                return Node(Node.Kind.Call, fn = f, args = listOf(namesProduct(rest)))
            }
            if (peek().type == Tok.LParen) return Node(Node.Kind.Call, fn = f, args = parseArguments(f.arity))
            if (peek().type == Tok.End) throw ParseError("'${f.n}' needs an argument")
            val arg = parsePower()
            if (f.arity != 1) throw ParseError("'${f.n}' needs ${f.arity} arguments")
            return Node(Node.Kind.Call, fn = f, args = listOf(arg))
        }

        fun nameNode(name: String) = if (isConstant(name)) Node(Node.Kind.Num, constantValue(name)) else Node(Node.Kind.Var, name = name)

        fun namesProduct(names: List<String>): Node {
            var result: Node? = null
            for (i in names.indices) {
                val f = findFunction(names[i])
                if (f != null) {
                    val factor = applyFunction(f, names.subList(i + 1, names.size))
                    return if (result != null) Node(Node.Kind.Mul, args = listOf(result, factor)) else factor
                }
                val factor = nameNode(names[i])
                result = if (result != null) Node(Node.Kind.Mul, args = listOf(result, factor)) else factor
            }
            return result ?: throw ParseError("Incomplete expression")
        }

        fun parsePrimary(): Node {
            val t = take()
            return when (t.type) {
                Tok.Number -> Node(Node.Kind.Num, t.number)
                Tok.LParen -> {
                    val e = parseExpr()
                    if (peek().type != Tok.RParen) throw ParseError("Missing ')'")
                    take()
                    e
                }
                Tok.Bar -> {
                    absDepth++
                    val e = parseExpr()
                    absDepth--
                    if (peek().type != Tok.Bar) throw ParseError("Missing '|'")
                    take()
                    Node(Node.Kind.Call, fn = Fn.Abs, args = listOf(e))
                }
                Tok.Root -> Node(Node.Kind.Call, fn = Fn.Sqrt, args = listOf(parsePrimary()))
                Tok.Ident -> namesProduct(splitIdentifier(t.text))
                Tok.End -> throw ParseError("Incomplete expression")
                else -> throw ParseError("Unexpected symbol")
            }
        }
    }

    companion object {
        private const val OP_CONST = 0
        private const val OP_VAR = 1
        private const val OP_NEG = 2
        private const val OP_ADD = 3
        private const val OP_SUB = 4
        private const val OP_MUL = 5
        private const val OP_DIV = 6
        private const val OP_POW = 7
        private const val OP_CALL1 = 8
        private const val OP_CALL2 = 9

        private val aliases = mapOf(
            "arcsin" to Fn.Asin, "arccos" to Fn.Acos, "arctan" to Fn.Atan, "sgn" to Fn.Sign,
        )

        private fun findFunction(name: String): Fn? {
            if (name == "fact") return null
            aliases[name]?.let { return it }
            return Fn.values().firstOrNull { it.n == name && it != Fn.Fact }
        }

        private fun isConstant(n: String) = n == "pi" || n == "π" || n == "e"
        private fun constantValue(n: String) = if (n == "e") Math.E else Math.PI

        private val allNames: List<String> by lazy {
            (Fn.values().filter { it != Fn.Fact }.map { it.n } + aliases.keys + listOf("pi")).sortedByDescending { it.length }
        }

        private fun splitIdentifier(ident: String): List<String> {
            if (findFunction(ident) != null || isConstant(ident) || ident.length == 1) return listOf(ident)
            val out = ArrayList<String>()
            var i = 0
            while (i < ident.length) {
                val m = allNames.firstOrNull { it.length > 1 && ident.startsWith(it, i) }
                if (m != null) { out.add(m); i += m.length } else { out.add(ident.substring(i, i + 1)); i++ }
            }
            return out
        }

        private fun normalize(s0: String): String = s0
            .replace('×', '*').replace('·', '*').replace('⋅', '*').replace('∗', '*')
            .replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("²", "^2").replace("³", "^3").replace("**", "^")

        private fun tokenize(input: String): List<Token> {
            val out = ArrayList<Token>()
            val s = normalize(input)
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                if (c.isWhitespace()) { i++; continue }
                if (c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit())) {
                    var j = i
                    while (j < n && (s[j].isDigit() || s[j] == '.')) j++
                    if (j < n && s[j] == 'E' && j + 1 < n &&
                        (s[j + 1].isDigit() || ((s[j + 1] == '-' || s[j + 1] == '+') && j + 2 < n && s[j + 2].isDigit()))
                    ) {
                        j += 2
                        while (j < n && s[j].isDigit()) j++
                    }
                    val v = s.substring(i, j).toDoubleOrNull() ?: throw ParseError("Invalid number '${s.substring(i, j)}'")
                    out.add(Token(Tok.Number, v))
                    i = j
                } else if (c.isLetter() || c == '_') {
                    var j = i
                    while (j < n && (s[j].isLetter() || s[j] == '_')) j++
                    out.add(Token(Tok.Ident, text = s.substring(i, j)))
                    i = j
                } else {
                    val t = when (c) {
                        '+' -> Tok.Plus
                        '-' -> Tok.Minus
                        '*' -> Tok.Star
                        '/' -> Tok.Slash
                        '^' -> Tok.Caret
                        '(', '[', '{' -> Tok.LParen
                        ')', ']', '}' -> Tok.RParen
                        ',', ';' -> Tok.Comma
                        '|' -> Tok.Bar
                        '√' -> Tok.Root
                        '!' -> Tok.Bang
                        '%' -> Tok.Percent
                        else -> throw ParseError("Unexpected character '$c'")
                    }
                    out.add(Token(t))
                    i++
                }
            }
            out.add(Token(Tok.End))
            return out
        }

        /** Result of a compilation: the expression and an error message (empty on success). */
        fun compileWithError(text: String, degrees: Boolean = false): Pair<Expression, String> {
            return try {
                val root = Parser(tokenize(text)).parse()
                val ops = ArrayList<Int>()
                val fns = ArrayList<Fn?>()
                val idx = ArrayList<Int>()
                val vals = ArrayList<Double>()
                val vars = ArrayList<String>()
                var maxDepth = 0
                fun push(op: Int, v: Double = 0.0, i: Int = 0, f: Fn? = null) { ops.add(op); vals.add(v); idx.add(i); fns.add(f) }
                fun emit(nd: Node, depth: Int) {
                    when (nd.kind) {
                        Node.Kind.Num -> { push(OP_CONST, nd.value); maxDepth = max(maxDepth, depth + 1) }
                        Node.Kind.Var -> {
                            var slot = vars.indexOf(nd.name)
                            if (slot < 0) { vars.add(nd.name); slot = vars.size - 1 }
                            push(OP_VAR, i = slot); maxDepth = max(maxDepth, depth + 1)
                        }
                        Node.Kind.Neg -> { emit(nd.args[0], depth); push(OP_NEG) }
                        Node.Kind.Call -> {
                            nd.args.forEachIndexed { k, a -> emit(a, depth + k) }
                            push(if (nd.args.size == 2) OP_CALL2 else OP_CALL1, f = nd.fn)
                        }
                        else -> {
                            emit(nd.args[0], depth)
                            emit(nd.args[1], depth + 1)
                            push(
                                when (nd.kind) {
                                    Node.Kind.Add -> OP_ADD
                                    Node.Kind.Sub -> OP_SUB
                                    Node.Kind.Mul -> OP_MUL
                                    Node.Kind.Div -> OP_DIV
                                    else -> OP_POW
                                },
                            )
                        }
                    }
                }
                emit(root, 0)
                val prog = Program(ops.toIntArray(), fns.toTypedArray(), idx.toIntArray(), vals.toDoubleArray(), maxDepth, degrees)
                Pair(Expression(prog, text, vars), "")
            } catch (e: ParseError) {
                Pair(Expression(null, text, emptyList()), e.message ?: "Invalid expression")
            } catch (e: StackOverflowError) {
                Pair(Expression(null, text, emptyList()), "Expression too complex")
            }
        }

        fun compile(text: String, degrees: Boolean = false) = compileWithError(text, degrees).first

        private val defRe = Regex("^\\s*([A-Za-z])\\s*(\\(\\s*[A-Za-z]\\s*\\))?\\s*=\\s*(.*)$")

        /** Splits "f(x) = expr" / "y = expr" into name and body. */
        fun stripDefinition(text: String): Pair<String, String> {
            val m = defRe.find(text) ?: return Pair("", text)
            return Pair(m.groupValues[1], m.groupValues[3])
        }

        private fun sinDeg(d: Double): Double {
            var r = d % 360.0
            if (r < 0) r += 360.0
            return when (r) {
                0.0, 180.0 -> 0.0
                90.0 -> 1.0
                270.0 -> -1.0
                30.0, 150.0 -> 0.5
                210.0, 330.0 -> -0.5
                else -> sin(r * Math.PI / 180.0)
            }
        }

        private fun cosDeg(d: Double) = sinDeg(d + 90.0)

        private fun tanDeg(d: Double): Double {
            var r = d % 180.0
            if (r < 0) r += 180.0
            return when (r) {
                0.0 -> 0.0
                90.0 -> Double.NaN
                45.0 -> 1.0
                135.0 -> -1.0
                else -> sinDeg(r) / cosDeg(r)
            }
        }

        private fun factorial(a: Double): Double {
            if (a < 0 || a != floor(a) || a > 170) return if (a > 170) Double.POSITIVE_INFINITY else Double.NaN
            var r = 1.0
            for (i in 2..a.toInt()) r *= i
            return r
        }

        private fun call1(fn: Fn?, a: Double, degrees: Boolean): Double {
            if (degrees) {
                when (fn) {
                    Fn.Sin -> return sinDeg(a)
                    Fn.Cos -> return cosDeg(a)
                    Fn.Tan -> return tanDeg(a)
                    Fn.Cot -> return 1.0 / tanDeg(a)
                    Fn.Sec -> return 1.0 / cosDeg(a)
                    Fn.Csc -> return 1.0 / sinDeg(a)
                    Fn.Asin -> return asin(a) * 180.0 / Math.PI
                    Fn.Acos -> return acos(a) * 180.0 / Math.PI
                    Fn.Atan -> return atan(a) * 180.0 / Math.PI
                    else -> {}
                }
            }
            return when (fn) {
                Fn.Sin -> sin(a)
                Fn.Cos -> cos(a)
                Fn.Tan -> tan(a)
                Fn.Cot -> 1.0 / tan(a)
                Fn.Sec -> 1.0 / cos(a)
                Fn.Csc -> 1.0 / sin(a)
                Fn.Asin -> asin(a)
                Fn.Acos -> acos(a)
                Fn.Atan -> atan(a)
                Fn.Sinh -> sinh(a)
                Fn.Cosh -> cosh(a)
                Fn.Tanh -> tanh(a)
                Fn.Sqrt -> sqrt(a)
                Fn.Cbrt -> cbrt(a)
                Fn.Abs -> abs(a)
                Fn.Ln -> ln(a)
                Fn.Log -> log10(a)
                Fn.Exp -> exp(a)
                Fn.Floor -> floor(a)
                Fn.Ceil -> ceil(a)
                Fn.Round -> round(a)
                Fn.Sign -> if (a > 0) 1.0 else if (a < 0) -1.0 else 0.0
                Fn.Fact -> factorial(a)
                else -> Double.NaN
            }
        }

        private fun power(a: Double, b: Double): Double {
            if (a < 0 && floor(b) != b) {
                val inv = 1.0 / b
                if (abs(inv - round(inv)) < 1e-9 && Math.round(inv) % 2L != 0L) return -(-a).pow(b)
            }
            return a.pow(b)
        }
    }
}
