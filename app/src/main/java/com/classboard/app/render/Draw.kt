package com.classboard.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.classboard.app.core.Pt
import kotlin.math.abs
import kotlin.math.hypot

/** Small drawing helpers shared by objects, tools and instruments. */
object Draw {
    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color; it.style = Paint.Style.FILL }

    fun stroke(color: Int, width: Double, cap: Paint.Cap = Paint.Cap.ROUND) = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = color
        it.style = Paint.Style.STROKE
        it.strokeWidth = width.toFloat()
        it.strokeCap = cap
        it.strokeJoin = Paint.Join.ROUND
    }

    fun text(color: Int, px: Double, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).also {
            it.color = color
            it.textSize = px.toFloat()
            it.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            it.textAlign = align
        }

    /** Draws text vertically centred on (x, cy). */
    fun textCentered(c: Canvas, s: String, x: Double, cy: Double, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(s, x.toFloat(), (cy - (fm.ascent + fm.descent) / 2).toFloat(), p)
    }

    fun line(c: Canvas, a: Pt, b: Pt, p: Paint) = c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), p)
    fun circle(c: Canvas, p0: Pt, r: Double, p: Paint) = c.drawCircle(p0.x.toFloat(), p0.y.toFloat(), r.toFloat(), p)

    fun valueLabelSize(text: String, px: Double = 22.0): Pair<Double, Double> {
        val p = text(Color.WHITE, px, true)
        val fm = p.fontMetrics
        return Pair(p.measureText(text) + px * 0.8, (fm.descent - fm.ascent) + px * 0.3)
    }

    /** Anchor beside segment a-b (upper side preferred) so the label clears the line. */
    fun labelBesideSegment(a: Pt, b: Pt, text: String, gap: Double, px: Double = 22.0): Pt {
        val mid = (a + b) / 2.0
        val d = b - a
        val len = hypot(d.x, d.y)
        var n = if (len > 1e-9) Pt(-d.y / len, d.x / len) else Pt(0.0, -1.0)
        if (n.y > 0 || (abs(n.y) < 1e-9 && n.x > 0)) n = -n
        val (w, h) = valueLabelSize(text, px)
        val extent = abs(n.x) * w / 2 + abs(n.y) * h / 2
        return mid + n * (gap + extent)
    }

    /** Rounded dark plate with coloured bold text centred at anchor. */
    fun valueLabel(c: Canvas, anchor: Pt, text: String, color: Int, counterRotation: Double = 0.0, px: Double = 22.0) {
        if (text.isEmpty()) return
        c.save()
        c.translate(anchor.x.toFloat(), anchor.y.toFloat())
        if (counterRotation != 0.0) c.rotate(-counterRotation.toFloat())
        val tp = text(color, px, true, Paint.Align.CENTER)
        val fm = tp.fontMetrics
        val w = tp.measureText(text) + px * 0.8
        val h = (fm.descent - fm.ascent) + px * 0.3
        val r = RectF((-w / 2).toFloat(), (-h / 2).toFloat(), (w / 2).toFloat(), (h / 2).toFloat())
        c.drawRoundRect(r, (h * 0.3).toFloat(), (h * 0.3).toFloat(), fill(Color.argb(190, 12, 16, 14)))
        textCentered(c, text, 0.0, 0.0, tp)
        c.restore()
    }

    fun lighter(c: Int, factor: Double): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = (hsv[2] * factor).toFloat().coerceIn(0f, 1f)
        if (hsv[2] >= 1f) hsv[1] = (hsv[1] / factor).toFloat()
        return Color.HSVToColor(Color.alpha(c), hsv)
    }
}
