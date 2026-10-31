package com.classboard.app.tools

import android.graphics.Canvas
import android.graphics.Color
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.Page
import com.classboard.app.doc.ShapeObject
import com.classboard.app.doc.StrokeObject
import com.classboard.app.input.PointerDevice
import com.classboard.app.input.PointerEvent
import com.classboard.app.render.Draw
import kotlin.math.ceil
import kotlin.math.max

/** Stroke, object and area eraser plus the palm / three-finger wipe (one undo step per session). */
class EraserTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Eraser

    private enum class Method { Stroke, Object, Area, Wipe }

    private var sessionPage: Page? = null
    private var sessionUsers = 0
    private var mode = EraserMode.Area
    private var changed = false
    private val pointers = HashMap<Int, Pt>()
    private var hoverView = Pt.ZERO
    private var hoverVisible = false
    private var palmActive = false
    private var palmCenter = Pt.ZERO

    private fun pageRadius() = host.viewToPageLength(host.theme.dpd(host.settings.eraserSize) * 0.5)

    private fun beginSession(m: EraserMode) {
        if (sessionPage != null) { sessionUsers++; return }
        val page = host.page ?: return
        sessionPage = page
        sessionUsers = 1
        mode = m
        changed = false
    }

    private fun methodFor(m: EraserMode) = when (m) {
        EraserMode.Stroke -> Method.Stroke
        EraserMode.Object -> Method.Object
        EraserMode.Area -> Method.Area
    }

    private fun remove(page: Page, o: BoardObject) {
        page.objects.remove(o)
        changed = true
    }

    private fun cutStroke(page: Page, s: StrokeObject, center: Pt, radius: Double): Boolean {
        val frags = s.eraseCircle(center, radius) ?: return false
        val idx = page.objects.indexOf(s)
        if (idx < 0) return false
        page.objects.removeAt(idx)
        page.objects.addAll(idx, frags)
        changed = true
        return true
    }

    private fun cutShape(page: Page, shape: ShapeObject, center: Pt, radius: Double): Boolean {
        if (!shape.hitTest(center, radius)) return false
        val b = shape.sceneBounds()
        if (max(b.width, b.height) <= 2.0 * radius) { remove(page, shape); return true }
        val pieces = shape.toInk()
        val idx = page.objects.indexOf(shape)
        page.objects.removeAt(idx)
        page.objects.addAll(idx, pieces)
        changed = true
        for (p in pieces) {
            if (p is StrokeObject) cutStroke(page, p, center, radius)
            else if (p.hitTest(center, radius)) remove(page, p)
        }
        return true
    }

    private fun eraseAt(center: Pt, radius: Double, method: Method) {
        val page = sessionPage ?: return
        if (page !== host.page) return
        val area = RectD.fromCenter(center, 2 * radius, 2 * radius)
        for (o in page.objectsIntersecting(area)) {
            if (!page.objects.contains(o)) continue
            when (method) {
                Method.Area -> when (o) {
                    is StrokeObject -> cutStroke(page, o, center, radius)
                    is ShapeObject -> cutShape(page, o, center, radius)
                    else -> {}
                }
                Method.Stroke -> if (o.type == ObjectType.Stroke && o.hitTest(center, radius)) remove(page, o)
                Method.Object -> if (o.hitTest(center, radius)) remove(page, o)
                Method.Wipe -> when (o) {
                    is StrokeObject -> cutStroke(page, o, center, radius)
                    is ShapeObject -> cutShape(page, o, center, radius)
                    else -> if (o.type != ObjectType.Image && o.hitTest(center, radius)) remove(page, o)
                }
            }
        }
        if (changed) { page.touch(); host.invalidateContent() }
    }

    private fun eraseAlong(from: Pt, to: Pt, radius: Double, method: Method) {
        val dist = Geom.distance(from, to)
        val step = max(radius * 0.5, host.viewToPageLength(1.0))
        val n = max(1, ceil(dist / step).toInt())
        for (i in 1..n) eraseAt(Geom.lerp(from, to, i.toDouble() / n), radius, method)
    }

    private fun commit() {
        val page = sessionPage ?: return
        sessionPage = null
        sessionUsers = 0
        if (changed) host.document.commit(page, "Erase")
        changed = false
    }

    override fun pointerDown(e: PointerEvent) {
        beginSession(host.settings.eraserMode)
        pointers[e.pointerId] = e.pagePos
        hoverView = e.viewPos
        hoverVisible = true
        eraseAt(e.pagePos, pageRadius(), methodFor(mode))
        host.updateOverlay()
    }

    override fun pointerMove(e: PointerEvent) {
        val last = pointers[e.pointerId] ?: return
        eraseAlong(last, e.pagePos, pageRadius(), methodFor(mode))
        pointers[e.pointerId] = e.pagePos
        hoverView = e.viewPos
        host.updateOverlay()
    }

    override fun pointerUp(e: PointerEvent) {
        if (pointers.remove(e.pointerId) == null) return
        if (--sessionUsers <= 0) commit()
        hoverVisible = e.device == PointerDevice.Mouse
        host.updateOverlay()
    }

    override fun pointerCancel(e: PointerEvent) = pointerUp(e)

    override fun hover(e: PointerEvent) {
        hoverView = e.viewPos
        hoverVisible = true
        host.updateOverlay()
    }

    override fun deactivate() {
        pointers.clear()
        commit()
        hoverVisible = false
    }

    override fun pageChanged() {
        pointers.clear()
        commit()
        palmActive = false
    }

    fun beginPalm(center: Pt, radius: Double) {
        if (palmActive) return
        beginSession(host.settings.eraserMode)
        if (sessionPage == null) return
        palmActive = true
        palmCenter = center
        eraseAt(center, radius, Method.Wipe)
    }

    fun movePalm(center: Pt, radius: Double) {
        if (!palmActive) return
        eraseAlong(palmCenter, center, radius, Method.Wipe)
        palmCenter = center
    }

    fun endPalm() {
        if (!palmActive) return
        palmActive = false
        if (--sessionUsers <= 0) commit()
    }

    override fun paintViewOverlay(c: Canvas) {
        if (!hoverVisible) return
        val r = host.theme.dpd(host.settings.eraserSize) * 0.5
        Draw.circle(c, hoverView, r, Draw.stroke(Color.argb(120, 0, 0, 0), host.theme.dpd(3.0)))
        Draw.circle(c, hoverView, r, Draw.fill(Color.argb(if (pointers.isEmpty()) 18 else 40, 255, 255, 255)))
        Draw.circle(c, hoverView, r, Draw.stroke(Color.argb(220, 255, 255, 255), host.theme.dpd(1.5)))
    }
}

