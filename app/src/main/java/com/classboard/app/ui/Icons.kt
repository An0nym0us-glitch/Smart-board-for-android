package com.classboard.app.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class IconElement(val d: String, val fill: Boolean, val stroke: Boolean, val width: Float, val dashed: Boolean)

/** Draws the ClassBoard line icons (24 x 24 design grid) in any colour and size. */
object Icons {
    private class Parsed(val path: Path, val fill: Boolean, val stroke: Boolean, val width: Float, val dashed: Boolean)

    private val cache = HashMap<String, List<Parsed>>()

    fun has(name: String) = IconData.icons.containsKey(name)

    private fun parsed(name: String): List<Parsed> = cache.getOrPut(name) {
        (IconData.icons[name] ?: emptyList()).map { Parsed(SvgPath.parse(it.d), it.fill, it.stroke, it.width, it.dashed) }
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Draws icon [name] centred at (cx, cy) with the given size in pixels. */
    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val els = parsed(name)
        if (els.isEmpty()) return
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(s, s)
        strokePaint.color = color
        fillPaint.color = color
        for (e in els) {
            if (e.fill) c.drawPath(e.path, fillPaint)
            if (e.stroke) {
                strokePaint.strokeWidth = e.width
                strokePaint.pathEffect = if (e.dashed) DashPathEffect(floatArrayOf(2.6f, 2.2f), 0f) else null
                c.drawPath(e.path, strokePaint)
            }
        }
        c.restore()
    }
}

/** Minimal SVG path-data parser (M L H V C S Q T A Z, absolute and relative). */
object SvgPath {
    fun parse(d: String): Path {
        val path = Path()
        val tokens = tokenize(d)
        var i = 0
        var cmd = 'M'
        var x = 0f; var y = 0f
        var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f // last control point
        var lastCmd = ' '
        fun num(): Float = (tokens[i++] as Float)
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Char) { cmd = t; i++ } else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var nx = num(); var ny = num()
                    if (rel) { nx += x; ny += y }
                    x = nx; y = ny; sx = x; sy = y
                    path.moveTo(x, y)
                }
                'L' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }; x = nx; y = ny; path.lineTo(x, y) }
                'H' -> { var nx = num(); if (rel) nx += x; x = nx; path.lineTo(x, y) }
                'V' -> { var ny = num(); if (rel) ny += y; y = ny; path.lineTo(x, y) }
                'C' -> {
                    var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var nx = num(); var ny = num()
                    if (rel) { x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y }
                    path.cubicTo(x1, y1, x2, y2, nx, ny)
                    lcx = x2; lcy = y2; x = nx; y = ny
                }
                'S' -> {
                    var x2 = num(); var y2 = num(); var nx = num(); var ny = num()
                    if (rel) { x2 += x; y2 += y; nx += x; ny += y }
                    val reflect = lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S'
                    val x1 = if (reflect) 2 * x - lcx else x
                    val y1 = if (reflect) 2 * y - lcy else y
                    path.cubicTo(x1, y1, x2, y2, nx, ny)
                    lcx = x2; lcy = y2; x = nx; y = ny
                }
                'Q' -> {
                    var x1 = num(); var y1 = num(); var nx = num(); var ny = num()
                    if (rel) { x1 += x; y1 += y; nx += x; ny += y }
                    path.quadTo(x1, y1, nx, ny)
                    lcx = x1; lcy = y1; x = nx; y = ny
                }
                'T' -> {
                    var nx = num(); var ny = num()
                    if (rel) { nx += x; ny += y }
                    val reflect = lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T'
                    val x1 = if (reflect) 2 * x - lcx else x
                    val y1 = if (reflect) 2 * y - lcy else y
                    path.quadTo(x1, y1, nx, ny)
                    lcx = x1; lcy = y1; x = nx; y = ny
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    var nx = num(); var ny = num()
                    if (rel) { nx += x; ny += y }
                    arcTo(path, x, y, nx, ny, rx, ry, rot, large, sweep)
                    x = nx; y = ny
                }
                'Z' -> { path.close(); x = sx; y = sy }
            }
            lastCmd = cmd
        }
        return path
    }

    private fun tokenize(d: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() && c != 'e' && c != 'E' -> { out.add(c); i++ }
                c == '-' || c == '.' || c.isDigit() || c == '+' -> {
                    var j = i + 1
                    var seenDot = c == '.'
                    while (j < d.length) {
                        val ch = d[j]
                        if (ch.isDigit()) j++
                        else if (ch == '.' && !seenDot) { seenDot = true; j++ }
                        else if ((ch == 'e' || ch == 'E') && j + 1 < d.length) { j += if (d[j + 1] == '-' || d[j + 1] == '+') 2 else 1 }
                        else break
                    }
                    out.add(d.substring(i, j).toFloat())
                    i = j
                }
                else -> i++
            }
        }
        // Arc flags may be written without separators ("a1 1 0 0 0-1-1" is fine, "0 01" is not used here).
        return out
    }

    /** SVG elliptical arc converted to cubic Béziers. */
    private fun arcTo(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, rx0: Float, ry0: Float, angle: Float, large: Boolean, sweep: Boolean) {
        if (rx0 == 0f || ry0 == 0f) { path.lineTo(x1, y1); return }
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        val phi = Math.toRadians(angle.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x0 - x1) / 2.0; val dy = (y0 - y1) / 2.0
        val x1p = cp * dx + sp * dy
        val y1p = -sp * dx + cp * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x0 + x1) / 2.0
        val cy = sp * cxp + cp * cyp + (y0 + y1) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val theta1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dtheta = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dtheta > 0) dtheta -= 2 * Math.PI else if (sweep && dtheta < 0) dtheta += 2 * Math.PI
        val segs = ceil(abs(dtheta) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val delta = dtheta / segs
        val t = 4.0 / 3.0 * tan(delta / 4)
        var th = theta1
        for (k in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th)
            val c2 = cos(th + delta); val s2 = sin(th + delta)
            val ex1 = c1 - t * s1; val ey1 = s1 + t * c1
            val ex2 = c2 + t * s2; val ey2 = s2 - t * c2
            fun mx(ex: Double, ey: Double) = (cp * rx * ex - sp * ry * ey + cx).toFloat()
            fun my(ex: Double, ey: Double) = (sp * rx * ex + cp * ry * ey + cy).toFloat()
            path.cubicTo(mx(ex1, ey1), my(ex1, ey1), mx(ex2, ey2), my(ex2, ey2), mx(c2, s2), my(c2, s2))
            th += delta
        }
    }
}
