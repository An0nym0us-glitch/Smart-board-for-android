package com.classboard.app.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.ResizeHint
import com.classboard.app.input.PointerEvent
import com.classboard.app.render.Draw
import kotlin.math.abs
import kotlin.math.max

/** Tap, rectangle and lasso selection; move, resize, rotate and point editing. */
class SelectTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Select

    private enum class Mode { None, Pending, Move, Resize, Rotate, ControlPoint, RubberBand, Lasso }

    private class Frame(
        val single: Boolean,
        val corners: List<Pt>,
        val rotateHandle: Pt,
        val canResize: Boolean,
        val canRotate: Boolean,
        val keepAspect: Boolean,
        val controlPoints: List<Pt>,
    )

    private var mode = Mode.None
    private var pointer = -1
    private var pressPage = Pt.ZERO
    private var pressView = Pt.ZERO
    private var pressedObject: String? = null
    private var pressedWasSelected = false
    private val originals = ArrayList<BoardObject>()
    private var handle = -1
    private var startBounds = RectD.EMPTY
    private var pivot = Pt.ZERO
    private var startAngle = 0.0
    private var band = RectD.EMPTY
    private val lasso = ArrayList<Pt>()
    private var lastTapTime = 0L
    private var lastTapView = Pt.ZERO
    private var lastTapObject: String? = null
    var onSelectionEdited: (() -> Unit)? = null

    fun selectedObjects(): List<BoardObject> {
        val page = host.page ?: return emptyList()
        return host.selection.ids.mapNotNull { page.objectById(it) }
    }

    fun selectionBounds(): RectD? {
        var r: RectD? = null
        for (o in selectedObjects()) r = r?.union(o.sceneBounds()) ?: o.sceneBounds()
        return r
    }

    private fun frame(): Frame? {
        val objs = selectedObjects()
        if (objs.isEmpty()) return null
        val view = host.view
        val f: Frame
        if (objs.size == 1) {
            val o = objs[0]
            var local = o.localBounds()
            val minExtent = host.viewToPageLength(host.theme.dpd(6.0))
            if (local.width < minExtent) local = RectD(local.left - minExtent / 2, local.top, local.right + minExtent / 2, local.bottom)
            if (local.height < minExtent) local = RectD(local.left, local.top - minExtent / 2, local.right, local.bottom + minExtent / 2)
            fun map(p: Pt) = view.pageToView(o.mapToPage(p))
            val corners = listOf(map(local.topLeft), map(local.topRight), map(local.bottomRight), map(local.bottomLeft))
            f = Frame(true, corners, rotHandle(corners), o.canResize(), o.canRotate(), o.keepAspectRatio(), o.controlPoints().map { map(it) })
        } else {
            val b = selectionBounds()!!
            val v = RectD.ofPoints(view.pageToView(b.topLeft), view.pageToView(b.bottomRight))
            val corners = listOf(v.topLeft, v.topRight, v.bottomRight, v.bottomLeft)
            f = Frame(false, corners, rotHandle(corners), true, objs.any { it.canRotate() }, true, emptyList())
        }
        return f
    }

    private fun rotHandle(c: List<Pt>): Pt {
        val topCenter = Geom.midpoint(c[0], c[1])
        val center = Geom.midpoint(c[0], c[2])
        var up = Geom.normalized(topCenter - center)
        if (up.length() < 0.5) up = Pt(0.0, -1.0)
        return topCenter + up * host.theme.dpd(40.0)
    }

    private fun resizeHandles(f: Frame): List<Pt> {
        val c = f.corners
        return listOf(c[0], Geom.midpoint(c[0], c[1]), c[1], Geom.midpoint(c[1], c[2]), c[2], Geom.midpoint(c[2], c[3]), c[3], Geom.midpoint(c[3], c[0]))
    }

    private fun hitResizeHandle(f: Frame, view: Pt): Int {
        if (!f.canResize) return -1
        val hit = host.theme.dpd(host.theme.handleHitSize) / 2
        val hs = resizeHandles(f)
        var best = -1
        var bestDist = hit
        for (i in hs.indices) {
            if ((f.keepAspect || !f.single) && i % 2 == 1) continue
            val d = Geom.distance(view, hs[i])
            if (d <= bestDist) { bestDist = d; best = i }
        }
        return best
    }

    override fun pointerDown(e: PointerEvent) {
        if (pointer != -1 || host.page == null) return
        pointer = e.pointerId
        pressPage = e.pagePos
        pressView = e.viewPos
        pressedObject = null
        pressedWasSelected = false
        mode = Mode.None
        val hit = host.theme.dpd(host.theme.handleHitSize) / 2
        val f = frame()
        if (f != null) {
            if (f.canRotate && Geom.distance(e.viewPos, f.rotateHandle) <= hit) { startDrag(Mode.Rotate, e); return }
            for (i in f.controlPoints.indices) if (Geom.distance(e.viewPos, f.controlPoints[i]) <= hit) { handle = i; startDrag(Mode.ControlPoint, e); return }
            val h = hitResizeHandle(f, e.viewPos)
            val centre = Geom.midpoint(f.corners[0], f.corners[2])
            val preferMove = h >= 0 && Geom.polygonContains(f.corners, e.viewPos) && Geom.distance(e.viewPos, centre) < Geom.distance(e.viewPos, resizeHandles(f)[h])
            if (h >= 0 && !preferMove) { handle = h; startDrag(Mode.Resize, e); return }
        }
        val additive = e.shift || host.settings.multiSelect
        val page = host.page!!
        val obj = page.topmostAt(e.pagePos, host.viewToPageLength(host.theme.dpd(8.0))) ?: page.topmostEnclosing(e.pagePos)
        if (obj != null) {
            pressedObject = obj.id
            pressedWasSelected = host.selection.contains(obj.id)
            if (!pressedWasSelected) { if (additive) host.selection.add(obj.id) else host.selection.setSingle(obj.id) }
            mode = Mode.Pending
            return
        }
        if (f != null && Geom.polygonContains(f.corners, e.viewPos)) { mode = Mode.Pending; return }
        if (!additive) host.selection.clear()
        mode = if (host.settings.selectMode == SelectMode.Lasso) Mode.Lasso else Mode.RubberBand
        band = RectD.ofPoints(e.pagePos, e.pagePos)
        lasso.clear()
        lasso.add(e.pagePos)
    }

    private fun startDrag(m: Mode, e: PointerEvent) {
        mode = m
        originals.clear()
        for (o in selectedObjects()) originals.add(o.clone())
        startBounds = selectionBounds() ?: RectD.EMPTY
        pivot = startBounds.center
        if (originals.size == 1) { val o = originals[0]; pivot = o.mapToPage(o.localBounds().center) }
        startAngle = Geom.angleDeg(e.pagePos - pivot)
    }

    private fun replaceLive(c: BoardObject) {
        val page = host.page ?: return
        val i = page.indexOf(c.id)
        if (i >= 0) page.objects[i] = c
        page.touch()
    }

    override fun pointerMove(e: PointerEvent) {
        if (e.pointerId != pointer) return
        when (mode) {
            Mode.Pending -> if (Geom.distance(e.viewPos, pressView) > host.theme.dpd(6.0)) { startDrag(Mode.Move, e); updateMove(e.pagePos) }
            Mode.Move -> updateMove(e.pagePos)
            Mode.Resize -> updateResize(e.pagePos, e.shift)
            Mode.Rotate -> updateRotate(e.pagePos)
            Mode.ControlPoint -> updateControlPoint(e.pagePos)
            Mode.RubberBand -> { band = RectD.ofPoints(pressPage, e.pagePos); host.updateOverlay() }
            Mode.Lasso -> {
                if (Geom.distance(host.view.pageToView(lasso.last()), e.viewPos) > 3.0) lasso.add(e.pagePos)
                host.updateOverlay()
            }
            Mode.None -> {}
        }
    }

    private fun updateMove(p: Pt) {
        val d = p - pressPage
        for (o in originals) { val c = o.clone(); c.position = o.position + d; replaceLive(c) }
        host.invalidateContent()
    }

    private fun updateResize(p: Pt, shift: Boolean) {
        if (originals.isEmpty()) return
        val h = handle
        if (originals.size == 1) {
            val orig = originals[0]
            val local = orig.localBounds()
            val lp = orig.mapFromPage(p)
            var l = local.left; var t = local.top; var r = local.right; var b = local.bottom
            when (h) {
                0 -> { l = lp.x; t = lp.y }
                1 -> t = lp.y
                2 -> { r = lp.x; t = lp.y }
                3 -> r = lp.x
                4 -> { r = lp.x; b = lp.y }
                5 -> b = lp.y
                6 -> { l = lp.x; b = lp.y }
                7 -> l = lp.x
            }
            var rect = RectD(l, t, r, b)
            val keep = orig.keepAspectRatio() || shift
            if (keep && h % 2 == 0 && local.width > 0.5 && local.height > 0.5) {
                val corners = listOf(local.topLeft, local.topRight, local.bottomRight, local.bottomLeft)
                val anchor = corners[((h / 2) + 2) % 4]
                val s = max(abs(lp.x - anchor.x) / local.width, abs(lp.y - anchor.y) / local.height)
                val dirX = if (h == 2 || h == 4) 1.0 else -1.0
                val dirY = if (h == 4 || h == 6) 1.0 else -1.0
                rect = RectD.ofPoints(anchor, anchor + Pt(dirX * local.width * s, dirY * local.height * s))
            }
            rect = rect.normalized()
            val hint = if (h == 3 || h == 7) ResizeHint.Horizontal else if (h == 1 || h == 5) ResizeHint.Vertical else ResizeHint.Corner
            val c = orig.clone()
            c.resizeTo(rect, hint)
            replaceLive(c)
        } else {
            val corners = listOf(startBounds.topLeft, startBounds.topRight, startBounds.bottomRight, startBounds.bottomLeft)
            val hs = corners[(h / 2) % 4]
            val anchor = corners[((h / 2) + 2) % 4]
            val d0 = hs - anchor
            val len2 = Geom.dot(d0, d0)
            if (len2 < 1e-6) return
            val s = max(0.05, Geom.dot(p - anchor, d0) / len2)
            for (o in originals) {
                val c = o.clone()
                if (o.canResize()) {
                    val lb = o.localBounds()
                    c.resizeTo(RectD(lb.left * s, lb.top * s, lb.right * s, lb.bottom * s))
                }
                c.position = anchor + (o.position - anchor) * s
                replaceLive(c)
            }
        }
        host.invalidateContent()
    }

    private fun updateRotate(p: Pt) {
        if (originals.isEmpty()) return
        var delta = Geom.angleDifference(startAngle, Geom.angleDeg(p - pivot))
        if (originals.size == 1) {
            val base = originals[0].rotation
            val snapped = Geom.snapAngle(Geom.normalizeDegrees(base + delta), 15.0, 3.0).first
            delta = Geom.angleDifference(base, snapped)
        } else delta = Geom.snapAngle(delta, 15.0, 3.0).first
        for (o in originals) {
            if (!o.canRotate()) continue
            val c = o.clone()
            c.rotation = o.rotation + delta
            c.position = pivot + Geom.rotated(o.position - pivot, delta)
            replaceLive(c)
        }
        host.invalidateContent()
    }

    private fun updateControlPoint(p: Pt) {
        if (originals.size != 1) return
        val c = originals[0].clone()
        c.moveControlPointTo(handle, p)
        replaceLive(c)
        host.invalidateContent()
    }

    private fun finishDrag() {
        val page = host.page
        if (page == null || originals.isEmpty()) { originals.clear(); return }
        val text = when (mode) {
            Mode.Move -> "Move"
            Mode.Resize -> "Resize"
            Mode.Rotate -> "Rotate"
            Mode.ControlPoint -> "Edit points"
            else -> "Edit"
        }
        host.document.commit(page, text, originals.map { it.id })
        originals.clear()
        onSelectionEdited?.invoke()
    }

    private fun cancelDrag() {
        for (o in originals) replaceLive(o.clone())
        originals.clear()
        host.invalidateContent()
    }

    private fun finishBand(e: PointerEvent) {
        val page = host.page ?: return
        val additive = e.shift || host.settings.multiSelect
        val poly: List<Pt>
        if (mode == Mode.RubberBand) {
            if (band.width * host.view.zoom < 4 && band.height * host.view.zoom < 4) return
            poly = band.corners()
        } else {
            if (lasso.size < 3) return
            poly = lasso.toList()
        }
        val ids = if (additive) ArrayList(host.selection.ids) else ArrayList()
        for (o in page.objects) {
            val inside = (mode == Mode.RubberBand && band.contains(o.sceneBounds())) || o.isInsidePolygon(poly)
            if (inside && !ids.contains(o.id)) ids.add(o.id)
        }
        host.selection.set(ids)
    }

    private fun handleTap(e: PointerEvent) {
        val id = pressedObject ?: return
        val additive = e.shift || host.settings.multiSelect
        if (additive) { if (pressedWasSelected) host.selection.remove(id) } else host.selection.setSingle(id)
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastTapTime < 450 && Geom.distance(e.viewPos, lastTapView) < host.theme.dpd(28.0) && lastTapObject == id
        if (doubleTap) {
            lastTapTime = 0
            if (host.page?.objectById(id) != null) host.requestEdit(id)
            return
        }
        lastTapTime = now
        lastTapView = e.viewPos
        lastTapObject = id
    }

    override fun pointerUp(e: PointerEvent) {
        if (e.pointerId != pointer) return
        when (mode) {
            Mode.Pending -> handleTap(e)
            Mode.Move, Mode.Resize, Mode.Rotate, Mode.ControlPoint -> finishDrag()
            Mode.RubberBand, Mode.Lasso -> finishBand(e)
            Mode.None -> {}
        }
        mode = Mode.None
        pointer = -1
        lasso.clear()
        host.updateOverlay()
    }

    override fun pointerCancel(e: PointerEvent) {
        if (e.pointerId != pointer) return
        cancelDrag()
        mode = Mode.None
        pointer = -1
        lasso.clear()
        host.updateOverlay()
    }

    fun nudge(d: Pt) {
        val page = host.page ?: return
        val objs = selectedObjects()
        if (objs.isEmpty()) return
        for (o in objs) o.position = o.position + d
        host.document.commit(page, "Move", objs.map { it.id })
    }

    override fun deactivate() {
        if (mode == Mode.Move || mode == Mode.Resize || mode == Mode.Rotate || mode == Mode.ControlPoint) finishDrag()
        mode = Mode.None
        pointer = -1
    }

    override fun pageChanged() {
        originals.clear()
        mode = Mode.None
        pointer = -1
        lasso.clear()
    }

    val isDragging get() = mode == Mode.Move || mode == Mode.Resize || mode == Mode.Rotate || mode == Mode.ControlPoint

    override fun paintViewOverlay(c: Canvas) {
        val t = host.theme
        val sel = t.selection
        if (mode == Mode.RubberBand) {
            val r = RectD.ofPoints(host.view.pageToView(band.topLeft), host.view.pageToView(band.bottomRight)).toRectF()
            c.drawRect(r, Draw.fill(t.selectionFill))
            val p = Draw.stroke(sel, t.dpd(1.5)); p.pathEffect = DashPathEffect(floatArrayOf(t.dp(6.0), t.dp(4.0)), 0f)
            c.drawRect(r, p)
        } else if (mode == Mode.Lasso && lasso.size > 1) {
            val path = Path()
            val v = lasso.map { host.view.pageToView(it) }
            path.moveTo(v[0].x.toFloat(), v[0].y.toFloat())
            v.drop(1).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
            path.close()
            c.drawPath(path, Draw.fill(t.selectionFill))
            val p = Draw.stroke(sel, t.dpd(1.5)); p.pathEffect = DashPathEffect(floatArrayOf(t.dp(6.0), t.dp(4.0)), 0f)
            c.drawPath(path, p)
        }
        val f = frame() ?: return
        val objs = selectedObjects()
        if (objs.size > 1) {
            val p = Draw.stroke(Color.argb(110, Color.red(sel), Color.green(sel), Color.blue(sel)), t.dpd(1.0))
            for (o in objs) {
                val b = o.sceneBounds()
                c.drawRect(RectD.ofPoints(host.view.pageToView(b.topLeft), host.view.pageToView(b.bottomRight)).toRectF(), p)
            }
        }
        val outline = Path()
        outline.moveTo(f.corners[0].x.toFloat(), f.corners[0].y.toFloat())
        for (k in 1..3) outline.lineTo(f.corners[k].x.toFloat(), f.corners[k].y.toFloat())
        outline.close()
        c.drawPath(outline, Draw.stroke(sel, t.dpd(1.6)))
        if (mode == Mode.Move) return
        val hs = t.dpd(t.handleSize) / 2
        if (f.canRotate) {
            val topCenter = Geom.midpoint(f.corners[0], f.corners[1])
            Draw.line(c, topCenter, f.rotateHandle, Draw.stroke(sel, t.dpd(1.5)))
            Draw.circle(c, f.rotateHandle, hs * 1.3, Draw.fill(t.handle))
            Draw.circle(c, f.rotateHandle, hs * 1.3, Draw.stroke(sel, t.dpd(1.5)))
            val ar = hs * 0.65
            c.drawArc(RectF((f.rotateHandle.x - ar).toFloat(), (f.rotateHandle.y - ar).toFloat(), (f.rotateHandle.x + ar).toFloat(), (f.rotateHandle.y + ar).toFloat()), -40f, -280f, false, Draw.stroke(sel, t.dpd(1.8)))
        }
        if (f.canResize) {
            val handles = resizeHandles(f)
            for (i in handles.indices) {
                if ((f.keepAspect || !f.single) && i % 2 == 1) continue
                val r = RectF((handles[i].x - hs).toFloat(), (handles[i].y - hs).toFloat(), (handles[i].x + hs).toFloat(), (handles[i].y + hs).toFloat())
                c.drawRoundRect(r, (hs * 0.35).toFloat(), (hs * 0.35).toFloat(), Draw.fill(t.handle))
                c.drawRoundRect(r, (hs * 0.35).toFloat(), (hs * 0.35).toFloat(), Draw.stroke(sel, t.dpd(1.6)))
            }
        }
        for (cp in f.controlPoints) {
            Draw.circle(c, cp, hs * 1.15, Draw.fill(sel))
            Draw.circle(c, cp, hs * 1.15, Draw.stroke(t.handle, t.dpd(2.0)))
        }
    }

    /** View-space rectangle around the selection frame (for the floating action bar). */
    fun selectionViewBounds(): RectD? {
        val f = frame() ?: return null
        var r = Geom.boundingRect(f.corners)
        if (f.canRotate) r = r.union(RectD.fromCenter(f.rotateHandle, 1.0, 1.0))
        return r
    }
}
