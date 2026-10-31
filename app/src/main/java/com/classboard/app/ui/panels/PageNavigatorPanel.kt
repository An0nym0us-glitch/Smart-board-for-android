package com.classboard.app.ui.panels

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.doc.MovePageCommand
import com.classboard.app.render.PageRenderer
import com.classboard.app.ui.App
import kotlin.math.min

/** Thumbnails of every page: tap to open, drag to reorder; rename, reorder and page actions. */
object PageNavigatorPanel {
    private val thumbCache = HashMap<String, Pair<Long, Bitmap>>()

    @SuppressLint("ViewConstructor")
    private class PageGrid(val app: App) : View(app.activity) {
        private val t = app.t
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var columns = 3
        private var pressIndex = -1
        private var pressPos = Pt.ZERO
        private var dragPos = Pt.ZERO
        private var dragging = false
        var onActivate: ((Int) -> Unit)? = null
        var onMove: ((Int, Int) -> Unit)? = null

        private fun tileW(): Float = (width - (columns - 1) * gap()) / columns
        private fun gap() = t.dp(10.0)
        private fun tileH() = tileW() * 9f / 16f + t.dp(30.0)

        override fun onMeasure(w: Int, h: Int) {
            val width = resolveSize(t.dp(540.0).toInt(), w)
            columns = if (width / t.dp(176.0) >= 3) 3 else 2
            val tw = (width - (columns - 1) * t.dp(10.0)) / columns
            val th = tw * 9f / 16f + t.dp(30.0)
            val rows = (app.doc.pageCount + columns - 1) / columns
            setMeasuredDimension(width, (rows * th + (rows - 1) * t.dp(10.0)).toInt())
        }

        private fun tileRect(i: Int): RectF {
            val x = (i % columns) * (tileW() + gap())
            val y = (i / columns) * (tileH() + gap())
            return RectF(x, y, x + tileW(), y + tileH())
        }

        private fun indexAt(p: Pt) = (0 until app.doc.pageCount).firstOrNull { tileRect(it).contains(p.x.toFloat(), p.y.toFloat()) } ?: -1
        private fun dropIndex(p: Pt): Int = (0 until app.doc.pageCount).minByOrNull { val r = tileRect(it); Geom.distance(Pt(r.centerX().toDouble(), r.centerY().toDouble()), p) } ?: 0

        private fun thumbnail(i: Int): Bitmap? {
            val page = app.doc.page(i) ?: return null
            val cached = thumbCache[page.id]
            if (cached != null && cached.first == page.revision) return cached.second
            val bmp = PageRenderer.thumbnail(app.doc, page, 320, 180)
            thumbCache[page.id] = Pair(page.revision, bmp)
            return bmp
        }

        private fun drawTile(c: Canvas, i: Int, r: RectF, alpha: Int) {
            val page = app.doc.page(i) ?: return
            val box = RectF(r.left, r.top, r.right, r.top + r.width() * 9f / 16f)
            val s = min(box.width() / page.width, box.height() / page.height).toFloat()
            val fw = (page.width * s).toFloat(); val fh = (page.height * s).toFloat()
            val thumb = RectF(box.centerX() - fw / 2, box.centerY() - fh / 2, box.centerX() + fw / 2, box.centerY() + fh / 2)
            paint.alpha = alpha
            c.save()
            val clip = Path(); clip.addRoundRect(thumb, t.dp(8.0), t.dp(8.0), Path.Direction.CW)
            c.clipPath(clip)
            val bmp = thumbnail(i)
            if (bmp != null) c.drawBitmap(bmp, null, thumb, paint) else { paint.color = page.background.background; c.drawRect(thumb, paint) }
            c.restore()
            val current = i == app.doc.currentPageIndex
            paint.style = Paint.Style.STROKE
            paint.color = if (current) t.accent else t.popoverBorder
            paint.strokeWidth = t.dp(if (current) 3.0 else 1.0)
            paint.alpha = alpha
            c.drawRoundRect(thumb, t.dp(8.0), t.dp(8.0), paint)
            paint.style = Paint.Style.FILL
            paint.color = if (current) t.accent else t.text
            paint.alpha = alpha
            paint.textSize = t.dp(t.smallFontSize)
            paint.typeface = if (current) t.typefaceBold else t.typeface
            paint.textAlign = Paint.Align.LEFT
            var label = (i + 1).toString()
            if (page.name.isNotEmpty()) label += "  " + page.name
            val txt = TextUtils.ellipsize(label, android.text.TextPaint(paint), r.width(), TextUtils.TruncateAt.END).toString()
            val fm = paint.fontMetrics
            c.drawText(txt, r.left, thumb.bottom + t.dp(15.0) - (fm.ascent + fm.descent) / 2, paint)
            paint.alpha = 255
        }

        override fun onDraw(c: Canvas) {
            val target = if (dragging) dropIndex(dragPos) else -1
            for (i in 0 until app.doc.pageCount) {
                if (dragging && i == pressIndex) {
                    val r = tileRect(i)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = t.popoverBorder; strokeWidth = t.dp(1.5); pathEffect = DashPathEffect(floatArrayOf(t.dp(6.0), t.dp(4.0)), 0f) }
                    c.drawRoundRect(RectF(r.left, r.top, r.right, r.top + r.width() * 9f / 16f), t.dp(8.0), t.dp(8.0), p)
                    continue
                }
                drawTile(c, i, tileRect(i), 255)
            }
            if (dragging && target >= 0) {
                val r = tileRect(target)
                val x = if (target > pressIndex) r.right + t.dp(5.0) else r.left - t.dp(5.0)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; strokeWidth = t.dp(4.0); strokeCap = Paint.Cap.ROUND }
                c.drawLine(x, r.top, x, r.top + r.width() * 9f / 16f, p)
                val m = tileRect(pressIndex)
                m.offset((dragPos.x - pressPos.x).toFloat(), (dragPos.y - pressPos.y).toFloat())
                drawTile(c, pressIndex, m, 217)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = Pt(e.x.toDouble(), e.y.toDouble())
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressIndex = indexAt(p); pressPos = p; dragPos = p; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (pressIndex < 0) return true
                    dragPos = p
                    if (!dragging && Geom.distance(p, pressPos) > t.dp(12.0) && app.doc.pageCount > 1) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                    if (dragging) invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val pressed = pressIndex
                    pressIndex = -1
                    if (pressed < 0) return true
                    if (dragging) {
                        dragging = false
                        val to = dropIndex(p)
                        invalidate()
                        if (to != pressed) onMove?.invoke(pressed, to)
                    } else if (indexAt(p) == pressed) onActivate?.invoke(pressed)
                }
                MotionEvent.ACTION_CANCEL -> { pressIndex = -1; dragging = false; invalidate() }
            }
            return true
        }
    }

    fun create(app: App): View {
        val k = app.kit
        val l = k.layout()
        val doc = app.doc
        l.addView(k.row(
            k.pill("page-add", "New page", primary = true) { app.addPage(); app.popovers.refresh() },
            k.pill("duplicate", "Duplicate") { app.duplicatePage(); app.popovers.refresh() },
            k.pill("page-clear", "Clear page", enabled = doc.currentPage.objects.isNotEmpty()) { app.confirmClearPage() },
            k.pill("page-delete", "Delete page", danger = true, enabled = doc.pageCount > 1) { app.deleteCurrentPage(); app.popovers.refresh() },
        ))
        l.addView(k.row(
            k.pill("chevron-left", "", enabled = doc.currentPageIndex > 0) {
                val i = doc.currentPageIndex
                if (i > 0) doc.push(MovePageCommand(i, i - 1))
                app.popovers.refresh()
            },
            k.pill("chevron-right", "", enabled = doc.currentPageIndex + 1 < doc.pageCount) {
                val i = doc.currentPageIndex
                if (i + 1 < doc.pageCount) doc.push(MovePageCommand(i, i + 1))
                app.popovers.refresh()
            },
            k.pill("page-size", "Page size") { app.popovers.push("pagesize") },
            k.pill("palette", "Background") { app.popovers.push("background") },
            k.pill("template", "Templates") { app.popovers.push("templates") },
        ))
        val name = k.input("Name of page ${doc.currentPageIndex + 1} (optional)")
        name.setText(doc.currentPage.name)
        fun rename() {
            val p = doc.currentPage
            val txt = name.text.toString()
            if (p.name != txt) doc.changePages(listOf(p), "Rename page") { pg -> doc.propsOf(pg).copy(name = txt) }
        }
        name.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { rename(); true } else false }
        name.setOnFocusChangeListener { _, has -> if (!has) rename() }
        l.addView(name)
        val grid = PageGrid(app)
        grid.onActivate = { i -> doc.setCurrentPage(i); app.popovers.closeAll() }
        grid.onMove = { from, to -> doc.push(MovePageCommand(from, to)); app.popovers.refresh() }
        l.addView(grid)
        return l
    }
}
