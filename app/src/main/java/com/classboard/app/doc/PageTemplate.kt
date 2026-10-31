package com.classboard.app.doc

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.classboard.app.core.Geom
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.math.CoordinateSystem
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class TemplateKind(val key: String) {
    Blank("blank"), Grid("grid"), Dots("dots"), Ruled("ruled"), GraphPaper("graph"),
    MusicStaff("music"), CoordinatePlane("coordinate"), Image("image");

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: Blank
    }
}

/** Background description of a page, stored by value in each page. */
data class TemplateSpec(
    val id: String = "blackboard",
    val name: String = "Blackboard",
    val kind: TemplateKind = TemplateKind.Blank,
    val background: Int = 0xFF1F2B26.toInt(),
    val lineColor: Int = 0x28FFFFFF,
    val accentColor: Int = 0x5AFFFFFF,
    val spacing: Double = 40.0,
    val majorEvery: Int = 5,
    val imageKey: String = "",
) {
    val isDark: Boolean
        get() {
            val hsl = FloatArray(3)
            val r = Color.red(background) / 255f
            val g = Color.green(background) / 255f
            val b = Color.blue(background) / 255f
            val l = (max(r, max(g, b)) + min(r, min(g, b))) / 2f
            hsl[2] = l
            return l < 0.5f
        }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("kind", kind.key)
        o.put("background", Json.fromColor(background))
        o.put("line", Json.fromColor(lineColor))
        o.put("accent", Json.fromColor(accentColor))
        o.put("spacing", spacing)
        o.put("major", majorEvery)
        if (imageKey.isNotEmpty()) o.put("image", imageKey)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): TemplateSpec {
            val d = TemplateSpec()
            if (o == null) return d
            return TemplateSpec(
                id = o.optString("id", d.id),
                name = o.optString("name", d.name),
                kind = TemplateKind.fromKey(o.optString("kind")),
                background = Json.toColor(o.opt("background"), d.background),
                lineColor = Json.toColor(o.opt("line"), d.lineColor),
                accentColor = Json.toColor(o.opt("accent"), d.accentColor),
                spacing = max(4.0, Json.double(o, "spacing", d.spacing)),
                majorEvery = max(0, o.optInt("major", d.majorEvery)),
                imageKey = o.optString("image", ""),
            )
        }
    }
}

/** Draws template backgrounds for any visible area with level-of-detail control. */
object TemplateRenderer {
    private fun line(c: Int, px: Double, zoom: Double): Paint {
        val p = Paint()
        p.color = c
        p.style = Paint.Style.STROKE
        p.strokeWidth = (px / zoom).toFloat()
        return p
    }

    /** canvas is in page coordinates. */
    fun paint(canvas: Canvas, spec: TemplateSpec, visible: RectD, zoom: Double, images: ImageStore?, cs: CoordinateSystem, frame: RectD) {
        val bg = Paint()
        bg.color = spec.background
        canvas.drawRect(visible.toRectF(), bg)
        val anchor = cs.originPx()
        when (spec.kind) {
            TemplateKind.Blank -> {}
            TemplateKind.Grid -> paintGrid(canvas, spec, visible, zoom, anchor, spec.spacing, spec.majorEvery)
            TemplateKind.GraphPaper -> paintGrid(canvas, spec, visible, zoom, anchor, spec.spacing, max(2, spec.majorEvery))
            TemplateKind.Dots -> paintDots(canvas, spec, visible, zoom, anchor)
            TemplateKind.Ruled -> paintRuled(canvas, spec, visible, zoom, frame)
            TemplateKind.MusicStaff -> paintMusic(canvas, spec, visible, zoom)
            TemplateKind.CoordinatePlane -> paintCoordinatePlane(canvas, spec, visible, zoom, cs)
            TemplateKind.Image -> {
                if (images != null && images.contains(spec.imageKey)) {
                    val bmp = images.bitmapForSize(spec.imageKey, frame.width * zoom, frame.height * zoom) ?: return
                    val s = min(frame.width / bmp.width, frame.height / bmp.height)
                    val w = bmp.width * s
                    val h = bmp.height * s
                    val target = RectD.fromCenter(frame.center, w, h)
                    canvas.drawBitmap(bmp, null, target.toRectF(), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }
    }

    fun paintGrid(canvas: Canvas, spec: TemplateSpec, visible: RectD, zoom: Double, anchor: Pt, spacing: Double, majorEvery: Int) {
        val screenSpacing = spacing * zoom
        val drawMinor = screenSpacing >= 5.0
        val haveMajor = majorEvery > 1
        if (!drawMinor && !haveMajor) return
        val step = if (drawMinor) spacing else spacing * majorEvery
        val i0 = floor((visible.left - anchor.x) / step).toLong()
        val i1 = ceil((visible.right - anchor.x) / step).toLong()
        val j0 = floor((visible.top - anchor.y) / step).toLong()
        val j1 = ceil((visible.bottom - anchor.y) / step).toLong()
        if (i1 - i0 > 4000 || j1 - j0 > 4000) return
        val minor = line(spec.lineColor, 1.0, zoom)
        val major = line(spec.accentColor, 1.0, zoom)
        val ratio = if (drawMinor) majorEvery else 1
        val t = visible.top.toFloat(); val b = visible.bottom.toFloat()
        val l = visible.left.toFloat(); val r = visible.right.toFloat()
        for (i in i0..i1) {
            val x = (anchor.x + i * step).toFloat()
            val isMajor = haveMajor && (!drawMinor || i % ratio == 0L)
            canvas.drawLine(x, t, x, b, if (isMajor) major else minor)
        }
        for (j in j0..j1) {
            val y = (anchor.y + j * step).toFloat()
            val isMajor = haveMajor && (!drawMinor || j % ratio == 0L)
            canvas.drawLine(l, y, r, y, if (isMajor) major else minor)
        }
    }

    private fun paintDots(canvas: Canvas, spec: TemplateSpec, visible: RectD, zoom: Double, anchor: Pt) {
        var step = spec.spacing
        while (step * zoom < 8.0) step *= 2.0
        val i0 = floor((visible.left - anchor.x) / step).toLong()
        val i1 = ceil((visible.right - anchor.x) / step).toLong()
        val j0 = floor((visible.top - anchor.y) / step).toLong()
        val j1 = ceil((visible.bottom - anchor.y) / step).toLong()
        if ((i1 - i0) * (j1 - j0) > 250000) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = spec.accentColor
        val r = max(1.2 / zoom, 1.6).toFloat()
        for (i in i0..i1) for (j in j0..j1)
            canvas.drawCircle((anchor.x + i * step).toFloat(), (anchor.y + j * step).toFloat(), r, p)
    }

    private fun paintRuled(canvas: Canvas, spec: TemplateSpec, visible: RectD, zoom: Double, frame: RectD) {
        val step = spec.spacing
        if (step * zoom >= 4.0) {
            val p = line(spec.lineColor, 1.0, zoom)
            val j0 = floor((visible.top - frame.top) / step).toLong()
            val j1 = ceil((visible.bottom - frame.top) / step).toLong()
            for (j in max(2L, j0)..j1) {
                val y = (frame.top + j * step).toFloat()
                canvas.drawLine(visible.left.toFloat(), y, visible.right.toFloat(), y, p)
            }
        }
        val mx = frame.left + step * 3
        if (mx >= visible.left && mx <= visible.right) {
            canvas.drawLine(mx.toFloat(), visible.top.toFloat(), mx.toFloat(), visible.bottom.toFloat(), line(spec.accentColor, 1.5, zoom))
        }
    }

    private fun paintMusic(canvas: Canvas, spec: TemplateSpec, visible: RectD, zoom: Double) {
        val gap = spec.spacing
        if (gap * zoom < 1.2) return
        val period = gap * 11.0
        val topMargin = gap * 4.0
        val p = line(spec.lineColor, 1.2, zoom)
        val k0 = floor((visible.top - topMargin) / period).toLong() - 1
        val k1 = ceil((visible.bottom - topMargin) / period).toLong()
        for (k in k0..k1) {
            val base = topMargin + k * period
            for (ln in 0 until 5) {
                val y = base + ln * gap
                if (y < visible.top - 1 || y > visible.bottom + 1) continue
                canvas.drawLine(visible.left.toFloat(), y.toFloat(), visible.right.toFloat(), y.toFloat(), p)
            }
        }
    }

    private fun paintCoordinatePlane(canvas: Canvas, spec: TemplateSpec, visible: RectD, zoom: Double, cs: CoordinateSystem) {
        val unit = cs.pxPerUnit()
        val origin = cs.originPx()
        var every = 1
        while (unit * every * zoom < 12.0 && every < 100000) every *= 2
        paintGrid(canvas, spec, visible, zoom, origin, unit * every, 5)
        val axisColor = if (spec.isDark) Color.argb(210, 235, 240, 235) else Color.argb(220, 30, 30, 30)
        val axis = line(axisColor, 2.0, zoom)
        axis.isAntiAlias = true
        if (origin.y >= visible.top && origin.y <= visible.bottom)
            canvas.drawLine(visible.left.toFloat(), origin.y.toFloat(), visible.right.toFloat(), origin.y.toFloat(), axis)
        if (origin.x >= visible.left && origin.x <= visible.right)
            canvas.drawLine(origin.x.toFloat(), visible.top.toFloat(), origin.x.toFloat(), visible.bottom.toFloat(), axis)
        var labelStep = 1.0
        while (labelStep * unit * zoom < 48.0) labelStep = Geom.niceNumber(labelStep * 2.0, true)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = axisColor
        tp.textSize = (14.0 / zoom).toFloat()
        val fontPx = 14.0 / zoom
        val a = cs.toMath(visible.topLeft)
        val b = cs.toMath(visible.bottomRight)
        val xMin = min(a.x, b.x); val xMax = max(a.x, b.x)
        val yMin = min(a.y, b.y); val yMax = max(a.y, b.y)
        if (origin.y >= visible.top - 30 && origin.y <= visible.bottom + 30) {
            tp.textAlign = Paint.Align.CENTER
            var x = ceil(xMin / labelStep) * labelStep
            while (x <= xMax) {
                if (abs(x) >= labelStep * 0.5) {
                    val pp = cs.toPage(Pt(x, 0.0))
                    canvas.drawText(Geom.formatNumber(x, 3), pp.x.toFloat(), (pp.y + fontPx * 1.3).toFloat(), tp)
                }
                x += labelStep
            }
        }
        if (origin.x >= visible.left - 90 && origin.x <= visible.right + 90) {
            tp.textAlign = Paint.Align.RIGHT
            var y = ceil(yMin / labelStep) * labelStep
            while (y <= yMax) {
                if (abs(y) >= labelStep * 0.5) {
                    val pp = cs.toPage(Pt(0.0, y))
                    canvas.drawText(Geom.formatNumber(y, 3), (pp.x - fontPx * 0.45).toFloat(), (pp.y + fontPx * 0.35).toFloat(), tp)
                }
                y += labelStep
            }
        }
        if (visible.contains(origin)) {
            tp.textAlign = Paint.Align.RIGHT
            canvas.drawText("0", (origin.x - fontPx * 0.45).toFloat(), (origin.y + fontPx * 1.3).toFloat(), tp)
        }
    }
}
