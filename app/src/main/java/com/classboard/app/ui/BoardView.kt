package com.classboard.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.Document
import com.classboard.app.doc.DocumentListener
import com.classboard.app.doc.Page
import com.classboard.app.geometry.Instrument
import com.classboard.app.geometry.InstrumentLayer
import com.classboard.app.input.GestureEvent
import com.classboard.app.input.GesturePhase
import com.classboard.app.input.GestureType
import com.classboard.app.input.InputManager
import com.classboard.app.input.InputSink
import com.classboard.app.input.PointerDevice
import com.classboard.app.input.PointerEvent
import com.classboard.app.input.PointerPhase
import com.classboard.app.render.Draw
import com.classboard.app.render.PageRenderer
import com.classboard.app.tools.ConstructTool
import com.classboard.app.tools.EraserTool
import com.classboard.app.tools.GraphTool
import com.classboard.app.tools.MagicHighlightLayer
import com.classboard.app.tools.MeasureTool
import com.classboard.app.tools.PenTool
import com.classboard.app.tools.SelectTool
import com.classboard.app.tools.SelectionModel
import com.classboard.app.tools.ShapeTool
import com.classboard.app.tools.TextTool
import com.classboard.app.tools.ToolController
import com.classboard.app.tools.ToolHost
import com.classboard.app.tools.ToolId
import com.classboard.app.tools.ToolSettings
import com.classboard.app.tools.ViewTransform
import kotlin.math.abs
import kotlin.math.min

/**
 * The board. Renders the current page through a cached bitmap, draws tool and instrument
 * overlays and turns touch / pen / mouse input into tool actions and gestures.
 */
@SuppressLint("ViewConstructor")
class BoardView(
    context: Context,
    override val document: Document,
    override val settings: ToolSettings,
    override val theme: Theme,
) : View(context), ToolHost, InputSink, DocumentListener {

    override val view = ViewTransform()
    override val selection = SelectionModel()
    override val instruments = InstrumentLayer()
    override val magicHighlights = MagicHighlightLayer()
    val tools = ToolController(this)
    val input = InputManager(this)
    private val eraser = EraserTool(this)
    val selectTool = SelectTool(this)
    val graphTool = GraphTool(this)

    override val page: Page? get() = document.page(document.currentPageIndex)

    private var cache: Bitmap? = null
    private var cacheView = ViewTransform()
    private var cacheDirty = true
    private var cacheRevision = -1L
    private var cachePageId: String? = null
    private var interactive = false
    private val hidden = HashSet<String>()
    private val pageViews = HashMap<String, ViewTransform>()
    private var viewPage: String? = null
    private var viewInitialised = false
    var showPageFrame = false
        set(v) { field = v; invalidateContent() }

    private enum class GestureTarget { None, Canvas, Instrument, Tool, Palm }
    private var gestureTarget = GestureTarget.None
    private var gestureInstrument: Instrument? = null
    private var gestureStartView = ViewTransform()
    private var palmCenter = Pt.ZERO
    private var palmRadius = 0.0
    private val instrumentPointers = HashSet<Int>()
    var lastPressPage = Pt.ZERO
        private set

    // Callbacks to the activity.
    var onViewChanged: (() -> Unit)? = null
    var onEditRequested: ((String) -> Unit)? = null
    var onStatusText: ((String) -> Unit)? = null
    var onInteractionFinished: (() -> Unit)? = null
    var onTextEdit: ((String?, Pt) -> Unit)? = null
    var onToast: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { finishInteractive() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        tools.register(PenTool(this))
        tools.register(eraser)
        tools.register(selectTool)
        tools.register(ShapeTool(this))
        tools.register(TextTool(this))
        tools.register(MeasureTool(this))
        tools.register(ConstructTool(this))
        tools.register(graphTool)
        document.addListener(this)
        instruments.onChanged = { postInvalidateOnAnimation() }
        instruments.onStatus = { onStatusText?.invoke(it) }
        selection.listeners.add { invalidate() }
        magicHighlights.onRepaint = { postInvalidateOnAnimation() }
        settings.listeners.add { instruments.setInk(settings.ink()); invalidate() }
        instruments.setUnits(document.coordinates.pxPerUnit(), document.coordinates.unitLabel)
        instruments.setInk(settings.ink())
        input.pixelsPerMm = resources.displayMetrics.xdpi / 25.4
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ---------------------------------------------------------------------------- ToolHost

    override fun updateOverlay() = postInvalidateOnAnimation()

    override fun invalidateContent() {
        cacheDirty = true
        postInvalidateOnAnimation()
    }

    override fun setObjectHidden(id: String, hidden: Boolean) {
        if (hidden) this.hidden.add(id) else this.hidden.remove(id)
        invalidateContent()
    }

    override fun requestEdit(id: String) { onEditRequested?.invoke(id) }
    override fun viewToPageLength(viewPixels: Double) = viewPixels / view.zoom
    override fun beginTextEdit(objectId: String?, pagePos: Pt) { onTextEdit?.invoke(objectId, pagePos) }
    override fun toast(text: String) { onToast?.invoke(text) }

    // ------------------------------------------------------------------------------- view

    fun fitPage() {
        val p = page ?: return
        if (width < 10 || height < 10) return
        fit(p.frameRect, theme.dpd(20.0))
        viewInitialised = true
        viewUpdated(true)
    }

    private fun fit(r: RectD, margin: Double) {
        val availW = width - 2 * margin - insetsLeft - insetsRight
        val availH = height - 2 * margin - insetsBottom - insetsTop
        val z = min(availW / r.width, availH / r.height).coerceIn(ViewTransform.MIN_ZOOM, ViewTransform.MAX_ZOOM)
        view.zoom = z
        val cx = insetsLeft + (width - insetsLeft - insetsRight) / 2.0
        val cy = insetsTop + (height - insetsTop - insetsBottom) / 2.0
        view.offset = Pt(cx - r.center.x * z, cy - r.center.y * z)
    }

    /** Space covered by the ribbon (so "fit" keeps the page visible above it). */
    var insetsBottom = 0.0
    var insetsTop = 0.0
    var insetsLeft = 0.0
    var insetsRight = 0.0

    fun fitWidth() {
        val p = page ?: return
        if (width < 10) return
        val f = p.frameRect
        val margin = theme.dpd(20.0)
        val z = ((width - 2 * margin) / f.width).coerceIn(ViewTransform.MIN_ZOOM, ViewTransform.MAX_ZOOM)
        view.zoom = z
        view.offset = Pt((width - f.width * z) / 2.0 - f.left * z, margin + insetsTop - f.top * z)
        viewInitialised = true
        viewUpdated(true)
    }

    fun setZoom(z0: Double) {
        val z = z0.coerceIn(ViewTransform.MIN_ZOOM, ViewTransform.MAX_ZOOM)
        view.zoomAt(Pt(width / 2.0, (height - insetsBottom) / 2.0), z / view.zoom)
        viewUpdated(true)
    }

    fun zoomStep(direction: Int) {
        val current = view.zoom
        if (direction > 0) {
            ZOOM_PRESETS.firstOrNull { it > current + 1e-6 }?.let { setZoom(it); return }
            setZoom(current * 1.25)
        } else {
            ZOOM_PRESETS.lastOrNull { it < current - 1e-6 }?.let { setZoom(it); return }
            setZoom(current * 0.8)
        }
    }

    fun viewCenterInPage(): Pt = view.viewToPage(Pt(width / 2.0, (height - insetsBottom) / 2.0))

    fun ensureVisible(r: RectD) {
        val tl = view.pageToView(r.topLeft)
        val br = view.pageToView(r.bottomRight)
        val v = RectD(tl.x, tl.y, br.x, br.y)
        val area = RectD(40.0, 40.0 + insetsTop, width - 40.0, height - 40.0 - insetsBottom)
        if (area.contains(v)) return
        var dx = 0.0
        var dy = 0.0
        if (v.width > area.width || v.height > area.height) {
            val d = area.center - v.center; dx = d.x; dy = d.y
        } else {
            if (v.left < area.left) dx = area.left - v.left else if (v.right > area.right) dx = area.right - v.right
            if (v.top < area.top) dy = area.top - v.top else if (v.bottom > area.bottom) dy = area.bottom - v.bottom
        }
        view.panBy(Pt(dx, dy))
        viewUpdated(true)
    }

    private fun viewUpdated(rerender: Boolean) {
        page?.let { pageViews[it.id] = view.copy() }
        if (rerender) cacheDirty = true
        onViewChanged?.invoke()
        postInvalidateOnAnimation()
    }

    private fun beginInteractive() {
        interactive = true
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, 180)
    }

    private fun finishInteractive() {
        if (gestureTarget == GestureTarget.Canvas) { handler.postDelayed(settle, 120); return }
        interactive = false
        invalidateContent()
    }

    // ------------------------------------------------------------------------- rendering

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!viewInitialised) fitPage()
        cache = null
        cacheDirty = true
        onViewChanged?.invoke()
    }

    private fun renderCache() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        var bmp = cache
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: return
            cache = bmp
            cacheDirty = true
        }
        val p = page
        if (!cacheDirty && p != null && p.revision == cacheRevision && p.id == cachePageId) return
        val c = Canvas(bmp)
        c.drawColor(p?.background?.background ?: theme.window)
        if (p != null) {
            c.save()
            c.translate(view.offset.x.toFloat(), view.offset.y.toFloat())
            c.scale(view.zoom.toFloat(), view.zoom.toFloat())
            val area = RectD(view.viewToPage(Pt.ZERO).x, view.viewToPage(Pt.ZERO).y, view.viewToPage(Pt(w.toDouble(), h.toDouble())).x, view.viewToPage(Pt(w.toDouble(), h.toDouble())).y).inflated(2.0)
            PageRenderer.render(c, p, area, view.zoom, document.images, document.coordinatesFor(p), hidden)
            val customSize = p.width != Page.DEFAULT_WIDTH || p.height != Page.DEFAULT_HEIGHT
            if (showPageFrame || customSize) {
                val fp = Draw.stroke(if (p.background.isDark) Color.argb(90, 255, 255, 255) else Color.argb(80, 0, 0, 0), 1.5 / view.zoom)
                fp.pathEffect = DashPathEffect(floatArrayOf((8 / view.zoom).toFloat(), (5 / view.zoom).toFloat()), 0f)
                c.drawRect(p.frameRect.toRectF(), fp)
            }
            c.restore()
            cacheRevision = p.revision
            cachePageId = p.id
        }
        cacheView = view.copy()
        cacheDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        val p = page
        if (!interactive) renderCache()
        val bmp = cache
        if (bmp != null) {
            if (interactive && cacheView.zoom > 0) {
                canvas.drawColor(p?.background?.background ?: theme.window)
                val s = view.zoom / cacheView.zoom
                canvas.save()
                canvas.translate((view.offset.x - cacheView.offset.x * s).toFloat(), (view.offset.y - cacheView.offset.y * s).toFloat())
                canvas.scale(s.toFloat(), s.toFloat())
                canvas.drawBitmap(bmp, 0f, 0f, BITMAP_PAINT)
                canvas.restore()
            } else canvas.drawBitmap(bmp, 0f, 0f, null)
        }
        canvas.save()
        canvas.translate(view.offset.x.toFloat(), view.offset.y.toFloat())
        canvas.scale(view.zoom.toFloat(), view.zoom.toFloat())
        if (p != null) magicHighlights.paint(canvas, p.id)
        tools.paintOverlay(canvas)
        instruments.paint(canvas, view.zoom)
        canvas.restore()
        tools.paintViewOverlay(canvas)
        if (gestureTarget == GestureTarget.Palm) {
            val c = view.pageToView(palmCenter)
            val r = palmRadius * view.zoom
            Draw.circle(canvas, c, r, Draw.fill(Color.argb(30, 255, 255, 255)))
            val pen = Draw.stroke(Color.argb(170, 255, 255, 255), theme.dpd(2.0))
            pen.pathEffect = DashPathEffect(floatArrayOf(theme.dp(8.0), theme.dp(5.0)), 0f)
            Draw.circle(canvas, c, r, pen)
        }
    }

    // ------------------------------------------------------------------- document events

    override fun onPageContentChanged(pageId: String) {
        if (page?.id == pageId) {
            val sel = page
            if (sel != null) selection.ids.retainAll { id -> sel.objectById(id) != null }
            invalidateContent()
        }
    }

    override fun onCurrentPageChanged(index: Int) {
        val p = page ?: return
        if (p.id == viewPage) { invalidateContent(); return }
        input.cancelAll()
        tools.pageChanged()
        magicHighlights.clear()
        selection.clear()
        hidden.clear()
        viewPage = p.id
        val v = pageViews[p.id]
        if (v != null) {
            view.zoom = v.zoom; view.offset = v.offset
            viewUpdated(true)
        } else fitPage()
    }

    override fun onPagesChanged() { invalidateContent() }

    override fun onDocumentReset() {
        input.cancelAll()
        tools.pageChanged()
        magicHighlights.clear()
        selection.clear()
        hidden.clear()
        pageViews.clear()
        viewPage = page?.id
        instruments.setUnits(document.coordinates.pxPerUnit(), document.coordinates.unitLabel)
        fitPage()
    }

    override fun onCoordinatesChanged() = invalidateContent()

    // ------------------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        return input.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean = input.onHoverEvent(event) || super.onHoverEvent(event)
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = input.onGenericMotion(event) || super.onGenericMotionEvent(event)

    override fun pointerEvent(e: PointerEvent) {
        e.pagePos = view.viewToPage(e.viewPos)
        when (e.phase) {
            PointerPhase.Down -> {
                lastPressPage = e.pagePos
                if (!instruments.isEmpty) {
                    val tol = viewToPageLength(theme.dpd(6.0))
                    val inst = instruments.instrumentAt(e.pagePos, tol)
                    val handle = inst?.handleAt(inst.toLocal(e.pagePos), tol) ?: Instrument.NO_HANDLE
                    val inking = tools.activeId == ToolId.Pen && e.device != PointerDevice.StylusEraser
                    val nearEdge = inking && instruments.edgeConstraint(e.pagePos, viewToPageLength(theme.dpd(26.0))) != null
                    val grab = handle > Instrument.BODY_HANDLE || (handle == Instrument.BODY_HANDLE && !nearEdge)
                    if (grab && instruments.pointerDown(e.pointerId, e.pagePos, tol)) {
                        instrumentPointers.add(e.pointerId)
                        invalidate()
                        return
                    }
                }
                tools.pointerEvent(e)
            }
            PointerPhase.Move -> if (e.pointerId in instrumentPointers) instruments.pointerMove(e.pointerId, e.pagePos) else tools.pointerEvent(e)
            PointerPhase.Up -> {
                if (instrumentPointers.remove(e.pointerId)) {
                    val r = instruments.pointerUp(e.pointerId)
                    val p = page
                    if (r.objects.isNotEmpty() && p != null) {
                        p.objects.addAll(r.objects)
                        document.commit(p, r.text.ifEmpty { "Construct" })
                    }
                    invalidate()
                    onInteractionFinished?.invoke()
                    return
                }
                tools.pointerEvent(e)
                onInteractionFinished?.invoke()
            }
            PointerPhase.Cancel -> if (instrumentPointers.remove(e.pointerId)) instruments.pointerCancel(e.pointerId) else tools.pointerEvent(e)
            PointerPhase.Hover -> tools.pointerEvent(e)
        }
    }

    override fun gestureEvent(e: GestureEvent) {
        val pageCentroid = view.viewToPage(e.centroid)
        if (e.type == GestureType.PalmErase) {
            val radius = viewToPageLength(e.radius)
            when (e.phase) {
                GesturePhase.Begin -> { gestureTarget = GestureTarget.Palm; eraser.beginPalm(pageCentroid, radius) }
                GesturePhase.Update -> eraser.movePalm(pageCentroid, radius)
                GesturePhase.End, GesturePhase.Cancel -> { eraser.endPalm(); gestureTarget = GestureTarget.None }
            }
            palmCenter = pageCentroid
            palmRadius = radius
            invalidate()
            return
        }
        when (e.phase) {
            GesturePhase.Begin -> {
                gestureStartView = view.copy()
                gestureInstrument = instruments.instrumentAt(pageCentroid, viewToPageLength(theme.dpd(10.0)))
                gestureTarget = when {
                    gestureInstrument != null -> GestureTarget.Instrument
                    tools.active?.gesture(e, pageCentroid) == true -> GestureTarget.Tool
                    else -> GestureTarget.Canvas
                }
            }
            GesturePhase.Update -> when (gestureTarget) {
                GestureTarget.Instrument -> {
                    gestureInstrument?.applyGesture(pageCentroid, e.panDelta / view.zoom, e.rotationDelta)
                    invalidate()
                }
                GestureTarget.Tool -> tools.active?.gesture(e, pageCentroid)
                GestureTarget.Canvas -> {
                    if (abs(e.scaleDelta - 1.0) > 1e-9) view.zoomAt(e.centroid, e.scaleDelta)
                    view.panBy(e.panDelta)
                    beginInteractive()
                    viewUpdated(false)
                }
                else -> {}
            }
            GesturePhase.End, GesturePhase.Cancel -> {
                if (gestureTarget == GestureTarget.Tool) tools.active?.gesture(e, pageCentroid)
                if (gestureTarget == GestureTarget.Canvas && e.phase == GesturePhase.Cancel) {
                    view.zoom = gestureStartView.zoom; view.offset = gestureStartView.offset
                }
                gestureTarget = GestureTarget.None
                gestureInstrument = null
                interactive = false
                viewUpdated(true)
            }
        }
    }

    override fun wheelZoom(viewPos: Pt, factor: Double) {
        view.zoomAt(viewPos, factor)
        beginInteractive()
        viewUpdated(false)
    }

    override fun wheelPan(delta: Pt) {
        view.panBy(delta)
        beginInteractive()
        viewUpdated(false)
    }

    override fun dragPan(delta: Pt) = wheelPan(delta)

    fun showInstrument(kind: Instrument.Kind) {
        instruments.setUnits(document.coordinates.pxPerUnit(), document.coordinates.unitLabel)
        instruments.toggle(kind, viewCenterInPage())
        if (instruments.isShown(kind)) setActive(ToolId.Pen)
    }

    fun setActive(id: ToolId) = tools.setActive(id)

    companion object {
        val ZOOM_PRESETS = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5)
        private val BITMAP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
