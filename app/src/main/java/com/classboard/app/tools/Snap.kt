package com.classboard.app.tools

import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.CoordResolver
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.TemplateKind
import kotlin.math.round

/** Snapping: to construction points first, then to whole coordinates (grid / graph). */
object Snap {
    fun snapPoint(host: ToolHost, pagePos: Pt, exclude: BoardObject? = null): Pair<Pt, Boolean> {
        val page = host.page ?: return Pair(pagePos, false)
        val tol = host.viewToPageLength(host.theme.dpd(16.0))
        var best = tol
        var result = pagePos
        var found = false
        for (o in page.objectsIntersecting(RectD.fromCenter(pagePos, 0.0, 0.0).inflated(tol * 2 + 80))) {
            if (o === exclude) continue
            val construction = o.type == ObjectType.Geometry || o.type == ObjectType.Measurement || (o.type == ObjectType.Shape && o.controlPoints().isNotEmpty())
            if (!construction) continue
            for (l in o.controlPoints()) {
                val p = o.mapToPage(l)
                val d = Geom.distance(p, pagePos)
                if (d <= best) { best = d; result = p; found = true }
            }
        }
        if (found) return Pair(result, true)
        if (!host.settings.snapToGrid) return Pair(pagePos, false)
        val global = host.document.coordinatesFor(page)
        val cs = CoordResolver.resolve(page, global, listOf(pagePos), exclude) ?: return Pair(pagePos, false)
        val kind = page.background.kind
        val gridBackground = kind == TemplateKind.Grid || kind == TemplateKind.GraphPaper || kind == TemplateKind.CoordinatePlane || kind == TemplateKind.Dots
        if (cs === global && !gridBackground) return Pair(pagePos, false)
        val m = cs.toMath(pagePos)
        val unitPx = Geom.distance(cs.toPage(Pt(0.0, 0.0)), cs.toPage(Pt(1.0, 0.0))) * host.view.zoom
        val step = if (unitPx > 120) 0.5 else 1.0
        val p = cs.toPage(Pt(round(m.x / step) * step, round(m.y / step) * step))
        return if (Geom.distance(p, pagePos) <= tol) Pair(p, true) else Pair(pagePos, false)
    }
}
