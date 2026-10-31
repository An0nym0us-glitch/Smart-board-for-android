package com.classboard.app.math

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.classboard.app.core.Geom
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.RenderContext
import com.classboard.app.doc.ResizeHint
import com.classboard.app.doc.ShapeObject
import com.classboard.app.render.Draw
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

data class GraphFunction(var expression: String, var color: Int, var visible: Boolean = true)
data class GraphParameter(val name: String, var value: Double = 1.0, var min: Double = -10.0, var max: Double = 10.0)
data class GraphPoint(val pos: Pt, val label: String = "")
data class GraphVector(val from: Pt, val to: Pt)

/** Cartesian graph with axes, grid, functions with parameter sliders, points and vectors. */
class GraphObject : BoardObject(ObjectType.Graph) {
    var w = 720.0
        private set
    var h = 540.0
        private set
    /** xMin, yMin, width, height (math units). */
    var window = RectD.xywh(-10.0, -7.5, 20.0, 15.0)
        private set
    var showGrid = true
        set(v) { field = v }
    val functions = ArrayList<GraphFunction>()
    val parameters = ArrayList<GraphParameter>()
    val points = ArrayList<GraphPoint>()
    val vectors = ArrayList<GraphVector>()

    private var compiled: List<Expression>? = null
    private var errors: List<String> = emptyList()
    private var curves: List<List<List<Pt>>>? = null

    private fun invalidateCurves() { curves = null }
    private fun invalidateCompiled() { compiled = null; curves = null }

    private fun compiledList(): List<Expression> {
        compiled?.let { return it }
        val list = ArrayList<Expression>()
        val errs = ArrayList<String>()
        for (f in functions) {
            val body = Expression.stripDefinition(f.expression).second
            if (body.isBlank()) { list.add(Expression.compile("")); errs.add("") } else {
                val (e, err) = Expression.compileWithError(body)
                list.add(e); errs.add(err)
            }
        }
        compiled = list
        errors = errs
        return list
    }

    fun functionError(i: Int): String {
        if (i !in functions.indices) return ""
        compiledList()
        return errors.getOrElse(i) { "" }
    }

    fun addFunction(expression: String, color: Int? = null): Int {
        functions.add(GraphFunction(expression, color ?: defaultColor(functions.size)))
        invalidateCompiled(); syncParameters()
        return functions.size - 1
    }

    fun setFunctionExpression(i: Int, e: String) {
        if (i !in functions.indices) return
        functions[i].expression = e
        invalidateCompiled(); syncParameters()
    }

    fun setFunctionVisible(i: Int, v: Boolean) {
        if (i !in functions.indices) return
        functions[i].visible = v; invalidateCurves()
    }

    fun setFunctionColor(i: Int, c: Int) {
        if (i !in functions.indices) return
        functions[i].color = c; invalidateCurves()
    }

    fun removeFunction(i: Int) {
        if (i !in functions.indices) return
        functions.removeAt(i)
        invalidateCompiled(); syncParameters()
    }

    private fun slotValues(e: Expression, x: Double): DoubleArray {
        val v = DoubleArray(e.variables.size) { 1.0 }
        for ((i, name) in e.variables.withIndex()) {
            if (name == "x") { v[i] = x; continue }
            parameters.firstOrNull { it.name == name }?.let { v[i] = it.value }
        }
        return v
    }

    fun evaluate(i: Int, x: Double): Double {
        if (i !in functions.indices) return Double.NaN
        val e = compiledList()[i]
        if (!e.isValid) return Double.NaN
        return e.evaluate(slotValues(e, x))
    }

    fun setParameterValue(name: String, value: Double) {
        parameters.filter { it.name == name }.forEach { it.value = value.coerceIn(it.min, it.max) }
        invalidateCurves()
    }

    fun setParameterRange(name: String, min: Double, max: Double) {
        if (max <= min) return
        parameters.filter { it.name == name }.forEach { it.min = min; it.max = max; it.value = it.value.coerceIn(min, max) }
        invalidateCurves()
    }

    fun syncParameters() {
        val used = LinkedHashSet<String>()
        for (e in compiledList()) for (v in e.variables) if (v != "x") used.add(v)
        val names = used.sorted()
        val next = names.map { n -> parameters.firstOrNull { it.name == n } ?: GraphParameter(n) }
        parameters.clear(); parameters.addAll(next)
        invalidateCurves()
    }

    fun addPoint(m: Pt, label: String = "") { points.add(GraphPoint(m, label)) }
    fun removePoint(i: Int) { if (i in points.indices) points.removeAt(i) }
    fun pointAt(local: Pt, tol: Double): Int {
        for (i in points.indices.reversed()) if (Geom.distance(mathToLocal(points[i].pos), local) <= tol) return i
        return -1
    }
    fun addVector(from: Pt, to: Pt) { vectors.add(GraphVector(from, to)) }
    fun clearPointsAndVectors() { points.clear(); vectors.clear() }

    fun setWindow(r: RectD) {
        if (r.width < 1e-6 || r.height < 1e-6 || r.width > 1e7 || r.height > 1e7) return
        window = r
        invalidateCurves()
    }

    fun panLocal(d: Pt) {
        val dx = -d.x / w * window.width
        val dy = d.y / h * window.height
        setWindow(window.translated(Pt(dx, dy)))
    }

    fun zoomAtLocal(l: Pt, factor: Double) {
        if (factor <= 0) return
        val a = localToMath(l)
        val nw = window.width / factor
        val nh = window.height / factor
        val fx = (a.x - window.left) / window.width
        val fy = (a.y - window.top) / window.height
        setWindow(RectD.xywh(a.x - fx * nw, a.y - fy * nh, nw, nh))
    }

    fun resetView() {
        val ww = 20.0
        val hh = ww * h / w
        setWindow(RectD.xywh(-ww / 2, -hh / 2, ww, hh))
    }

    fun localToMath(l: Pt) = Pt(window.left + (l.x + w / 2) / w * window.width, window.top + (h / 2 - l.y) / h * window.height)
    fun mathToLocal(m: Pt) = Pt((m.x - window.left) / window.width * w - w / 2, h / 2 - (m.y - window.top) / window.height * h)

    override fun mathCoordinateSystem(): CoordinateSystem {
        // page -> local (inverse of object transform), then local -> math.
        val r = Geom.degToRad(-rotation)
        val c = kotlin.math.cos(r)
        val s = kotlin.math.sin(r)
        val toLocal = Affine.translate(-position.x, -position.y).then(Affine(c, s, -s, c, 0.0, 0.0))
        val l2m = Affine(window.width / w, 0.0, 0.0, -window.height / h, window.left + window.width / 2, window.top + window.height / 2)
        return CoordinateSystem.fromTransform(toLocal.then(l2m), "")
    }

    override fun localBounds() = RectD(-w / 2, -h / 2, w / 2, h / 2)
    override fun outlineMargin() = 2.0
    override fun hitTestLocal(local: Pt, tolerance: Double) = localBounds().inflated(tolerance).contains(local)
    override fun canRotate() = false
    override fun isEditable() = true

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        val c = window.center
        val nw = window.width * w / this.w
        val nh = window.height * h / this.h
        this.w = max(120.0, w); this.h = max(90.0, h)
        window = RectD.xywh(c.x - nw / 2, c.y - nh / 2, nw, nh)
        invalidateCurves()
    }

    private fun rebuildCurves(): List<List<List<Pt>>> {
        val exprs = compiledList()
        val out = ArrayList<List<List<Pt>>>()
        val n = max(240, (w * 1.5).toInt())
        for ((i, f) in functions.withIndex()) {
            val res = ArrayList<List<Pt>>()
            out.add(res)
            if (!f.visible) continue
            val e = exprs[i]
            if (!e.isValid) continue
            val values = slotValues(e, 0.0)
            val xSlot = e.slotOf("x")
            var current = ArrayList<Pt>()
            var prevY = 0.0
            fun flush() { if (current.size >= 2) res.add(current); current = ArrayList() }
            for (k in 0..n) {
                val x = window.left + window.width * k / n
                if (xSlot >= 0) values[xSlot] = x
                val y = e.evaluate(values)
                if (!y.isFinite()) { flush(); continue }
                var l = mathToLocal(Pt(x, y))
                if (abs(l.y) > 20 * h) { flush(); continue }
                if (current.isNotEmpty() && abs(l.y - prevY) > 1.5 * h) flush()
                l = Pt(l.x, l.y.coerceIn(-3 * h, 3 * h))
                current.add(l)
                prevY = l.y
            }
            flush()
        }
        curves = out
        return out
    }

    private fun paintGridAndAxes(c: Canvas, zoom: Double) {
        val r = localBounds()
        val step = Geom.niceNumber(window.width / w * 48.0, true)
        val yStep = Geom.niceNumber(window.height / h * 48.0, true)
        val origin = mathToLocal(Pt(0.0, 0.0))
        val xAxisVisible = origin.y >= r.top && origin.y <= r.bottom
        val yAxisVisible = origin.x >= r.left && origin.x <= r.right
        val labelY = if (xAxisVisible) origin.y else r.bottom - 18
        val labelX = if (yAxisVisible) origin.x else r.left + 4
        val grid = Draw.stroke(GRID, 1.0 / zoom)
        val gridMajor = Draw.stroke(GRID_MAJOR, 1.0 / zoom)
        val lc = Draw.text(LABEL, 14.0, align = Paint.Align.CENTER)
        val lr = Draw.text(LABEL, 14.0, align = Paint.Align.RIGHT)
        var x = ceil(window.left / step) * step
        while (x <= window.right) {
            val lx = mathToLocal(Pt(x, 0.0)).x
            val major = abs(round(x / step) % 5.0) < 0.5
            if (showGrid) c.drawLine(lx.toFloat(), r.top.toFloat(), lx.toFloat(), r.bottom.toFloat(), if (major) gridMajor else grid)
            if (abs(x) > step * 0.5) c.drawText(Geom.formatNumber(x, 3), lx.toFloat(), (labelY + 17).toFloat(), lc)
            x += step
        }
        var y = ceil(window.top / yStep) * yStep
        while (y <= window.bottom) {
            val ly = mathToLocal(Pt(0.0, y)).y
            val major = abs(round(y / yStep) % 5.0) < 0.5
            if (showGrid) c.drawLine(r.left.toFloat(), ly.toFloat(), r.right.toFloat(), ly.toFloat(), if (major) gridMajor else grid)
            if (abs(y) > yStep * 0.5) Draw.textCentered(c, Geom.formatNumber(y, 3), labelX - 4, ly, lr)
            y += yStep
        }
        val axis = Draw.stroke(AXIS, 2.0)
        val at = Draw.text(AXIS, 14.0, align = Paint.Align.CENTER)
        if (xAxisVisible) {
            c.drawLine(r.left.toFloat(), origin.y.toFloat(), r.right.toFloat(), origin.y.toFloat(), axis)
            ShapeObject.paintArrow(c, Pt(r.right - 20, origin.y), Pt(r.right - 2, origin.y), axis, false, true)
            Draw.textCentered(c, "x", r.right - 16, origin.y - 16, at)
        }
        if (yAxisVisible) {
            c.drawLine(origin.x.toFloat(), r.top.toFloat(), origin.x.toFloat(), r.bottom.toFloat(), axis)
            ShapeObject.paintArrow(c, Pt(origin.x, r.top + 20), Pt(origin.x, r.top + 2), axis, false, true)
            Draw.textCentered(c, "y", origin.x + 16, r.top + 12, at)
        }
        if (xAxisVisible && yAxisVisible) c.drawText("0", (origin.x - 5).toFloat(), (origin.y + 16).toFloat(), lr)
    }

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val r = localBounds()
        val rf = r.toRectF()
        canvas.drawRoundRect(rf, 10f, 10f, Draw.fill(PANEL))
        canvas.drawRoundRect(rf, 10f, 10f, Draw.stroke(BORDER, 1.5))
        canvas.save()
        canvas.clipRect(RectF(rf.left + 1, rf.top + 1, rf.right - 1, rf.bottom - 1))
        paintGridAndAxes(canvas, ctx.zoom)
        val cv = curves ?: rebuildCurves()
        for (i in functions.indices) {
            if (i >= cv.size) break
            val pen = Draw.stroke(functions[i].color, 3.5)
            for (poly in cv[i]) {
                val path = Path()
                path.moveTo(poly[0].x.toFloat(), poly[0].y.toFloat())
                for (k in 1 until poly.size) path.lineTo(poly[k].x.toFloat(), poly[k].y.toFloat())
                canvas.drawPath(path, pen)
            }
        }
        for (v in vectors) {
            val pen = Draw.stroke(Color.rgb(255, 213, 79), 3.0)
            val a = mathToLocal(v.from)
            val b = mathToLocal(v.to)
            ShapeObject.paintArrow(canvas, a, b, pen, false, true)
            val d = v.to - v.from
            Draw.valueLabel(canvas, Geom.midpoint(a, b) + Pt(0.0, -22.0), "⟨${Geom.formatNumber(d.x, 2)}, ${Geom.formatNumber(d.y, 2)}⟩", Color.rgb(255, 224, 130), 0.0, 16.0)
        }
        for (pt in points) {
            val l = mathToLocal(pt.pos)
            Draw.circle(canvas, l, 6.5, Draw.fill(Color.WHITE))
            Draw.circle(canvas, l, 6.5, Draw.stroke(Color.rgb(15, 15, 15), 1.5))
            val coords = "(${Geom.formatNumber(pt.pos.x, 2)}, ${Geom.formatNumber(pt.pos.y, 2)})"
            Draw.valueLabel(canvas, l + Pt(0.0, -24.0), if (pt.label.isEmpty()) coords else "${pt.label} $coords", Color.WHITE, 0.0, 15.0)
        }
        // Legend
        val lp = Draw.text(Color.WHITE, 17.0, true)
        val fm = lp.fontMetrics
        val fh = (fm.descent - fm.ascent).toDouble()
        var y = r.top + 10
        val names = "fghpqrs"
        for ((i, f) in functions.withIndex()) {
            if (f.expression.isBlank()) continue
            val (defName, body) = Expression.stripDefinition(f.expression)
            val fname = if (i < 7) names[i] else 'f'
            val text = (if (defName.isEmpty() || defName == "y") fname.toString() else defName) + "(x) = " + body.trim()
            val error = functionError(i).isNotEmpty()
            val tw = lp.measureText(text) + 36
            canvas.drawRoundRect(RectF((r.left + 8).toFloat(), y.toFloat(), (r.left + 8 + tw).toFloat(), (y + fh + 8).toFloat()), 6f, 6f, Draw.fill(Color.argb(200, 10, 14, 12)))
            Draw.circle(canvas, Pt(r.left + 22, y + (fh + 8) / 2), 6.0, Draw.fill(f.color))
            lp.color = if (error) Color.rgb(239, 106, 100) else if (f.visible) Draw.lighter(f.color, 1.15) else LABEL
            Draw.textCentered(canvas, text, r.left + 34, y + (fh + 8) / 2, lp)
            y += fh + 12
        }
        if (parameters.isNotEmpty()) {
            val text = parameters.joinToString("   ") { "${it.name} = ${Geom.formatNumber(it.value, 2)}" }
            val tw = lp.measureText(text) + 20
            canvas.drawRoundRect(RectF((r.left + 8).toFloat(), y.toFloat(), (r.left + 8 + tw).toFloat(), (y + fh + 8).toFloat()), 6f, 6f, Draw.fill(Color.argb(200, 10, 14, 12)))
            lp.color = Color.rgb(255, 224, 130)
            Draw.textCentered(canvas, text, r.left + 18, y + (fh + 8) / 2, lp)
        }
        canvas.restore()
    }

    override fun clone(): BoardObject {
        val g = GraphObject()
        copyBaseTo(g)
        g.w = w; g.h = h; g.window = window; g.showGrid = showGrid
        functions.forEach { g.functions.add(it.copy()) }
        parameters.forEach { g.parameters.add(it.copy()) }
        g.points.addAll(points)
        g.vectors.addAll(vectors)
        return g
    }

    override fun writeProperties(o: JSONObject) {
        o.put("size", Json.fromSize(w, h))
        o.put("window", JSONArray().put(window.left).put(window.top).put(window.width).put(window.height))
        o.put("grid", showGrid)
        val fa = JSONArray()
        for (f in functions) fa.put(JSONObject().put("expr", f.expression).put("color", Json.fromColor(f.color)).put("visible", f.visible))
        o.put("functions", fa)
        val pa = JSONArray()
        for (p in parameters) pa.put(JSONObject().put("name", p.name).put("value", p.value).put("min", p.min).put("max", p.max))
        o.put("parameters", pa)
        val pts = JSONArray()
        for (p in points) pts.put(JSONObject().put("x", p.pos.x).put("y", p.pos.y).put("label", p.label))
        o.put("points", pts)
        val va = JSONArray()
        for (v in vectors) va.put(JSONArray().put(v.from.x).put(v.from.y).put(v.to.x).put(v.to.y))
        o.put("vectors", va)
    }

    override fun readProperties(o: JSONObject): Boolean {
        val (sw, sh) = Json.toSize(o.opt("size"), 720.0, 540.0)
        w = sw; h = sh
        val wa = o.optJSONArray("window")
        if (wa != null && wa.length() == 4) window = RectD.xywh(wa.optDouble(0), wa.optDouble(1), wa.optDouble(2), wa.optDouble(3))
        if (window.width <= 0 || window.height <= 0) window = RectD.xywh(-10.0, -7.5, 20.0, 15.0)
        showGrid = o.optBoolean("grid", true)
        functions.clear()
        o.optJSONArray("functions")?.let { a ->
            for (k in 0 until a.length()) {
                val f = a.optJSONObject(k) ?: continue
                functions.add(GraphFunction(f.optString("expr", ""), Json.toColor(f.opt("color"), defaultColor(functions.size)), f.optBoolean("visible", true)))
            }
        }
        parameters.clear()
        o.optJSONArray("parameters")?.let { a ->
            for (k in 0 until a.length()) {
                val p = a.optJSONObject(k) ?: continue
                val name = p.optString("name", "")
                if (name.isNotEmpty()) parameters.add(GraphParameter(name, p.optDouble("value", 1.0), p.optDouble("min", -10.0), p.optDouble("max", 10.0)))
            }
        }
        points.clear()
        o.optJSONArray("points")?.let { a ->
            for (k in 0 until a.length()) {
                val p = a.optJSONObject(k) ?: continue
                points.add(GraphPoint(Pt(p.optDouble("x", 0.0), p.optDouble("y", 0.0)), p.optString("label", "")))
            }
        }
        vectors.clear()
        o.optJSONArray("vectors")?.let { a ->
            for (k in 0 until a.length()) {
                val v = a.optJSONArray(k) ?: continue
                if (v.length() == 4) vectors.add(GraphVector(Pt(v.optDouble(0), v.optDouble(1)), Pt(v.optDouble(2), v.optDouble(3))))
            }
        }
        invalidateCompiled()
        return true
    }

    companion object {
        private val PANEL = Color.argb(248, 14, 19, 17)
        private val BORDER = Color.argb(70, 255, 255, 255)
        private val GRID = Color.argb(26, 255, 255, 255)
        private val GRID_MAJOR = Color.argb(48, 255, 255, 255)
        private val AXIS = Color.argb(210, 235, 240, 235)
        private val LABEL = Color.argb(230, 220, 226, 222)

        private val COLORS = intArrayOf(Color.rgb(79, 195, 247), Color.rgb(255, 183, 77), Color.rgb(129, 199, 132), Color.rgb(240, 98, 146), Color.rgb(186, 104, 200), Color.rgb(255, 241, 118))
        fun defaultColor(i: Int) = COLORS[abs(i) % COLORS.size]

        fun create(center: Pt, w: Double, h: Double): GraphObject {
            val g = GraphObject()
            g.w = w; g.h = h
            g.position = center
            g.resetView()
            return g
        }
    }
}
