package com.classboard.app.doc

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.classboard.app.core.Json
import com.classboard.app.core.RectD
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Qt alignment flags kept for file compatibility. */
object Align {
    const val LEFT = 0x1
    const val RIGHT = 0x2
    const val HCENTER = 0x4
}

data class TextFormat(
    val family: String = "",
    val pixelSize: Int = 40,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int = 0xFFF5F5F0.toInt(),
    val alignment: Int = Align.LEFT,
) {
    fun typeface(): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(if (family.isEmpty()) Typeface.SANS_SERIF else Typeface.create(family, Typeface.NORMAL), style)
    }

    fun paint(): TextPaint {
        val p = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
        p.typeface = typeface()
        p.textSize = max(6, pixelSize).toFloat()
        p.isUnderlineText = underline
        p.color = color
        return p
    }

    fun layoutAlignment(): Layout.Alignment = when {
        alignment and Align.HCENTER != 0 -> Layout.Alignment.ALIGN_CENTER
        alignment and Align.RIGHT != 0 -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
}

/** Editable text box: width set by the user, height follows the wrapped content. */
class TextObject : BoardObject(ObjectType.Text) {
    var text: String = ""
        set(v) { field = v; layoutCache = null; invalidateBounds() }
    var format = TextFormat()
        set(v) { field = v; layoutCache = null; invalidateBounds() }
    var boxWidth = 400.0
        set(v) { field = max(20.0, v); layoutCache = null; invalidateBounds() }

    private var layoutCache: StaticLayout? = null

    private fun layout(): StaticLayout {
        layoutCache?.let { return it }
        val l = makeLayout(if (text.isEmpty()) " " else text, format, boxWidth)
        layoutCache = l
        return l
    }

    private fun contentHeight(): Double {
        val p = format.paint()
        return max(layout().height.toDouble(), (p.fontMetrics.descent - p.fontMetrics.ascent).toDouble())
    }

    override fun localBounds(): RectD {
        val h = contentHeight()
        return RectD(-boxWidth / 2, -h / 2, boxWidth / 2, h / 2)
    }

    override fun outlineMargin() = 4.0

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        if (text.isEmpty()) return
        val b = localBounds()
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        layout().draw(canvas)
        canvas.restore()
    }

    override fun setColor(color: Int): Boolean {
        format = format.copy(color = color); return true
    }

    override fun color() = format.color
    override fun isEditable() = true

    override fun clone(): BoardObject {
        val t = TextObject()
        copyBaseTo(t)
        t.text = text; t.format = format; t.boxWidth = boxWidth
        return t
    }

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        val old = localBounds()
        if (hint != ResizeHint.Horizontal && old.height > 1.0) {
            val f = h / old.height
            format = format.copy(pixelSize = (format.pixelSize * f).roundToInt().coerceIn(8, 800))
        }
        boxWidth = max(20.0, w)
    }

    override fun writeProperties(o: JSONObject) {
        o.put("text", text)
        o.put("width", boxWidth)
        if (format.family.isNotEmpty()) o.put("family", format.family)
        o.put("size", format.pixelSize)
        o.put("bold", format.bold)
        o.put("italic", format.italic)
        o.put("underline", format.underline)
        o.put("color", Json.fromColor(format.color))
        o.put("align", format.alignment)
    }

    override fun readProperties(o: JSONObject): Boolean {
        text = o.optString("text", "")
        boxWidth = max(20.0, Json.double(o, "width", 400.0))
        format = TextFormat(
            family = o.optString("family", ""),
            pixelSize = o.optInt("size", 40).coerceIn(6, 800),
            bold = o.optBoolean("bold"),
            italic = o.optBoolean("italic"),
            underline = o.optBoolean("underline"),
            color = Json.toColor(o.opt("color"), 0xFFF5F5F0.toInt()),
            alignment = o.optInt("align", Align.LEFT),
        )
        return true
    }

    companion object {
        fun makeLayout(text: String, format: TextFormat, width: Double): StaticLayout {
            val p = format.paint()
            return StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, ceil(width).toInt()))
                .setAlignment(format.layoutAlignment())
                .setIncludePad(false)
                .build()
        }

        fun naturalWidth(text: String, format: TextFormat): Double {
            val p = format.paint()
            var w = 0.0
            for (line in text.split('\n')) w = max(w, p.measureText(line).toDouble())
            return min(w, 1600.0)
        }

        fun create(text: String, topLeft: com.classboard.app.core.Pt, format: TextFormat, width: Double = 0.0): TextObject {
            val t = TextObject()
            t.text = text
            t.format = format
            t.boxWidth = if (width > 0) width else max(120.0, naturalWidth(text, format) + 8.0)
            val b = t.localBounds()
            t.position = com.classboard.app.core.Pt(topLeft.x + t.boxWidth / 2, topLeft.y + b.height / 2)
            return t
        }
    }
}
