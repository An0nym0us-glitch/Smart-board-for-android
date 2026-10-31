package com.classboard.app.ai

import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.InkStyle
import com.classboard.app.doc.ShapeKind
import com.classboard.app.doc.ShapeObject
import com.classboard.app.doc.ShapeStyle
import com.classboard.app.doc.StrokeStyle
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Turns roughly drawn lines, circles, ellipses, rectangles and polygons into clean shapes. */
object ShapeRecognizer {
    enum class Kind { None, Line, Circle, Ellipse, Rectangle, Polygon }
    class Result(val kind: Kind = Kind.None, val points: List<Pt> = emptyList(), val box: RectD = RectD.EMPTY, val confidence: Double = 0.0)

    private fun maxDeviation(points: List<Pt>, poly: List<Pt>, closed: Boolean): Double {
        var worst = 0.0
        val m = poly.size
        val edges = if (closed) m else m - 1
        for (p in points) {
            var best = Double.MAX_VALUE
            for (i in 0 until edges) best = min(best, Geom.distanceToSegment(p, poly[i], poly[(i + 1) % m]))
            worst = max(worst, best)
        }
        return worst
    }

    private fun cornerAngle(poly: List<Pt>, i: Int): Double {
        val m = poly.size
        val a = poly[(i + m - 1) % m] - poly[i]
        val b = poly[(i + 1) % m] - poly[i]
        val la = a.length()
        val lb = b.length()
        if (la < 1e-9 || lb < 1e-9) return 180.0
        return Geom.radToDeg(acos((Geom.dot(a, b) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    private fun closedVertices(points: List<Pt>, eps: Double): List<Pt> {
        val pts = points + points.first()
        val v = Geom.simplifyIndices(pts, eps).map { pts[it] }.toMutableList()
        if (v.size > 1 && Geom.distance(v.first(), v.last()) < eps) v.removeAt(v.size - 1)
        var changed = true
        while (changed && v.size > 3) {
            changed = false
            for (i in v.indices) if (cornerAngle(v, i) > 160.0) { v.removeAt(i); changed = true; break }
        }
        return v
    }

    fun classify(points: List<Pt>, pixel: Double): Result {
        if (points.size < 4) return Result()
        val box = Geom.boundingRect(points)
        val diag = hypot(box.width, box.height)
        if (diag < 30.0 * pixel) return Result()
        val length = Geom.polylineLength(points)
        val gap = Geom.distance(points.first(), points.last())
        val closed = gap < max(0.2 * diag, 14.0 * pixel) && length > 1.8 * diag
        if (!closed) {
            if (gap / length > 0.94) {
                val dev = maxDeviation(points, listOf(points.first(), points.last()), false)
                if (dev < 0.06 * gap) {
                    val a = points.first()
                    var b = points.last()
                    val (s, snapped) = Geom.snapAngle(Geom.angleDeg(b - a), 90.0, 4.0)
                    if (snapped) b = a + Geom.rotated(Pt(gap, 0.0), s)
                    return Result(Kind.Line, listOf(a, b), confidence = 1.0 - dev / (0.06 * gap))
                }
            }
            return Result()
        }
        val poly = closedVertices(points, 0.07 * diag)
        val polyErr = if (poly.size >= 3) maxDeviation(points, poly, true) / diag else 1.0
        fun polygonResult(): Result {
            if (poly.size == 4) {
                val right = (0 until 4).all { abs(cornerAngle(poly, it) - 90.0) < 16.0 }
                val axis = (0 until 4).all { Geom.snapAngle(Geom.angleDeg(poly[(it + 1) % 4] - poly[it]), 90.0, 10.0).second }
                if (right && axis) return Result(Kind.Rectangle, box = Geom.boundingRect(poly), confidence = 1.0 - polyErr / 0.06)
            }
            return Result(Kind.Polygon, poly, confidence = 1.0 - polyErr / 0.06)
        }
        if (poly.size in 3..4 && polyErr < 0.06) return polygonResult()
        val rx = box.width / 2
        val ry = box.height / 2
        if (rx > 1e-6 && ry > 1e-6) {
            val c = box.center
            var err = 0.0
            for (p in points) {
                val dx = (p.x - c.x) / rx
                val dy = (p.y - c.y) / ry
                err += abs(sqrt(dx * dx + dy * dy) - 1.0)
            }
            err /= points.size
            if (err < 0.09) {
                return if (abs(rx - ry) / max(rx, ry) < 0.16) {
                    val rr = (rx + ry) / 2
                    Result(Kind.Circle, box = RectD.fromCenter(c, 2 * rr, 2 * rr), confidence = 1.0 - err / 0.09)
                } else Result(Kind.Ellipse, box = box, confidence = 1.0 - err / 0.09)
            }
        }
        if (poly.size in 5..6 && polyErr < 0.05) return polygonResult()
        return Result()
    }

    fun recognize(points: List<Pt>, ink: InkStyle, pixel: Double): BoardObject? {
        val r = classify(points, pixel)
        val style = ShapeStyle(ink.color, ink.width, 0, ink.style == StrokeStyle.Dashed)
        return when (r.kind) {
            Kind.Line -> ShapeObject.createLine(ShapeKind.Line, r.points[0], r.points[1], style)
            Kind.Circle -> ShapeObject.createBox(ShapeKind.Circle, r.box, style)
            Kind.Ellipse -> ShapeObject.createBox(ShapeKind.Ellipse, r.box, style)
            Kind.Rectangle -> ShapeObject.createBox(ShapeKind.Rectangle, r.box, style)
            Kind.Polygon -> ShapeObject.createPolygon(r.points, style)
            Kind.None -> null
        }
    }
}
