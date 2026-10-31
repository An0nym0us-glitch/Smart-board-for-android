package com.classboard.app.ui.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.classboard.app.core.Pt
import com.classboard.app.doc.Align
import com.classboard.app.doc.ShapeKind
import com.classboard.app.doc.ShapeObject
import com.classboard.app.doc.StrokeStyle
import com.classboard.app.doc.TextObject
import com.classboard.app.tools.EraserMode
import com.classboard.app.tools.SelectMode
import com.classboard.app.tools.ShapeFill
import com.classboard.app.tools.ToolId
import com.classboard.app.ui.App
import com.classboard.app.ui.TileGrid
import com.classboard.app.ui.TouchButton
import com.classboard.app.ui.WRAP

object DrawingPanels {
    fun pen(app: App): View {
        val k = app.kit
        val tools = app.tools
        val l = k.layout()
        l.addView(k.section("Style"))
        val segment = when {
            tools.magicHighlighter -> 2
            tools.penStyle == StrokeStyle.Highlighter -> 1
            tools.penStyle == StrokeStyle.Dashed -> 3
            tools.penStyle == StrokeStyle.Dotted -> 4
            else -> 0
        }
        val magicHint = k.hint("Magic Highlighter marks disappear by themselves after a few seconds. They are not saved and do not change the lesson.")
        magicHint.visibility = if (tools.magicHighlighter) View.VISIBLE else View.GONE
        lateinit var thickness: com.classboard.app.ui.TouchSlider
        fun makeThickness() = k.slider("Thickness", 1.0, if (tools.usesHighlighterWidth) 80.0 else 40.0, tools.penWidth, 0.5, dot = true,
            format = { v -> String.format(if (v < 10) "%.1f px" else "%.0f px", v) }) { v, _ -> tools.penWidth = v }
        val style = k.segmented(listOf("pen" to "Pen", "highlighter" to "Highlighter", "magic-highlighter" to "Magic", "line-dashed" to "Dashed", "line-dotted" to "Dotted"), segment) { i ->
            if (i == 2) tools.magicHighlighter = true
            else tools.penStyle = listOf(StrokeStyle.Pen, StrokeStyle.Highlighter, StrokeStyle.Pen, StrokeStyle.Dashed, StrokeStyle.Dotted)[i]
            magicHint.visibility = if (tools.magicHighlighter) View.VISIBLE else View.GONE
            val idx = l.indexOfChild(thickness)
            l.removeView(thickness)
            thickness = makeThickness()
            l.addView(thickness, idx)
        }
        l.addView(style)
        l.addView(magicHint)
        thickness = makeThickness()
        l.addView(thickness)
        l.addView(k.section("Colour"))
        l.addView(k.swatches(tools.palette, tools.penColor) { tools.penColor = it })
        l.addView(k.toggle("Pressure sensitivity", "Line width follows stylus pressure", tools.pressureEnabled) { tools.pressureEnabled = it })
        l.addView(k.toggle("Shape recognition", "Turns drawn lines, circles and polygons into clean shapes", tools.shapeRecognition) { tools.shapeRecognition = it })
        return l
    }

    fun eraser(app: App): View {
        val k = app.kit
        val tools = app.tools
        val l = k.layout()
        l.addView(k.section("Mode"))
        val desc = k.hint("")
        fun describe(m: EraserMode) {
            desc.text = when (m) {
                EraserMode.Stroke -> "Removes every ink stroke you touch."
                EraserMode.Object -> "Removes any object you touch: ink, shapes, text, images."
                EraserMode.Area -> "Rubs out exactly what is under the eraser: ink and shapes, even part of a shape."
            }
        }
        describe(tools.eraserMode)
        l.addView(k.segmented(listOf("eraser-stroke" to "Stroke", "eraser-object" to "Object", "eraser" to "Area"), tools.eraserMode.ordinal) { i ->
            tools.eraserMode = EraserMode.values()[i]; describe(tools.eraserMode)
        })
        l.addView(desc)
        l.addView(k.slider("Size", 16.0, 200.0, tools.eraserSize, 2.0, format = { "${it.toInt()} px" }) { v, _ -> tools.eraserSize = v })
        l.addView(k.hint("Tip: wipe with three fingers held together or the flat of your hand to erase anytime."))
        l.addView(k.row(k.pill("page-clear", "Clear page", danger = true) { app.confirmClearPage() }))
        return l
    }

    fun select(app: App): View {
        val k = app.kit
        val tools = app.tools
        val l = k.layout()
        l.addView(k.section("Selection"))
        l.addView(k.segmented(listOf("select-rect" to "Rectangle", "lasso" to "Lasso"), tools.selectMode.ordinal) { tools.selectMode = SelectMode.values()[it] })
        l.addView(k.toggle("Multi-select", "Each tap adds to the selection", tools.multiSelect) { tools.multiSelect = it })
        l.addView(k.row(
            k.pill("select-all", "Select all") { app.edit.selectAll(); app.popovers.closeAll() },
            k.pill("paste", "Paste", enabled = app.edit.canPaste()) {
                if (!app.edit.paste(app.board.viewCenterInPage())) app.toast.show("The clipboard is empty.", durationMs = 2000)
                app.popovers.closeAll()
            },
        ))
        l.addView(k.hint("Drag handles to resize, the round handle to rotate. Double tap text, formulas, graphs and tables to edit them."))
        return l
    }

    private class ShapeButton(app: App, val kind: ShapeKind) : View(app.activity) {
        private val t = app.t
        var checked = false
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isClickable = true }

        override fun onMeasure(w: Int, h: Int) {
            val s = t.dp(62.0).toInt()
            setMeasuredDimension(s, s)
        }

        override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

        override fun onDraw(c: Canvas) {
            val inset = t.dp(2.0)
            val r = android.graphics.RectF(inset, inset, width - inset, height - inset)
            paint.style = Paint.Style.FILL
            paint.color = if (checked) t.buttonChecked else if (isPressed) t.buttonPressed else t.buttonHover
            c.drawRoundRect(r, t.dp(10.0), t.dp(10.0), paint)
            if (checked) {
                paint.style = Paint.Style.STROKE; paint.color = t.accent; paint.strokeWidth = t.dp(1.5)
                c.drawRoundRect(r, t.dp(10.0), t.dp(10.0), paint)
            }
            val fg = if (checked) t.accent else t.text
            val s = r.width() * 0.52
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = fg; strokeWidth = t.dp(2.2); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            c.save()
            c.translate(r.centerX(), r.centerY())
            if (kind.isLine) {
                ShapeObject.paintArrow(c, Pt(-s / 2.0, s / 3.0), Pt(s / 2.0, -s / 3.0), pen, kind == ShapeKind.DoubleArrow, kind != ShapeKind.Line)
            } else {
                val square = kind == ShapeKind.Circle || kind == ShapeKind.Hexagon || kind == ShapeKind.RegularPolygon
                c.drawPath(ShapeObject.outlinePathFor(kind, s.toDouble(), if (square) s.toDouble() else s * 0.78, 5), pen)
            }
            c.restore()
        }
    }

    fun shapes(app: App): View {
        val k = app.kit
        val tools = app.tools
        val l = k.layout()
        val grid = TileGrid(app.activity, 5, app.t.dp(6.0).toInt())
        val kinds = listOf(ShapeKind.Line, ShapeKind.Arrow, ShapeKind.DoubleArrow, ShapeKind.Rectangle, ShapeKind.RoundedRect, ShapeKind.Circle,
            ShapeKind.Ellipse, ShapeKind.Triangle, ShapeKind.RightTriangle, ShapeKind.Diamond, ShapeKind.Parallelogram, ShapeKind.Hexagon,
            ShapeKind.RegularPolygon, ShapeKind.FreePolygon)
        for (kind in kinds) {
            val b = ShapeButton(app, kind)
            b.checked = tools.shapeKind == kind && app.board.tools.activeId == ToolId.Shape
            b.contentDescription = kind.label
            b.setOnClickListener {
                tools.shapeKind = kind
                app.activateTool(ToolId.Shape)
                app.popovers.closeAll()
                if (kind == ShapeKind.FreePolygon) app.toast.show("Tap the corners. Tap the first corner again to finish.", durationMs = 3500)
            }
            grid.addView(b)
        }
        l.addView(grid)
        l.addView(k.section("Fill"))
        l.addView(k.segmented(listOf("" to "None", "" to "Tint", "" to "Solid"), tools.shapeFill.ordinal) { tools.shapeFill = ShapeFill.values()[it] })
        l.addView(k.slider("Polygon sides", 3.0, 12.0, tools.polygonSides.toDouble(), 1.0, format = { it.toInt().toString() }) { v, _ -> tools.polygonSides = v.toInt() })
        l.addView(k.hint("Shapes use the pen colour and thickness."))
        return l
    }

    fun applyTextFormat(app: App, f: com.classboard.app.doc.TextFormat) {
        app.tools.textFormat = f
        if (app.editors.applyTextFormat(f)) return
        val page = app.doc.currentPage
        val changed = app.board.selection.ids.mapNotNull { page.objectById(it) as? TextObject }
        changed.forEach { it.format = f }
        if (changed.isNotEmpty()) app.doc.commit(page, "Format text", changed.map { it.id })
    }

    fun text(app: App): View {
        val k = app.kit
        val l = k.layout()
        val f0 = app.tools.textFormat
        var size = f0.pixelSize.toDouble()
        val bold = TouchButton(app.activity, app.t, "bold", "Bold", TouchButton.Style.Icon).also { it.checked = f0.bold }
        val italic = TouchButton(app.activity, app.t, "italic", "Italic", TouchButton.Style.Icon).also { it.checked = f0.italic }
        val underline = TouchButton(app.activity, app.t, "underline", "Underline", TouchButton.Style.Icon).also { it.checked = f0.underline }
        var align = if (f0.alignment and Align.HCENTER != 0) 1 else if (f0.alignment and Align.RIGHT != 0) 2 else 0
        var color = f0.color
        fun update() {
            val f = app.tools.textFormat.copy(
                pixelSize = size.toInt(), bold = bold.checked, italic = italic.checked, underline = underline.checked,
                alignment = when (align) { 1 -> Align.HCENTER; 2 -> Align.RIGHT; else -> Align.LEFT }, color = color,
            )
            applyTextFormat(app, f)
        }
        l.addView(k.slider("Size", 14.0, 160.0, size, 2.0, format = { "${it.toInt()} px" }) { v, final -> size = v; if (final) update() })
        for (b in listOf(bold, italic, underline)) b.setOnClickListener { b.checked = !b.checked; update() }
        val alignCtl = k.segmented(listOf("align-left" to "", "align-center" to "", "align-right" to ""), align) { align = it; update() }
        alignCtl.layoutParams = android.view.ViewGroup.LayoutParams(app.t.dp(190.0).toInt(), WRAP)
        l.addView(k.row(bold, italic, underline, alignCtl))
        l.addView(k.section("Colour"))
        l.addView(k.swatches(app.tools.palette, color) { color = it; update() })
        l.addView(k.row(k.pill("text", "Add text box", primary = true) {
            app.activateTool(ToolId.Text)
            app.popovers.closeAll()
            app.toast.show("Tap the board where the text should go.", durationMs = 2500)
        }))
        return l
    }

    fun color(app: App): View {
        val k = app.kit
        val l = k.layout()
        l.addView(k.swatches(app.tools.palette, null) { app.edit.setSelectionColor(it); app.popovers.closeAll() })
        return l
    }
}
