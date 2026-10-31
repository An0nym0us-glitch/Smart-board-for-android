package com.classboard.app.tools

import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.doc.CoordResolver
import com.classboard.app.geometry.ConstructKind
import com.classboard.app.geometry.GeometryObject
import com.classboard.app.geometry.MeasureKind
import com.classboard.app.geometry.MeasurementObject
import com.classboard.app.input.PointerEvent
import com.classboard.app.render.Draw

/** Live distance, angle, slope and area measurements. */
class MeasureTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Measure
    private val points = ArrayList<Pt>()
    private var pointer = -1
    private var current = Pt.ZERO
    private var snapped = false
    private var lastTap = 0L

    private fun snap(p: Pt) { val (q, s) = Snap.snapPoint(host, p); current = q; snapped = s }

    private fun reset() { points.clear(); pointer = -1; host.updateOverlay() }

    override fun pointerDown(e: PointerEvent) {
        if (pointer != -1 || host.page == null) return
        pointer = e.pointerId
        snap(e.pagePos)
        val kind = host.settings.measureKind
        if (points.isEmpty() || kind == MeasureKind.Distance || kind == MeasureKind.Slope) {
            if (kind != MeasureKind.Area || points.isEmpty()) { points.clear(); points.add(current) }
        }
        host.updateOverlay()
    }

    override fun pointerMove(e: PointerEvent) {
        if (e.pointerId != pointer) return
        snap(e.pagePos)
        host.updateOverlay()
    }

    override fun hover(e: PointerEvent) {
        if (points.isEmpty()) return
        snap(e.pagePos)
        host.updateOverlay()
    }

    override fun pointerUp(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        snap(e.pagePos)
        val minLen = host.viewToPageLength(host.theme.dpd(10.0))
        when (host.settings.measureKind) {
            MeasureKind.Distance, MeasureKind.Slope ->
                if (points.isNotEmpty() && Geom.distance(points.first(), current) >= minLen) {
                    val a = points.first(); points.clear(); points.add(a); points.add(current); commit()
                } else reset()
            MeasureKind.Angle -> {
                if (points.size == 1) {
                    if (Geom.distance(points.first(), current) >= minLen) points.add(current) else reset()
                } else if (points.size == 2 && Geom.distance(points.first(), current) >= minLen) {
                    val v = points[0]; val a = points[1]
                    points.clear(); points.add(a); points.add(v); points.add(current)
                    commit()
                }
            }
            MeasureKind.Area -> {
                val now = SystemClock.uptimeMillis()
                val doubleTap = now - lastTap < 400
                lastTap = now
                if (points.size >= 3 && (Geom.distance(current, points.first()) < host.viewToPageLength(host.theme.dpd(22.0)) || doubleTap)) commit()
                else if (points.isEmpty() || Geom.distance(points.last(), current) >= minLen) points.add(current)
            }
        }
        host.updateOverlay()
    }

    override fun pointerCancel(e: PointerEvent) { if (e.pointerId == pointer) reset() }

    private fun commit() {
        val page = host.page
        if (page != null && points.size >= 2) {
            page.objects.add(MeasurementObject.create(host.settings.measureKind, ArrayList(points), COLOR))
            host.document.commit(page, "Measure")
        }
        reset()
    }

    override fun deactivate() {
        if (host.settings.measureKind == MeasureKind.Area && points.size >= 3) commit()
        reset()
    }

    override fun pageChanged() { points.clear(); pointer = -1 }

    private fun previewPoints(): List<Pt> {
        if (points.isEmpty()) return emptyList()
        return when (host.settings.measureKind) {
            MeasureKind.Distance, MeasureKind.Slope -> listOf(points.first(), current)
            MeasureKind.Angle -> if (points.size == 1) listOf(current, points[0]) else listOf(points[1], points[0], current)
            MeasureKind.Area -> points + current
        }
    }

    override fun paintOverlay(c: Canvas) {
        val pts = previewPoints()
        if (pts.size < 2) return
        val global = host.document.coordinatesFor(host.page)
        val cs = CoordResolver.resolve(host.page, global, pts) ?: global
        MeasurementObject.paintPreview(c, host.settings.measureKind, pts, COLOR, cs)
    }

    override fun paintViewOverlay(c: Canvas) {
        if ((points.isEmpty() && pointer < 0) || !snapped) return
        Draw.circle(c, host.view.pageToView(current), host.theme.dpd(10.0), Draw.stroke(host.theme.accent, host.theme.dpd(2.0)))
    }

    companion object {
        val COLOR = Color.rgb(255, 213, 79)
    }
}

/** Points, segments, lines, rays and vectors with snapping. */
class ConstructTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Construct
    private var pointer = -1
    private var start = Pt.ZERO
    private var current = Pt.ZERO
    private var snapped = false
    private var hovering = false
    private var nameCounter = 0

    private fun colorFor(k: ConstructKind) = if (k == ConstructKind.Vector) Color.rgb(255, 171, 64) else Color.rgb(129, 212, 250)
    private fun snap(p: Pt) { val (q, s) = Snap.snapPoint(host, p); current = q; snapped = s }

    override fun pointerDown(e: PointerEvent) {
        if (pointer != -1 || host.page == null) return
        pointer = e.pointerId
        snap(e.pagePos)
        start = current
        host.updateOverlay()
    }

    override fun pointerMove(e: PointerEvent) {
        if (e.pointerId != pointer) return
        snap(e.pagePos)
        host.updateOverlay()
    }

    override fun hover(e: PointerEvent) {
        snap(e.pagePos)
        hovering = true
        host.updateOverlay()
    }

    override fun pointerUp(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        snap(e.pagePos)
        val page = host.page ?: return
        val kind = host.settings.constructKind
        val show = host.settings.showCoordinates
        val obj = if (kind == ConstructKind.Point) {
            GeometryObject.create(kind, listOf(current), colorFor(kind), show).also { it.name = ('A' + (nameCounter++ % 26)).toString() }
        } else if (Geom.distance(start, current) >= host.viewToPageLength(host.theme.dpd(12.0))) {
            GeometryObject.create(kind, listOf(start, current), colorFor(kind), show)
        } else null
        if (obj != null) {
            page.objects.add(obj)
            host.document.commit(page, "Construct")
        }
        host.updateOverlay()
    }

    override fun pointerCancel(e: PointerEvent) {
        if (e.pointerId == pointer) pointer = -1
        host.updateOverlay()
    }

    override fun pageChanged() { pointer = -1; nameCounter = 0 }

    override fun paintOverlay(c: Canvas) {
        if (pointer < 0) return
        val kind = host.settings.constructKind
        val pts = if (kind == ConstructKind.Point) listOf(current) else listOf(start, current)
        val global = host.document.coordinatesFor(host.page)
        val cs = CoordResolver.resolve(host.page, global, pts) ?: global
        GeometryObject.paintPreview(c, kind, pts, colorFor(kind), cs, host.settings.showCoordinates)
    }

    override fun paintViewOverlay(c: Canvas) {
        if (!snapped || (pointer < 0 && !hovering)) return
        Draw.circle(c, host.view.pageToView(current), host.theme.dpd(10.0), Draw.stroke(host.theme.accent, host.theme.dpd(2.0)))
    }
}
