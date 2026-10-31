package com.classboard.app.doc

import android.graphics.Canvas
import android.graphics.Matrix
import com.classboard.app.core.Geom
import com.classboard.app.core.Ids
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.math.CoordinateSystem
import org.json.JSONObject

enum class ObjectType(val key: String) {
    Stroke("stroke"), Shape("shape"), Text("text"), Equation("equation"), Image("image"),
    Graph("graph"), Geometry("geometry"), Measurement("measurement"), Table("table");

    companion object {
        fun fromKey(k: String): ObjectType? = values().firstOrNull { it.key == k }
    }
}

/** Everything an object needs to render itself. */
class RenderContext(
    val zoom: Double = 1.0,
    val images: ImageStore? = null,
    val coordinates: CoordinateSystem? = null,
    val page: Page? = null,
    val exporting: Boolean = false,
)

enum class ResizeHint { Corner, Horizontal, Vertical }

/**
 * Base class of every item on a page. Geometry is kept in a local frame centred on the origin;
 * position + rotation place it on the page. Mutations of objects living in a document go
 * through commands so undo/redo works.
 */
abstract class BoardObject(val type: ObjectType) {
    var id: String = Ids.newId()

    var position: Pt = Pt.ZERO
        set(v) { field = v; invalidateBounds() }

    var rotation: Double = 0.0
        set(v) { field = Geom.normalizeDegrees(v); invalidateBounds() }

    private var cachedScene: RectD? = null

    fun mapToPage(local: Pt): Pt = Geom.rotated(local, rotation) + position
    fun mapFromPage(page: Pt): Pt = Geom.rotated(page - position, -rotation)

    /** Local -> page matrix. */
    fun matrix(): Matrix {
        val m = Matrix()
        m.setRotate(rotation.toFloat())
        m.postTranslate(position.x.toFloat(), position.y.toFloat())
        return m
    }

    abstract fun localBounds(): RectD
    open fun outlineMargin(): Double = 0.0

    fun sceneBounds(): RectD {
        cachedScene?.let { return it }
        val local = localBounds().inflated(outlineMargin())
        val r = Geom.boundingRect(local.corners().map { mapToPage(it) })
        cachedScene = r
        return r
    }

    /** Paints in the object's local frame (canvas already transformed). */
    abstract fun paint(canvas: Canvas, ctx: RenderContext)

    /** Paints with the object transform applied. */
    fun paintOnPage(canvas: Canvas, ctx: RenderContext) {
        canvas.save()
        canvas.translate(position.x.toFloat(), position.y.toFloat())
        if (rotation != 0.0) canvas.rotate(rotation.toFloat())
        paint(canvas, ctx)
        canvas.restore()
    }

    fun hitTest(pagePos: Pt, tolerance: Double): Boolean {
        if (!sceneBounds().inflated(tolerance).contains(pagePos)) return false
        return hitTestLocal(mapFromPage(pagePos), tolerance)
    }

    protected open fun hitTestLocal(local: Pt, tolerance: Double): Boolean =
        localBounds().inflated(tolerance + outlineMargin()).contains(local)

    open fun enclosesPoint(pagePos: Pt): Boolean = false

    open fun isInsidePolygon(pagePolygon: List<Pt>): Boolean {
        val b = sceneBounds()
        return Geom.polygonContains(pagePolygon, b.center) && Geom.boundingRect(pagePolygon).intersects(b)
    }

    open fun canResize() = true
    open fun canRotate() = true
    open fun keepAspectRatio() = false

    fun resizeTo(newLocalRect: RectD, hint: ResizeHint = ResizeHint.Corner) {
        var t = newLocalRect.normalized()
        if (t.width < 1.0) t = RectD(t.left, t.top, t.left + 1.0, t.bottom)
        if (t.height < 1.0) t = RectD(t.left, t.top, t.right, t.top + 1.0)
        val old = localBounds()
        applyResize(t.width, t.height, hint)
        val shift = t.center - old.center
        position = position + Geom.rotated(shift, rotation)
        invalidateBounds()
    }

    open fun controlPoints(): List<Pt> = emptyList()
    fun moveControlPointTo(index: Int, pagePos: Pt) {
        setControlPoint(index, mapFromPage(pagePos))
        invalidateBounds()
    }

    open fun setColor(color: Int): Boolean = false
    open fun color(): Int? = null

    open fun collectImageKeys(keys: MutableSet<String>) {}
    open fun mathCoordinateSystem(): CoordinateSystem? = null
    open fun isEditable() = false

    abstract fun clone(): BoardObject

    protected fun copyBaseTo(o: BoardObject): BoardObject {
        o.id = id
        o.position = position
        o.rotation = rotation
        return o
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type.key)
        o.put("id", id)
        o.put("pos", Json.fromPoint(position))
        if (rotation != 0.0) o.put("rot", rotation)
        writeProperties(o)
        return o
    }

    fun fromJson(o: JSONObject): Boolean {
        val i = o.optString("id", "")
        if (i.isNotEmpty()) id = i
        position = Json.toPoint(o.opt("pos"))
        rotation = Json.double(o, "rot", 0.0)
        val ok = readProperties(o)
        invalidateBounds()
        return ok
    }

    protected abstract fun applyResize(w: Double, h: Double, hint: ResizeHint)
    protected open fun setControlPoint(index: Int, local: Pt) {}
    protected open fun translateContent(delta: Pt) {}

    protected fun recenter() {
        val c = localBounds().center
        if (c.length() < 1e-9) return
        translateContent(-c)
        position = position + Geom.rotated(c, rotation)
        invalidateBounds()
    }

    protected abstract fun writeProperties(o: JSONObject)
    protected abstract fun readProperties(o: JSONObject): Boolean

    fun invalidateBounds() { cachedScene = null }
}
