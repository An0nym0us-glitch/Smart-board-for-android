package com.classboard.app.geometry

import android.graphics.Canvas
import android.graphics.Color
import com.classboard.app.core.Geom
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.CoordResolver
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.RenderContext
import com.classboard.app.doc.ResizeHint
import com.classboard.app.doc.ShapeObject
import com.classboard.app.math.CoordinateSystem
import com.classboard.app.render.Draw
import org.json.JSONObject
import kotlin.math.abs

enum class ConstructKind(val key: String) {
    Point("point"), Segment("segment"), Line("line"), Ray("ray"), Vector("vector");

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: Point
    }
}

/** Construction element: points (with coordinates), segments, lines, rays and vectors. */
class GeometryObject : BoardObject(ObjectType.Geometry) {
    var kind = ConstructKind.Point
        private set
    private var pts: List<Pt> = emptyList()
    var colorValue = Color.rgb(129, 212, 250)
    var showLabel = true
    var name = ""

    fun pagePoints() = pts.map { mapToPage(it) }
    fun labelText(cs: CoordinateSystem) = format(kind, pagePoints(), name, cs)

    override fun localBounds(): RectD {
        if (kind == ConstructKind.Line || kind == ConstructKind.Ray) {
            val (a, b) = drawnSegment(kind, pts)
            return RectD.ofPoints(a, b).union(Geom.boundingRect(pts))
        }
        return Geom.boundingRect(pts)
    }

    override fun outlineMargin() = 200.0

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val label = if (showLabel) {
            val pagePts = pagePoints()
            val cs = CoordResolver.resolve(ctx.page, ctx.coordinates, pagePts, this) ?: CoordinateSystem.default()
            format(kind, pagePts, name, cs)
        } else name
        paintShape(canvas, kind, pts, colorValue, label)
    }

    override fun canResize() = false
    override fun canRotate() = false
    override fun controlPoints() = pts

    override fun hitTestLocal(local: Pt, tolerance: Double): Boolean {
        val reach = tolerance + 7.0
        if (pts.any { Geom.distance(local, it) <= reach + 4 }) return true
        if (kind == ConstructKind.Point || pts.size < 2) return false
        val (a, b) = drawnSegment(kind, pts)
        return Geom.distanceToSegment(local, a, b) <= reach
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
        val g = GeometryObject()
        copyBaseTo(g)
        g.kind = kind; g.pts = pts; g.colorValue = colorValue; g.showLabel = showLabel; g.name = name
        return g
    }

    override fun writeProperties(o: JSONObject) {
        o.put("kind", kind.key)
        o.put("pts", Json.fromPoints(pts))
        o.put("color", Json.fromColor(colorValue))
        o.put("label", showLabel)
        if (name.isNotEmpty()) o.put("name", name)
    }

    override fun readProperties(o: JSONObject): Boolean {
        kind = ConstructKind.fromKey(o.optString("kind"))
        pts = Json.toPoints(o.opt("pts"))
        colorValue = Json.toColor(o.opt("color"), colorValue)
        showLabel = o.optBoolean("label", true)
        name = o.optString("name", "")
        return pts.size >= (if (kind == ConstructKind.Point) 1 else 2)
    }

    companion object {
        const val LINE_EXTENT = 2400.0

        fun create(kind: ConstructKind, pagePoints: List<Pt>, color: Int, showLabel: Boolean): GeometryObject {
            val g = GeometryObject()
            g.kind = kind; g.colorValue = color; g.showLabel = showLabel
            val c = Geom.boundingRect(pagePoints).center
            g.position = c
            g.pts = pagePoints.map { it - c }
            return g
        }

        fun drawnSegment(kind: ConstructKind, pts: List<Pt>): Pair<Pt, Pt> {
            var a = pts.getOrElse(0) { Pt.ZERO }
            var b = pts.getOrElse(1) { a }
            val dir = Geom.normalized(b - a)
            if (kind == ConstructKind.Line) { a -= dir * LINE_EXTENT; b += dir * LINE_EXTENT }
            else if (kind == ConstructKind.Ray) b += dir * LINE_EXTENT
            return Pair(a, b)
        }

        fun format(kind: ConstructKind, pts: List<Pt>, name: String, cs: CoordinateSystem): String {
            val prefix = if (name.isEmpty()) "" else "$name "
            fun coords(p: Pt): String { val m = cs.toMath(p); return "(${Geom.formatNumber(m.x, 2)}, ${Geom.formatNumber(m.y, 2)})" }
            return when (kind) {
                ConstructKind.Point -> if (pts.isEmpty()) "" else prefix + coords(pts[0])
                ConstructKind.Segment -> if (pts.size < 2) "" else prefix + cs.formatLength(cs.mathDistance(pts[0], pts[1]), 2)
                ConstructKind.Line, ConstructKind.Ray -> {
                    if (pts.size < 2) return ""
                    val m = cs.mathSlope(pts[0], pts[1]) ?: return prefix + "x = " + Geom.formatNumber(cs.toMath(pts[0]).x, 2)
                    val p0 = cs.toMath(pts[0])
                    val b = p0.y - m * p0.x
                    var eq = "y = " + Geom.formatNumber(m, 2) + "x"
                    if (abs(b) > 1e-9) eq += (if (b > 0) " + " else " − ") + Geom.formatNumber(abs(b), 2)
                    prefix + eq
                }
                ConstructKind.Vector -> {
                    if (pts.size < 2) return ""
                    val d = cs.toMath(pts[1]) - cs.toMath(pts[0])
                    if (cs.usesScale) prefix + "⟨${Geom.formatNumber(cs.toReal(d.x), 2)}, ${Geom.formatNumber(cs.toReal(d.y), 2)}⟩ ${cs.realUnitLabel}  |v| = ${cs.formatLength(d.length(), 2)}"
                    else prefix + "⟨${Geom.formatNumber(d.x, 2)}, ${Geom.formatNumber(d.y, 2)}⟩  |v| = ${Geom.formatNumber(d.length(), 2)}"
                }
            }
        }

        fun paintShape(c: Canvas, kind: ConstructKind, pts: List<Pt>, color: Int, label: String) {
            if (pts.isEmpty()) return
            val pen = Draw.stroke(color, 3.5)
            fun dot(p: Pt, r: Double) {
                Draw.circle(c, p, r, Draw.fill(color))
                Draw.circle(c, p, r, Draw.stroke(Color.rgb(15, 15, 15), 1.5))
            }
            var anchor = pts[0] + Pt(0.0, -34.0)
            when (kind) {
                ConstructKind.Point -> dot(pts[0], 8.0)
                ConstructKind.Segment, ConstructKind.Line, ConstructKind.Ray -> {
                    if (pts.size >= 2) {
                        val (a, b) = drawnSegment(kind, pts)
                        Draw.line(c, a, b, pen)
                        dot(pts[0], 6.5); dot(pts[1], 6.5)
                        anchor = Draw.labelBesideSegment(pts[0], pts[1], label, 12.0, 20.0)
                    }
                }
                ConstructKind.Vector -> {
                    if (pts.size >= 2) {
                        ShapeObject.paintArrow(c, pts[0], pts[1], pen, false, true)
                        dot(pts[0], 5.5)
                        anchor = Draw.labelBesideSegment(pts[0], pts[1], label, 14.0, 20.0)
                    }
                }
            }
            Draw.valueLabel(c, anchor, label, color, 0.0, 20.0)
        }

        fun paintPreview(c: Canvas, kind: ConstructKind, pagePoints: List<Pt>, color: Int, cs: CoordinateSystem, showLabel: Boolean) =
            paintShape(c, kind, pagePoints, color, if (showLabel) format(kind, pagePoints, "", cs) else "")
    }
}
