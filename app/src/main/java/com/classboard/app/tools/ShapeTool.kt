package com.classboard.app.tools

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.ShapeKind
import com.classboard.app.doc.ShapeObject
import com.classboard.app.doc.alphaOf
import com.classboard.app.input.PointerEvent
import com.classboard.app.render.Draw
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** Drag (or tap) to place shapes; tap corners for a free polygon. */
class ShapeTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Shape

    private var pointer = -1
    private var start = Pt.ZERO
    private var current = Pt.ZERO
    private var shift = false
    private val polygon = ArrayList<Pt>()
    private var lastTap = 0L

    private fun dragRect(): RectD {
        var end = current
        if (shift || host.settings.shapeKind == ShapeKind.Circle) {
            val d = end - start
            val s = max(abs(d.x), abs(d.y))
            end = start + Pt(if (d.x < 0) -s else s, if (d.y < 0) -s else s)
        }
        return RectD.ofPoints(start, end)
    }

    private fun lineEnd(): Pt {
        if (!shift) return current
        val d = current - start
        val angle = round(Geom.angleDeg(d) / 45.0) * 45.0
        return start + Geom.rotated(Pt(d.length(), 0.0), angle)
    }

    override fun pointerDown(e: PointerEvent) {
        if (pointer != -1 || host.page == null) return
        pointer = e.pointerId
        start = e.pagePos
        current = e.pagePos
        shift = e.shift
    }

    override fun pointerMove(e: PointerEvent) {
        if (e.pointerId != pointer) return
        current = e.pagePos
        shift = e.shift
        host.updateOverlay()
    }

    override fun pointerUp(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        current = e.pagePos
        val page = host.page ?: return
        val s = host.settings
        val kind = s.shapeKind
        val style = s.shapeStyle()
        val tap = Geom.distance(start, current) < host.viewToPageLength(host.theme.dpd(10.0))
        if (kind == ShapeKind.FreePolygon) {
            val now = SystemClock.uptimeMillis()
            val doubleTap = now - lastTap < 400
            lastTap = now
            if (polygon.size >= 3 && (Geom.distance(current, polygon.first()) < host.viewToPageLength(host.theme.dpd(22.0)) || doubleTap)) finishPolygon()
            else polygon.add(current)
            host.updateOverlay()
            return
        }
        val shape = if (kind.isLine) {
            var a = start
            var b = lineEnd()
            if (tap) { a = start - Pt(90.0, 0.0); b = start + Pt(90.0, 0.0) }
            ShapeObject.createLine(kind, a, b, style)
        } else {
            var r = dragRect()
            if (tap) r = if (kind == ShapeKind.Circle) RectD.fromCenter(start, 160.0, 160.0) else RectD.fromCenter(start, 200.0, 150.0)
            ShapeObject.createBox(kind, r, style, s.polygonSides)
        }
        page.objects.add(shape)
        host.document.commit(page, "Draw ${kind.label.lowercase()}")
        host.updateOverlay()
    }

    fun finishPolygon() {
        val page = host.page
        if (page != null && polygon.size >= 3) {
            page.objects.add(ShapeObject.createPolygon(ArrayList(polygon), host.settings.shapeStyle()))
            host.document.commit(page, "Draw polygon")
        }
        polygon.clear()
        host.updateOverlay()
    }

    override fun pointerCancel(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        host.updateOverlay()
    }

    override fun deactivate() {
        if (polygon.size >= 3) finishPolygon()
        polygon.clear()
        pointer = -1
    }

    override fun pageChanged() { polygon.clear(); pointer = -1 }

    override fun paintOverlay(c: Canvas) {
        val s = host.settings
        val style = s.shapeStyle()
        val pen = ShapeObject.strokePaint(style)
        val fill = if (alphaOf(style.fill) > 0) Draw.fill(style.fill) else null
        if (polygon.isNotEmpty()) {
            val pts = polygon + current
            val path = ShapeObject.polyPath(pts, false)
            fill?.let { c.drawPath(path, it) }
            c.drawPath(path, pen)
            val r = host.viewToPageLength(host.theme.dpd(7.0))
            for (v in polygon) Draw.circle(c, v, r, Draw.fill(style.stroke))
            if (polygon.size >= 3) {
                val hint = Draw.stroke(host.theme.accent, host.viewToPageLength(2.0))
                hint.pathEffect = DashPathEffect(floatArrayOf(host.viewToPageLength(6.0).toFloat(), host.viewToPageLength(4.0).toFloat()), 0f)
                Draw.circle(c, polygon.first(), host.viewToPageLength(host.theme.dpd(22.0)), hint)
            }
        } else if (pointer >= 0) {
            val kind = s.shapeKind
            if (kind.isLine) {
                ShapeObject.paintArrow(c, start, lineEnd(), pen, kind == ShapeKind.DoubleArrow, kind != ShapeKind.Line)
            } else if (kind != ShapeKind.FreePolygon) {
                val r = dragRect()
                c.save()
                c.translate(r.center.x.toFloat(), r.center.y.toFloat())
                val path: Path = ShapeObject.outlinePathFor(kind, r.width, r.height, s.polygonSides)
                fill?.let { c.drawPath(path, it) }
                c.drawPath(path, pen)
                c.restore()
            }
        }
        pen.style = Paint.Style.STROKE
    }
}
