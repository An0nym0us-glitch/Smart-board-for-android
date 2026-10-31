package com.classboard.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.classboard.app.core.RectD
import com.classboard.app.doc.Document
import com.classboard.app.doc.ImageStore
import com.classboard.app.doc.Page
import com.classboard.app.doc.RenderContext
import com.classboard.app.doc.TemplateRenderer
import com.classboard.app.math.CoordinateSystem
import kotlin.math.max
import kotlin.math.min

/** Draws a page (template + objects); used by the canvas, thumbnails and every exporter. */
object PageRenderer {
    /** canvas must be in page coordinates. */
    fun render(
        canvas: Canvas, page: Page, area: RectD, zoom: Double, images: ImageStore, pageCoordinates: CoordinateSystem,
        hidden: Set<String> = emptySet(), exporting: Boolean = false, drawBackground: Boolean = true,
    ) {
        if (drawBackground) TemplateRenderer.paint(canvas, page.background, area, zoom, images, pageCoordinates, page.frameRect)
        val ctx = RenderContext(zoom, images, pageCoordinates, page, exporting)
        for (o in page.objects) {
            if (o.id in hidden) continue
            if (!o.sceneBounds().intersects(area)) continue
            o.paintOnPage(canvas, ctx)
        }
    }

    fun renderToBitmap(doc: Document, page: Page, area: RectD, w: Int, h: Int, exporting: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(page.background.background)
        val c = Canvas(bmp)
        val sx = w / area.width
        val sy = h / area.height
        c.scale(sx.toFloat(), sy.toFloat())
        c.translate((-area.left).toFloat(), (-area.top).toFloat())
        render(c, page, area, min(sx, sy), doc.images, doc.coordinatesFor(page), exporting = exporting)
        return bmp
    }

    /** Thumbnail of the page frame fitting in maxW x maxH. */
    fun thumbnail(doc: Document, page: Page, maxW: Int, maxH: Int): Bitmap {
        val f = page.frameRect
        val s = min(maxW / f.width, maxH / f.height)
        return renderToBitmap(doc, page, f, (f.width * s).toInt(), (f.height * s).toInt(), exporting = false)
    }
}
