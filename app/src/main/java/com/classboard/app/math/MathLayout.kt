package com.classboard.app.math

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Typesets a LaTeX-style formula (\frac, \sqrt, ^, _, \int, \sum, \lim, \vec, matrices, Greek
 * letters, relations, arrows) into a box tree drawn with plain text and paths, so it stays crisp
 * at any zoom and vector in PDF export. Unknown commands are shown literally.
 */
object MathTypesetter {
    fun layout(latex: String, pixelSize: Double): MathBox = MathParser(latex).parseAll(max(4.0, pixelSize))
}

enum class MKind { Ord, Op, Bin, Rel, Open, Close, Punct, Inner }

/** Laid-out box: baseline at y = 0, ascent upwards, descent downwards. */
abstract class MathBox {
    var width = 0.0
    var ascent = 0.0
    var descent = 0.0
    var kind = MKind.Ord
    var limits = false
    val height get() = ascent + descent
    abstract fun paint(c: Canvas, x: Double, y: Double, color: Int)
}

private object MathPaints {
    val serif: Typeface = Typeface.SERIF
    val serifItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    fun font(size: Double, italic: Boolean, bold: Boolean = false): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        p.typeface = if (bold) serifBold else if (italic) serifItalic else serif
        p.textSize = max(4, size.roundToInt()).toFloat()
        return p
    }

    fun tight(p: Paint, text: String): RectF {
        val path = Path()
        p.getTextPath(text, 0, text.length, 0f, 0f, path)
        val r = RectF()
        path.computeBounds(r, true)
        return r
    }

    fun xHeight(p: Paint): Double {
        val r = tight(p, "x")
        return -r.top.toDouble()
    }

    fun line(color: Int, w: Double, cap: Paint.Cap = Paint.Cap.ROUND): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = w.toFloat()
        p.strokeCap = cap
        p.strokeJoin = Paint.Join.ROUND
        return p
    }
}

private class GlyphBox(val text: String, val font: Paint, k: MKind, size: Double, italic: Boolean) : MathBox() {
    init {
        kind = k
        width = font.measureText(text).toDouble()
        if (italic) width += size * 0.04
        val t = MathPaints.tight(font, text)
        val xh = MathPaints.xHeight(font)
        ascent = max(-t.top.toDouble(), xh * 0.9)
        descent = max(t.bottom.toDouble(), 0.0)
        if (text.isBlank()) { ascent = xh; descent = 0.0 }
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        font.color = color
        c.drawText(text, x.toFloat(), y.toFloat(), font)
    }
}

private class SpaceBox(w: Double) : MathBox() {
    init { width = w }
    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {}
}

private class RowBox : MathBox() {
    class Item(val box: MathBox, var kind: MKind, var x: Double = 0.0)
    val items = ArrayList<Item>()

    fun append(b: MathBox?, k: MKind) { if (b != null) items.add(Item(b, k)) }
    val isEmpty get() = items.isEmpty()
    val count get() = items.size

    fun finish(size: Double) {
        val em = size
        var x = 0.0
        ascent = 0.0; descent = 0.0
        var prev = MKind.Open
        var first = true
        for (it in items) {
            var k = it.kind
            if (k == MKind.Bin && (first || prev == MKind.Bin || prev == MKind.Rel || prev == MKind.Open || prev == MKind.Punct || prev == MKind.Op))
                k = MKind.Ord
            it.kind = k
            if (!first) {
                var space = 0.0
                if (k == MKind.Bin || prev == MKind.Bin) space = 0.22 * em
                else if (k == MKind.Rel || prev == MKind.Rel) space = 0.28 * em
                else if (prev == MKind.Punct) space = 0.17 * em
                else if (prev == MKind.Op && (k == MKind.Ord || k == MKind.Inner)) space = 0.14 * em
                else if (k == MKind.Op && (prev == MKind.Ord || prev == MKind.Close)) space = 0.14 * em
                x += space
            }
            it.x = x
            x += it.box.width
            ascent = max(ascent, it.box.ascent)
            descent = max(descent, it.box.descent)
            prev = k
            first = false
        }
        width = x
        if (items.isEmpty()) { ascent = size * 0.5; descent = 0.0; width = size * 0.3 }
        if (items.size == 1) {
            kind = items[0].kind
            limits = items[0].box.limits
        } else kind = MKind.Ord
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        for (it in items) it.box.paint(c, x + it.x, y, color)
    }
}

private class FracBox(val num: MathBox, val den: MathBox, size: Double, val bar: Boolean) : MathBox() {
    val axis = size * 0.27
    val rule = max(1.0, size * 0.055)
    val pad = size * 0.12
    val numY: Double
    val denY: Double

    init {
        kind = MKind.Inner
        val gap = size * 0.14
        width = max(num.width, den.width) + 2 * pad
        numY = -(axis + rule / 2 + gap + num.descent)
        denY = -axis + rule / 2 + gap + den.ascent
        ascent = -numY + num.ascent
        descent = max(0.0, denY + den.descent)
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        num.paint(c, x + (width - num.width) / 2, y + numY, color)
        den.paint(c, x + (width - den.width) / 2, y + denY, color)
        if (bar) c.drawLine((x + pad * 0.4).toFloat(), (y - axis).toFloat(), (x + width - pad * 0.4).toFloat(), (y - axis).toFloat(), MathPaints.line(color, rule, Paint.Cap.BUTT))
    }
}

private class RadicalBox(val body: MathBox, val index: MathBox?, val size: Double) : MathBox() {
    val rule = max(1.0, size * 0.055)
    val top: Double
    val bottom: Double
    val signW: Double
    var offset = 0.0
    val bodyX: Double
    var indexY = 0.0

    init {
        val gap = size * 0.12
        top = -(body.ascent + gap + rule / 2)
        bottom = body.descent + size * 0.08
        val h = bottom - top
        signW = min(size * 0.9, size * 0.42 + h * 0.12)
        if (index != null) offset = max(0.0, index.width - signW * 0.45)
        bodyX = offset + signW + size * 0.06
        width = bodyX + body.width + size * 0.12
        ascent = -top + rule / 2
        descent = bottom
        if (index != null) {
            val ib = bottom - h * 0.52
            ascent = max(ascent, -ib + index.ascent)
            indexY = ib
        }
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        val h = bottom - top
        val x0 = x + offset
        val yMid = y + bottom - h * 0.42
        val path = Path()
        path.moveTo(x0.toFloat(), (yMid + size * 0.02).toFloat())
        path.lineTo((x0 + signW * 0.2).toFloat(), (yMid - size * 0.07).toFloat())
        path.lineTo((x0 + signW * 0.5).toFloat(), (y + bottom).toFloat())
        path.lineTo((x0 + signW).toFloat(), (y + top).toFloat())
        path.lineTo((x + width - size * 0.04).toFloat(), (y + top).toFloat())
        val p = MathPaints.line(color, rule, Paint.Cap.SQUARE)
        p.strokeJoin = Paint.Join.MITER
        c.drawPath(path, p)
        body.paint(c, x + bodyX, y, color)
        index?.paint(c, x + max(0.0, offset + signW * 0.45 - index.width), y + indexY, color)
    }
}

private class ScriptBox(val base: MathBox, val sup: MathBox?, val sub: MathBox?, size: Double, baseKind: MKind) : MathBox() {
    var supY = 0.0
    var subY = 0.0
    val scriptX: Double

    init {
        kind = baseKind
        var u = 0.0
        var d = 0.0
        if (sup != null) u = max(size * 0.42, base.ascent - size * 0.32)
        if (sub != null) d = max(size * 0.2, base.descent - size * 0.05)
        if (sup != null && sub != null) {
            val gap = (u - sup.descent) - (sub.ascent - d)
            if (gap < size * 0.12) d += size * 0.12 - gap
        }
        supY = -u; subY = d
        scriptX = base.width + size * 0.03
        width = scriptX + max(sup?.width ?: 0.0, sub?.width ?: 0.0) + size * 0.04
        ascent = max(base.ascent, if (sup != null) u + sup.ascent else 0.0)
        descent = max(base.descent, if (sub != null) d + sub.descent else 0.0)
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        base.paint(c, x, y, color)
        sup?.paint(c, x + scriptX, y + supY, color)
        sub?.paint(c, x + scriptX, y + subY, color)
    }
}

private class LimitsBox(val nucleus: MathBox, val over: MathBox?, val under: MathBox?, size: Double) : MathBox() {
    val overY: Double
    val underY: Double

    init {
        kind = MKind.Op
        val gap = size * 0.12
        width = maxOf(nucleus.width, over?.width ?: 0.0, under?.width ?: 0.0)
        overY = -(nucleus.ascent + gap + (over?.descent ?: 0.0))
        underY = nucleus.descent + gap + (under?.ascent ?: 0.0)
        ascent = if (over != null) -overY + over.ascent else nucleus.ascent
        descent = if (under != null) underY + under.descent else nucleus.descent
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        nucleus.paint(c, x + (width - nucleus.width) / 2, y, color)
        over?.paint(c, x + (width - over.width) / 2, y + overY, color)
        under?.paint(c, x + (width - under.width) / 2, y + underY, color)
    }
}

private class BigGlyphBox(val text: String, val font: Paint, size: Double) : MathBox() {
    val shift: Double

    init {
        kind = MKind.Op
        val t = MathPaints.tight(font, text)
        width = font.measureText(text) + size * 0.08
        val axis = size * 0.27
        val h = (t.bottom - t.top).toDouble()
        shift = -axis - (t.top + h / 2)
        ascent = -(t.top + shift)
        descent = t.bottom + shift
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        font.color = color
        c.drawText(text, x.toFloat(), (y + shift).toFloat(), font)
    }
}

private enum class AccentKind { Vec, Hat, Bar, Dot, DDot, Tilde, Underline }

private class AccentBox(val body: MathBox, val accent: AccentKind, val size: Double) : MathBox() {
    val gap = size * 0.1
    val h = if (accent == AccentKind.Underline) 0.0 else size * 0.22

    init {
        width = max(body.width, if (accent == AccentKind.Vec) size * 0.55 else 0.0)
        ascent = body.ascent + gap + h
        descent = body.descent + if (accent == AccentKind.Underline) size * 0.18 else 0.0
        kind = body.kind
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        body.paint(c, x + (width - body.width) / 2, y, color)
        val rule = max(1.0, size * 0.05)
        val ay = y - body.ascent - gap - h * 0.5
        val x0 = x + size * 0.04
        val x1 = x + width - size * 0.02
        val cx = (x0 + x1) / 2
        val p = MathPaints.line(color, rule)
        fun ln(ax: Double, ay0: Double, bx: Double, by: Double) = c.drawLine(ax.toFloat(), ay0.toFloat(), bx.toFloat(), by.toFloat(), p)
        when (accent) {
            AccentKind.Vec -> {
                ln(x0, ay, x1, ay)
                val head = size * 0.14
                ln(x1, ay, x1 - head, ay - head * 0.7)
                ln(x1, ay, x1 - head, ay + head * 0.7)
            }
            AccentKind.Hat -> {
                val w = min(width * 0.5, size * 0.35)
                ln(cx - w, ay + h * 0.4, cx, ay - h * 0.4)
                ln(cx, ay - h * 0.4, cx + w, ay + h * 0.4)
            }
            AccentKind.Bar -> ln(x0, ay, x1, ay)
            AccentKind.Dot -> {
                val f = Paint(Paint.ANTI_ALIAS_FLAG); f.color = color
                c.drawCircle(cx.toFloat(), ay.toFloat(), (rule * 1.1).toFloat(), f)
            }
            AccentKind.DDot -> {
                val f = Paint(Paint.ANTI_ALIAS_FLAG); f.color = color
                c.drawCircle((cx - size * 0.1).toFloat(), ay.toFloat(), (rule * 1.1).toFloat(), f)
                c.drawCircle((cx + size * 0.1).toFloat(), ay.toFloat(), (rule * 1.1).toFloat(), f)
            }
            AccentKind.Tilde -> {
                val w = min(width * 0.5, size * 0.3)
                val path = Path()
                path.moveTo((cx - w).toFloat(), (ay + h * 0.2).toFloat())
                path.cubicTo((cx - w * 0.4).toFloat(), (ay - h * 0.6).toFloat(), (cx + w * 0.4).toFloat(), (ay + h * 0.6).toFloat(), (cx + w).toFloat(), (ay - h * 0.2).toFloat())
                c.drawPath(path, p)
            }
            AccentKind.Underline -> {
                val uy = y + body.descent + size * 0.1
                ln(x0, uy, x1, uy)
            }
        }
    }
}

private fun paintDelimiter(c: Canvas, d: Char, left: Double, top: Double, w: Double, h: Double, color: Int, rule: Double) {
    val p = MathPaints.line(color, rule)
    val l = left + w * 0.2
    val rr = left + w - w * 0.2
    val bottom = top + h
    val cy = top + h / 2
    val cxm = left + w / 2
    fun f(v: Double) = v.toFloat()
    when (d) {
        '(' -> { val path = Path(); path.moveTo(f(rr), f(top)); path.quadTo(f(l - w * 0.25), f(cy), f(rr), f(bottom)); c.drawPath(path, p) }
        ')' -> { val path = Path(); path.moveTo(f(l), f(top)); path.quadTo(f(rr + w * 0.25), f(cy), f(l), f(bottom)); c.drawPath(path, p) }
        '[' -> { val path = Path(); path.moveTo(f(rr), f(top)); path.lineTo(f(l), f(top)); path.lineTo(f(l), f(bottom)); path.lineTo(f(rr), f(bottom)); c.drawPath(path, p) }
        ']' -> { val path = Path(); path.moveTo(f(l), f(top)); path.lineTo(f(rr), f(top)); path.lineTo(f(rr), f(bottom)); path.lineTo(f(l), f(bottom)); c.drawPath(path, p) }
        '{', '}' -> {
            val isLeft = d == '{'
            val outer = if (isLeft) rr else l
            val inner = if (isLeft) l else rr
            val mid = (outer + inner) / 2
            val path = Path()
            path.moveTo(f(outer), f(top))
            path.quadTo(f(mid), f(top), f(mid), f(top + h * 0.12))
            path.lineTo(f(mid), f(cy - h * 0.1))
            path.quadTo(f(mid), f(cy), f(inner), f(cy))
            path.quadTo(f(mid), f(cy), f(mid), f(cy + h * 0.1))
            path.lineTo(f(mid), f(bottom - h * 0.12))
            path.quadTo(f(mid), f(bottom), f(outer), f(bottom))
            c.drawPath(path, p)
        }
        '|' -> c.drawLine(f(cxm), f(top), f(cxm), f(bottom), p)
        '‖' -> {
            c.drawLine(f(cxm - w * 0.15), f(top), f(cxm - w * 0.15), f(bottom), p)
            c.drawLine(f(cxm + w * 0.15), f(top), f(cxm + w * 0.15), f(bottom), p)
        }
        '⟨' -> { val path = Path(); path.moveTo(f(rr), f(top)); path.lineTo(f(l), f(cy)); path.lineTo(f(rr), f(bottom)); c.drawPath(path, p) }
        '⟩' -> { val path = Path(); path.moveTo(f(l), f(top)); path.lineTo(f(rr), f(cy)); path.lineTo(f(l), f(bottom)); c.drawPath(path, p) }
        else -> {}
    }
}

private class DelimBox(val left: Char?, val body: MathBox, val right: Char?, val size: Double) : MathBox() {
    val top: Double
    val bottom: Double
    val delimW: Double
    val bodyX: Double

    init {
        kind = MKind.Inner
        val axis = size * 0.27
        val half = max(body.ascent - axis, body.descent + axis) + size * 0.12
        top = -axis - half
        bottom = -axis + half
        delimW = min(size * 0.7, size * 0.32 + (bottom - top) * 0.04)
        val lw = if (left == null || left == '.') 0.0 else delimW
        val rw = if (right == null || right == '.') 0.0 else delimW
        bodyX = lw + size * 0.04
        width = bodyX + body.width + size * 0.04 + rw
        ascent = -top
        descent = max(0.0, bottom)
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        val rule = max(1.0, size * 0.06)
        if (left != null && left != '.') paintDelimiter(c, left, x, y + top, delimW, bottom - top, color, rule)
        body.paint(c, x + bodyX, y, color)
        if (right != null && right != '.') paintDelimiter(c, right, x + width - delimW, y + top, delimW, bottom - top, color, rule)
    }
}

private class MatrixBox(val cells: List<List<MathBox>>, size: Double, val leftAlign: Boolean) : MathBox() {
    val colWidths: DoubleArray
    val rowBaselines = ArrayList<Double>()
    val offsetY: Double
    val colGap: Double

    init {
        kind = MKind.Inner
        val cols = cells.maxOfOrNull { it.size } ?: 0
        colWidths = DoubleArray(cols)
        for (row in cells) for (c in row.indices) colWidths[c] = max(colWidths[c], row[c].width)
        colGap = size * (if (leftAlign) 1.0 else 0.9)
        val rowGap = size * 0.35
        var y = 0.0
        for (row in cells) {
            var a = size * 0.5
            var d = size * 0.15
            for (cell in row) { a = max(a, cell.ascent); d = max(d, cell.descent) }
            y += a
            rowBaselines.add(y)
            y += d + rowGap
        }
        val total = max(0.0, y - rowGap)
        val axis = size * 0.27
        offsetY = -axis - total / 2
        var w = 0.0
        for (c in 0 until cols) w += colWidths[c] + if (c + 1 < cols) colGap else 0.0
        width = w
        ascent = -offsetY
        descent = max(0.0, total + offsetY)
    }

    override fun paint(c: Canvas, x: Double, y: Double, color: Int) {
        for (r in cells.indices) {
            var cx = x
            for (col in colWidths.indices) {
                if (col < cells[r].size) {
                    val cell = cells[r][col]
                    val px = if (leftAlign) cx else cx + (colWidths[col] - cell.width) / 2
                    cell.paint(c, px, y + offsetY + rowBaselines[r], color)
                }
                cx += colWidths[col] + colGap
            }
        }
    }
}

private class MathParser(val s: String) {
    var i = 0

    companion object {
        val symbols: Map<String, Pair<Char, MKind>> = buildMap {
            fun put(k: String, c: Int, kind: MKind) = put(k, Pair(c.toChar(), kind))
            val ord = MKind.Ord
            listOf(
                "alpha" to 0x03B1, "beta" to 0x03B2, "gamma" to 0x03B3, "delta" to 0x03B4, "epsilon" to 0x03B5,
                "varepsilon" to 0x03B5, "zeta" to 0x03B6, "eta" to 0x03B7, "theta" to 0x03B8, "vartheta" to 0x03D1,
                "iota" to 0x03B9, "kappa" to 0x03BA, "lambda" to 0x03BB, "mu" to 0x03BC, "nu" to 0x03BD, "xi" to 0x03BE,
                "pi" to 0x03C0, "rho" to 0x03C1, "sigma" to 0x03C3, "tau" to 0x03C4, "upsilon" to 0x03C5, "phi" to 0x03C6,
                "varphi" to 0x03C6, "chi" to 0x03C7, "psi" to 0x03C8, "omega" to 0x03C9,
                "Gamma" to 0x0393, "Delta" to 0x0394, "Theta" to 0x0398, "Lambda" to 0x039B, "Xi" to 0x039E,
                "Pi" to 0x03A0, "Sigma" to 0x03A3, "Phi" to 0x03A6, "Psi" to 0x03A8, "Omega" to 0x03A9,
            ).forEach { (k, c) -> put(k, c, ord) }
            listOf(
                "pm" to 0x00B1, "mp" to 0x2213, "times" to 0x00D7, "div" to 0x00F7, "cdot" to 0x22C5, "ast" to 0x2217,
                "circ" to 0x2218, "cup" to 0x222A, "cap" to 0x2229, "setminus" to 0x2216, "oplus" to 0x2295,
                "otimes" to 0x2297, "wedge" to 0x2227, "vee" to 0x2228,
            ).forEach { (k, c) -> put(k, c, MKind.Bin) }
            listOf(
                "leq" to 0x2264, "le" to 0x2264, "geq" to 0x2265, "ge" to 0x2265, "neq" to 0x2260, "ne" to 0x2260,
                "approx" to 0x2248, "equiv" to 0x2261, "sim" to 0x223C, "simeq" to 0x2243, "cong" to 0x2245,
                "propto" to 0x221D, "ll" to 0x226A, "gg" to 0x226B, "in" to 0x2208, "notin" to 0x2209, "ni" to 0x220B,
                "subset" to 0x2282, "supset" to 0x2283, "subseteq" to 0x2286, "supseteq" to 0x2287, "perp" to 0x22A5,
                "parallel" to 0x2225, "mid" to 0x2223, "to" to 0x2192, "rightarrow" to 0x2192, "leftarrow" to 0x2190,
                "gets" to 0x2190, "leftrightarrow" to 0x2194, "Rightarrow" to 0x21D2, "implies" to 0x21D2,
                "Leftarrow" to 0x21D0, "Leftrightarrow" to 0x21D4, "iff" to 0x21D4, "mapsto" to 0x21A6,
                "uparrow" to 0x2191, "downarrow" to 0x2193, "therefore" to 0x2234, "because" to 0x2235,
            ).forEach { (k, c) -> put(k, c, MKind.Rel) }
            listOf(
                "infty" to 0x221E, "partial" to 0x2202, "nabla" to 0x2207, "forall" to 0x2200, "exists" to 0x2203,
                "emptyset" to 0x2205, "varnothing" to 0x2205, "angle" to 0x2220, "triangle" to 0x25B3, "degree" to 0x00B0,
                "prime" to 0x2032, "vdots" to 0x22EE, "ddots" to 0x22F1, "hbar" to 0x210F, "ell" to 0x2113,
                "Re" to 0x211C, "Im" to 0x2111, "aleph" to 0x2135, "neg" to 0x00AC, "|" to 0x2016, "%" to 0x25,
                "#" to 0x23, "$" to 0x24, "&" to 0x26, "_" to 0x5F,
            ).forEach { (k, c) -> put(k, c, ord) }
            put("ldots", 0x2026, MKind.Inner); put("cdots", 0x22EF, MKind.Inner); put("dots", 0x2026, MKind.Inner)
            put("langle", 0x27E8, MKind.Open); put("rangle", 0x27E9, MKind.Close)
            put("lvert", 0x7C, MKind.Open); put("rvert", 0x7C, MKind.Close)
            put("{", 0x7B, MKind.Open); put("}", 0x7D, MKind.Close)
        }

        val bigOps = mapOf(
            "sum" to Pair('∑', true), "prod" to Pair('∏', true), "coprod" to Pair('∐', true),
            "bigcup" to Pair('⋃', true), "bigcap" to Pair('⋂', true), "int" to Pair('∫', false),
            "iint" to Pair('∬', false), "iiint" to Pair('∭', false), "oint" to Pair('∮', false),
        )
        val functions = setOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "deg", "dim", "arg", "ker", "hom")
        val limitFunctions = setOf("lim", "max", "min", "sup", "inf", "det", "gcd", "limsup", "liminf")
        val accents = mapOf(
            "vec" to AccentKind.Vec, "overrightarrow" to AccentKind.Vec, "hat" to AccentKind.Hat, "widehat" to AccentKind.Hat,
            "bar" to AccentKind.Bar, "overline" to AccentKind.Bar, "dot" to AccentKind.Dot, "ddot" to AccentKind.DDot,
            "tilde" to AccentKind.Tilde, "widetilde" to AccentKind.Tilde, "underline" to AccentKind.Underline,
        )

        fun classifyChar(c: Char): MKind = when (c) {
            '+', '-', '−', '*', '±', '×', '÷', '⋅', '·', '∪', '∩', '∘' -> MKind.Bin
            '=', '<', '>', ':', '≤', '≥', '≠', '≈', '≡', '→', '←', '⇒', '⇔',
            '∈', '⊂', '⊆', '∼' -> MKind.Rel
            '(', '[' -> MKind.Open
            ')', ']', '!' -> MKind.Close
            ',', ';' -> MKind.Punct
            else -> MKind.Ord
        }

        fun scriptSize(size: Double) = max(size * 0.7, 6.0)
    }

    fun atEnd() = i >= s.length
    fun lookingAt(t: String) = s.startsWith(t, i)
    fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

    fun parseAll(size: Double): MathBox {
        var row: MathBox = parseRow(size)
        while (i < s.length) {
            when {
                lookingAt("\\right") -> { i += 6; readDelimiter() }
                lookingAt("\\end") -> { i += 4; readRawGroup() }
                lookingAt("\\\\") -> i += 2
                else -> i++
            }
            val more = parseRow(size)
            val combined = RowBox()
            combined.append(row, MKind.Ord)
            combined.append(more, MKind.Ord)
            combined.finish(size)
            row = combined
        }
        return row
    }

    fun readCommand(): String {
        i++
        if (atEnd()) return ""
        if (!s[i].isLetter()) return s[i++].toString()
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        return s.substring(start, i)
    }

    fun readRawGroup(): String {
        skipSpaces()
        if (atEnd() || s[i] != '{') return ""
        i++
        var depth = 1
        val start = i
        while (i < s.length) {
            if (s[i] == '{') depth++ else if (s[i] == '}' && --depth == 0) break
            i++
        }
        val text = s.substring(start, i)
        if (i < s.length) i++
        return text
    }

    fun atRowTerminator(): Boolean {
        if (atEnd()) return true
        val c = s[i]
        if (c == '}' || c == '&') return true
        return lookingAt("\\\\") || lookingAt("\\right") || lookingAt("\\end")
    }

    fun parseRow(size: Double): RowBox {
        val row = RowBox()
        while (true) {
            skipSpaces()
            if (atRowTerminator()) break
            val kind = arrayOf(MKind.Ord)
            var atom = parseAtom(size, kind) ?: continue
            atom = parseScripts(atom, size, kind)
            row.append(atom, kind[0])
        }
        row.finish(size)
        return row
    }

    fun parseScripts(base: MathBox, size: Double, kind: Array<MKind>): MathBox {
        var sup: MathBox? = null
        var sub: MathBox? = null
        while (true) {
            skipSpaces()
            if (atEnd()) break
            val c = s[i]
            if (c == '^' && sup == null) { i++; sup = parseArgument(scriptSize(size)) }
            else if (c == '_' && sub == null) { i++; sub = parseArgument(scriptSize(size)) }
            else if (c == '\'' && sup == null) { i++; sup = GlyphBox("′", MathPaints.font(scriptSize(size), false), MKind.Ord, size, false) }
            else break
        }
        if (sup == null && sub == null) return base
        if (base.limits) return LimitsBox(base, sup, sub, size)
        return ScriptBox(base, sup, sub, size, kind[0])
    }

    fun parseArgument(size: Double): MathBox {
        skipSpaces()
        if (atEnd()) return RowBox().also { it.finish(size) }
        if (s[i] == '{') {
            i++
            val row = parseRow(size)
            if (!atEnd() && s[i] == '}') i++
            return row
        }
        if (s[i].isDigit()) return glyph(s[i++].toString(), size, false, MKind.Ord)
        return parseAtom(size, arrayOf(MKind.Ord)) ?: RowBox().also { it.finish(size) }
    }

    fun glyph(text: String, size: Double, italic: Boolean, kind: MKind, bold: Boolean = false): MathBox =
        GlyphBox(text, MathPaints.font(size, italic, bold), kind, size, italic)

    fun parseAtom(size: Double, kind: Array<MKind>): MathBox? {
        val c = s[i]
        if (c == '{') {
            i++
            val row = parseRow(size)
            if (!atEnd() && s[i] == '}') i++
            kind[0] = if (row.count == 1) row.kind else MKind.Ord
            return row
        }
        if (c == '\\') return parseCommand(size, kind)
        if (c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit())) {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            kind[0] = MKind.Ord
            return glyph(s.substring(start, i), size, false, MKind.Ord)
        }
        i++
        if (c == '^' || c == '_') {
            i--
            kind[0] = MKind.Ord
            return SpaceBox(0.0)
        }
        if (c.isLetter()) {
            kind[0] = MKind.Ord
            val upperGreek = c.code in 0x0391..0x03A9
            return glyph(c.toString(), size, !upperGreek, MKind.Ord)
        }
        var text = c.toString()
        if (c == '-') text = "−"
        else if (c == '*') text = "⋅"
        else if (c == '~') { kind[0] = MKind.Ord; return SpaceBox(size * 0.33) }
        kind[0] = classifyChar(c)
        return glyph(text, size, false, kind[0])
    }

    fun readDelimiter(): Char? {
        skipSpaces()
        if (atEnd()) return null
        if (s[i] == '\\') {
            return when (readCommand()) {
                "{" -> '{'
                "}" -> '}'
                "langle" -> '⟨'
                "rangle" -> '⟩'
                "|", "Vert" -> '‖'
                "lvert", "rvert", "vert" -> '|'
                else -> '.'
            }
        }
        return s[i++]
    }

    fun parseMatrix(env: String, size: Double, kind: Array<MKind>): MathBox {
        val rows = ArrayList<ArrayList<MathBox>>()
        rows.add(ArrayList())
        val cellSize = if (env == "smallmatrix") size * 0.75 else size
        while (!atEnd()) {
            val cell = parseRow(cellSize)
            rows.last().add(cell)
            if (atEnd()) break
            if (s[i] == '&') { i++; continue }
            if (lookingAt("\\\\")) { i += 2; rows.add(ArrayList()); continue }
            if (lookingAt("\\end")) { i += 4; readRawGroup(); break }
            i++
        }
        while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.size - 1)
        if (rows.size > 1 && rows.last().size == 1) {
            val last = rows.last()[0] as? RowBox
            if (last != null && last.isEmpty) rows.removeAt(rows.size - 1)
        }
        val cases = env == "cases"
        val grid = MatrixBox(rows, cellSize, cases)
        kind[0] = MKind.Inner
        val (l, r) = when (env) {
            "pmatrix" -> Pair('(', ')')
            "bmatrix" -> Pair('[', ']')
            "Bmatrix" -> Pair('{', '}')
            "vmatrix" -> Pair('|', '|')
            "Vmatrix" -> Pair('‖', '‖')
            "cases" -> Pair('{', '.')
            else -> return grid
        }
        return DelimBox(l, grid, r, size)
    }

    fun parseCommand(size: Double, kind: Array<MKind>): MathBox? {
        val cmd = readCommand()
        kind[0] = MKind.Ord
        if (cmd.isEmpty()) return null
        when (cmd) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val inner = if (cmd == "tfrac") scriptSize(size) else max(size * 0.9, 6.0)
                val num = parseArgument(inner)
                val den = parseArgument(inner)
                kind[0] = MKind.Inner
                return FracBox(num, den, size, true)
            }
            "binom" -> {
                val num = parseArgument(size * 0.9)
                val den = parseArgument(size * 0.9)
                kind[0] = MKind.Inner
                return DelimBox('(', FracBox(num, den, size, false), ')', size)
            }
            "sqrt" -> {
                var index: MathBox? = null
                skipSpaces()
                if (!atEnd() && s[i] == '[') {
                    i++
                    val start = i
                    var depth = 0
                    while (i < s.length && !(s[i] == ']' && depth == 0)) {
                        if (s[i] == '{') depth++ else if (s[i] == '}') depth--
                        i++
                    }
                    val indexText = s.substring(start, i)
                    if (i < s.length) i++
                    index = MathParser(indexText).parseAll(max(size * 0.5, 6.0))
                }
                val body = parseArgument(size)
                return RadicalBox(body, index, size)
            }
            "left" -> {
                val left = readDelimiter()
                val body = parseRow(size)
                var right: Char? = '.'
                if (lookingAt("\\right")) { i += 6; right = readDelimiter() }
                kind[0] = MKind.Inner
                return DelimBox(left, body, right, size)
            }
            "right" -> return null
            "begin" -> return parseMatrix(readRawGroup(), size, kind)
            "end" -> { readRawGroup(); return null }
            "text", "mathrm", "textrm", "operatorname", "mbox" -> {
                val text = readRawGroup()
                kind[0] = if (cmd == "operatorname") MKind.Op else MKind.Ord
                return glyph(text, size, false, kind[0])
            }
            "mathbf", "textbf", "boldsymbol" -> return glyph(readRawGroup(), size, false, MKind.Ord, true)
            "mathit", "textit" -> return glyph(readRawGroup(), size, true, MKind.Ord)
            "," -> return SpaceBox(size * 3.0 / 18.0)
            ":", ">" -> return SpaceBox(size * 4.0 / 18.0)
            ";" -> return SpaceBox(size * 5.0 / 18.0)
            "!" -> return SpaceBox(-size * 3.0 / 18.0)
            "quad" -> return SpaceBox(size)
            "qquad" -> return SpaceBox(size * 2)
            " " -> return SpaceBox(size * 0.33)
        }
        accents[cmd]?.let { return AccentBox(parseArgument(size), it, size) }
        bigOps[cmd]?.let { (ch, lim) ->
            val b = BigGlyphBox(ch.toString(), MathPaints.font(size * (if (lim) 1.45 else 1.75), false), size)
            b.limits = lim
            kind[0] = MKind.Op
            return b
        }
        if (cmd in limitFunctions) {
            val g = glyph(cmd, size, false, MKind.Op)
            g.limits = true
            kind[0] = MKind.Op
            return g
        }
        if (cmd in functions) {
            kind[0] = MKind.Op
            return glyph(cmd, size, false, MKind.Op)
        }
        symbols[cmd]?.let { (ch, k) ->
            kind[0] = k
            val lowerGreek = ch.code in 0x03B1..0x03C9
            return glyph(ch.toString(), size, lowerGreek, k)
        }
        return glyph("\\" + cmd, size, false, MKind.Ord)
    }
}
