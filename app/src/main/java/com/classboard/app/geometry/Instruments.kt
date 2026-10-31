package com.classboard.app.geometry

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.InkStyle
import com.classboard.app.doc.StrokeObject
import com.classboard.app.doc.StrokePoint
import com.classboard.app.doc.StrokeStyle
import com.classboard.app.render.Draw
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/** A straight edge or circular arc that ink can be drawn along. */
data class EdgeConstraint(val kind: Kind = Kind.None, val origin: Pt = Pt.ZERO, val direction: Pt = Pt(1.0, 0.0), val radius: Double = 0.0) {
    enum class Kind { None, Line, Circle }

    val isValid get() = kind != Kind.None

    fun project(p: Pt): Pt = when (kind) {
        Kind.Line -> Geom.projectOntoLine(p, origin, direction)
        Kind.Circle -> {
            var d = p - origin
            val len = d.length()
            d = if (len < 1e-9) Pt(radius, 0.0) else d / len * radius
            origin + d
        }
        Kind.None -> p
    }

    fun distanceTo(p: Pt) = Geom.distance(p, project(p))
}

class InstrumentResult(val objects: List<BoardObject> = emptyList(), val text: String = "")

/** A drawing instrument lying on the board; an overlay, never document content. */
abstract class Instrument {
    enum class Kind { Ruler, Protractor, SetSquare, Compass }

    abstract val kind: Kind
    var position = Pt.ZERO
    var rotation = 0.0
        set(v) { field = Geom.normalizeDegrees(v) }
    var pxPerUnit = 40.0
        set(v) { field = if (v > 1) v else 40.0 }
    var unitLabel = "cm"
    var activeHandle = NO_HANDLE
        protected set
    protected var grabLocal = Pt.ZERO
    protected var grabAngleOffset = 0.0

    val isInteracting get() = activeHandle != NO_HANDLE

    fun toLocal(page: Pt) = Geom.rotated(page - position, -rotation)
    fun toPage(local: Pt) = Geom.rotated(local, rotation) + position

    abstract fun bodyContains(local: Pt): Boolean
    abstract fun bodyBounds(): RectD
    abstract fun paint(c: Canvas, zoom: Double, active: Boolean)
    protected abstract fun closeButtonPos(): Pt
    protected abstract fun rotateHandlePos(): Pt
    protected open val handleRadius = 22.0

    fun sceneBounds(): RectD {
        val local = bodyBounds().union(RectD.fromCenter(closeButtonPos(), 1.0, 1.0)).union(RectD.fromCenter(rotateHandlePos(), 1.0, 1.0))
        return Geom.boundingRect(local.corners().map { toPage(it) }).inflated(handleRadius * 2.5)
    }

    open fun handleAt(local: Pt, tol: Double): Int {
        val r = handleRadius + tol
        if (Geom.distance(local, closeButtonPos()) <= r) return CLOSE_HANDLE
        if (Geom.distance(local, rotateHandlePos()) <= r) return ROTATE_HANDLE
        if (bodyContains(local)) return BODY_HANDLE
        return NO_HANDLE
    }

    open fun beginInteraction(handle: Int, pagePos: Pt): Boolean {
        activeHandle = handle
        grabLocal = toLocal(pagePos)
        if (handle == ROTATE_HANDLE) grabAngleOffset = Geom.angleDifference(Geom.angleDeg(pagePos - position), rotation)
        return handle != NO_HANDLE
    }

    open fun updateInteraction(pagePos: Pt) {
        when (activeHandle) {
            BODY_HANDLE -> position = pagePos - Geom.rotated(grabLocal, rotation)
            ROTATE_HANDLE -> {
                val angle = Geom.angleDeg(pagePos - position) + grabAngleOffset
                rotation = Geom.snapAngle(Geom.normalizeDegrees(angle), 15.0, 1.5).first
            }
        }
    }

    open fun endInteraction(): InstrumentResult { activeHandle = NO_HANDLE; return InstrumentResult() }
    open fun cancelInteraction() { activeHandle = NO_HANDLE }
    open fun edgeConstraint(pagePos: Pt, tol: Double): EdgeConstraint? = null
    open fun statusText(): String = ""

    fun applyGesture(pageCenter: Pt, pageDelta: Pt, rotationDelta: Double) {
        val rel = position - pageCenter
        position = pageCenter + Geom.rotated(rel, rotationDelta) + pageDelta
        rotation += rotationDelta
    }

    companion object {
        const val NO_HANDLE = -1
        const val BODY_HANDLE = 0
        const val CLOSE_HANDLE = 1
        const val ROTATE_HANDLE = 2

        val PLASTIC = Color.argb(200, 232, 238, 234)
        val PLASTIC_ACTIVE = Color.argb(215, 236, 244, 240)
        val EDGE = Color.argb(150, 20, 26, 24)
        val TICK = Color.argb(230, 28, 32, 30)
        val ARM1 = Color.rgb(33, 150, 243)
        val ARM2 = Color.rgb(245, 124, 0)
        val KNOB = Color.argb(230, 40, 44, 46)

        fun create(kind: Kind): Instrument = when (kind) {
            Kind.Ruler -> Ruler()
            Kind.Protractor -> Protractor()
            Kind.SetSquare -> SetSquare()
            Kind.Compass -> Compass()
        }

        fun cosmetic(color: Int, w: Double, zoom: Double) = Draw.stroke(color, w / zoom)

        fun centeredText(c: Canvas, center: Pt, text: String, px: Double, color: Int, bold: Boolean = false) {
            Draw.textCentered(c, text, center.x, center.y, Draw.text(color, px, bold, Paint.Align.CENTER))
        }

        fun unitText(pixels: Double, ppu: Double, unit: String) = Geom.formatNumber(pixels / ppu, 1) + " " + unit

        fun lineAngle(rotation: Double): Double {
            var a = Geom.normalizeDegrees(-rotation)
            if (a >= 180.0) a -= 180.0
            return a
        }

        fun paintCloseButton(c: Canvas, center: Pt, r: Double) {
            Draw.circle(c, center, r, Draw.fill(KNOB))
            Draw.circle(c, center, r, Draw.stroke(Color.argb(60, 0, 0, 0), 1.0))
            val cross = Draw.stroke(Color.rgb(240, 240, 240), r * 0.16)
            val k = r * 0.38
            Draw.line(c, center + Pt(-k, -k), center + Pt(k, k), cross)
            Draw.line(c, center + Pt(-k, k), center + Pt(k, -k), cross)
        }

        fun paintRotateKnob(c: Canvas, center: Pt, r: Double) {
            Draw.circle(c, center, r, Draw.fill(KNOB))
            Draw.circle(c, center, r, Draw.stroke(Color.argb(60, 0, 0, 0), 1.0))
            val arc = Draw.stroke(Color.rgb(240, 240, 240), r * 0.14)
            val box = RectF((center.x - r * 0.5).toFloat(), (center.y - r * 0.5).toFloat(), (center.x + r * 0.5).toFloat(), (center.y + r * 0.5).toFloat())
            // Qt draws counter-clockwise from 30°; Android angles are clockwise.
            c.drawArc(box, -30f, -270f, false, arc)
            val tip = center + Pt(cos(Geom.degToRad(-30.0)) * r * 0.5, sin(Geom.degToRad(-30.0)) * r * 0.5)
            Draw.line(c, tip, tip + Pt(r * 0.25, -r * 0.02), arc)
            Draw.line(c, tip, tip + Pt(r * 0.02, -r * 0.27), arc)
        }

        fun paintScale(c: Canvas, start: Pt, dir: Pt, inward: Pt, length: Double, ppu: Double, zoom: Double, labels: Boolean) {
            val mm = ppu / 10.0
            val drawMm = mm * zoom >= 3.5
            val labelEvery = if (ppu * zoom >= 26.0) 1 else if (ppu * zoom >= 8.0) 5 else 10
            val count = floor(length / mm + 1e-6).toInt()
            for (i in 0..count) {
                val cm = i % 10 == 0
                val half = i % 5 == 0
                if (!drawMm && !half) continue
                val len = if (cm) 24.0 else if (half) 16.0 else 9.0
                val a = start + dir * (i * mm)
                Draw.line(c, a, a + inward * len, cosmetic(TICK, if (cm) 1.4 else 1.0, zoom))
                if (labels && cm && (i / 10) % labelEvery == 0) centeredText(c, a + inward * 38.0, (i / 10).toString(), 15.0, TICK)
            }
        }
    }
}

class Ruler : Instrument() {
    override val kind = Kind.Ruler
    var units = 30.0
        private set

    fun setLengthUnits(u: Double) { units = round(u).coerceIn(5.0, 100.0) }
    private fun length() = units * pxPerUnit + 2 * MARGIN

    override fun bodyBounds() = RectD(-length() / 2, -HEIGHT / 2, length() / 2, HEIGHT / 2)
    override fun bodyContains(local: Pt) = bodyBounds().contains(local)
    override fun closeButtonPos() = Pt(-length() / 2 + 34, 20.0)
    override fun rotateHandlePos() = Pt(length() / 2 - 34, 20.0)

    override fun handleAt(local: Pt, tol: Double): Int {
        if (Geom.distance(local, Pt(length() / 2 + 18, 0.0)) <= 18 + tol) return LENGTH_HANDLE
        return super.handleAt(local, tol)
    }

    override fun updateInteraction(pagePos: Pt) {
        if (activeHandle != LENGTH_HANDLE) { super.updateInteraction(pagePos); return }
        val leftPage = toPage(Pt(-length() / 2, 0.0))
        val dx = toLocal(pagePos).x - toLocal(leftPage).x
        setLengthUnits((dx - 18 - 2 * MARGIN) / pxPerUnit)
        position = leftPage + Geom.rotated(Pt(length() / 2, 0.0), rotation)
    }

    override fun edgeConstraint(pagePos: Pt, tol: Double): EdgeConstraint? {
        val local = toLocal(pagePos)
        val half = length() / 2
        if (local.x < -half - tol || local.x > half + tol) return null
        var best = tol
        var out: EdgeConstraint? = null
        for (y in doubleArrayOf(-HEIGHT / 2, HEIGHT / 2)) {
            val d = abs(local.y - y)
            if (d <= best) {
                best = d
                out = EdgeConstraint(EdgeConstraint.Kind.Line, toPage(Pt(0.0, y)), Geom.rotated(Pt(1.0, 0.0), rotation))
            }
        }
        return out
    }

    override fun statusText() = "${Geom.formatNumber(units, 0)} $unitLabel · ${Geom.formatNumber(lineAngle(rotation), 1)}°"

    override fun paint(c: Canvas, zoom: Double, active: Boolean) {
        val len = length()
        val body = RectF((-len / 2).toFloat(), (-HEIGHT / 2).toFloat(), (len / 2).toFloat(), (HEIGHT / 2).toFloat())
        c.drawRoundRect(body, 8f, 8f, Draw.fill(if (active) PLASTIC_ACTIVE else PLASTIC))
        c.drawRoundRect(body, 8f, 8f, cosmetic(EDGE, 1.2, zoom))
        paintScale(c, Pt(-len / 2 + MARGIN, -HEIGHT / 2), Pt(1.0, 0.0), Pt(0.0, 1.0), units * pxPerUnit, pxPerUnit, zoom, true)
        val tick = cosmetic(TICK, 1.0, zoom)
        for (i in 0..units.toInt()) {
            val x = -len / 2 + MARGIN + i * pxPerUnit
            Draw.line(c, Pt(x, HEIGHT / 2), Pt(x, HEIGHT / 2 - if (i % 5 == 0) 14 else 8), tick)
        }
        centeredText(c, Pt(0.0, 22.0), "${Geom.formatNumber(lineAngle(rotation), 1)}°", 16.0, TICK, true)
        paintCloseButton(c, closeButtonPos(), 17.0)
        paintRotateKnob(c, rotateHandlePos(), 17.0)
        val tab = Pt(len / 2 + 18, 0.0)
        val tr = RectF((tab.x - 10).toFloat(), (tab.y - 22).toFloat(), (tab.x + 10).toFloat(), (tab.y + 22).toFloat())
        c.drawRoundRect(tr, 6f, 6f, Draw.fill(Color.argb(220, 40, 44, 46)))
        c.drawRoundRect(tr, 6f, 6f, cosmetic(EDGE, 1.0, zoom))
        val grip = cosmetic(Color.rgb(230, 230, 230), 1.4, zoom)
        for (k in -1..1) Draw.line(c, Pt(tab.x - 4, tab.y + k * 7), Pt(tab.x + 4, tab.y + k * 7), grip)
    }

    companion object {
        const val LENGTH_HANDLE = 3
        const val HEIGHT = 96.0
        const val MARGIN = 18.0
    }
}

class Protractor : Instrument() {
    override val kind = Kind.Protractor
    var arm1 = 0.0
        private set
    var arm2 = 60.0
        private set

    private fun radius() = 7.0 * pxPerUnit
    private fun armKnob(angle: Double): Pt {
        val r = radius() + 46
        val a = Geom.degToRad(angle)
        return Pt(cos(a) * r, -sin(a) * r)
    }
    private fun stampPos() = Pt(-radius() + 68, 13.0)

    fun measuredAngle() = abs(arm2 - arm1)
    fun setArmAngles(a1: Double, a2: Double) { arm1 = a1.coerceIn(0.0, 180.0); arm2 = a2.coerceIn(0.0, 180.0) }

    override fun bodyBounds(): RectD { val r = radius() + 12; return RectD(-r, -r, r, 26.0) }
    override fun bodyContains(local: Pt): Boolean {
        val r = radius() + 12
        if (local.y in 0.0..26.0 && abs(local.x) <= r) return true
        return local.y <= 0 && local.length() <= r
    }

    override fun closeButtonPos() = Pt(-radius() + 22, 13.0)
    override fun rotateHandlePos() = Pt(radius() - 22, 13.0)

    override fun handleAt(local: Pt, tol: Double): Int {
        if (Geom.distance(local, armKnob(arm2)) <= 20 + tol) return ARM2_HANDLE
        if (Geom.distance(local, armKnob(arm1)) <= 20 + tol) return ARM1_HANDLE
        if (Geom.distance(local, stampPos()) <= 18 + tol) return STAMP_HANDLE
        return super.handleAt(local, tol)
    }

    override fun updateInteraction(pagePos: Pt) {
        if (activeHandle != ARM1_HANDLE && activeHandle != ARM2_HANDLE) { super.updateInteraction(pagePos); return }
        val l = toLocal(pagePos)
        var a = Geom.radToDeg(atan2(-l.y, l.x))
        if (a < -90.0) a = 180.0
        a = round(a).coerceIn(0.0, 180.0)
        if (activeHandle == ARM1_HANDLE) arm1 = a else arm2 = a
    }

    override fun endInteraction(): InstrumentResult {
        var result = InstrumentResult()
        if (activeHandle == STAMP_HANDLE) {
            val reach = radius() * 0.8
            fun armEnd(angle: Double): Pt { val a = Geom.degToRad(angle); return toPage(Pt(cos(a) * reach, -sin(a) * reach)) }
            val pts = listOf(armEnd(arm1), toPage(Pt.ZERO), armEnd(arm2))
            result = InstrumentResult(listOf(MeasurementObject.create(MeasureKind.Angle, pts, Color.rgb(255, 213, 79))), "Angle stamped")
        }
        activeHandle = NO_HANDLE
        return result
    }

    override fun edgeConstraint(pagePos: Pt, tol: Double): EdgeConstraint? {
        val l = toLocal(pagePos)
        val r = radius() + 12
        if (abs(l.y - 26) <= tol && abs(l.x) <= r + tol)
            return EdgeConstraint(EdgeConstraint.Kind.Line, toPage(Pt(0.0, 26.0)), Geom.rotated(Pt(1.0, 0.0), rotation))
        if (l.y <= 4 && abs(l.length() - r) <= tol)
            return EdgeConstraint(EdgeConstraint.Kind.Circle, toPage(Pt.ZERO), radius = r)
        return null
    }

    override fun statusText() = "${Geom.formatNumber(measuredAngle(), 0)}°"

    override fun paint(c: Canvas, zoom: Double, active: Boolean) {
        val r = radius()
        val rr = r + 12
        val body = Path()
        body.moveTo((-rr).toFloat(), 26f)
        body.lineTo(rr.toFloat(), 26f)
        body.lineTo(rr.toFloat(), 0f)
        body.arcTo(RectF((-rr).toFloat(), (-rr).toFloat(), rr.toFloat(), rr.toFloat()), 0f, -180f, false)
        body.close()
        c.drawPath(body, Draw.fill(if (active) PLASTIC_ACTIVE else PLASTIC))
        c.drawPath(body, cosmetic(EDGE, 1.2, zoom))
        val fine = r * zoom * Math.PI / 180.0 >= 3.0
        for (d in 0..180) {
            val ten = d % 10 == 0
            val five = d % 5 == 0
            if (!fine && !five) continue
            val len = if (ten) 22.0 else if (five) 14.0 else 8.0
            val a = Geom.degToRad(d.toDouble())
            val dir = Pt(cos(a), -sin(a))
            Draw.line(c, dir * (r + 12), dir * (r + 12 - len), cosmetic(TICK, if (ten) 1.3 else 1.0, zoom))
            if (ten) {
                centeredText(c, dir * (r - 26), d.toString(), 14.0, TICK)
                centeredText(c, dir * (r - 50), (180 - d).toString(), 11.0, Color.argb(200, 60, 64, 62))
            }
        }
        val base = cosmetic(TICK, 1.2, zoom)
        Draw.line(c, Pt(-r - 12, 0.0), Pt(r + 12, 0.0), base)
        val ir = r * 0.42
        c.drawArc(RectF((-ir).toFloat(), (-ir).toFloat(), ir.toFloat(), ir.toFloat()), 0f, -180f, false, base)
        Draw.line(c, Pt(-10.0, 0.0), Pt(10.0, 0.0), base)
        Draw.line(c, Pt(0.0, -10.0), Pt(0.0, 10.0), base)
        val lo = min(arm1, arm2)
        val span = measuredAngle()
        val pie = RectF(-70f, -70f, 70f, 70f)
        c.drawArc(pie, (-lo).toFloat(), (-span).toFloat(), true, Draw.fill(Color.argb(70, 255, 213, 79)))
        c.drawArc(pie, (-lo).toFloat(), (-span).toFloat(), true, Draw.stroke(Color.rgb(255, 193, 7), 2.0))
        fun drawArm(angle: Double, color: Int) {
            val knob = armKnob(angle)
            Draw.line(c, Pt.ZERO, knob, Draw.stroke(color, 3.0))
            Draw.circle(c, knob, 15.0, Draw.fill(color))
            Draw.circle(c, knob, 15.0, cosmetic(Color.WHITE, 1.5, zoom))
        }
        drawArm(arm1, ARM1)
        drawArm(arm2, ARM2)
        val mid = Geom.degToRad(lo + span / 2)
        centeredText(c, Pt(cos(mid), -sin(mid)) * 100.0, "${Geom.formatNumber(span, 0)}°", 22.0, Color.rgb(20, 20, 20), true)
        paintCloseButton(c, closeButtonPos(), 15.0)
        paintRotateKnob(c, rotateHandlePos(), 15.0)
        Draw.circle(c, stampPos(), 15.0, Draw.fill(KNOB))
        Draw.circle(c, stampPos(), 15.0, cosmetic(EDGE, 1.0, zoom))
        centeredText(c, stampPos(), "∠", 18.0, Color.rgb(255, 213, 79), true)
    }

    companion object {
        const val ARM1_HANDLE = 3
        const val ARM2_HANDLE = 4
        const val STAMP_HANDLE = 5
    }
}

class SetSquare : Instrument() {
    override val kind = Kind.SetSquare
    var thirtySixty = false

    private fun vertices(): Triple<Pt, Pt, Pt> {
        val l = 9.0 * pxPerUnit
        var a = Pt.ZERO
        var b: Pt
        var cc: Pt
        if (thirtySixty) {
            val w = l * 1.25
            b = Pt(w, 0.0)
            cc = Pt(0.0, -w * tan(Geom.degToRad(30.0)))
        } else {
            b = Pt(l, 0.0); cc = Pt(0.0, -l)
        }
        val g = (a + b + cc) / 3.0
        a -= g; b -= g; cc -= g
        return Triple(a, b, cc)
    }

    override fun bodyBounds(): RectD { val (a, b, c) = vertices(); return Geom.boundingRect(listOf(a, b, c)) }
    override fun bodyContains(local: Pt): Boolean { val (a, b, c) = vertices(); return Geom.polygonContains(listOf(a, b, c), local) }
    override fun closeButtonPos(): Pt = vertices().first + Pt(30.0, -30.0)
    override fun rotateHandlePos(): Pt = vertices().second + Pt(-64.0, -16.0)
    private fun flipPos(): Pt = vertices().third + Pt(18.0, 76.0)

    override fun handleAt(local: Pt, tol: Double): Int {
        if (Geom.distance(local, flipPos()) <= 16 + tol) return FLIP_HANDLE
        return super.handleAt(local, tol)
    }

    override fun endInteraction(): InstrumentResult {
        if (activeHandle == FLIP_HANDLE) thirtySixty = !thirtySixty
        activeHandle = NO_HANDLE
        return InstrumentResult()
    }

    override fun edgeConstraint(pagePos: Pt, tol: Double): EdgeConstraint? {
        val (a, b, c) = vertices()
        val l = toLocal(pagePos)
        var best = tol
        var out: EdgeConstraint? = null
        for ((p, q) in listOf(Pair(a, b), Pair(a, c), Pair(b, c))) {
            val d = Geom.distanceToSegment(l, p, q)
            if (d <= best) {
                best = d
                out = EdgeConstraint(EdgeConstraint.Kind.Line, toPage(p), Geom.rotated(Geom.normalized(q - p), rotation))
            }
        }
        return out
    }

    override fun statusText() = "${Geom.formatNumber(lineAngle(rotation), 1)}°"

    override fun paint(c: Canvas, zoom: Double, active: Boolean) {
        val (a, b, cc) = vertices()
        val path = Path()
        path.moveTo(a.x.toFloat(), a.y.toFloat()); path.lineTo(b.x.toFloat(), b.y.toFloat()); path.lineTo(cc.x.toFloat(), cc.y.toFloat()); path.close()
        c.drawPath(path, Draw.fill(if (active) PLASTIC_ACTIVE else PLASTIC))
        c.drawPath(path, cosmetic(EDGE, 1.2, zoom))
        val g = (a + b + cc) / 3.0
        val inner = Path()
        val iv = listOf(a, b, cc).map { g + (it - g) * 0.42 }
        inner.moveTo(iv[0].x.toFloat(), iv[0].y.toFloat()); iv.drop(1).forEach { inner.lineTo(it.x.toFloat(), it.y.toFloat()) }; inner.close()
        c.drawPath(inner, Draw.fill(Color.argb(30, 0, 0, 0)))
        c.drawPath(inner, cosmetic(EDGE, 1.2, zoom))
        paintScale(c, a, Geom.normalized(b - a), Pt(0.0, -1.0), Geom.distance(a, b) - 10, pxPerUnit, zoom, true)
        paintScale(c, a, Geom.normalized(cc - a), Pt(1.0, 0.0), Geom.distance(a, cc) - 10, pxPerUnit, zoom, false)
        centeredText(c, a + Pt(58.0, -64.0), "90°", 15.0, TICK, true)
        centeredText(c, b + Pt(-120.0, -22.0), if (thirtySixty) "30°" else "45°", 15.0, TICK, true)
        centeredText(c, cc + Pt(22.0, 130.0), if (thirtySixty) "60°" else "45°", 15.0, TICK, true)
        centeredText(c, g + Pt(0.0, 30.0), statusText(), 14.0, TICK)
        paintCloseButton(c, closeButtonPos(), 15.0)
        paintRotateKnob(c, rotateHandlePos(), 15.0)
        Draw.circle(c, flipPos(), 15.0, Draw.fill(KNOB))
        Draw.circle(c, flipPos(), 15.0, cosmetic(EDGE, 1.0, zoom))
        centeredText(c, flipPos(), if (thirtySixty) "45" else "30", 12.0, Color.rgb(240, 240, 240), true)
    }

    companion object {
        const val FLIP_HANDLE = 3
    }
}

class Compass : Instrument() {
    override val kind = Kind.Compass
    var radius = 160.0
        private set
    var ink = InkStyle()
    private val arc = ArrayList<StrokePoint>()
    private var sweep = 0.0
    private var startAngle = 0.0
    private var lastAngle = 0.0

    fun setRadius(r: Double) { radius = r.coerceIn(20.0, 5000.0) }
    private fun hinge(): Pt = Pt(radius / 2, -(radius * 0.55).coerceIn(70.0, 260.0))

    override fun bodyBounds(): RectD = Geom.boundingRect(listOf(Pt.ZERO, hinge(), Pt(radius, 0.0))).inflated(26.0)
    override fun bodyContains(local: Pt): Boolean {
        val h = hinge()
        return Geom.distanceToSegment(local, Pt.ZERO, h) <= 13 || Geom.distanceToSegment(local, h, Pt(radius, 0.0)) <= 13 || Geom.distance(local, h) <= 22
    }

    override fun closeButtonPos() = Pt(-40.0, hinge().y - 10)
    override fun rotateHandlePos() = Pt(radius * 0.25 - 36, hinge().y * 0.5)

    override fun handleAt(local: Pt, tol: Double): Int {
        if (Geom.distance(local, Pt(radius, 0.0)) <= 26 + tol) return PENCIL_HANDLE
        if (Geom.distance(local, hinge()) <= 24 + tol) return RADIUS_HANDLE
        return super.handleAt(local, tol)
    }

    override fun beginInteraction(handle: Int, pagePos: Pt): Boolean {
        if (!super.beginInteraction(handle, pagePos)) return false
        if (handle == PENCIL_HANDLE) {
            arc.clear()
            arc.add(StrokePoint(toPage(Pt(radius, 0.0))))
            sweep = 0.0
            startAngle = rotation
            lastAngle = rotation
        }
        return true
    }

    private fun appendArcTo(target: Double) {
        val current = startAngle + sweep
        val step = min(2.0, max(0.2, Geom.radToDeg(3.0 / radius)))
        val n = ceil(abs(target - current) / step).toInt()
        for (i in 1..n) {
            val a = current + (target - current) * i / n
            arc.add(StrokePoint(position + Geom.rotated(Pt(radius, 0.0), a)))
        }
        sweep = target - startAngle
    }

    override fun updateInteraction(pagePos: Pt) {
        when (activeHandle) {
            PENCIL_HANDLE -> {
                val a = Geom.angleDeg(pagePos - position)
                var d = Geom.angleDifference(lastAngle, a)
                if (sweep + d > 360.0) d = 360.0 - sweep else if (sweep + d < -360.0) d = -360.0 - sweep
                appendArcTo(startAngle + sweep + d)
                lastAngle = Geom.normalizeDegrees(lastAngle + d)
                rotation = lastAngle
            }
            RADIUS_HANDLE -> {
                val l = toLocal(pagePos)
                val mm = pxPerUnit / 10.0
                setRadius(round(max(20.0, 2.0 * l.x) / mm) * mm)
            }
            else -> super.updateInteraction(pagePos)
        }
    }

    override fun endInteraction(): InstrumentResult {
        var result = InstrumentResult()
        if (activeHandle == PENCIL_HANDLE && abs(sweep) >= 1.0 && arc.size >= 2) {
            var i = ink.copy(pressure = false)
            if (i.style == StrokeStyle.Highlighter) i = i.copy(style = StrokeStyle.Pen)
            result = InstrumentResult(listOf(StrokeObject.fromPagePoints(ArrayList(arc), i)), "Arc drawn")
        }
        arc.clear()
        sweep = 0.0
        activeHandle = NO_HANDLE
        return result
    }

    override fun cancelInteraction() {
        arc.clear(); sweep = 0.0
        super.cancelInteraction()
    }

    override fun statusText(): String {
        var s = "r = " + unitText(radius, pxPerUnit, unitLabel)
        if (activeHandle == PENCIL_HANDLE) s += " · ${Geom.formatNumber(abs(sweep), 0)}°"
        return s
    }

    override fun paint(c: Canvas, zoom: Double, active: Boolean) {
        if (arc.size >= 2) StrokeObject.paintPoints(c, arc.map { StrokePoint(toLocal(it.pos)) }, ink.copy(pressure = false))
        val h = hinge()
        val pencil = Pt(radius, 0.0)
        val guide = cosmetic(Color.argb(200, 255, 213, 79), 1.5, zoom)
        guide.pathEffect = DashPathEffect(floatArrayOf((6 / zoom).toFloat(), (4 / zoom).toFloat()), 0f)
        Draw.line(c, Pt.ZERO, pencil, guide)
        centeredText(c, Pt(radius / 2, 18.0), "r = " + unitText(radius, pxPerUnit, unitLabel), 16.0, Color.rgb(255, 224, 130), true)
        val dark = Draw.stroke(Color.argb(220, 30, 34, 36), 16.0)
        Draw.line(c, Pt(0.0, -6.0), h, dark); Draw.line(c, h, pencil + Pt(0.0, -10.0), dark)
        val metal = Draw.stroke(Color.argb(235, 120, 128, 134), 12.0)
        Draw.line(c, Pt(0.0, -6.0), h, metal); Draw.line(c, h, pencil + Pt(0.0, -10.0), metal)
        Draw.line(c, Pt(0.0, -8.0), Pt.ZERO, Draw.stroke(Color.rgb(220, 220, 220), 3.0))
        Draw.circle(c, Pt.ZERO, 5.0, Draw.fill(Color.rgb(239, 83, 80)))
        val tip = Path()
        tip.moveTo((pencil.x - 8).toFloat(), (pencil.y - 14).toFloat()); tip.lineTo((pencil.x + 8).toFloat(), (pencil.y - 14).toFloat()); tip.lineTo(pencil.x.toFloat(), pencil.y.toFloat()); tip.close()
        c.drawPath(tip, Draw.fill(Color.rgb(250, 224, 170)))
        c.drawPath(tip, Draw.stroke(Color.rgb(40, 40, 40), 1.0))
        Draw.circle(c, pencil, 4.0, Draw.fill(ink.color))
        val ring = Draw.stroke(Color.argb(180, 255, 255, 255), 2.0)
        ring.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        Draw.circle(c, pencil, 24.0, ring)
        Draw.circle(c, h, 18.0, Draw.fill(Color.rgb(60, 66, 70)))
        Draw.circle(c, h, 18.0, Draw.stroke(Color.rgb(20, 20, 20), 1.5))
        val arr = Draw.stroke(Color.rgb(230, 230, 230), 2.0)
        Draw.line(c, h + Pt(-8.0, 0.0), h + Pt(8.0, 0.0), arr)
        Draw.line(c, h + Pt(-8.0, 0.0), h + Pt(-4.0, -4.0), arr); Draw.line(c, h + Pt(-8.0, 0.0), h + Pt(-4.0, 4.0), arr)
        Draw.line(c, h + Pt(8.0, 0.0), h + Pt(4.0, -4.0), arr); Draw.line(c, h + Pt(8.0, 0.0), h + Pt(4.0, 4.0), arr)
        paintCloseButton(c, closeButtonPos(), 15.0)
        paintRotateKnob(c, rotateHandlePos(), 15.0)
    }

    companion object {
        const val PENCIL_HANDLE = 3
        const val RADIUS_HANDLE = 4
    }
}

/** The instruments lying on the board; routes pointer interactions to them. */
class InstrumentLayer {
    val instruments = ArrayList<Instrument>()
    private val owners = HashMap<Int, Instrument>()
    private var pxPerUnit = 40.0
    private var unitLabel = "cm"
    var onChanged: (() -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onVisibilityChanged: (() -> Unit)? = null

    fun isShown(k: Instrument.Kind) = find(k) != null
    fun find(k: Instrument.Kind) = instruments.firstOrNull { it.kind == k }
    val isEmpty get() = instruments.isEmpty()

    fun show(k: Instrument.Kind, pagePos: Pt) {
        if (find(k) != null) return
        val inst = Instrument.create(k)
        inst.pxPerUnit = pxPerUnit
        inst.unitLabel = unitLabel
        inst.position = pagePos
        instruments.add(inst)
        onVisibilityChanged?.invoke()
        onChanged?.invoke()
    }

    fun hide(k: Instrument.Kind) {
        val inst = find(k) ?: return
        owners.entries.removeAll { it.value === inst }
        instruments.remove(inst)
        onVisibilityChanged?.invoke()
        onChanged?.invoke()
    }

    fun toggle(k: Instrument.Kind, pagePos: Pt) = if (find(k) != null) hide(k) else show(k, pagePos)

    fun setUnits(ppu: Double, label: String) {
        pxPerUnit = ppu; unitLabel = label
        for (i in instruments) { i.pxPerUnit = ppu; i.unitLabel = label }
        onChanged?.invoke()
    }

    fun instrumentAt(pagePos: Pt, tol: Double): Instrument? =
        instruments.lastOrNull { it.handleAt(it.toLocal(pagePos), tol) != Instrument.NO_HANDLE }

    fun hitTest(pagePos: Pt, tol: Double) = instrumentAt(pagePos, tol) != null
    fun ownsPointer(id: Int) = owners.containsKey(id)

    fun pointerDown(id: Int, pagePos: Pt, tol: Double): Boolean {
        for (inst in instruments.asReversed()) {
            if (inst.isInteracting) continue
            val handle = inst.handleAt(inst.toLocal(pagePos), tol)
            if (handle == Instrument.NO_HANDLE) continue
            if (handle == Instrument.CLOSE_HANDLE) {
                owners[id] = inst
                inst.beginInteraction(handle, pagePos)
                return true
            }
            if (!inst.beginInteraction(handle, pagePos)) return false
            owners[id] = inst
            instruments.remove(inst)
            instruments.add(inst)
            onChanged?.invoke()
            return true
        }
        return false
    }

    fun pointerMove(id: Int, pagePos: Pt) {
        val inst = owners[id] ?: return
        if (inst.activeHandle == Instrument.CLOSE_HANDLE) return
        inst.updateInteraction(pagePos)
        onStatus?.invoke(inst.statusText())
        onChanged?.invoke()
    }

    fun pointerUp(id: Int): InstrumentResult {
        val inst = owners.remove(id) ?: return InstrumentResult()
        if (inst.activeHandle == Instrument.CLOSE_HANDLE) {
            inst.cancelInteraction()
            hide(inst.kind)
            onStatus?.invoke("")
            return InstrumentResult()
        }
        val r = inst.endInteraction()
        onStatus?.invoke("")
        onChanged?.invoke()
        return r
    }

    fun pointerCancel(id: Int) {
        val inst = owners.remove(id) ?: return
        inst.cancelInteraction()
        onStatus?.invoke("")
        onChanged?.invoke()
    }

    fun edgeConstraint(pagePos: Pt, tol: Double): EdgeConstraint? {
        var best = Double.MAX_VALUE
        var out: EdgeConstraint? = null
        for (inst in instruments) {
            val c = inst.edgeConstraint(pagePos, tol) ?: continue
            val d = c.distanceTo(pagePos)
            if (d < best) { best = d; out = c }
        }
        return out
    }

    fun setInk(ink: InkStyle) { (find(Instrument.Kind.Compass) as? Compass)?.ink = ink }

    fun paint(c: Canvas, zoom: Double) {
        for (inst in instruments) {
            c.save()
            c.translate(inst.position.x.toFloat(), inst.position.y.toFloat())
            c.rotate(inst.rotation.toFloat())
            inst.paint(c, zoom, inst.isInteracting)
            c.restore()
        }
    }
}
