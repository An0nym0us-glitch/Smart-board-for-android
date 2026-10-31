package com.classboard.app.tools

import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.input.GestureEvent
import com.classboard.app.input.GesturePhase
import com.classboard.app.input.PointerEvent
import com.classboard.app.math.GraphObject
import kotlin.math.round

/** Pans (drag) and zooms (pinch) graphs; optionally taps points onto curves. */
class GraphTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Graph
    var pointMode = false
    private var pointer = -1
    private var pressPage = Pt.ZERO
    private var lastPage = Pt.ZERO
    private var moved = false
    private var graphId: String? = null
    var onGraphChanged: ((String) -> Unit)? = null

    private fun targetGraph(p: Pt): GraphObject? = host.page?.objects?.lastOrNull { it is GraphObject && it.hitTest(p, 0.0) } as? GraphObject
    private fun graph(): GraphObject? = graphId?.let { host.page?.objectById(it) as? GraphObject }

    private fun commit(text: String) {
        val page = host.page ?: return
        val id = graphId ?: return
        host.document.commit(page, text, listOf(id))
        onGraphChanged?.invoke(id)
    }

    override fun pointerDown(e: PointerEvent) {
        if (pointer != -1) return
        val g = targetGraph(e.pagePos) ?: return
        pointer = e.pointerId
        pressPage = e.pagePos
        lastPage = e.pagePos
        moved = false
        graphId = g.id
        host.selection.setSingle(g.id)
    }

    override fun pointerMove(e: PointerEvent) {
        if (e.pointerId != pointer) return
        if (!moved && Geom.distance(host.view.pageToView(e.pagePos), host.view.pageToView(pressPage)) < host.theme.dpd(6.0)) return
        moved = true
        val g = graph() ?: return
        g.panLocal(g.mapFromPage(e.pagePos) - g.mapFromPage(lastPage))
        lastPage = e.pagePos
        host.page?.touch()
        host.invalidateContent()
    }

    override fun pointerUp(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        if (moved) { commit("Pan graph"); return }
        val g = graph()
        if (g == null || !pointMode) { graphId?.let { onGraphChanged?.invoke(it) }; return }
        val local = g.mapFromPage(e.pagePos)
        val tol = host.viewToPageLength(host.theme.dpd(18.0))
        val existing = g.pointAt(local, tol)
        if (existing >= 0) g.removePoint(existing) else {
            var m = g.localToMath(local)
            var onCurve = false
            for (i in g.functions.indices) {
                if (!g.functions[i].visible) continue
                val y = g.evaluate(i, m.x)
                if (y.isFinite() && Geom.distance(g.mathToLocal(Pt(m.x, y)), local) <= tol) { m = Pt(m.x, y); onCurve = true; break }
            }
            if (!onCurve) {
                val r = Pt(round(m.x), round(m.y))
                if (Geom.distance(g.mathToLocal(r), local) <= tol) m = r
            }
            g.addPoint(m)
        }
        host.invalidateContent()
        commit(if (existing >= 0) "Remove point" else "Add point")
    }

    override fun pointerCancel(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        host.page?.let { host.document.revert(it) }
    }

    override fun gesture(e: GestureEvent, pageCentroid: Pt): Boolean {
        when (e.phase) {
            GesturePhase.Begin -> {
                val g = targetGraph(pageCentroid) ?: return false
                graphId = g.id
                host.selection.setSingle(g.id)
                return true
            }
            GesturePhase.Update -> {
                val g = graph() ?: return true
                val local = g.mapFromPage(pageCentroid)
                g.panLocal(e.panDelta / host.view.zoom)
                g.zoomAtLocal(local, e.scaleDelta)
                host.page?.touch()
                host.invalidateContent()
                return true
            }
            GesturePhase.End -> { commit("Zoom graph"); return true }
            GesturePhase.Cancel -> { host.page?.let { host.document.revert(it) }; return true }
        }
    }

    override fun deactivate() { pointer = -1 }
    override fun pageChanged() { pointer = -1; graphId = null }
}

/** Tap the board to type; tap a text box to edit it. The editor itself lives in the board view. */
class TextTool(host: ToolHost) : Tool(host) {
    override val id = ToolId.Text
    private var pointer = -1
    private var press = Pt.ZERO

    override fun pointerDown(e: PointerEvent) {
        if (pointer != -1) return
        pointer = e.pointerId
        press = e.pagePos
    }

    override fun pointerMove(e: PointerEvent) {}

    override fun pointerUp(e: PointerEvent) {
        if (e.pointerId != pointer) return
        pointer = -1
        val page = host.page ?: return
        val hit = page.objects.lastOrNull { it is com.classboard.app.doc.TextObject && it.hitTest(press, host.viewToPageLength(host.theme.dpd(6.0))) }
        host.beginTextEdit(hit?.id, press)
    }

    override fun pointerCancel(e: PointerEvent) { if (e.pointerId == pointer) pointer = -1 }
    override fun pageChanged() { pointer = -1 }
}
