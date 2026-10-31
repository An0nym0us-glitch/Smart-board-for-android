package com.classboard.app.tools

import android.graphics.Canvas
import android.graphics.Color
import com.classboard.app.ai.ShapeRecognizer
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.InkStyle
import com.classboard.app.doc.StrokeObject
import com.classboard.app.doc.StrokePoint
import com.classboard.app.geometry.EdgeConstraint
import com.classboard.app.input.PointerEvent
import com.classboard.app.render.Draw
import kotlin.math.max

/** Freehand ink, one live stroke per pointer (ten-point writing). */
class PenTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Pen

    private class LiveStroke(val ink: InkStyle, val magic: Boolean, val constraint: EdgeConstraint?) {
        val points = ArrayList<StrokePoint>()
        var smoothed = Pt.ZERO
    }

    private val live = LinkedHashMap<Int, LiveStroke>()

    override fun pointerDown(e: PointerEvent) {
        host.page ?: return
        val base = host.settings.ink()
        val ink = base.copy(pressure = base.pressure && e.hasPressure)
        val tol = host.viewToPageLength(host.theme.dpd(26.0))
        val constraint = host.instruments.edgeConstraint(e.pagePos, tol)
        val s = LiveStroke(ink, host.settings.magicHighlighter, constraint)
        val p = constraint?.project(e.pagePos) ?: e.pagePos
        s.smoothed = p
        s.points.add(StrokePoint(p, (if (e.hasPressure) max(0.05, e.pressure) else 1.0).toFloat()))
        live[e.pointerId] = s
        host.updateOverlay()
    }

    private fun addPoint(s: LiveStroke, raw: Pt, pressure: Double, force: Boolean) {
        val p: Pt
        if (s.constraint != null) {
            p = s.constraint.project(raw)
        } else {
            val alpha = 1.0 - host.settings.smoothing * 0.72
            s.smoothed = s.smoothed + (raw - s.smoothed) * alpha
            p = if (force) raw else s.smoothed
        }
        val minDist = host.viewToPageLength(0.9)
        if (!force && s.points.isNotEmpty() && Geom.distance(s.points.last().pos, p) < minDist) return
        val sp = StrokePoint(p, pressure.toFloat())
        if (s.constraint != null && s.constraint.kind == EdgeConstraint.Kind.Line && s.points.size >= 2) {
            s.points[s.points.size - 1] = sp
            return
        }
        s.points.add(sp)
    }

    override fun pointerMove(e: PointerEvent) {
        val s = live[e.pointerId] ?: return
        addPoint(s, e.pagePos, if (e.hasPressure) max(0.05, e.pressure) else 1.0, false)
        host.updateOverlay()
    }

    override fun pointerUp(e: PointerEvent) {
        val s = live[e.pointerId] ?: return
        if (Geom.distance(s.points.last().pos, e.pagePos) > host.viewToPageLength(0.5))
            addPoint(s, e.pagePos, if (e.hasPressure) max(0.05, e.pressure) else 1.0, true)
        finish(e.pointerId)
    }

    override fun pointerCancel(e: PointerEvent) {
        live.remove(e.pointerId)
        host.updateOverlay()
    }

    private fun finish(pointerId: Int) {
        val s = live.remove(pointerId) ?: return
        val page = host.page
        if (page == null || s.points.isEmpty()) { host.updateOverlay(); return }
        var pts: List<StrokePoint> = s.points
        if (pts.size > 3 && s.constraint == null) {
            val eps = host.viewToPageLength(if (s.ink.pressure) 0.15 else 0.3)
            pts = Geom.simplifyIndices(pts.map { it.pos }, eps).map { pts[it] }
        }
        if (s.magic) {
            host.magicHighlights.add(page.id, pts, s.ink)
            host.updateOverlay()
            return
        }
        var obj: BoardObject? = null
        var text = "Draw"
        if (host.settings.shapeRecognition && s.constraint == null && pts.size >= 4) {
            obj = ShapeRecognizer.recognize(pts.map { it.pos }, s.ink, host.viewToPageLength(1.0))
            if (obj != null) text = "Draw shape"
        }
        page.objects.add(obj ?: StrokeObject.fromPagePoints(pts, s.ink))
        host.document.commit(page, text)
        host.updateOverlay()
    }

    override fun deactivate() {
        for (id in live.keys.toList()) finish(id)
    }

    override fun pageChanged() = live.clear()

    override fun paintOverlay(c: Canvas) {
        for (s in live.values) {
            if (s.magic) MagicHighlightLayer.paintStroke(c, s.points, s.ink, 1.0)
            else StrokeObject.paintPoints(c, s.points, s.ink)
        }
    }

    override fun paintViewOverlay(c: Canvas) {
        for (s in live.values) {
            if (s.constraint?.kind != EdgeConstraint.Kind.Line || s.points.size < 2) continue
            val cs = host.document.coordinatesFor(host.page)
            val len = cs.mathDistance(s.points.first().pos, s.points.last().pos)
            val anchor = host.view.pageToView(s.points.last().pos) + Pt(0.0, -host.theme.dpd(40.0))
            var text = Geom.formatNumber(len, 1) + " " + cs.unitLabel
            if (cs.usesScale) text += " = " + cs.formatLength(len)
            Draw.valueLabel(c, anchor, text, Color.rgb(255, 224, 130), 0.0, host.theme.dpd(18.0))
        }
    }
}
