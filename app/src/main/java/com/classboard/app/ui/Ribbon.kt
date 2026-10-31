package com.classboard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * The bottom ribbon: MORE · PEN · ERASER · SELECT · (tool chip) · UNDO · REDO · FILE · INSERT · EDIT ·
 * ‹ page › · NEW PAGE · PAGE · − zoom + · fit · full screen. Optional groups hide on narrow screens.
 */
@SuppressLint("ViewConstructor")
class Ribbon(context: Context, private val t: Theme) : ViewGroup(context) {
    private fun rb(icon: String, text: String, style: TouchButton.Style = TouchButton.Style.Ribbon) =
        TouchButton(context, t, icon, text, style, ribbon = true).also { addView(it) }

    private inner class Sep : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            p.color = t.separator; p.strokeWidth = t.rdp(1.0)
            c.drawLine(width / 2f, t.rdp(16.0), width / 2f, height - t.rdp(16.0), p)
        }
    }

    private fun sep() = Sep().also { addView(it) }

    val more = rb("more", "More")
    private val sep1 = sep()
    val pen = rb("pen", "Pen").also { it.hasOptions = true }
    val eraser = rb("eraser", "Eraser").also { it.hasOptions = true }
    val select = rb("select", "Select").also { it.hasOptions = true }
    val toolChip = rb("", "").also { it.hasOptions = true; it.visibility = GONE }
    private val sep2 = sep()
    val undo = rb("undo", "Undo")
    val redo = rb("redo", "Redo")
    private val groupSep = sep()
    val file = rb("file", "File").also { it.hasOptions = true }
    val insert = rb("insert", "Insert").also { it.hasOptions = true }
    val edit = rb("edit", "Edit").also { it.hasOptions = true }
    val prev = rb("chevron-left", "Previous page", TouchButton.Style.Icon)
    val pageIndicator = rb("", "1 / 1", TouchButton.Style.Pill)
    val next = rb("chevron-right", "Next page", TouchButton.Style.Icon)
    val newPage = rb("page-add", "New page")
    val pageMenu = rb("pages", "Page").also { it.hasOptions = true }
    val zoomOut = rb("zoom-out", "Zoom out", TouchButton.Style.Icon)
    val zoom = rb("", "100%", TouchButton.Style.Pill)
    val zoomIn = rb("zoom-in", "Zoom in", TouchButton.Style.Icon)
    val fit = rb("fit-page", "Fit page", TouchButton.Style.Icon)
    val fullScreen = rb("fullscreen", "Full screen", TouchButton.Style.Icon)

    private val left = listOf(more, sep1, pen, eraser, select, toolChip, sep2, undo, redo, groupSep, file, insert, edit)
    private val middle = listOf(prev, pageIndicator, next, newPage, pageMenu)
    private val right = listOf(zoomOut, zoom, zoomIn, fit, fullScreen)
    /** Hidden first when the ribbon gets too narrow. */
    private val optional: List<List<View>> = listOf(listOf(fit), listOf(zoomOut, zoomIn), listOf(edit), listOf(insert), listOf(file, groupSep), listOf(prev, next), listOf(newPage), listOf(redo))

    private val bg = Paint()

    init {
        setWillNotDraw(false)
        pageIndicator.minWidthPx = t.rdp(110.0).toInt()
        zoom.minWidthPx = t.rdp(80.0).toInt()
    }

    private fun gap() = t.rdp(4.0).toInt()
    private fun margin() = t.rdp(8.0).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = t.rdp(t.ribbonHeight).toInt()
        val inner = MeasureSpec.makeMeasureSpec(h - t.rdp(12.0).toInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is Sep) c.measure(MeasureSpec.makeMeasureSpec(t.rdp(13.0).toInt(), MeasureSpec.EXACTLY), inner)
            else c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), inner)
        }
        // Fit: hide optional groups until everything fits (decide first, then apply only changes).
        val hidden = HashSet<View>()
        fun required(): Int {
            var total = 2 * margin()
            var count = 0
            for (v in left + middle + right) {
                if (v in hidden || (v === toolChip && toolChip.visibility == GONE)) continue
                total += v.measuredWidth; count++
            }
            return total + max(0, count - 1) * gap() + t.rdp(24.0).toInt()
        }
        for (group in optional) {
            if (required() <= w) break
            hidden.addAll(group)
        }
        for (v in optional.flatten()) {
            val want = if (v in hidden) GONE else VISIBLE
            if (v.visibility != want) v.visibility = want
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
        val h = b - top
        val g = gap()
        fun widthOf(list: List<View>): Int {
            var w = 0; var n = 0
            for (v in list) if (v.visibility != GONE) { w += v.measuredWidth; n++ }
            return w + max(0, n - 1) * g
        }
        fun layoutRow(list: List<View>, x0: Int) {
            var x = x0
            for (v in list) {
                if (v.visibility == GONE) continue
                val y = (h - v.measuredHeight) / 2
                v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
                x += v.measuredWidth + g
            }
        }
        val width = r - l
        val lw = widthOf(left)
        val mw = widthOf(middle)
        val rw = widthOf(right)
        layoutRow(left, margin())
        layoutRow(right, width - margin() - rw)
        // Middle group centred in the free space between left and right.
        val freeL = margin() + lw + g
        val freeR = width - margin() - rw - g
        val mx = (freeL + (freeR - freeL - mw) / 2).coerceAtLeast(freeL)
        layoutRow(middle, mx)
    }

    override fun onDraw(c: Canvas) {
        bg.color = t.ribbon
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        bg.color = t.ribbonBorder
        c.drawRect(0f, 0f, width.toFloat(), t.rdp(1.0), bg)
    }

    fun setActiveTool(id: com.classboard.app.tools.ToolId) {
        pen.checked = id == com.classboard.app.tools.ToolId.Pen
        eraser.checked = id == com.classboard.app.tools.ToolId.Eraser
        select.checked = id == com.classboard.app.tools.ToolId.Select
        toolChip.checked = toolChip.visibility == VISIBLE && !pen.checked && !eraser.checked && !select.checked
    }

    fun setToolChip(icon: String, label: String) {
        toolChip.iconName = icon
        toolChip.label = label
        toolChip.visibility = if (icon.isEmpty()) GONE else VISIBLE
        toolChip.checked = icon.isNotEmpty()
        requestLayout()
    }

    fun setPageInfo(index: Int, count: Int) {
        pageIndicator.label = "${index + 1} / $count"
        prev.isEnabled = index > 0
        next.isEnabled = index + 1 < count
    }

    fun setUndoRedo(canUndo: Boolean, canRedo: Boolean) {
        undo.isEnabled = canUndo
        redo.isEnabled = canRedo
    }

    fun setZoomPercent(p: Int) { zoom.label = "$p%" }
    fun setFullScreen(on: Boolean) { fullScreen.iconName = if (on) "fullscreen-exit" else "fullscreen" }
    fun setPenColor(c: Int) { pen.indicatorColor = c }
    fun setMoreOpen(open: Boolean) { more.checked = open }
}
