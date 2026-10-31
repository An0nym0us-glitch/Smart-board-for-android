package com.classboard.app.input

import android.os.SystemClock
import android.view.MotionEvent
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import kotlin.math.max
import kotlin.math.pow

enum class PointerDevice { Mouse, Touch, Stylus, StylusEraser }
enum class PointerPhase { Down, Move, Up, Cancel, Hover }

/** Device independent pointer event. */
class PointerEvent(
    val pointerId: Int,
    val device: PointerDevice,
    val phase: PointerPhase,
    val viewPos: Pt,
    val pressure: Double = 1.0,
    val hasPressure: Boolean = false,
    val timestamp: Long = SystemClock.uptimeMillis(),
    val shift: Boolean = false,
) {
    var pagePos: Pt = Pt.ZERO
}

enum class GestureType { PanZoom, PalmErase }
enum class GesturePhase { Begin, Update, End, Cancel }

class GestureEvent(
    val type: GestureType,
    val phase: GesturePhase,
    val centroid: Pt,
    val panDelta: Pt = Pt.ZERO,
    val scaleDelta: Double = 1.0,
    val rotationDelta: Double = 0.0,
    val radius: Double = 0.0,
    val touchCount: Int = 0,
)

interface InputSink {
    fun pointerEvent(e: PointerEvent)
    fun gestureEvent(e: GestureEvent)
    fun wheelZoom(viewPos: Pt, factor: Double)
    fun wheelPan(delta: Pt)
    fun dragPan(delta: Pt)
}

/** Palm / wipe thresholds (millimetres, converted with the screen density). */
object PalmGesture {
    const val FINGER_COUNT = 3
    const val MAX_SPACING_MM = 40.0
    const val MAX_GROUP_SIZE_MM = 40.0
    const val GROUP_WINDOW_MS = 450L
    const val MAX_TRAVEL_MM = 20.0
    const val PALM_CONTACT_MM = 24.0
    const val MIN_RADIUS_MM = 14.0
    const val RADIUS_MARGIN_MM = 6.0

    fun isTightGroup(contacts: List<Pt>, ppm: Double): Boolean {
        if (contacts.size != FINGER_COUNT) return false
        var maxSpacing = 0.0
        for (i in contacts.indices) for (j in i + 1 until contacts.size) maxSpacing = max(maxSpacing, Geom.distance(contacts[i], contacts[j]))
        val b = Geom.boundingRect(contacts)
        val box = MAX_GROUP_SIZE_MM * ppm
        return maxSpacing <= MAX_SPACING_MM * ppm && b.width <= box && b.height <= box
    }
}

/**
 * Converts Android MotionEvents into pointer events and recognises multi-touch gestures.
 * Ten-point touch: each finger writes on its own unless it forms a pinch with a young stroke
 * (pan/zoom), a tight group of three (wipe), or lands near an active gesture. The stylus has
 * priority: while it touches or hovers, new touches are ignored.
 */
class InputManager(private val sink: InputSink) {
    var palmEraseEnabled = true
    var multiUserTouch = false
    var pixelsPerMm = 3.78
        set(v) { field = if (v > 0.5) v else 3.78 }

    private enum class Role { Write, Gesture, Palm, Ignored }

    private class Track(var start: Pt, var pos: Pt, val startTime: Long, var diameter: Double, var role: Role = Role.Ignored)

    private val tracks = LinkedHashMap<Int, Track>()
    private var panZoom = false
    private val palmIds = ArrayList<Int>()
    private var prevPalmCentroid = Pt.ZERO
    private var prevCentroid = Pt.ZERO
    private var prevSpread = 0.0
    private var prevAngle = 0.0

    private var stylusDown = false
    private var stylusPointer = -1
    private var stylusDevice = PointerDevice.Stylus
    private var lastStylusTime = -1L
    private var mouseDown = false
    private var mousePanning = false
    private var lastMouse = Pt.ZERO

    private fun now() = SystemClock.uptimeMillis()

    fun stylusActive() = stylusDown || (lastStylusTime >= 0 && now() - lastStylusTime < STYLUS_PROXIMITY_MS)

    // ------------------------------------------------------------------------------ entry points

    fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelAll(); return true
        }
        val idx = e.actionIndex
        // Dispatch per pointer by tool type.
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> handlePointer(e, idx, PointerPhase.Down)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handlePointer(e, idx, PointerPhase.Up)
            MotionEvent.ACTION_MOVE -> handleMove(e)
        }
        if (action == MotionEvent.ACTION_UP && tracks.isNotEmpty()) {
            // Some drivers end the sequence without releasing every point.
            val leftover = tracks.keys.toList()
            for (id in leftover) releaseTouch(id, tracks[id]?.pos ?: Pt.ZERO)
        }
        return true
    }

    fun onHoverEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusTime = now()
            if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_ENTER)
                sink.pointerEvent(PointerEvent(STYLUS_POINTER_ID, if (tool == MotionEvent.TOOL_TYPE_ERASER) PointerDevice.StylusEraser else PointerDevice.Stylus, PointerPhase.Hover, Pt(e.x.toDouble(), e.y.toDouble())))
            return true
        }
        if (tool == MotionEvent.TOOL_TYPE_MOUSE && e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            sink.pointerEvent(PointerEvent(MOUSE_POINTER_ID, PointerDevice.Mouse, PointerPhase.Hover, Pt(e.x.toDouble(), e.y.toDouble())))
            return true
        }
        return false
    }

    fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val pos = Pt(e.x.toDouble(), e.y.toDouble())
            if ((e.metaState and android.view.KeyEvent.META_CTRL_ON != 0) || (h == 0f && !(e.metaState and android.view.KeyEvent.META_SHIFT_ON != 0))) {
                sink.wheelZoom(pos, 1.0015.pow(v * 120.0))
            } else {
                sink.wheelPan(Pt((if ((e.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)) v else h) * 60.0, 0.0))
            }
            return true
        }
        return onHoverEvent(e)
    }

    private fun isStylus(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isMouse(e: MotionEvent, i: Int) = e.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE

    private fun diameterOf(e: MotionEvent, i: Int): Double = max(e.getTouchMajor(i), e.getTouchMinor(i)).toDouble()

    private fun handlePointer(e: MotionEvent, i: Int, phase: PointerPhase) {
        val id = e.getPointerId(i)
        val pos = Pt(e.getX(i).toDouble(), e.getY(i).toDouble())
        when {
            isStylus(e, i) -> handleStylus(e, i, id, pos, phase)
            isMouse(e, i) -> handleMouse(e, pos, phase)
            else -> if (phase == PointerPhase.Down) newTouch(id, pos, diameterOf(e, i)) else releaseTouch(id, pos)
        }
    }

    private fun handleStylus(e: MotionEvent, i: Int, id: Int, pos: Pt, phase: PointerPhase) {
        lastStylusTime = now()
        val device = if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0)) PointerDevice.StylusEraser else PointerDevice.Stylus
        val pressure = e.getPressure(i).toDouble().coerceIn(0.0, 1.0)
        when (phase) {
            PointerPhase.Down -> if (!stylusDown) {
                for (tid in tracks.keys.toList()) cancelWriting(tid)
                endPanZoom(GesturePhase.End)
                endPalm()
                tracks.values.forEach { it.role = Role.Ignored }
                stylusDown = true
                stylusPointer = id
                stylusDevice = device
                sink.pointerEvent(PointerEvent(STYLUS_POINTER_ID, device, PointerPhase.Down, pos, pressure, true))
            }
            PointerPhase.Up -> if (stylusDown && id == stylusPointer) {
                stylusDown = false
                stylusPointer = -1
                sink.pointerEvent(PointerEvent(STYLUS_POINTER_ID, stylusDevice, PointerPhase.Up, pos, pressure, true))
            }
            else -> {}
        }
    }

    private fun handleMouse(e: MotionEvent, pos: Pt, phase: PointerPhase) {
        val secondary = e.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0
        when (phase) {
            PointerPhase.Down -> {
                if (secondary) { mousePanning = true }
                else if (!mouseDown) {
                    mouseDown = true
                    sink.pointerEvent(PointerEvent(MOUSE_POINTER_ID, PointerDevice.Mouse, PointerPhase.Down, pos, shift = (e.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)))
                }
            }
            PointerPhase.Up -> {
                if (mousePanning) mousePanning = false
                else if (mouseDown) {
                    mouseDown = false
                    sink.pointerEvent(PointerEvent(MOUSE_POINTER_ID, PointerDevice.Mouse, PointerPhase.Up, pos, shift = (e.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)))
                }
            }
            else -> {}
        }
        lastMouse = pos
    }

    private fun handleMove(e: MotionEvent) {
        var gestureMoved = false
        var palmMoved = false
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (isStylus(e, i)) {
                lastStylusTime = now()
                if (!stylusDown || id != stylusPointer) continue
                // Historical samples give smooth ink at high pen report rates.
                for (h in 0 until e.historySize) {
                    sink.pointerEvent(PointerEvent(STYLUS_POINTER_ID, stylusDevice, PointerPhase.Move,
                        Pt(e.getHistoricalX(i, h).toDouble(), e.getHistoricalY(i, h).toDouble()),
                        e.getHistoricalPressure(i, h).toDouble().coerceIn(0.0, 1.0), true))
                }
                sink.pointerEvent(PointerEvent(STYLUS_POINTER_ID, stylusDevice, PointerPhase.Move, Pt(e.getX(i).toDouble(), e.getY(i).toDouble()), e.getPressure(i).toDouble().coerceIn(0.0, 1.0), true))
                continue
            }
            if (isMouse(e, i)) {
                val pos = Pt(e.getX(i).toDouble(), e.getY(i).toDouble())
                if (mousePanning) sink.dragPan(pos - lastMouse)
                else if (mouseDown) sink.pointerEvent(PointerEvent(MOUSE_POINTER_ID, PointerDevice.Mouse, PointerPhase.Move, pos, shift = (e.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)))
                lastMouse = pos
                continue
            }
            val t = tracks[id] ?: continue
            val pos = Pt(e.getX(i).toDouble(), e.getY(i).toDouble())
            t.diameter = max(t.diameter, diameterOf(e, i))
            if (t.pos == pos) continue
            when (t.role) {
                Role.Write -> {
                    for (h in 0 until e.historySize) {
                        t.pos = Pt(e.getHistoricalX(i, h).toDouble(), e.getHistoricalY(i, h).toDouble())
                        forwardTouch(id, PointerPhase.Move, t)
                    }
                    t.pos = pos
                    forwardTouch(id, PointerPhase.Move, t)
                }
                Role.Gesture -> { t.pos = pos; gestureMoved = true }
                Role.Palm -> { t.pos = pos; palmMoved = true }
                Role.Ignored -> t.pos = pos
            }
        }
        if (gestureMoved && panZoom) updatePanZoom()
        if (palmMoved) updatePalm(GesturePhase.Update)
    }

    // ------------------------------------------------------------------------------------ touch

    private fun newTouch(id: Int, pos: Pt, diameter: Double) {
        if (tracks.containsKey(id)) return
        tracks[id] = Track(pos, pos, now(), diameter)
        classifyNewTouch(id)
    }

    private fun releaseTouch(id: Int, pos: Pt) {
        val t = tracks.remove(id) ?: return
        t.pos = pos
        when (t.role) {
            Role.Write -> forwardTouch(id, PointerPhase.Up, t)
            Role.Gesture -> if (countRole(Role.Gesture) < 2) endPanZoom(GesturePhase.End) else rebaseline()
            Role.Palm -> if (palmIds.remove(id) && palmIds.isEmpty()) updatePalm(GesturePhase.End)
            Role.Ignored -> {}
        }
    }

    private fun forwardTouch(id: Int, phase: PointerPhase, t: Track) {
        sink.pointerEvent(PointerEvent(TOUCH_POINTER_BASE + id, PointerDevice.Touch, phase, t.pos))
    }

    private fun cancelWriting(id: Int) {
        val t = tracks[id] ?: return
        if (t.role != Role.Write) return
        forwardTouch(id, PointerPhase.Cancel, t)
        t.role = Role.Ignored
    }

    private fun countRole(r: Role) = tracks.values.count { it.role == r }

    private fun isYoung(t: Track, now: Long): Boolean {
        val moved = Geom.distance(t.start, t.pos)
        return moved < YOUNG_STROKE_MM * pixelsPerMm || ((now - t.startTime) < YOUNG_STROKE_MS && moved < YOUNG_STROKE_MAX_MM * pixelsPerMm)
    }

    private fun centroid(): Pt {
        var c = Pt.ZERO
        var n = 0
        for (t in tracks.values) if (t.role == Role.Gesture) { c += t.pos; n++ }
        return if (n > 0) c / n.toDouble() else prevCentroid
    }

    private fun spread(c: Pt): Double {
        var s = 0.0
        var n = 0
        for (t in tracks.values) if (t.role == Role.Gesture) { s += Geom.distance(t.pos, c); n++ }
        return if (n > 0) s / n else 0.0
    }

    private fun angle(): Double {
        val ids = tracks.filter { it.value.role == Role.Gesture }.keys.sorted()
        if (ids.size < 2) return 0.0
        return Geom.angleDeg(tracks[ids[1]]!!.pos - tracks[ids[0]]!!.pos)
    }

    private fun palmCentroid(): Pt {
        var c = Pt.ZERO
        var n = 0
        for (id in palmIds) tracks[id]?.let { c += it.pos; n++ }
        return if (n > 0) c / n.toDouble() else prevPalmCentroid
    }

    private fun palmRadius(c: Pt): Double {
        var r = PalmGesture.MIN_RADIUS_MM * pixelsPerMm
        for (id in palmIds) tracks[id]?.let { r = max(r, Geom.distance(it.pos, c) + it.diameter * 0.5 + PalmGesture.RADIUS_MARGIN_MM * pixelsPerMm) }
        return r
    }

    private fun isPalmContact(t: Track) = t.diameter >= PalmGesture.PALM_CONTACT_MM * pixelsPerMm

    private fun findPalmGroup(newId: Int, now: Long): List<Int> {
        if (!palmEraseEnabled || palmIds.isNotEmpty()) return emptyList()
        val added = tracks[newId] ?: return emptyList()
        val candidates = tracks.filter { (id, t) ->
            id != newId && t.role != Role.Ignored && now - t.startTime <= PalmGesture.GROUP_WINDOW_MS &&
                Geom.distance(t.start, t.pos) <= PalmGesture.MAX_TRAVEL_MM * pixelsPerMm
        }.map { Pair(it.key, Geom.distance(it.value.pos, added.pos)) }.sortedBy { it.second }
        val needed = PalmGesture.FINGER_COUNT - 1
        if (candidates.size < needed) return emptyList()
        val ids = listOf(newId) + candidates.take(needed).map { it.first }
        if (!PalmGesture.isTightGroup(ids.map { tracks[it]!!.pos }, pixelsPerMm)) return emptyList()
        return ids
    }

    private fun findPinchPartner(newId: Int, now: Long): Int {
        if (multiUserTouch || palmIds.isNotEmpty() || panZoom) return -1
        val added = tracks[newId] ?: return -1
        var partner = -1
        var best = 0.0
        for ((id, t) in tracks) {
            if (id == newId || t.role != Role.Write) continue
            if (!isYoung(t, now)) return -1
            val d = Geom.distance(t.pos, added.pos)
            val oneHand = d <= PINCH_MAX_SPAN_MM * pixelsPerMm
            val twoHands = d <= TWO_HAND_MAX_SPAN_MM * pixelsPerMm && added.startTime - t.startTime <= TWO_HAND_WINDOW_MS
            if ((oneHand || twoHands) && (partner < 0 || d < best)) { partner = id; best = d }
        }
        return partner
    }

    private fun rebaseline() {
        prevCentroid = centroid()
        prevSpread = spread(prevCentroid)
        prevAngle = angle()
    }

    private fun beginPanZoom() {
        panZoom = true
        rebaseline()
        sink.gestureEvent(GestureEvent(GestureType.PanZoom, GesturePhase.Begin, prevCentroid, touchCount = countRole(Role.Gesture)))
    }

    private fun updatePanZoom() {
        val c = centroid()
        val s = spread(c)
        val a = angle()
        val n = countRole(Role.Gesture)
        val scale = if (prevSpread > 8.0 && s > 8.0) s / prevSpread else 1.0
        val rot = if (n >= 2) Geom.angleDifference(prevAngle, a) else 0.0
        val g = GestureEvent(GestureType.PanZoom, GesturePhase.Update, c, c - prevCentroid, scale, rot, touchCount = n)
        prevCentroid = c; prevSpread = s; prevAngle = a
        sink.gestureEvent(g)
    }

    private fun endPanZoom(phase: GesturePhase) {
        if (!panZoom) return
        sink.gestureEvent(GestureEvent(GestureType.PanZoom, phase, prevCentroid))
        panZoom = false
        tracks.values.forEach { if (it.role == Role.Gesture) it.role = Role.Ignored }
    }

    private fun beginPalm(ids: List<Int>) {
        endPanZoom(GesturePhase.Cancel)
        for (id in ids) {
            cancelWriting(id)
            tracks[id]?.role = Role.Palm
        }
        palmIds.clear(); palmIds.addAll(ids)
        updatePalm(GesturePhase.Begin)
    }

    private fun updatePalm(phase: GesturePhase) {
        val c = palmCentroid()
        val g = GestureEvent(GestureType.PalmErase, phase, c, radius = palmRadius(c), touchCount = palmIds.size)
        prevPalmCentroid = c
        sink.gestureEvent(g)
    }

    private fun endPalm() {
        if (palmIds.isEmpty()) return
        updatePalm(GesturePhase.End)
        for (id in palmIds) tracks[id]?.role = Role.Ignored
        palmIds.clear()
    }

    private fun classifyNewTouch(id: Int) {
        val t = tracks[id] ?: return
        val now = now()
        t.role = Role.Ignored
        if (stylusActive()) return
        val group = findPalmGroup(id, now)
        if (group.isNotEmpty()) { beginPalm(group); return }
        if (palmEraseEnabled && palmIds.isEmpty() && isPalmContact(t)) { beginPalm(listOf(id)); return }
        if (panZoom) {
            if (Geom.distance(t.pos, centroid()) <= GESTURE_JOIN_MM * pixelsPerMm) {
                t.role = Role.Gesture
                rebaseline()
            }
            return
        }
        if (palmIds.isNotEmpty() && Geom.distance(t.pos, palmCentroid()) <= WIPE_HAND_REACH_MM * pixelsPerMm) return
        val partner = findPinchPartner(id, now)
        if (partner >= 0) {
            for (other in tracks.keys.toList()) cancelWriting(other)
            tracks[partner]?.role = Role.Gesture
            t.role = Role.Gesture
            beginPanZoom()
            return
        }
        t.role = Role.Write
        forwardTouch(id, PointerPhase.Down, t)
    }

    fun cancelAll() {
        if (mouseDown) {
            sink.pointerEvent(PointerEvent(MOUSE_POINTER_ID, PointerDevice.Mouse, PointerPhase.Cancel, lastMouse))
            mouseDown = false
        }
        mousePanning = false
        if (stylusDown) {
            sink.pointerEvent(PointerEvent(STYLUS_POINTER_ID, stylusDevice, PointerPhase.Cancel, Pt.ZERO))
            stylusDown = false
        }
        for ((id, t) in tracks) if (t.role == Role.Write) forwardTouch(id, PointerPhase.Cancel, t)
        endPanZoom(GesturePhase.Cancel)
        endPalm()
        tracks.clear()
    }

    companion object {
        const val STYLUS_PROXIMITY_MS = 400L
        const val MOUSE_POINTER_ID = 0
        const val STYLUS_POINTER_ID = 1
        const val TOUCH_POINTER_BASE = 100

        const val YOUNG_STROKE_MM = 12.0
        const val YOUNG_STROKE_MS = 350L
        const val YOUNG_STROKE_MAX_MM = 30.0
        const val PINCH_MAX_SPAN_MM = 160.0
        const val TWO_HAND_WINDOW_MS = 200L
        const val TWO_HAND_MAX_SPAN_MM = 450.0
        const val GESTURE_JOIN_MM = 160.0
        const val WIPE_HAND_REACH_MM = 160.0
    }
}
