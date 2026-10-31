package com.classboard.app.math

import android.graphics.Canvas
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.RenderContext
import com.classboard.app.doc.ResizeHint
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** A typeset, editable formula. */
class EquationObject : BoardObject(ObjectType.Equation) {
    var latex = ""
        set(v) { field = v; layoutCache = null; invalidateBounds() }
    var pixelSize = 48.0
        set(v) { field = v.coerceIn(8.0, 600.0); layoutCache = null; invalidateBounds() }
    var colorValue = 0xFFF5F5F0.toInt()

    private var layoutCache: MathBox? = null

    fun layout(): MathBox = layoutCache ?: MathTypesetter.layout(latex, pixelSize).also { layoutCache = it }

    override fun localBounds(): RectD {
        val b = layout()
        val w = b.width + 2 * PADDING
        val h = b.height + 2 * PADDING
        return RectD(-w / 2, -h / 2, w / 2, h / 2)
    }

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val b = layout()
        val r = localBounds()
        b.paint(canvas, r.left + PADDING, r.top + PADDING + b.ascent, colorValue)
    }

    override fun keepAspectRatio() = true
    override fun isEditable() = true
    override fun setColor(color: Int): Boolean { colorValue = color; return true }
    override fun color() = colorValue

    override fun clone(): BoardObject {
        val e = EquationObject()
        copyBaseTo(e)
        e.latex = latex; e.pixelSize = pixelSize; e.colorValue = colorValue
        e.layoutCache = layoutCache
        return e
    }

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        val old = localBounds()
        if (old.height > 1.0) pixelSize *= h / old.height
    }

    override fun writeProperties(o: JSONObject) {
        o.put("latex", latex)
        o.put("size", pixelSize)
        o.put("color", Json.fromColor(colorValue))
    }

    override fun readProperties(o: JSONObject): Boolean {
        latex = o.optString("latex", "")
        pixelSize = Json.double(o, "size", 48.0).coerceIn(8.0, 600.0)
        colorValue = Json.toColor(o.opt("color"), colorValue)
        return true
    }

    companion object {
        const val PADDING = 6.0

        fun create(latex: String, center: Pt, pixelSize: Double, color: Int): EquationObject {
            val e = EquationObject()
            e.latex = latex; e.pixelSize = pixelSize; e.colorValue = color
            e.position = center
            return e
        }

        /** Paints a formula centred in a target rect, shrunk to fit (previews). */
        fun paintFormula(c: Canvas, latex: String, target: RectD, pixelSize: Double, color: Int) {
            val box = MathTypesetter.layout(latex, pixelSize)
            var scale = 1.0
            if (box.width > target.width || box.height > target.height)
                scale = min(target.width / max(1.0, box.width), target.height / max(1.0, box.height))
            c.save()
            c.translate(target.center.x.toFloat(), target.center.y.toFloat())
            c.scale(scale.toFloat(), scale.toFloat())
            box.paint(c, -box.width / 2, -box.height / 2 + box.ascent, color)
            c.restore()
        }
    }
}
