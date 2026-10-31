package com.classboard.app.math

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.RenderContext
import com.classboard.app.doc.ResizeHint
import com.classboard.app.render.Draw
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** Editable table; the first row can be a header. */
class TableObject : BoardObject(ObjectType.Table) {
    var rows = 3
        private set
    var cols = 3
        private set
    private var cells = MutableList(9) { "" }
    var cellWidth = 150.0
        private set
    var cellHeight = 56.0
        private set
    var fontSize = 26.0
        private set
    var header = true
    var colorValue = Color.rgb(235, 240, 235)

    fun cell(r: Int, c: Int) = if (r in 0 until rows && c in 0 until cols) cells.getOrElse(r * cols + c) { "" } else ""

    fun setCell(r: Int, c: Int, text: String) {
        if (r in 0 until rows && c in 0 until cols) cells[r * cols + c] = text
    }

    fun setDimensions(r0: Int, c0: Int) {
        val r = r0.coerceIn(1, 60)
        val c = c0.coerceIn(1, 20)
        val next = MutableList(r * c) { "" }
        for (i in 0 until r) for (j in 0 until c) if (i < rows && j < cols) next[i * c + j] = cell(i, j)
        rows = r; cols = c; cells = next
        invalidateBounds()
    }

    override fun localBounds(): RectD {
        val w = cols * cellWidth
        val h = rows * cellHeight
        return RectD(-w / 2, -h / 2, w / 2, h / 2)
    }

    fun cellRect(r: Int, c: Int): RectD {
        val b = localBounds()
        return RectD.xywh(b.left + c * cellWidth, b.top + r * cellHeight, cellWidth, cellHeight)
    }

    fun cellAt(local: Pt): Pair<Int, Int>? {
        val b = localBounds()
        if (!b.contains(local)) return null
        val c = ((local.x - b.left) / cellWidth).toInt().coerceIn(0, cols - 1)
        val r = ((local.y - b.top) / cellHeight).toInt().coerceIn(0, rows - 1)
        return Pair(r, c)
    }

    override fun outlineMargin() = 2.0

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val b = localBounds()
        canvas.drawRect(b.toRectF(), Draw.fill(Color.argb(150, 10, 14, 12)))
        if (header) canvas.drawRect(RectD(b.left, b.top, b.right, b.top + cellHeight).toRectF(), Draw.fill(Color.argb(45, Color.red(colorValue), Color.green(colorValue), Color.blue(colorValue))))
        val gridColor = Color.argb(150, Color.red(colorValue), Color.green(colorValue), Color.blue(colorValue))
        val grid = Draw.stroke(gridColor, 1.5)
        val thick = Draw.stroke(gridColor, 3.0)
        for (r in 0..rows) {
            val y = (b.top + r * cellHeight).toFloat()
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, if (header && r == 1) thick else grid)
        }
        for (c in 0..cols) {
            val x = (b.left + c * cellWidth).toFloat()
            canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), grid)
        }
        val tp = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        tp.color = colorValue
        tp.textSize = max(6, fontSize.roundToInt()).toFloat()
        for (r in 0 until rows) {
            tp.typeface = if (header && r == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            for (c in 0 until cols) {
                val text = cell(r, c)
                if (text.isEmpty()) continue
                val cr = cellRect(r, c)
                val inner = max(1, (cr.width - 12).toInt())
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, inner).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
                canvas.save()
                canvas.clipRect(cr.toRectF())
                canvas.translate((cr.left + 6).toFloat(), (cr.center.y - layout.height / 2.0).toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }

    override fun setColor(color: Int): Boolean { colorValue = color; return true }
    override fun color() = colorValue
    override fun isEditable() = true

    override fun clone(): BoardObject {
        val t = TableObject()
        copyBaseTo(t)
        t.rows = rows; t.cols = cols; t.cells = cells.toMutableList()
        t.cellWidth = cellWidth; t.cellHeight = cellHeight; t.fontSize = fontSize
        t.header = header; t.colorValue = colorValue
        return t
    }

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        val oldH = cellHeight
        cellWidth = max(20.0, w / cols)
        cellHeight = max(16.0, h / rows)
        if (hint != ResizeHint.Horizontal && oldH > 0) fontSize = (fontSize * cellHeight / oldH).coerceIn(6.0, 400.0)
    }

    override fun writeProperties(o: JSONObject) {
        o.put("rows", rows); o.put("cols", cols)
        val a = JSONArray()
        cells.forEach { a.put(it) }
        o.put("cells", a)
        o.put("cellSize", Json.fromSize(cellWidth, cellHeight))
        o.put("fontSize", fontSize)
        o.put("header", header)
        o.put("color", Json.fromColor(colorValue))
    }

    override fun readProperties(o: JSONObject): Boolean {
        rows = o.optInt("rows", 3).coerceIn(1, 60)
        cols = o.optInt("cols", 3).coerceIn(1, 20)
        val a = o.optJSONArray("cells")
        cells = MutableList(rows * cols) { i -> a?.optString(i, "") ?: "" }
        val (cw, ch) = Json.toSize(o.opt("cellSize"), 150.0, 56.0)
        cellWidth = max(20.0, cw); cellHeight = max(16.0, ch)
        fontSize = Json.double(o, "fontSize", 26.0).coerceIn(6.0, 400.0)
        header = o.optBoolean("header", true)
        colorValue = Json.toColor(o.opt("color"), colorValue)
        return true
    }

    companion object {
        fun create(rows: Int, cols: Int, center: Pt): TableObject {
            val t = TableObject()
            t.setDimensions(rows, cols)
            t.position = center
            return t
        }
    }
}
