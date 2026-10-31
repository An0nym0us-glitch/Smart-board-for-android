package com.classboard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.ShapeKind
import com.classboard.app.doc.ShapeObject
import com.classboard.app.geometry.ConstructKind
import com.classboard.app.geometry.GeometryObject
import com.classboard.app.geometry.MeasureKind
import com.classboard.app.geometry.MeasurementObject
import com.classboard.app.geometry.Precision

/** Label, − / + steppers, numeric field and unit. Calls onEdit when a value is committed. */
@SuppressLint("ViewConstructor")
class NumberField(ctx: Context, t: Theme, label: String, unit: String, value: Double, step: Double, decimals: Int, readOnly: Boolean = false, onEdit: (Double) -> Unit) : LinearLayout(ctx) {
    private val field = InputField(ctx, t, numeric = true)
    var value = value
        private set

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val lbl = bodyText(ctx, t, label)
        addView(lbl, LayoutParams(t.dp(120.0).toInt(), WRAP))
        fun set(v: Double, commit: Boolean) {
            this.value = v
            field.setText(Geom.formatNumber(v, decimals).replace('−', '-'))
            if (commit) onEdit(v)
        }
        val bs = t.dp(46.0).toInt()
        if (!readOnly) {
            val minus = TouchButton(ctx, t, "", "−", TouchButton.Style.Pill)
            minus.setOnClickListener { set(this.value - step, true) }
            addView(minus, LayoutParams(bs, bs))
        }
        field.isEnabled = !readOnly
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                field.text.toString().replace(',', '.').replace('−', '-').toDoubleOrNull()?.let { set(it, true) }
                true
            } else false
        }
        addView(field, LayoutParams(0, WRAP, 1f))
        if (!readOnly) {
            val plus = TouchButton(ctx, t, "", "+", TouchButton.Style.Pill)
            plus.setOnClickListener { set(this.value + step, true) }
            addView(plus, LayoutParams(bs, bs))
        }
        if (unit.isNotEmpty()) addView(bodyText(ctx, t, unit, muted = true).also { it.setPadding(t.dp(6.0).toInt(), 0, 0, 0) })
        set(value, false)
    }
}

/** Exact values of lines, vectors, angles, slopes, areas, points and shapes. */
object PropertiesPanel {
    private enum class Figure { None, Line, Vector, Angle, Slope, Area, Box, Point }

    private fun figureOf(o: BoardObject): Figure = when (o) {
        is MeasurementObject -> when (o.kind) {
            MeasureKind.Distance -> Figure.Line
            MeasureKind.Angle -> Figure.Angle
            MeasureKind.Slope -> Figure.Slope
            MeasureKind.Area -> Figure.Area
        }
        is GeometryObject -> when (o.kind) {
            ConstructKind.Point -> Figure.Point
            ConstructKind.Vector -> Figure.Vector
            else -> Figure.Line
        }
        is ShapeObject -> if (o.kind.isLine) Figure.Line else Figure.Box
        else -> Figure.None
    }

    fun supports(o: BoardObject) = figureOf(o) != Figure.None

    private fun titleFor(o: BoardObject) = when (figureOf(o)) {
        Figure.Line -> "Line"
        Figure.Vector -> "Vector"
        Figure.Angle -> "Angle"
        Figure.Slope -> "Slope"
        Figure.Area -> "Area"
        Figure.Box -> (o as ShapeObject).kind.label
        Figure.Point -> "Point"
        Figure.None -> "Properties"
    }

    private val QUICK = listOf(0.0, 30.0, 45.0, 60.0, 90.0, 120.0, 135.0, 180.0)

    fun create(app: App): PopoverHost.PopoverSpec {
        val k = app.kit
        val l = k.layout()
        val page = app.doc.currentPage
        val id = app.propertiesTarget
        val o = id?.let { page.objectById(it) }
        if (o == null || !supports(o)) {
            l.addView(k.hint("Select a line, vector, angle, slope, area, point or shape."))
            return PopoverHost.PopoverSpec("Properties", l, 460.0)
        }
        val figure = figureOf(o)
        val cs = Precision.coordinateSystemFor(app.doc, page, o)
        val graphUnits = cs.unitLabel.isEmpty()
        val boardUnit = if (graphUnits) "units" else cs.unitLabel
        val scaled = cs.usesScale
        val realUnit = cs.realUnitLabel
        val pts = Precision.definingPoints(o)
        fun commit(newPts: List<Pt>, text: String) {
            Precision.applyPoints(app.doc, app.doc.currentPage, o.id, newPts, text)
            app.popovers.refresh()
        }
        fun field(label: String, unit: String, value: Double, step: Double, decimals: Int, readOnly: Boolean = false, onEdit: (Double) -> Unit = {}) =
            NumberField(app.activity, app.t, label, unit, value, step, decimals, readOnly, onEdit).also { l.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        fun anglePills(apply: (Double) -> Unit) = l.addView(k.choices(QUICK.map { "${Geom.formatNumber(it, 0)}°" }, -1, 4) { apply(QUICK[it]) })
        fun lengthFields(label: String) {
            val len = Precision.length(pts, cs)
            field(if (scaled) "$label (board)" else label, boardUnit, len, 0.5, 3) { v -> if (v > 0) commit(Precision.withLength(pts, v, cs), "Set length") }
            if (scaled) field("$label (real)", realUnit, cs.toReal(len), 0.1, 4) { v -> if (v > 0) commit(Precision.withLength(pts, cs.fromReal(v), cs), "Set length") }
        }
        fun directionField() = field("Direction", "°", Precision.direction(pts, cs), 15.0, 3) { v -> commit(Precision.withDirection(pts, v, cs), "Set direction") }
        val unitOrBoard = realUnit.ifEmpty { boardUnit }
        when (figure) {
            Figure.Line -> {
                lengthFields("Length")
                directionField()
                anglePills { a -> commit(Precision.withDirection(pts, a, cs), "Set direction") }
            }
            Figure.Vector -> {
                val len = Precision.length(pts, cs)
                field("Magnitude", unitOrBoard, cs.toReal(len), if (scaled) 0.1 else 0.5, 4) { v -> if (v > 0) commit(Precision.withLength(pts, cs.fromReal(v), cs), "Set magnitude") }
                directionField()
                anglePills { a -> commit(Precision.withDirection(pts, a, cs), "Set direction") }
                val d = cs.toMath(pts[1]) - cs.toMath(pts[0])
                field("Δx", unitOrBoard, cs.toReal(d.x), 1.0, 4, readOnly = true)
                field("Δy", unitOrBoard, cs.toReal(d.y), 1.0, 4, readOnly = true)
                if (scaled) field("Board length", boardUnit, len, 1.0, 3, readOnly = true)
            }
            Figure.Angle -> {
                field("Angle", "°", maxOf(0.0, Precision.angle(pts, cs)), 1.0, 3) { v -> commit(Precision.withAngle(pts, v.coerceIn(0.0, 360.0), cs), "Set angle") }
                anglePills { a -> commit(Precision.withAngle(pts, a, cs), "Set angle") }
            }
            Figure.Slope -> {
                val v = Precision.slopeValues(pts, cs)
                field("Rise", unitOrBoard, cs.toReal(v.rise), if (scaled) 0.1 else 0.5, 4) { r -> commit(Precision.withRiseRun(pts, cs.fromReal(r), v.run, cs), "Set rise") }
                field("Run", unitOrBoard, cs.toReal(v.run), if (scaled) 0.1 else 0.5, 4) { r -> commit(Precision.withRiseRun(pts, v.rise, cs.fromReal(r), cs), "Set run") }
                field("Slope", "", v.slope, 0.25, 4, readOnly = v.vertical) { m -> commit(Precision.withSlope(pts, m, cs), "Set slope") }
                if (v.vertical) l.addView(k.hint("The line is vertical: the slope is undefined."))
                field("Angle", "°", v.angle, 5.0, 3) { a -> commit(Precision.withInclination(pts, a, cs), "Set slope angle") }
                l.addView(k.hint("Rise and run are measured from the first point; the dashed triangle shows them."))
            }
            Figure.Area, Figure.Box -> {
                val m = Precision.shapeMetrics(o, cs)
                if (m == null) l.addView(k.hint("This shape has no measurable area.")) else {
                    val resizable = figure == Figure.Box && o.canResize()
                    val circle = o is ShapeObject && o.kind == ShapeKind.Circle
                    fun sizeField(label: String, value: Double, widthField: Boolean) {
                        fun apply(bv: Double) {
                            val w = if (circle || widthField) bv else m.width
                            val h = if (circle || !widthField) bv else m.height
                            Precision.applyShapeSize(app.doc, app.doc.currentPage, o.id, w, h, cs, "Set size")
                            app.popovers.refresh()
                        }
                        field(if (scaled) "$label (board)" else label, boardUnit, value, 0.5, 3, readOnly = !resizable) { apply(it) }
                        if (scaled) field("$label (real)", realUnit, cs.toReal(value), 0.1, 4, readOnly = !resizable) { apply(cs.fromReal(it)) }
                    }
                    if (circle) sizeField("Diameter", m.width, true) else { sizeField("Width", m.width, true); sizeField("Height", m.height, false) }
                    field("Area", "$unitOrBoard²", cs.toRealArea(m.area), 1.0, 4, readOnly = true)
                    field("Perimeter", unitOrBoard, cs.toReal(m.perimeter), 1.0, 4, readOnly = true)
                    if (figure == Figure.Area) l.addView(k.hint("Drag the corner points with SELECT to change the area."))
                }
            }
            Figure.Point -> {
                val m = cs.toMath(pts[0])
                field("x", "", m.x, 0.5, 4) { v -> commit(listOf(cs.toPage(Pt(v, m.y))), "Move point") }
                field("y", "", m.y, 0.5, 4) { v -> commit(listOf(cs.toPage(Pt(m.x, v))), "Move point") }
            }
            Figure.None -> {}
        }
        if (graphUnits) l.addView(k.hint("Inside a graph: values use the graph's axes."))
        else if (figure != Figure.Angle && figure != Figure.Point) {
            l.addView(k.row(k.hint(if (scaled) "Scale ${cs.scale.text()}" else "No scale (1 : 1)"), k.pill("scale", "Change scale") { app.popovers.push("scale") }))
        }
        l.addView(k.hint("Dragging still works; values here stay in sync."))
        return PopoverHost.PopoverSpec(titleFor(o), l, 460.0)
    }

    @Suppress("unused")
    private fun unused(v: View) = v
}
