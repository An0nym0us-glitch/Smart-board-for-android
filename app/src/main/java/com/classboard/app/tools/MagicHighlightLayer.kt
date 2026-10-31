package com.classboard.app.tools

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.classboard.app.doc.InkStyle
import com.classboard.app.doc.StrokeObject
import com.classboard.app.doc.StrokePoint
import com.classboard.app.doc.StrokeStyle
import kotlin.math.max

/**
 * Temporary highlights of the Magic Highlighter: visible for ~5 s, then fade. Never document
 * objects, never saved, exported or undoable.
 */
class MagicHighlightLayer {
    private class Highlight(val page: String, val points: List<StrokePoint>, val ink: InkStyle, val finishedAt: Long)

    private val items = ArrayList<Highlight>()
    private val handler = Handler(Looper.getMainLooper())
    var onRepaint: (() -> Unit)? = null
    var visibleMs = DURATION_MS
    var fadeMs = FADE_MS

    private val tick = Runnable { tick() }

    fun add(page: String, points: List<StrokePoint>, ink: InkStyle) {
        if (points.isEmpty()) return
        items.add(Highlight(page, points, ink, SystemClock.uptimeMillis()))
        onRepaint?.invoke()
        schedule()
    }

    fun clear() {
        items.clear()
        handler.removeCallbacks(tick)
        onRepaint?.invoke()
    }

    fun countOnPage(page: String) = items.count { it.page == page }

    private fun age(h: Highlight) = SystemClock.uptimeMillis() - h.finishedAt

    private fun tick() {
        var repaint = false
        val it = items.iterator()
        while (it.hasNext()) {
            val h = it.next()
            val a = age(h)
            if (a >= visibleMs + fadeMs) { it.remove(); repaint = true } else if (a > visibleMs) repaint = true
        }
        if (repaint) onRepaint?.invoke()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (items.isEmpty()) return
        var wait = (visibleMs + fadeMs).toLong()
        for (h in items) {
            val a = age(h)
            if (a >= visibleMs) { wait = FRAME_MS; break }
            wait = minOf(wait, visibleMs - a + 1)
        }
        handler.postDelayed(tick, max(1L, wait))
    }

    fun paint(c: Canvas, page: String) {
        for (h in items) {
            if (h.page != page) continue
            val op = opacityAt(age(h), visibleMs, fadeMs)
            if (op > 0) paintStroke(c, h.points, h.ink, op)
        }
    }

    companion object {
        const val DURATION_MS = 5000L
        const val FADE_MS = 1000L
        const val FRAME_MS = 33L

        fun opacityAt(age: Long, visible: Long, fade: Long): Double {
            if (age <= visible) return 1.0
            val t = ((age - visible).toDouble() / max(1L, fade)).coerceIn(0.0, 1.0)
            return 1.0 - t * t * (3.0 - 2.0 * t)
        }

        private fun withAlphaF(c: Int, a: Double) = ((a.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (c and 0xFFFFFF)
        private fun mulAlpha(c: Int, f: Double) = withAlphaF(c, ((c ushr 24) and 0xFF) / 255.0 * f)

        fun paintStroke(c: Canvas, points: List<StrokePoint>, ink: InkStyle, opacity: Double) {
            if (points.isEmpty() || opacity <= 0) return
            val glow = ink.copy(style = StrokeStyle.Pen, pressure = false, width = ink.width * 1.6, color = withAlphaF(ink.color, 0.16 * opacity))
            StrokeObject.paintPoints(c, points, glow)
            val bandColor = mulAlpha(StrokeObject.effectiveColor(ink.copy(style = StrokeStyle.Highlighter)), opacity)
            val band = ink.copy(style = StrokeStyle.Pen, pressure = false, color = bandColor)
            StrokeObject.paintPoints(c, points, band)
            val core = ink.copy(style = StrokeStyle.Pen, pressure = false, width = max(1.0, ink.width * 0.16), color = withAlphaF(0xFFFFFF, 150 / 255.0 * opacity))
            StrokeObject.paintPoints(c, points, core)
        }
    }
}
