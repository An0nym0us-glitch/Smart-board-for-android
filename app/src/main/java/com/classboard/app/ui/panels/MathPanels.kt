package com.classboard.app.ui.panels

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.ObjectType
import com.classboard.app.geometry.ConstructKind
import com.classboard.app.geometry.Instrument
import com.classboard.app.geometry.MeasureKind
import com.classboard.app.math.EquationObject
import com.classboard.app.math.Expression
import com.classboard.app.math.GraphObject
import com.classboard.app.math.TableObject
import com.classboard.app.tools.ToolId
import com.classboard.app.ui.App
import com.classboard.app.ui.InputField
import com.classboard.app.ui.MATCH
import com.classboard.app.ui.Tile
import com.classboard.app.ui.TileGrid
import com.classboard.app.ui.TouchButton
import com.classboard.app.ui.WRAP
import com.classboard.app.ui.hbox
import kotlin.math.abs
import kotlin.math.floor

object MathPanels {
    private fun selectedOf(app: App, type: ObjectType) =
        app.board.selection.ids.singleOrNull()?.let { app.doc.currentPage.objectById(it) }?.takeIf { it.type == type }

    fun geometry(app: App, full: Boolean): View {
        val k = app.kit
        val l = k.layout()
        val tools = app.tools
        if (full) {
            l.addView(k.section("Instruments"))
            val layer = app.board.instruments
            fun instrument(kind: Instrument.Kind): () -> Unit = { app.board.showInstrument(kind); app.activateTool(ToolId.Pen); app.popovers.closeAll() }
            l.addView(k.tiles(4, listOf(
                Tile("ruler", "Ruler", layer.isShown(Instrument.Kind.Ruler), action = instrument(Instrument.Kind.Ruler)),
                Tile("protractor", "Protractor", layer.isShown(Instrument.Kind.Protractor), action = instrument(Instrument.Kind.Protractor)),
                Tile("set-square", "Set square", layer.isShown(Instrument.Kind.SetSquare), action = instrument(Instrument.Kind.SetSquare)),
                Tile("compass", "Compass", layer.isShown(Instrument.Kind.Compass), action = instrument(Instrument.Kind.Compass)),
            )))
        }
        l.addView(k.section("Measure"))
        val measuring = app.board.tools.activeId == ToolId.Measure
        fun measure(kind: MeasureKind, tip: String): () -> Unit = {
            tools.measureKind = kind
            app.activateTool(ToolId.Measure)
            app.popovers.closeAll()
            app.toast.show(tip, durationMs = 3500)
        }
        val mk = tools.measureKind
        l.addView(k.tiles(4, listOf(
            Tile("measure-distance", "Distance", measuring && mk == MeasureKind.Distance, action = measure(MeasureKind.Distance, "Drag from one point to another.")),
            Tile("measure-angle", "Angle", measuring && mk == MeasureKind.Angle, action = measure(MeasureKind.Angle, "Drag from the vertex along the first arm, then tap the second arm.")),
            Tile("measure-slope", "Slope", measuring && mk == MeasureKind.Slope, action = measure(MeasureKind.Slope, "Drag along the line to measure its slope.")),
            Tile("measure-area", "Area", measuring && mk == MeasureKind.Area, action = measure(MeasureKind.Area, "Tap the corners, then tap the first corner again.")),
        )))
        val scale = app.doc.coordinates.scale
        l.addView(k.row(k.pill("scale", if (scale.isIdentity) "Scale: none" else "Scale: ${scale.text()}") { app.popovers.push("scale") }))
        l.addView(k.hint("Exact values: select a measurement, line, vector or shape and tap the precision button (or double-tap it)."))
        if (full) {
            l.addView(k.section("Construct"))
            val constructing = app.board.tools.activeId == ToolId.Construct
            val ck = tools.constructKind
            fun construct(kind: ConstructKind): () -> Unit = { tools.constructKind = kind; app.activateTool(ToolId.Construct); app.popovers.closeAll() }
            l.addView(k.tiles(5, listOf(
                Tile("point", "Point", constructing && ck == ConstructKind.Point, action = construct(ConstructKind.Point)),
                Tile("segment", "Segment", constructing && ck == ConstructKind.Segment, action = construct(ConstructKind.Segment)),
                Tile("line", "Line", constructing && ck == ConstructKind.Line, action = construct(ConstructKind.Line)),
                Tile("ray", "Ray", constructing && ck == ConstructKind.Ray, action = construct(ConstructKind.Ray)),
                Tile("vector", "Vector", constructing && ck == ConstructKind.Vector, action = construct(ConstructKind.Vector)),
            )))
            l.addView(k.section("Coordinates"))
            l.addView(k.row(k.pill("coordinates", "Coordinate plane background") {
                val spec = app.templates.find("coordinate")
                app.doc.changePages(listOf(app.doc.currentPage), "Coordinate plane") { p -> app.doc.propsOf(p).copy(background = spec) }
                app.popovers.closeAll()
            }))
        }
        l.addView(k.toggle("Show coordinates and lengths", "", tools.showCoordinates) { tools.showCoordinates = it })
        l.addView(k.toggle("Snap to grid and points", "", tools.snapToGrid) { tools.snapToGrid = it })
        return l
    }

    @SuppressLint("ViewConstructor")
    class FormulaPreview(private val app: App) : View(app.activity) {
        var latex = ""
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(app.t.dp(400.0).toInt(), w), app.t.dp(130.0).toInt())

        override fun onDraw(c: Canvas) {
            val t = app.t
            paint.color = Color.rgb(0x1f, 0x2b, 0x26)
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), t.dp(12.0), t.dp(12.0), paint)
            if (latex.isBlank()) {
                paint.color = t.textMuted
                paint.textSize = t.dp(t.fontSize)
                paint.textAlign = Paint.Align.CENTER
                val fm = paint.fontMetrics
                c.drawText("Type a formula or tap the symbols below", width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, paint)
                return
            }
            EquationObject.paintFormula(c, latex, RectD(t.dpd(12.0), t.dpd(8.0), width - t.dpd(12.0), height - t.dpd(8.0)), t.dpd(40.0), Color.rgb(245, 245, 240))
        }
    }

    fun equation(app: App): View {
        val k = app.kit
        val l = k.layout()
        val page = app.doc.currentPage
        val target = app.equationTarget?.let { page.objectById(it) as? EquationObject }
        var size = target?.pixelSize ?: 56.0
        val initial = target?.latex ?: app.pendingEquation.also { app.pendingEquation = "" }
        val preview = FormulaPreview(app)
        preview.latex = initial
        l.addView(preview)
        val input = k.input("\\frac{a}{b},  x^{2},  \\sqrt{x},  \\int_0^1 f(x)\\,dx")
        input.setText(initial)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { preview.latex = s?.toString() ?: "" }
        })
        l.addView(input)
        fun commit() {
            val latex = input.text.toString().trim()
            val p = app.doc.currentPage
            val existing = app.equationTarget?.let { p.objectById(it) as? EquationObject }
            if (existing != null) {
                if (latex.isEmpty()) { p.objects.remove(existing); app.doc.commit(p, "Delete formula") }
                else { existing.latex = latex; existing.pixelSize = size; app.doc.commit(p, "Edit formula", listOf(existing.id)) }
            } else if (latex.isNotEmpty()) {
                val eq = EquationObject.create(latex, app.board.viewCenterInPage(), size, app.tools.penColor)
                p.objects.add(eq)
                app.doc.commit(p, "Insert formula")
                app.activateTool(ToolId.Select)
                app.board.selection.setSingle(eq.id)
            }
            app.equationTarget = null
            app.popovers.closeAll()
        }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { commit(); true } else false }
        val keys = listOf("x²" to "^{2}", "xₙ" to "_{}", "a⁄b" to "\\frac{}{}", "√x" to "\\sqrt{}", "ⁿ√x" to "\\sqrt[n]{}", "∫" to "\\int_{a}^{b} ",
            "∑" to "\\sum_{i=1}^{n} ", "lim" to "\\lim_{x \\to 0} ", "( )" to "\\left( \\right)", "vec" to "\\vec{}",
            "[ ]" to "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", "{" to "\\begin{cases} x & x \\geq 0 \\\\ -x & x < 0 \\end{cases}",
            "π" to "\\pi ", "θ" to "\\theta ", "α" to "\\alpha ", "β" to "\\beta ", "Δ" to "\\Delta ", "∞" to "\\infty ", "±" to "\\pm ",
            "×" to "\\times ", "÷" to "\\div ", "·" to "\\cdot ", "≤" to "\\leq ", "≥" to "\\geq ", "≠" to "\\neq ", "≈" to "\\approx ",
            "→" to "\\to ", "°" to "^{\\circ}")
        val grid = TileGrid(app.activity, 8, app.t.dp(6.0).toInt())
        for ((label, snippet) in keys) {
            val b = TouchButton(app.activity, app.t, "", label, TouchButton.Style.Pill)
            b.setOnClickListener {
                val pos = input.selectionStart.coerceAtLeast(0)
                val text = StringBuilder(input.text.toString()).insert(pos, snippet).toString()
                input.setText(text)
                val hole = snippet.indexOf("{}")
                input.setSelection((if (hole >= 0) pos + hole + 1 else pos + snippet.length).coerceAtMost(text.length))
                input.requestFocus()
            }
            grid.addView(b)
        }
        l.addView(grid)
        l.addView(k.slider("Size", 24.0, 200.0, size, 2.0, format = { "${it.toInt()} px" }) { v, _ -> size = v })
        val editing = target != null
        val insert = k.pill("check", if (editing) "Update formula" else "Insert formula", primary = true) { commit() }
        if (!editing) l.addView(k.row(insert, k.pill("magic", "✨ Magic Equation Maker") { app.openMagicEquation() }))
        else l.addView(k.row(insert))
        return l
    }

    fun function(app: App): View {
        val k = app.kit
        val l = k.layout()
        val page = app.doc.currentPage
        val g = selectedOf(app, ObjectType.Graph) as? GraphObject
        if (g == null) {
            l.addView(k.hint("Insert a graph, then type functions such as  2x + 1,  a·sin(bx),  x² − 4."))
            l.addView(k.row(k.pill("function", "Insert graph", primary = true) {
                val graph = GraphObject.create(app.board.viewCenterInPage(), 760.0, 560.0)
                graph.addFunction("a*x^2 + b*x + c")
                page.objects.add(graph)
                app.doc.commit(page, "Insert graph")
                app.board.selection.setSingle(graph.id)
                app.activateTool(ToolId.Graph)
                app.board.selection.setSingle(graph.id)
                app.popovers.refresh()
            }))
            return l
        }
        fun modify(text: String, change: (GraphObject) -> Unit, rebuild: Boolean = false) {
            val p = app.doc.currentPage
            val live = p.objectById(g.id) as? GraphObject ?: return
            change(live)
            app.doc.commit(p, text, listOf(live.id))
            if (rebuild) app.popovers.refresh()
        }
        l.addView(k.section("Functions"))
        val names = "fghpqrs"
        for ((i, f) in g.functions.withIndex()) {
            val row = hbox(app.activity, app.t.dp(6.0).toInt())
            val vis = TouchButton(app.activity, app.t, if (f.visible) "eye" else "eye-off", "Show / hide", TouchButton.Style.Icon)
            vis.indicatorColor = if (f.visible) f.color else null
            vis.setOnClickListener { modify("Show function", { it.setFunctionVisible(i, !it.functions[i].visible) }, true) }
            val label = k.text("${if (i < 7) names[i] else 'f'}(x) =")
            label.setTextColor(f.color)
            label.typeface = app.t.typefaceBold
            val edit = k.input()
            edit.setText(Expression.stripDefinition(f.expression).second)
            edit.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    val txt = edit.text.toString()
                    if (txt != g.functions.getOrNull(i)?.expression) modify("Edit function", { it.setFunctionExpression(i, txt) }, true)
                    true
                } else false
            }
            edit.setOnFocusChangeListener { _, has ->
                val txt = edit.text.toString()
                if (!has && txt != g.functions.getOrNull(i)?.expression) modify("Edit function", { it.setFunctionExpression(i, txt) }, false)
            }
            val remove = TouchButton(app.activity, app.t, "trash", "Remove", TouchButton.Style.Icon)
            remove.setOnClickListener { modify("Remove function", { it.removeFunction(i) }, true) }
            val bs = app.t.dp(48.0).toInt()
            row.addView(vis, LinearLayout.LayoutParams(bs, bs))
            row.addView(label)
            row.addView(edit, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(remove, LinearLayout.LayoutParams(bs, bs))
            l.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            val err = g.functionError(i)
            if (err.isNotEmpty()) l.addView(k.hint(err).also { it.setTextColor(app.t.danger) })
        }
        l.addView(k.row(k.pill("plus", "Add function") { modify("Add function", { it.addFunction("") }, true) }))
        if (g.parameters.isNotEmpty()) {
            l.addView(k.section("Parameters"))
            for (prm in g.parameters) {
                l.addView(k.slider(prm.name, prm.min, prm.max, prm.value, 0.1, format = { Geom.formatNumber(it, 1) }) { v, final ->
                    val p = app.doc.currentPage
                    val live = p.objectById(g.id) as? GraphObject ?: return@slider
                    live.setParameterValue(prm.name, v)
                    p.touch()
                    app.board.invalidateContent()
                    if (final) app.doc.commit(p, "Change ${prm.name}", listOf(live.id))
                })
            }
        }
        l.addView(k.section("View"))
        l.addView(k.row(
            k.pill("zoom-in", "") { modify("Zoom graph", { it.zoomAtLocal(it.mathToLocal(it.window.center), 1.5) }) },
            k.pill("zoom-out", "") { modify("Zoom graph", { it.zoomAtLocal(it.mathToLocal(it.window.center), 1 / 1.5) }) },
            k.pill("reset", "Reset") { modify("Reset graph view", { it.resetView() }) },
            k.pill("grid", "Grid") { modify("Toggle grid", { it.showGrid = !it.showGrid }) },
        ))
        l.addView(k.section("Points and vectors"))
        l.addView(k.toggle("Tap to place points", "Points snap onto curves; tap a point again to remove it", app.board.graphTool.pointMode) {
            app.board.graphTool.pointMode = it
            app.activateTool(ToolId.Graph)
            app.board.selection.setSingle(g.id)
        })
        val vec = k.input("Vector: 3, 2  or  1, 1, 4, 3")
        val vrow = hbox(app.activity, app.t.dp(6.0).toInt())
        vrow.addView(vec, LinearLayout.LayoutParams(0, WRAP, 1f))
        vrow.addView(k.pill("vector", "Add") {
            val values = Regex("-?\\d+(?:[.,]\\d+)?").findAll(vec.text.toString().replace('−', '-')).map { it.value.replace(',', '.').toDouble() }.toList()
            if (values.size != 2 && values.size != 4) { app.toast.show("Enter 2 numbers (components) or 4 numbers (start and end).", durationMs = 3000); return@pill }
            val from = if (values.size == 4) Pt(values[0], values[1]) else Pt.ZERO
            val to = if (values.size == 4) Pt(values[2], values[3]) else Pt(values[0], values[1])
            modify("Add vector", { it.addVector(from, to) })
            vec.setText("")
        })
        vrow.addView(k.pill("trash", "Clear points") { modify("Clear points", { it.clearPointsAndVectors() }) })
        l.addView(vrow, LinearLayout.LayoutParams(MATCH, WRAP))
        l.addView(k.section("Value table"))
        val from = k.input(numeric = true).also { it.setText("-3") }
        val to = k.input(numeric = true).also { it.setText("3") }
        val step = k.input(numeric = true).also { it.setText("1") }
        val trow = hbox(app.activity, app.t.dp(6.0).toInt())
        trow.addView(k.text("x from"))
        trow.addView(from, LinearLayout.LayoutParams(0, WRAP, 1f))
        trow.addView(k.text("to"))
        trow.addView(to, LinearLayout.LayoutParams(0, WRAP, 1f))
        trow.addView(k.text("step"))
        trow.addView(step, LinearLayout.LayoutParams(0, WRAP, 1f))
        trow.addView(k.pill("table", "Create") {
            val p = app.doc.currentPage
            val gr = p.objectById(g.id) as? GraphObject ?: return@pill
            val x0 = from.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val x1 = to.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val dx = abs(step.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0)
            if (dx <= 0 || x1 < x0 || (x1 - x0) / dx > 40) { app.toast.show("Choose a range with at most 40 rows.", durationMs = 3000); return@pill }
            val cols = gr.functions.indices.filter { gr.functions[it].visible && gr.functionError(it).isEmpty() && gr.functions[it].expression.isNotBlank() }
            val rows = floor((x1 - x0) / dx + 1e-9).toInt() + 2
            val table = TableObject.create(rows, cols.size + 1, Pt.ZERO)
            table.setCell(0, 0, "x")
            cols.forEachIndexed { c, fi -> table.setCell(0, c + 1, "${if (fi < 7) names[fi] else 'f'}(x)") }
            for (r in 1 until rows) {
                val x = x0 + (r - 1) * dx
                table.setCell(r, 0, Geom.formatNumber(x, 3))
                cols.forEachIndexed { c, fi -> table.setCell(r, c + 1, Geom.formatNumber(gr.evaluate(fi, x), 3)) }
            }
            val gb = gr.sceneBounds()
            val lb = table.localBounds()
            table.position = Pt(gb.right + lb.width / 2 + 30, gb.top + lb.height / 2)
            p.objects.add(table)
            app.doc.commit(p, "Value table")
            app.board.ensureVisible(table.sceneBounds())
        })
        l.addView(trow, LinearLayout.LayoutParams(MATCH, WRAP))
        l.addView(k.hint("Drag inside the graph to pan, pinch to zoom."))
        return l
    }

    fun table(app: App): View {
        val k = app.kit
        val l = k.layout()
        val sel = selectedOf(app, ObjectType.Table) as? TableObject
        var rows = sel?.rows ?: 4
        var cols = sel?.cols ?: 3
        var header = sel?.header ?: true
        fun apply() {
            val p = app.doc.currentPage
            val t = sel?.let { p.objectById(it.id) as? TableObject } ?: return
            t.setDimensions(rows, cols)
            t.header = header
            app.doc.commit(p, "Change table", listOf(t.id))
        }
        l.addView(k.slider("Rows", 1.0, 30.0, rows.toDouble(), 1.0, format = { it.toInt().toString() }) { v, final -> rows = v.toInt(); if (final && sel != null) apply() })
        l.addView(k.slider("Columns", 1.0, 12.0, cols.toDouble(), 1.0, format = { it.toInt().toString() }) { v, final -> cols = v.toInt(); if (final && sel != null) apply() })
        l.addView(k.toggle("Header row", "", header) { header = it; if (sel != null) apply() })
        if (sel == null) {
            l.addView(k.row(k.pill("table", "Insert table", primary = true) {
                val p = app.doc.currentPage
                val t = TableObject.create(rows, cols, app.board.viewCenterInPage())
                t.header = header
                p.objects.add(t)
                app.doc.commit(p, "Insert table")
                app.activateTool(ToolId.Select)
                app.board.selection.setSingle(t.id)
                app.popovers.closeAll()
            }))
        }
        l.addView(k.hint("Double tap a cell to type in it. Enter moves to the next cell."))
        return l
    }

    @Suppress("unused")
    private fun unused(f: InputField) = TypedValue.COMPLEX_UNIT_PX
}
