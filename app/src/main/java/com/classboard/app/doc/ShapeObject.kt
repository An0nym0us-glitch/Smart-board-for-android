package com.classboard.app.doc

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.classboard.app.core.Geom
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ShapeKind(val key: String, val label: String) {
    Line("line", "Line"),
    Arrow("arrow", "Arrow"),
    DoubleArrow("double-arrow", "Double arrow"),
    Rectangle("rectangle", "Rectangle"),
    RoundedRect("rounded-rectangle", "Rounded rectangle"),
    Ellipse("ellipse", "Ellipse"),
    Circle("circle", "Circle"),
    Triangle("triangle", "Triangle"),
    RightTriangle("right-triangle", "Right triangle"),
    Diamond("diamond", "Diamond"),
    Parallelogram("parallelogram", "Parallelogram"),
    Hexagon("hexagon", "Hexagon"),
    RegularPolygon("polygon", "Polygon"),
    FreePolygon("free-polygon", "Free polygon");

    val isLine get() = this == Line || this == Arrow || this == DoubleArrow

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: Rectangle
    }
}

data class ShapeStyle(
    val stroke: Int = 0xFFFFFFFF.toInt(),
    val width: Double = 4.0,
    val fill: Int = 0,
    val dashed: Boolean = false,
)

fun alphaOf(c: Int) = (c ushr 24) and 0xFF
fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

/** Vector shape. Box shapes resize/rotate; lines and free polygons are edited via control points. */
class ShapeObject : BoardObject(ObjectType.Shape) {
    var kind = ShapeKind.Rectangle
        private set
    var style = ShapeStyle()
    var sides = 5
        private set
    var w = 100.0
        private set
    var h = 100.0
        private set
    private var p1 = Pt(-50.0, 0.0)
    private var p2 = Pt(50.0, 0.0)
    private var pts: List<Pt> = emptyList()

    /** Closed outline polylines in local coordinates (lines: one open polyline). */
    fun outlinePolys(): List<List<Pt>> = when {
        kind == ShapeKind.FreePolygon -> if (pts.isEmpty()) emptyList() else listOf(pts + pts.first())
        kind.isLine -> listOf(listOf(p1, p2))
        else -> outlinePolysFor(kind, w, h, sides)
    }

    fun outlinePath(): Path = when {
        kind == ShapeKind.FreePolygon -> polyPath(pts, true)
        kind.isLine -> polyPath(listOf(p1, p2), false)
        else -> outlinePathFor(kind, w, h, sides)
    }

    override fun localBounds(): RectD = when {
        kind.isLine -> RectD.ofPoints(p1, p2)
        kind == ShapeKind.FreePolygon -> Geom.boundingRect(pts)
        else -> RectD(-w / 2, -h / 2, w / 2, h / 2)
    }

    override fun outlineMargin(): Double {
        val head = if (kind.isLine && kind != ShapeKind.Line) max(14.0, style.width * 3.5) else 0.0
        return style.width / 2 + head + 2.0
    }

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val pen = strokePaint(style)
        if (kind.isLine) {
            paintArrow(canvas, p1, p2, pen, kind == ShapeKind.DoubleArrow, kind == ShapeKind.Arrow || kind == ShapeKind.DoubleArrow)
        } else {
            val path = outlinePath()
            if (alphaOf(style.fill) > 0) {
                val f = Paint(Paint.ANTI_ALIAS_FLAG)
                f.style = Paint.Style.FILL
                f.color = style.fill
                canvas.drawPath(path, f)
            }
            canvas.drawPath(path, pen)
        }
    }

    override fun hitTestLocal(local: Pt, tolerance: Double): Boolean {
        val reach = tolerance + style.width / 2
        if (kind.isLine) return Geom.distanceToSegment(local, p1, p2) <= reach + 2
        val polys = outlinePolys()
        if (alphaOf(style.fill) > 0 && polys.any { Geom.polygonContains(it, local) }) return true
        for (poly in polys) for (i in 1 until poly.size)
            if (Geom.distanceToSegment(local, poly[i - 1], poly[i]) <= reach + 1) return true
        return false
    }

    override fun enclosesPoint(pagePos: Pt): Boolean {
        if (kind.isLine) return false
        val l = mapFromPage(pagePos)
        return outlinePolys().any { Geom.polygonContains(it, l) }
    }

    override fun canResize() = !kind.isLine
    override fun canRotate() = !kind.isLine
    override fun keepAspectRatio() = kind == ShapeKind.Circle

    override fun controlPoints(): List<Pt> = when {
        kind.isLine -> listOf(p1, p2)
        kind == ShapeKind.FreePolygon -> pts
        else -> emptyList()
    }

    /** Line end points in page coordinates (line shapes). */
    fun pageLine(): Pair<Pt, Pt> = Pair(mapToPage(p1), mapToPage(p2))

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        if (kind == ShapeKind.FreePolygon || kind.isLine) {
            val old = localBounds()
            val sx = if (old.width > 0.5) w / old.width else 1.0
            val sy = if (old.height > 0.5) h / old.height else 1.0
            val c = old.center
            fun sc(p: Pt) = Pt(c.x + (p.x - c.x) * sx, c.y + (p.y - c.y) * sy)
            pts = pts.map { sc(it) }
            p1 = sc(p1); p2 = sc(p2)
            return
        }
        this.w = w; this.h = h
        if (kind == ShapeKind.Circle) {
            val d = max(w, h)
            this.w = d; this.h = d
        }
    }

    /** Sets the box size directly (precision panel). */
    fun setBoxSize(nw: Double, nh: Double) {
        if (kind.isLine || kind == ShapeKind.FreePolygon) {
            resizeTo(RectD.fromCenter(localBounds().center, nw, nh))
        } else {
            w = max(1.0, nw); h = max(1.0, nh)
            if (kind == ShapeKind.Circle) { h = w }
            invalidateBounds()
        }
    }

    override fun setControlPoint(index: Int, local: Pt) {
        if (kind.isLine) {
            if (index == 0) p1 = local else if (index == 1) p2 = local
        } else if (kind == ShapeKind.FreePolygon && index in pts.indices) {
            pts = pts.toMutableList().also { it[index] = local }
        }
        recenter()
    }

    override fun translateContent(delta: Pt) {
        p1 += delta; p2 += delta
        pts = pts.map { it + delta }
    }

    override fun setColor(color: Int): Boolean {
        style = style.copy(stroke = color, fill = if (alphaOf(style.fill) > 0) withAlpha(color, alphaOf(style.fill)) else style.fill)
        return true
    }

    override fun color() = style.stroke

    override fun clone(): BoardObject {
        val s = ShapeObject()
        copyBaseTo(s)
        s.kind = kind; s.style = style; s.sides = sides; s.w = w; s.h = h
        s.p1 = p1; s.p2 = p2; s.pts = pts
        return s
    }

    /** The shape as straight ink strokes (page coordinates) so the area eraser can rub out part of it. */
    fun toInk(): List<BoardObject> {
        val out = ArrayList<BoardObject>()
        val ink = InkStyle(style.stroke, style.width, if (style.dashed) StrokeStyle.Dashed else StrokeStyle.Pen, false)
        fun addStroke(poly: List<Pt>) {
            if (poly.size >= 2) out.add(StrokeObject.fromPagePoints(densified(poly, 2.0), ink))
        }
        if (kind.isLine) {
            val g = arrowGeometry(p1, p2, style.width, kind == ShapeKind.DoubleArrow, kind == ShapeKind.Arrow || kind == ShapeKind.DoubleArrow)
            addStroke(listOf(mapToPage(g.first.first), mapToPage(g.first.second)))
            val headStyle = style.copy(dashed = false, fill = style.stroke)
            for (head in g.second) out.add(createPolygon(head.map { mapToPage(it) }, headStyle))
            return out
        }
        if (alphaOf(style.stroke) == 0 || style.width <= 0) return out
        for (poly in outlinePolys()) addStroke(poly.map { mapToPage(it) })
        return out
    }

    override fun writeProperties(o: JSONObject) {
        o.put("kind", kind.key)
        o.put("stroke", Json.fromColor(style.stroke))
        o.put("width", style.width)
        if (alphaOf(style.fill) > 0) o.put("fill", Json.fromColor(style.fill))
        if (style.dashed) o.put("dashed", true)
        when {
            kind.isLine -> { o.put("p1", Json.fromPoint(p1)); o.put("p2", Json.fromPoint(p2)) }
            kind == ShapeKind.FreePolygon -> o.put("pts", Json.fromPoints(pts))
            else -> {
                o.put("size", Json.fromSize(w, h))
                if (kind == ShapeKind.RegularPolygon) o.put("sides", sides)
            }
        }
    }

    override fun readProperties(o: JSONObject): Boolean {
        kind = ShapeKind.fromKey(o.optString("kind"))
        style = ShapeStyle(
            stroke = Json.toColor(o.opt("stroke")),
            width = max(0.5, Json.double(o, "width", 4.0)),
            fill = Json.toColor(o.opt("fill"), 0),
            dashed = o.optBoolean("dashed", false),
        )
        p1 = Json.toPoint(o.opt("p1"), Pt(-50.0, 0.0))
        p2 = Json.toPoint(o.opt("p2"), Pt(50.0, 0.0))
        pts = Json.toPoints(o.opt("pts"))
        val (sw, sh) = Json.toSize(o.opt("size"), 100.0, 100.0)
        w = sw; h = sh
        sides = o.optInt("sides", 5).coerceIn(3, 24)
        if (kind == ShapeKind.FreePolygon && pts.size < 3) return false
        return true
    }

    companion object {
        fun createBox(kind: ShapeKind, pageRect: RectD, style: ShapeStyle, sides: Int = 5): ShapeObject {
            val s = ShapeObject()
            s.kind = kind; s.style = style; s.sides = sides.coerceIn(3, 24)
            val r = pageRect.normalized()
            s.w = max(2.0, r.width); s.h = max(2.0, r.height)
            if (kind == ShapeKind.Circle) { val d = max(s.w, s.h); s.w = d; s.h = d }
            s.position = r.center
            return s
        }

        fun createLine(kind: ShapeKind, a: Pt, b: Pt, style: ShapeStyle): ShapeObject {
            val s = ShapeObject()
            s.kind = kind; s.style = style
            val c = Geom.midpoint(a, b)
            s.position = c
            s.p1 = a - c; s.p2 = b - c
            return s
        }

        fun createPolygon(pagePoints: List<Pt>, style: ShapeStyle): ShapeObject {
            val s = ShapeObject()
            s.kind = ShapeKind.FreePolygon; s.style = style
            val c = Geom.boundingRect(pagePoints).center
            s.position = c
            s.pts = pagePoints.map { it - c }
            return s
        }

        fun strokePaint(style: ShapeStyle): Paint {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE
            p.color = style.stroke
            p.strokeWidth = style.width.toFloat()
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            if (style.dashed) {
                val w = style.width.toFloat()
                p.pathEffect = DashPathEffect(floatArrayOf(4f * w, 2f * w), 0f)
                p.strokeCap = Paint.Cap.BUTT
            }
            return p
        }

        fun polyPath(pts: List<Pt>, closed: Boolean): Path {
            val path = Path()
            if (pts.isEmpty()) return path
            path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) path.lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            if (closed) path.close()
            return path
        }

        private fun ellipsePoints(w: Double, h: Double, n: Int = 72): List<Pt> =
            (0 until n).map { i ->
                val a = 2 * Math.PI * i / n
                Pt(cos(a) * w / 2, sin(a) * h / 2)
            }

        private fun roundedRectPoints(w: Double, h: Double): List<Pt> {
            val r = min(w, h) * 0.18
            val out = ArrayList<Pt>()
            val corners = listOf(
                Triple(w / 2 - r, -h / 2 + r, -90.0), Triple(w / 2 - r, h / 2 - r, 0.0),
                Triple(-w / 2 + r, h / 2 - r, 90.0), Triple(-w / 2 + r, -h / 2 + r, 180.0),
            )
            for ((cx, cy, start) in corners) for (k in 0..8) {
                val a = Geom.degToRad(start + 90.0 * k / 8)
                out.add(Pt(cx + cos(a) * r, cy + sin(a) * r))
            }
            return out
        }

        /** Vertices of polygonal box shapes (null for curved shapes). */
        fun polygonVertices(kind: ShapeKind, w: Double, h: Double, sides: Int): List<Pt>? {
            val l = -w / 2; val t = -h / 2; val r = w / 2; val b = h / 2
            return when (kind) {
                ShapeKind.Rectangle -> listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))
                ShapeKind.Triangle -> listOf(Pt(0.0, t), Pt(r, b), Pt(l, b))
                ShapeKind.RightTriangle -> listOf(Pt(l, t), Pt(r, b), Pt(l, b))
                ShapeKind.Diamond -> listOf(Pt(0.0, t), Pt(r, 0.0), Pt(0.0, b), Pt(l, 0.0))
                ShapeKind.Parallelogram -> { val sk = w * 0.22; listOf(Pt(l + sk, t), Pt(r, t), Pt(r - sk, b), Pt(l, b)) }
                ShapeKind.Hexagon, ShapeKind.RegularPolygon -> {
                    val n = if (kind == ShapeKind.Hexagon) 6 else sides.coerceIn(3, 24)
                    val start = if (kind == ShapeKind.Hexagon) 0.0 else -90.0
                    (0 until n).map { i ->
                        val a = Geom.degToRad(start + 360.0 * i / n)
                        Pt(cos(a) * w / 2, sin(a) * h / 2)
                    }
                }
                ShapeKind.FreePolygon -> listOf(Pt(l, t + h * 0.3), Pt(l + w * 0.6, t), Pt(r, b), Pt(l + w * 0.2, b))
                else -> null
            }
        }

        fun outlinePolysFor(kind: ShapeKind, w: Double, h: Double, sides: Int): List<List<Pt>> {
            if (kind.isLine) return listOf(listOf(Pt(-w / 2, 0.0), Pt(w / 2, 0.0)))
            val pts = when (kind) {
                ShapeKind.Ellipse, ShapeKind.Circle -> ellipsePoints(w, h)
                ShapeKind.RoundedRect -> roundedRectPoints(w, h)
                else -> polygonVertices(kind, w, h, sides) ?: emptyList()
            }
            if (pts.isEmpty()) return emptyList()
            return listOf(pts + pts.first())
        }

        fun outlinePathFor(kind: ShapeKind, w: Double, h: Double, sides: Int): Path {
            val path = Path()
            val rect = android.graphics.RectF((-w / 2).toFloat(), (-h / 2).toFloat(), (w / 2).toFloat(), (h / 2).toFloat())
            when (kind) {
                ShapeKind.Ellipse, ShapeKind.Circle -> path.addOval(rect, Path.Direction.CW)
                ShapeKind.RoundedRect -> {
                    val r = (min(w, h) * 0.18).toFloat()
                    path.addRoundRect(rect, r, r, Path.Direction.CW)
                }
                ShapeKind.Line, ShapeKind.Arrow, ShapeKind.DoubleArrow -> {
                    path.moveTo(rect.left, 0f); path.lineTo(rect.right, 0f)
                }
                else -> return polyPath(polygonVertices(kind, w, h, sides) ?: emptyList(), true)
            }
            return path
        }

        /** Shaft (start,end) and arrowhead triangles. */
        fun arrowGeometry(a: Pt, b: Pt, penWidth: Double, startHead: Boolean, endHead: Boolean): Pair<Pair<Pt, Pt>, List<List<Pt>>> {
            val len = Geom.distance(a, b)
            val head = min(len * 0.3, max(8.0, penWidth * 3.5))
            val dir = if (len > 1e-6) (b - a) / len else Pt(1.0, 0.0)
            val n = Geom.perpendicular(dir)
            var from = a
            var to = b
            if (startHead) from = a + dir * (head * 0.8)
            if (endHead) to = b - dir * (head * 0.8)
            val heads = ArrayList<List<Pt>>()
            fun makeHead(tip: Pt, d: Pt) {
                heads.add(listOf(tip, tip - d * head + n * (head * 0.5), tip - d * head - n * (head * 0.5)))
            }
            if (endHead) makeHead(b, dir)
            if (startHead) makeHead(a, -dir)
            return Pair(Pair(from, to), heads)
        }

        fun paintArrow(canvas: Canvas, a: Pt, b: Pt, pen: Paint, startHead: Boolean, endHead: Boolean) {
            val (shaft, heads) = arrowGeometry(a, b, pen.strokeWidth.toDouble(), startHead, endHead)
            canvas.drawLine(shaft.first.x.toFloat(), shaft.first.y.toFloat(), shaft.second.x.toFloat(), shaft.second.y.toFloat(), pen)
            if (heads.isEmpty()) return
            val hp = Paint(Paint.ANTI_ALIAS_FLAG)
            hp.color = pen.color
            hp.style = Paint.Style.FILL_AND_STROKE
            hp.strokeWidth = pen.strokeWidth
            hp.strokeJoin = Paint.Join.ROUND
            for (tri in heads) canvas.drawPath(polyPath(tri, true), hp)
        }

        fun densified(poly: List<Pt>, step: Double): List<StrokePoint> {
            val out = ArrayList<StrokePoint>()
            for (i in poly.indices) {
                if (i > 0) {
                    val a = poly[i - 1]; val b = poly[i]
                    val n = max(1, ceil(Geom.distance(a, b) / step).toInt())
                    for (k in 1 until n) out.add(StrokePoint(Geom.lerp(a, b, k.toDouble() / n)))
                }
                out.add(StrokePoint(poly[i]))
            }
            return out
        }
    }
}
