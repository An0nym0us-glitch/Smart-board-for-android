package com.classboard.app.geometry

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.RectF
import com.classboard.app.core.Geom
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.CoordResolver
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.RenderContext
import com.classboard.app.doc.ResizeHint
import com.classboard.app.math.CoordinateSystem
import com.classboard.app.render.Draw
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

enum class MeasureKind(val key: String) {
    Distance("distance"), Angle("angle"), Slope("slope"), Area("area");

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: Distance
    }
}

/** A live measurement whose value is recomputed from the current points. */
class MeasurementObject : BoardObject(ObjectType.Measurement) {
    var kind = MeasureKind.Distance
        private set
    private var pts: List<Pt> = emptyList()
    var colorValue = Color.rgb(255, 213, 79)

    fun pagePoints() = pts.map { mapToPage(it) }
    fun valueText(cs: CoordinateSystem) = format(kind, pagePoints(), cs)

    override fun localBounds() = Geom.boundingRect(pts)
    override fun outlineMargin() = 170.0

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val pagePts = pagePoints()
        val cs = CoordResolver.resolve(ctx.page, ctx.coordinates, pagePts, this) ?: CoordinateSystem.default()
        paintShape(canvas, kind, pts, colorValue, format(kind, pagePts, cs), rotation)
    }

    override fun canResize() = false
    override fun canRotate() = false
    override fun controlPoints() = pts

    override fun hitTestLocal(local: Pt, tolerance: Double): Boolean {
        val reach = tolerance + 8.0
        val n = pts.size
        for (i in 0 until n) {
            if (Geom.distance(local, pts[i]) <= reach + 4) return true
            if (i + 1 < n && Geom.distanceToSegment(local, pts[i], pts[i + 1]) <= reach) return true
        }
        if (kind == MeasureKind.Area && n >= 3) {
            if (Geom.polygonContains(pts, local)) return true
            if (Geom.distanceToSegment(local, pts.last(), pts.first()) <= reach) return true
        }
        if (kind == MeasureKind.Slope && n >= 2) {
            val corner = Pt(pts[1].x, pts[0].y)
            if (Geom.distanceToSegment(local, pts[0], corner) <= reach || Geom.distanceToSegment(local, corner, pts[1]) <= reach) return true
        }
        return false
    }

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {}

    override fun setControlPoint(index: Int, local: Pt) {
        if (index !in pts.indices) return
        pts = pts.toMutableList().also { it[index] = local }
        recenter()
    }

    override fun translateContent(delta: Pt) { pts = pts.map { it + delta } }
    override fun setColor(color: Int): Boolean { colorValue = color; return true }
    override fun color() = colorValue

    override fun clone(): BoardObject {
        val m = MeasurementObject()
        copyBaseTo(m)
        m.kind = kind; m.pts = pts; m.colorValue = colorValue
        return m
    }

    override fun writeProperties(o: JSONObject) {
        o.put("kind", kind.key)
        o.put("pts", Json.fromPoints(pts))
        o.put("color", Json.fromColor(colorValue))
    }

    override fun readProperties(o: JSONObject): Boolean {
        kind = MeasureKind.fromKey(o.optString("kind"))
        pts = Json.toPoints(o.opt("pts"))
        colorValue = Json.toColor(o.opt("color"), colorValue)
        return pts.size >= 2
    }

    companion object {
        fun create(kind: MeasureKind, pagePoints: List<Pt>, color: Int): MeasurementObject {
            val m = MeasurementObject()
            m.kind = kind; m.colorValue = color
            val c = Geom.boundingRect(pagePoints).center
            m.position = c
            m.pts = pagePoints.map { it - c }
            return m
        }

        fun angleDegrees(pts: List<Pt>, cs: CoordinateSystem): Double {
            if (pts.size < 3) return -1.0
            val v = cs.toMath(pts[1])
            val a = cs.toMath(pts[0]) - v
            val b = cs.toMath(pts[2]) - v
            val la = a.length()
            val lb = b.length()
            if (la < 1e-9 || lb < 1e-9) return -1.0
            return Geom.radToDeg(acos((Geom.dot(a, b) / (la * lb)).coerceIn(-1.0, 1.0)))
        }

        fun perimeter(pts: List<Pt>, cs: CoordinateSystem): Double {
            if (pts.size < 3) return 0.0
            var sum = 0.0
            for (i in pts.indices) sum += cs.mathDistance(pts[i], pts[(i + 1) % pts.size])
            return sum
        }

        fun format(kind: MeasureKind, pts: List<Pt>, cs: CoordinateSystem): String = when (kind) {
            MeasureKind.Distance -> if (pts.size < 2) "" else cs.formatLength(cs.mathDistance(pts[0], pts[1]), 2)
            MeasureKind.Angle -> {
                val a = angleDegrees(pts, cs)
                if (a < 0) "" else Geom.formatNumber(a, 1) + "°"
            }
            MeasureKind.Slope -> if (pts.size < 2) "" else {
                val m = cs.mathSlope(pts[0], pts[1])
                if (m == null) "m undefined" else "m = " + Geom.formatNumber(m, 3)
            }
            MeasureKind.Area -> if (pts.size < 3) "" else "A = " + cs.formatArea(cs.mathArea(pts), 2) + "  ·  P = " + cs.formatLength(perimeter(pts, cs), 2)
        }

        fun paintShape(c: Canvas, kind: MeasureKind, pts: List<Pt>, color: Int, label: String, rotation: Double) {
            val pen = Draw.stroke(color, 3.0)
            fun dot(p: Pt) {
                Draw.circle(c, p, 6.5, Draw.fill(color))
                Draw.circle(c, p, 6.5, Draw.stroke(Color.rgb(20, 20, 20), 1.5))
            }
            val tint = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
            when (kind) {
                MeasureKind.Distance -> {
                    if (pts.size < 2) return
                    val a = pts[0]; val b = pts[1]
                    Draw.line(c, a, b, pen)
                    val n = Geom.perpendicular(Geom.normalized(b - a))
                    Draw.line(c, a - n * 12.0, a + n * 12.0, pen)
                    Draw.line(c, b - n * 12.0, b + n * 12.0, pen)
                    Draw.valueLabel(c, Draw.labelBesideSegment(a, b, label, 16.0), label, color, rotation)
                }
                MeasureKind.Angle -> {
                    if (pts.size < 3) {
                        if (pts.size == 2) Draw.line(c, pts[1], pts[0], pen)
                        return
                    }
                    val v = pts[1]
                    Draw.line(c, v, pts[0], pen)
                    Draw.line(c, v, pts[2], pen)
                    val r = (min(Geom.distance(v, pts[0]), Geom.distance(v, pts[2])) * 0.4).coerceIn(18.0, 70.0)
                    val a0 = Geom.angleDeg(pts[0] - v)
                    val a1 = Geom.angleDeg(pts[2] - v)
                    val sweep = Geom.angleDifference(a0, a1)
                    if (abs(abs(sweep) - 90.0) < 0.5) {
                        val u = Geom.normalized(pts[0] - v) * (r * 0.6)
                        val w = Geom.normalized(pts[2] - v) * (r * 0.6)
                        val path = Path()
                        path.moveTo(v.x.toFloat(), v.y.toFloat())
                        listOf(v + u, v + u + w, v + w).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
                        path.close()
                        c.drawPath(path, Draw.fill(tint))
                        c.drawPath(path, pen)
                    } else {
                        val oval = RectF((v.x - r).toFloat(), (v.y - r).toFloat(), (v.x + r).toFloat(), (v.y + r).toFloat())
                        c.drawArc(oval, a0.toFloat(), sweep.toFloat(), true, Draw.fill(tint))
                        c.drawArc(oval, a0.toFloat(), sweep.toFloat(), true, pen)
                    }
                    val bis = Geom.rotated(Pt(1.0, 0.0), a0 + sweep / 2)
                    Draw.valueLabel(c, v + bis * (r + 34), label, color, rotation)
                    dot(v)
                }
                MeasureKind.Slope -> {
                    if (pts.size < 2) return
                    val a = pts[0]; val b = pts[1]
                    Draw.line(c, a, b, pen)
                    val dash = Draw.stroke(color, 2.0)
                    dash.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
                    val corner = Pt(b.x, a.y)
                    Draw.line(c, a, corner, dash)
                    Draw.line(c, corner, b, dash)
                    Draw.valueLabel(c, Geom.midpoint(a, b) + Pt(0.0, -34.0), label, color, rotation)
                    dot(a); dot(b)
                }
                MeasureKind.Area -> {
                    if (pts.size < 2) return
                    val path = Path()
                    path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
                    for (k in 1 until pts.size) path.lineTo(pts[k].x.toFloat(), pts[k].y.toFloat())
                    if (pts.size >= 3) {
                        path.close()
                        c.drawPath(path, Draw.fill(Color.argb(45, Color.red(color), Color.green(color), Color.blue(color))))
                    }
                    c.drawPath(path, pen)
                    pts.forEach { dot(it) }
                    var cen = Pt.ZERO
                    pts.forEach { cen += it }
                    Draw.valueLabel(c, cen / pts.size.toDouble(), label, color, rotation)
                }
            }
        }

        fun paintPreview(c: Canvas, kind: MeasureKind, pagePoints: List<Pt>, color: Int, cs: CoordinateSystem) =
            paintShape(c, kind, pagePoints, color, format(kind, pagePoints, cs), 0.0)
    }
}
