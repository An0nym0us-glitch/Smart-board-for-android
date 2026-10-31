package com.classboard.app.geometry

import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.CoordResolver
import com.classboard.app.doc.Document
import com.classboard.app.doc.Page
import com.classboard.app.doc.ShapeKind
import com.classboard.app.doc.ShapeObject
import com.classboard.app.math.CoordinateSystem
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Exact values of lines, vectors, angles, slopes and shapes. */
object Precision {
    private fun fromPolar(len: Double, deg: Double): Pt { val r = Geom.degToRad(deg); return Pt(len * cos(r), len * sin(r)) }

    fun length(p: List<Pt>, cs: CoordinateSystem) = if (p.size >= 2) cs.mathDistance(p[0], p[1]) else 0.0
    fun direction(p: List<Pt>, cs: CoordinateSystem) = if (p.size >= 2) cs.mathAngle(p[0], p[1]) else 0.0

    fun withPolar(p: List<Pt>, len: Double, deg: Double, cs: CoordinateSystem): List<Pt> {
        if (p.size < 2 || !(len > 0) || !len.isFinite() || !deg.isFinite()) return p
        return p.toMutableList().also { it[1] = cs.toPage(cs.toMath(p[0]) + fromPolar(len, deg)) }
    }

    fun withLength(p: List<Pt>, len: Double, cs: CoordinateSystem) = withPolar(p, len, direction(p, cs), cs)
    fun withDirection(p: List<Pt>, deg: Double, cs: CoordinateSystem): List<Pt> {
        val len = length(p, cs)
        return withPolar(p, if (len > 1e-9) len else 1.0, deg, cs)
    }

    fun angle(p: List<Pt>, cs: CoordinateSystem) = MeasurementObject.angleDegrees(p, cs)

    fun withAngle(p: List<Pt>, deg: Double, cs: CoordinateSystem): List<Pt> {
        if (p.size < 3 || !deg.isFinite()) return p
        val v = cs.toMath(p[1])
        val a = cs.toMath(p[0]) - v
        val b = cs.toMath(p[2]) - v
        val la = a.length()
        var lb = b.length()
        if (la < 1e-9) return p
        if (lb < 1e-9) lb = la
        val cross = a.x * b.y - a.y * b.x
        val sign = if (cross < -1e-12) -1.0 else 1.0
        val dirA = Geom.radToDeg(atan2(a.y, a.x))
        return p.toMutableList().also { it[2] = cs.toPage(v + fromPolar(lb, dirA + sign * deg)) }
    }

    class SlopeValues(val rise: Double, val run: Double, val slope: Double, val vertical: Boolean, val angle: Double)

    fun slopeValues(p: List<Pt>, cs: CoordinateSystem): SlopeValues {
        if (p.size < 2) return SlopeValues(0.0, 0.0, 0.0, false, 0.0)
        val d = cs.toMath(p[1]) - cs.toMath(p[0])
        val vertical = abs(d.x) < 1e-9
        val slope = if (vertical) 0.0 else d.y / d.x
        return SlopeValues(d.y, d.x, slope, vertical, if (vertical) 90.0 else Geom.radToDeg(atan(slope)))
    }

    fun withRiseRun(p: List<Pt>, rise: Double, run: Double, cs: CoordinateSystem): List<Pt> {
        if (p.size < 2 || !rise.isFinite() || !run.isFinite() || (abs(rise) < 1e-12 && abs(run) < 1e-12)) return p
        return p.toMutableList().also { it[1] = cs.toPage(cs.toMath(p[0]) + Pt(run, rise)) }
    }

    fun withSlope(p: List<Pt>, slope: Double, cs: CoordinateSystem): List<Pt> {
        val s = slopeValues(p, cs)
        var run = s.run
        if (abs(run) < 1e-9) run = max(1.0, abs(s.rise))
        return withRiseRun(p, slope * run, run, cs)
    }

    fun withInclination(p: List<Pt>, deg0: Double, cs: CoordinateSystem): List<Pt> {
        if (p.size < 2 || !deg0.isFinite()) return p
        val deg = deg0.coerceIn(-90.0, 90.0)
        val s = slopeValues(p, cs)
        var len = length(p, cs)
        if (len < 1e-9) len = 1.0
        val leftward = if (s.run < -1e-9) -1.0 else 1.0
        val r = Geom.degToRad(deg)
        return withRiseRun(p, len * sin(r) * leftward, len * cos(r) * leftward, cs)
    }

    class ShapeMetrics(val width: Double, val height: Double, val area: Double, val perimeter: Double)

    fun shapeMetrics(o: BoardObject, cs: CoordinateSystem): ShapeMetrics? {
        val ppu = cs.pxPerUnit()
        if (ppu <= 0) return null
        if (o is MeasurementObject) {
            if (o.kind != MeasureKind.Area) return null
            val pts = o.pagePoints()
            val b = Geom.boundingRect(pts)
            return ShapeMetrics(b.width / ppu, b.height / ppu, cs.mathArea(pts), MeasurementObject.perimeter(pts, cs))
        }
        if (o !is ShapeObject || o.kind.isLine) return null
        val poly: List<Pt>
        val w: Double
        val h: Double
        if (o.kind == ShapeKind.FreePolygon) {
            poly = o.controlPoints().map { o.mapToPage(it) }
            val b = Geom.boundingRect(poly)
            w = b.width / ppu; h = b.height / ppu
        } else {
            w = o.w / ppu; h = o.h / ppu
            if (o.kind == ShapeKind.Ellipse || o.kind == ShapeKind.Circle) {
                val a = w / 2; val b = h / 2
                val hh = ((a - b) * (a - b)) / ((a + b) * (a + b))
                return ShapeMetrics(w, h, Math.PI * a * b, Math.PI * (a + b) * (1 + 3 * hh / (10 + sqrt(4 - 3 * hh))))
            }
            val outline = ShapeObject.outlinePolysFor(o.kind, o.w, o.h, o.sides).firstOrNull() ?: return null
            poly = outline.dropLast(1).map { o.mapToPage(it) }
        }
        if (poly.size < 3) return null
        var per = 0.0
        for (i in poly.indices) per += cs.mathDistance(poly[i], poly[(i + 1) % poly.size])
        return ShapeMetrics(w, h, cs.mathArea(poly), per)
    }

    fun definingPoints(o: BoardObject): List<Pt> = when (o) {
        is MeasurementObject -> o.pagePoints()
        is GeometryObject -> o.pagePoints()
        is ShapeObject -> if (o.kind.isLine) o.controlPoints().map { o.mapToPage(it) } else emptyList()
        else -> emptyList()
    }

    fun applyPoints(doc: Document, page: Page, id: String, newPts: List<Pt>, text: String): Boolean {
        val o = page.objectById(id) ?: return false
        val cur = definingPoints(o)
        if (cur.size != newPts.size || cur.isEmpty()) return false
        if (newPts.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        var changed = false
        for (i in newPts.indices) {
            if (Geom.distance(cur[i], newPts[i]) < 1e-9) continue
            o.moveControlPointTo(i, newPts[i])
            changed = true
        }
        if (!changed) return false
        doc.commit(page, text, listOf(id))
        return true
    }

    fun applyShapeSize(doc: Document, page: Page, id: String, mw: Double, mh: Double, cs: CoordinateSystem, text: String): Boolean {
        val o = page.objectById(id) as? ShapeObject ?: return false
        if (!o.canResize()) return false
        val ppu = cs.pxPerUnit()
        if (!(mw > 0) || !(mh > 0) || ppu <= 0 || !mw.isFinite() || !mh.isFinite()) return false
        val local = o.localBounds()
        val target = RectD.fromCenter(local.center, mw * ppu, mh * ppu)
        if (abs(target.width - local.width) < 1e-9 && abs(target.height - local.height) < 1e-9) return false
        o.resizeTo(target)
        doc.commit(page, text, listOf(id))
        return true
    }

    fun coordinateSystemFor(doc: Document, page: Page, o: BoardObject): CoordinateSystem {
        val global = doc.coordinatesFor(page)
        var pts = definingPoints(o)
        if (pts.isEmpty()) pts = listOf(o.sceneBounds().center)
        return CoordResolver.resolve(page, global, pts, o) ?: global
    }
}
