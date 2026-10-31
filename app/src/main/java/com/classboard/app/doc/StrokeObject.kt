package com.classboard.app.doc

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.classboard.app.core.Geom
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class StrokeStyle(val key: String) {
    Pen("pen"), Highlighter("highlighter"), Dashed("dashed"), Dotted("dotted");

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: Pen
    }
}

data class StrokePoint(val pos: Pt, val pressure: Float = 1f)

data class InkStyle(
    val color: Int = 0xFFFFFFFF.toInt(),
    val width: Double = 4.0,
    val style: StrokeStyle = StrokeStyle.Pen,
    val pressure: Boolean = false,
)

/** Freehand ink. */
class StrokeObject : BoardObject(ObjectType.Stroke) {
    var points: List<StrokePoint> = emptyList()
        private set
    var ink = InkStyle()
        set(v) { field = v; invalidateCache() }

    private var pathCache: Path? = null
    private var boundsCache: RectD? = null

    fun pagePoints(): List<Pt> = points.map { mapToPage(it.pos) }

    private fun invalidateCache() {
        pathCache = null
        boundsCache = null
        invalidateBounds()
    }

    override fun localBounds(): RectD {
        boundsCache?.let { return it }
        val b = Geom.boundingRect(points.map { it.pos })
        boundsCache = b
        return b
    }

    override fun outlineMargin() = ink.width * 0.5 + 1.0

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        if (points.isEmpty()) return
        val filled = (ink.pressure && ink.style == StrokeStyle.Pen) || points.size == 1
        val path = pathCache ?: buildPath(points, ink).also { pathCache = it }
        canvas.drawPath(path, paintFor(ink, filled))
    }

    override fun hitTestLocal(local: Pt, tolerance: Double): Boolean {
        val reach = tolerance + ink.width * 0.5
        val n = points.size
        if (n == 0) return false
        if (n == 1) return Geom.distance(local, points[0].pos) <= reach
        for (i in 1 until n) if (Geom.distanceToSegment(local, points[i - 1].pos, points[i].pos) <= reach) return true
        return false
    }

    override fun isInsidePolygon(pagePolygon: List<Pt>): Boolean {
        if (points.isEmpty()) return false
        val n = points.size
        val step = max(1, n / 64)
        var inside = 0
        var total = 0
        var i = 0
        while (i < n) {
            total++
            if (Geom.polygonContains(pagePolygon, mapToPage(points[i].pos))) inside++
            i += step
        }
        return inside * 2 > total
    }

    override fun setColor(color: Int): Boolean {
        ink = ink.copy(color = color)
        return true
    }

    override fun color() = ink.color

    override fun clone(): BoardObject {
        val s = StrokeObject()
        copyBaseTo(s)
        s.points = points
        s.ink = ink
        return s
    }

    /** Erases the part covered by a page-space circle; returns remaining fragments, or null if untouched. */
    fun eraseCircle(pageCenter: Pt, radius: Double): List<StrokeObject>? {
        val c = mapFromPage(pageCenter)
        val r = radius + ink.width * 0.5
        val n = points.size
        if (n == 0) return null
        if (n == 1) return if (Geom.distance(points[0].pos, c) <= r) emptyList() else null
        var hit = false
        val fragments = ArrayList<List<StrokePoint>>()
        val current = ArrayList<StrokePoint>()
        fun flush() {
            if (current.size >= 2) {
                var len = 0.0
                for (k in 1 until current.size) len += Geom.distance(current[k - 1].pos, current[k].pos)
                if (len > 0.75) fragments.add(ArrayList(current))
            }
            current.clear()
        }
        fun lerpP(a: StrokePoint, b: StrokePoint, t: Double) =
            StrokePoint(Geom.lerp(a.pos, b.pos, t), (a.pressure + (b.pressure - a.pressure) * t).toFloat())
        if (Geom.distance(points[0].pos, c) > r) current.add(points[0])
        for (i in 0 until n - 1) {
            val a = points[i]
            val b = points[i + 1]
            val iv = Geom.segmentCircleInterval(a.pos, b.pos, c, r)
            if (iv == null) {
                if (current.isEmpty()) current.add(a)
                current.add(b)
                continue
            }
            hit = true
            val (t0, t1) = iv
            if (t0 > 0.0) {
                if (current.isEmpty()) current.add(a)
                current.add(lerpP(a, b, t0))
            }
            flush()
            if (t1 < 1.0) {
                current.add(lerpP(a, b, t1))
                current.add(b)
            }
        }
        flush()
        if (!hit) return null
        return fragments.map { frag -> fromPagePoints(frag.map { StrokePoint(mapToPage(it.pos), it.pressure) }, ink) }
    }

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        val old = localBounds()
        val sx = if (old.width > 0.5) w / old.width else 1.0
        val sy = if (old.height > 0.5) h / old.height else 1.0
        val c = old.center
        points = points.map { p ->
            val d = p.pos - c
            p.copy(pos = c + Pt(d.x * sx, d.y * sy))
        }
        invalidateCache()
    }

    override fun translateContent(delta: Pt) {
        points = points.map { it.copy(pos = it.pos + delta) }
        invalidateCache()
    }

    override fun writeProperties(o: JSONObject) {
        val pts = JSONArray()
        val pr = JSONArray()
        var anyPressure = false
        for (p in points) {
            pts.put(Json.round2(p.pos.x)); pts.put(Json.round2(p.pos.y))
            pr.put(Json.round3(p.pressure.toDouble()))
            if (p.pressure < 0.999f) anyPressure = true
        }
        o.put("pts", pts)
        if (anyPressure) o.put("pr", pr)
        o.put("color", Json.fromColor(ink.color))
        o.put("width", ink.width)
        o.put("style", ink.style.key)
        if (ink.pressure) o.put("pressure", true)
    }

    override fun readProperties(o: JSONObject): Boolean {
        val pts = Json.toPoints(o.opt("pts"))
        val pr = o.optJSONArray("pr")
        points = pts.mapIndexed { i, p ->
            StrokePoint(p, if (pr != null && i < pr.length()) pr.optDouble(i, 1.0).toFloat() else 1f)
        }
        ink = InkStyle(
            color = Json.toColor(o.opt("color")),
            width = max(0.25, Json.double(o, "width", 4.0)),
            style = StrokeStyle.fromKey(o.optString("style")),
            pressure = o.optBoolean("pressure", false),
        )
        invalidateCache()
        return points.isNotEmpty()
    }

    companion object {
        fun fromPagePoints(pagePoints: List<StrokePoint>, ink: InkStyle): StrokeObject {
            val s = StrokeObject()
            s.ink = ink
            if (pagePoints.isEmpty()) return s
            val center = Geom.boundingRect(pagePoints.map { it.pos }).center
            s.position = center
            s.points = pagePoints.map { it.copy(pos = it.pos - center) }
            s.invalidateCache()
            return s
        }

        fun effectiveColor(ink: InkStyle): Int {
            if (ink.style != StrokeStyle.Highlighter) return ink.color
            val a = (ink.color ushr 24) and 0xFF
            val na = min(a, (0.38 * 255).toInt())
            return (na shl 24) or (ink.color and 0xFFFFFF)
        }

        fun paintFor(ink: InkStyle, filled: Boolean): Paint {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = effectiveColor(ink)
            if (filled) {
                p.style = Paint.Style.FILL
            } else {
                p.style = Paint.Style.STROKE
                p.strokeWidth = ink.width.toFloat()
                p.strokeCap = Paint.Cap.ROUND
                p.strokeJoin = Paint.Join.ROUND
                val w = ink.width.toFloat()
                when (ink.style) {
                    StrokeStyle.Dashed -> {
                        p.strokeCap = Paint.Cap.BUTT
                        p.pathEffect = DashPathEffect(floatArrayOf(3f * w, 2f * w), 0f)
                    }
                    StrokeStyle.Dotted -> p.pathEffect = DashPathEffect(floatArrayOf(0.01f * w, 2.2f * w), 0f)
                    else -> {}
                }
            }
            return p
        }

        fun buildPath(pts: List<StrokePoint>, ink: InkStyle): Path {
            if (ink.pressure && ink.style == StrokeStyle.Pen) return pressureOutline(pts, ink.width)
            if (pts.size == 1) {
                val p = Path()
                p.addCircle(pts[0].pos.x.toFloat(), pts[0].pos.y.toFloat(), (ink.width * 0.5).toFloat(), Path.Direction.CW)
                return p
            }
            return centerlinePath(pts)
        }

        /** Renders arbitrary points with an ink style (live previews). */
        fun paintPoints(canvas: Canvas, pts: List<StrokePoint>, ink: InkStyle) {
            if (pts.isEmpty()) return
            val filled = (ink.pressure && ink.style == StrokeStyle.Pen) || pts.size == 1
            canvas.drawPath(buildPath(pts, ink), paintFor(ink, filled))
        }

        fun centerlinePath(pts: List<StrokePoint>): Path {
            val path = Path()
            val n = pts.size
            if (n == 0) return path
            path.moveTo(pts[0].pos.x.toFloat(), pts[0].pos.y.toFloat())
            if (n <= 2) {
                path.lineTo(pts[n - 1].pos.x.toFloat(), pts[n - 1].pos.y.toFloat())
                return path
            }
            for (i in 1 until n - 1) {
                val m = Geom.midpoint(pts[i].pos, pts[i + 1].pos)
                path.quadTo(pts[i].pos.x.toFloat(), pts[i].pos.y.toFloat(), m.x.toFloat(), m.y.toFloat())
            }
            path.lineTo(pts[n - 1].pos.x.toFloat(), pts[n - 1].pos.y.toFloat())
            return path
        }

        fun pressureOutline(pts: List<StrokePoint>, width: Double): Path {
            val path = Path()
            path.fillType = Path.FillType.WINDING
            val n = pts.size
            if (n == 0) return path
            fun hw(i: Int): Double {
                val pr = pts[i].pressure.toDouble().coerceIn(0.0, 1.0)
                return max(0.35, width * 0.5 * (0.18 + 0.82 * pr))
            }
            fun circle(p: Pt, r: Double) = path.addCircle(p.x.toFloat(), p.y.toFloat(), r.toFloat(), Path.Direction.CW)
            if (n == 1) {
                circle(pts[0].pos, hw(0)); return path
            }
            val left = ArrayList<Pt>(n)
            val right = ArrayList<Pt>(n)
            for (i in 0 until n) {
                val prev = pts[max(0, i - 1)].pos
                val next = pts[min(n - 1, i + 1)].pos
                var dir = Geom.normalized(next - prev)
                if (dir.length() < 0.5) dir = Pt(1.0, 0.0)
                val normal = Geom.perpendicular(dir) * hw(i)
                left.add(pts[i].pos + normal)
                right.add(pts[i].pos - normal)
            }
            fun appendSmooth(side: List<Pt>) {
                val m = side.size
                for (k in 1 until m - 1) {
                    val mid = Geom.midpoint(side[k], side[k + 1])
                    path.quadTo(side[k].x.toFloat(), side[k].y.toFloat(), mid.x.toFloat(), mid.y.toFloat())
                }
                path.lineTo(side[m - 1].x.toFloat(), side[m - 1].y.toFloat())
            }
            path.moveTo(left[0].x.toFloat(), left[0].y.toFloat())
            appendSmooth(left)
            path.lineTo(right[n - 1].x.toFloat(), right[n - 1].y.toFloat())
            appendSmooth(right.reversed())
            path.close()
            circle(pts[0].pos, hw(0))
            circle(pts[n - 1].pos, hw(n - 1))
            for (i in 1 until n - 1) {
                val a = pts[i].pos - pts[i - 1].pos
                val b = pts[i + 1].pos - pts[i].pos
                val la = a.length()
                val lb = b.length()
                if (la < 1e-6 || lb < 1e-6) continue
                if (Geom.dot(a, b) / (la * lb) < 0.35) circle(pts[i].pos, hw(i))
            }
            return path
        }
    }
}
