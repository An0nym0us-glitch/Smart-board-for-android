package com.classboard.app.doc

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import org.json.JSONObject
import kotlin.math.max

/** A picture placed on a page; pixels live once in the document ImageStore. */
class ImageObject : BoardObject(ObjectType.Image) {
    var imageKey = ""
        private set
    var w = 200.0
        private set
    var h = 150.0
        private set

    override fun localBounds() = RectD(-w / 2, -h / 2, w / 2, h / 2)

    override fun paint(canvas: Canvas, ctx: RenderContext) {
        val target = localBounds()
        val store = ctx.images
        if (store != null && store.contains(imageKey)) {
            val scale = if (ctx.exporting) 3.0 else max(0.05, ctx.zoom * 1.5)
            val bmp = store.bitmapForSize(imageKey, w * scale, h * scale)
            if (bmp != null) {
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(bmp, null, target.toRectF(), p)
                return
            }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = 0x14FFFFFF
        canvas.drawRect(target.toRectF(), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0x78FFFFFF
        p.pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
        canvas.drawRect(target.toRectF(), p)
        canvas.drawLine(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat(), p)
        canvas.drawLine(target.right.toFloat(), target.top.toFloat(), target.left.toFloat(), target.bottom.toFloat(), p)
    }

    override fun keepAspectRatio() = true
    override fun collectImageKeys(keys: MutableSet<String>) { keys.add(imageKey) }

    override fun clone(): BoardObject {
        val o = ImageObject()
        copyBaseTo(o)
        o.imageKey = imageKey; o.w = w; o.h = h
        return o
    }

    override fun applyResize(w: Double, h: Double, hint: ResizeHint) {
        this.w = w; this.h = h
    }

    override fun writeProperties(o: JSONObject) {
        o.put("image", imageKey)
        o.put("size", Json.fromSize(w, h))
    }

    override fun readProperties(o: JSONObject): Boolean {
        imageKey = o.optString("image", "")
        val (sw, sh) = Json.toSize(o.opt("size"), 200.0, 150.0)
        w = sw; h = sh
        return imageKey.isNotEmpty()
    }

    companion object {
        fun create(key: String, w: Double, h: Double, center: Pt): ImageObject {
            val o = ImageObject()
            o.imageKey = key; o.w = w; o.h = h
            o.position = center
            return o
        }
    }
}
