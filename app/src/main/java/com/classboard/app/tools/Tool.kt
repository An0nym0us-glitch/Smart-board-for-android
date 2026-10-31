package com.classboard.app.tools

import android.graphics.Canvas
import com.classboard.app.core.Pt
import com.classboard.app.doc.Document
import com.classboard.app.doc.InkStyle
import com.classboard.app.doc.Page
import com.classboard.app.doc.ShapeKind
import com.classboard.app.doc.ShapeStyle
import com.classboard.app.doc.StrokeStyle
import com.classboard.app.doc.TextFormat
import com.classboard.app.doc.alphaOf
import com.classboard.app.doc.withAlpha
import com.classboard.app.geometry.ConstructKind
import com.classboard.app.geometry.InstrumentLayer
import com.classboard.app.geometry.MeasureKind
import com.classboard.app.input.GestureEvent
import com.classboard.app.input.PointerEvent
import com.classboard.app.ui.Theme

enum class ToolId { Pen, Eraser, Select, Shape, Text, Measure, Construct, Graph }

/** Page <-> view mapping: view = page * zoom + offset. */
class ViewTransform(var zoom: Double = 1.0, var offset: Pt = Pt.ZERO) {
    fun pageToView(p: Pt) = p * zoom + offset
    fun viewToPage(v: Pt) = (v - offset) / zoom
    fun copy() = ViewTransform(zoom, offset)

    fun zoomAt(viewPos: Pt, factor: Double) {
        val page = viewToPage(viewPos)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = viewPos - page * zoom
    }

    fun panBy(d: Pt) { offset += d }

    companion object {
        const val MIN_ZOOM = 0.05
        const val MAX_ZOOM = 12.0
    }
}

/** Services the canvas offers to tools. */
interface ToolHost {
    val document: Document
    val page: Page?
    val view: ViewTransform
    val selection: SelectionModel
    val settings: ToolSettings
    val theme: Theme
    val instruments: InstrumentLayer
    val magicHighlights: MagicHighlightLayer
    fun updateOverlay()
    /** Live page content changed without a commit (eraser, drags): re-render the cache. */
    fun invalidateContent()
    fun setObjectHidden(id: String, hidden: Boolean)
    fun requestEdit(id: String)
    fun viewToPageLength(viewPixels: Double): Double
    /** Text editing at a page point (text tool). */
    fun beginTextEdit(objectId: String?, pagePos: Pt)
    fun toast(text: String)
}

abstract class Tool(protected val host: ToolHost) {
    abstract val id: ToolId
    open fun activate() {}
    open fun deactivate() {}
    abstract fun pointerDown(e: PointerEvent)
    abstract fun pointerMove(e: PointerEvent)
    abstract fun pointerUp(e: PointerEvent)
    abstract fun pointerCancel(e: PointerEvent)
    open fun hover(e: PointerEvent) {}
    /** Canvas in page coordinates. */
    open fun paintOverlay(c: Canvas) {}
    /** Canvas in view coordinates. */
    open fun paintViewOverlay(c: Canvas) {}
    open fun gesture(e: GestureEvent, pageCentroid: Pt): Boolean = false
    open fun pageChanged() {}
}

enum class EraserMode { Stroke, Object, Area }
enum class SelectMode { Rectangle, Lasso }
enum class ShapeFill { None, Tint, Solid }

/** Shared settings of the drawing tools, persisted by AppSettings. */
class ToolSettings {
    val listeners = ArrayList<() -> Unit>()
    private fun changed() = listeners.toList().forEach { it() }

    var penColor = 0xFFF5F5F0.toInt()
        set(v) { field = v; changed() }
    var penStyle = StrokeStyle.Pen
        set(v) { field = v; magicHighlighter = false; changed() }
    var magicHighlighter = false
        set(v) { field = v; changed() }
    var basePenWidth = 4.0
    var highlighterWidth = 22.0
    var penWidth: Double
        get() = if (usesHighlighterWidth) highlighterWidth else basePenWidth
        set(v) { if (usesHighlighterWidth) highlighterWidth = v.coerceIn(1.0, 80.0) else basePenWidth = v.coerceIn(0.5, 60.0); changed() }
    var pressureEnabled = true
        set(v) { field = v; changed() }
    var smoothing = 0.45
    var shapeRecognition = false
        set(v) { field = v; changed() }
    val usesHighlighterWidth get() = magicHighlighter || penStyle == StrokeStyle.Highlighter
    val palette = intArrayOf(
        0xFFF5F5F0.toInt(), 0xFFFFD54F.toInt(), 0xFFFF8A65.toInt(), 0xFFEF5350.toInt(), 0xFFF48FB1.toInt(), 0xFFBA68C8.toInt(),
        0xFF64B5F6.toInt(), 0xFF4DD0E1.toInt(), 0xFF81C784.toInt(), 0xFFC5E1A5.toInt(), 0xFFA1887F.toInt(), 0xFF212121.toInt(),
    )

    fun ink() = InkStyle(penColor, penWidth, if (magicHighlighter) StrokeStyle.Highlighter else penStyle, pressureEnabled && penStyle == StrokeStyle.Pen && !magicHighlighter)

    var eraserMode = EraserMode.Area
        set(v) { field = v; changed() }
    var eraserSize = 48.0
        set(v) { field = v.coerceIn(16.0, 200.0); changed() }
    var selectMode = SelectMode.Rectangle
        set(v) { field = v; changed() }
    var multiSelect = false
        set(v) { field = v; changed() }
    var shapeKind = ShapeKind.Rectangle
        set(v) { field = v; changed() }
    var shapeFill = ShapeFill.None
        set(v) { field = v; changed() }
    var polygonSides = 5
        set(v) { field = v.coerceIn(3, 12); changed() }

    fun shapeStyle(): ShapeStyle {
        val fill = when (shapeFill) {
            ShapeFill.None -> 0
            ShapeFill.Tint -> withAlpha(penColor, 60)
            ShapeFill.Solid -> withAlpha(penColor, 255)
        }
        return ShapeStyle(penColor, basePenWidth.coerceAtLeast(1.0), fill, penStyle == StrokeStyle.Dashed || penStyle == StrokeStyle.Dotted)
    }

    var textFormat = TextFormat()
        set(v) { field = v; changed() }
    var measureKind = MeasureKind.Distance
        set(v) { field = v; changed() }
    var constructKind = ConstructKind.Point
        set(v) { field = v; changed() }
    var showCoordinates = true
        set(v) { field = v; changed() }
    var snapToGrid = true
        set(v) { field = v; changed() }

    fun notifyChanged() = changed()

    @Suppress("unused")
    private fun unusedAlpha(c: Int) = alphaOf(c)
}

/** The selected objects on the current page (in selection order). */
class SelectionModel {
    val ids = ArrayList<String>()
    val listeners = ArrayList<() -> Unit>()
    private fun changed() = listeners.toList().forEach { it() }

    val isEmpty get() = ids.isEmpty()
    val count get() = ids.size
    fun contains(id: String) = ids.contains(id)
    fun set(list: List<String>) { ids.clear(); ids.addAll(list.distinct()); changed() }
    fun setSingle(id: String) = set(listOf(id))
    fun add(id: String) { if (!ids.contains(id)) { ids.add(id); changed() } }
    fun toggle(id: String) { if (!ids.remove(id)) ids.add(id); changed() }
    fun remove(id: String) { if (ids.remove(id)) changed() }
    fun clear() { if (ids.isNotEmpty()) { ids.clear(); changed() } }
}

/** Owns the tools; each pointer belongs to the tool that received its Down event. */
class ToolController(private val host: ToolHost) {
    private val tools = LinkedHashMap<ToolId, Tool>()
    var activeId = ToolId.Pen
        private set
    private val owners = HashMap<Int, Tool>()
    val listeners = ArrayList<(ToolId) -> Unit>()

    fun register(t: Tool) { tools[t.id] = t }
    fun tool(id: ToolId) = tools[id]
    val active: Tool? get() = tools[activeId]

    fun setActive(id: ToolId) {
        if (id == activeId) return
        active?.deactivate()
        activeId = id
        active?.activate()
        listeners.toList().forEach { it(id) }
        host.updateOverlay()
    }

    fun pointerEvent(e: PointerEvent) {
        when (e.phase) {
            com.classboard.app.input.PointerPhase.Down -> {
                val t = if (e.device == com.classboard.app.input.PointerDevice.StylusEraser) tools[ToolId.Eraser] else active
                t ?: return
                owners[e.pointerId] = t
                t.pointerDown(e)
            }
            com.classboard.app.input.PointerPhase.Move -> owners[e.pointerId]?.pointerMove(e)
            com.classboard.app.input.PointerPhase.Up -> owners.remove(e.pointerId)?.pointerUp(e)
            com.classboard.app.input.PointerPhase.Cancel -> owners.remove(e.pointerId)?.pointerCancel(e)
            com.classboard.app.input.PointerPhase.Hover -> {
                val t = if (e.device == com.classboard.app.input.PointerDevice.StylusEraser) tools[ToolId.Eraser] else active
                t?.hover(e)
            }
        }
    }

    val hasActivePointers get() = owners.isNotEmpty()

    fun paintOverlay(c: Canvas) {
        val seen = HashSet<Tool>()
        active?.let { it.paintOverlay(c); seen.add(it) }
        for (t in owners.values) if (seen.add(t)) t.paintOverlay(c)
    }

    fun paintViewOverlay(c: Canvas) {
        val seen = HashSet<Tool>()
        active?.let { it.paintViewOverlay(c); seen.add(it) }
        for (t in owners.values) if (seen.add(t)) t.paintViewOverlay(c)
    }

    fun pageChanged() {
        owners.clear()
        tools.values.forEach { it.pageChanged() }
    }
}
