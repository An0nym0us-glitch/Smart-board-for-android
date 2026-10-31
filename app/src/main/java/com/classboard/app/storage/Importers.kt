package com.classboard.app.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.classboard.app.doc.Document
import com.classboard.app.doc.ImageObject
import com.classboard.app.doc.InsertPageCommand
import com.classboard.app.doc.CompositeCommand
import com.classboard.app.doc.Command
import com.classboard.app.doc.PageSize
import com.classboard.app.doc.TemplateLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One imported page: PNG bytes plus the physical page size. */
class ImportedPage(val encoded: ByteArray, val pixelW: Int, val pixelH: Int, val widthMm: Double, val heightMm: Double)

/** Renders PDF pages with Android's built-in PdfRenderer (offline), keeping each page's size. */
object PdfImporter {
    const val DEFAULT_DPI = 150

    fun render(context: Context, uri: Uri, dpi: Int = DEFAULT_DPI, progress: (Int, Int) -> Unit = { _, _ -> }): List<ImportedPage> {
        // PdfRenderer needs a seekable file: copy content URIs to the cache first.
        val tmp = File.createTempFile("import", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("The PDF could not be read.")
            val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(pfd) } catch (e: Exception) {
                pfd.close()
                throw IllegalStateException("The PDF could not be opened. It may be damaged or protected.")
            }
            val out = ArrayList<ImportedPage>()
            renderer.use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { page ->
                        val wPt = page.width.toDouble()
                        val hPt = page.height.toDouble()
                        var scale = dpi / 72.0
                        // Bound the bitmap size (memory) for very large pages.
                        val maxSide = 3000.0
                        if (max(wPt, hPt) * scale > maxSide) scale = maxSide / max(wPt, hPt)
                        val bw = max(1, (wPt * scale).roundToInt())
                        val bh = max(1, (hPt * scale).roundToInt())
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        bmp.recycle()
                        out.add(ImportedPage(bos.toByteArray(), bw, bh, wPt * 25.4 / 72.0, hPt * 25.4 / 72.0))
                    }
                    progress(i + 1, r.pageCount)
                }
            }
            if (out.isEmpty()) throw IllegalStateException("The PDF does not contain any pages.")
            return out
        } finally {
            tmp.delete()
        }
    }
}

enum class ImportSizing { Physical, BoardSized }

object PageImport {
    /** Inserts imported pages after afterIndex as one undo step. Returns the number added. */
    fun insertPages(doc: Document, afterIndex: Int, pages: List<ImportedPage>, baseName: String, sizing: ImportSizing, text: String): Int {
        var index = (afterIndex + 1).coerceIn(0, doc.pageCount)
        val cmds = ArrayList<Command>()
        for ((i, src) in pages.withIndex()) {
            if (src.pixelW <= 0 || src.pixelH <= 0) continue
            val key = doc.images.addEncoded(src.encoded) ?: continue
            val aspect = src.pixelH.toDouble() / src.pixelW
            val (w, h) = if (sizing == ImportSizing.Physical && src.widthMm > 1.0) {
                val width = (src.widthMm / 10.0).coerceIn(5.0, 500.0) * PageSize.UNITS_PER_CM
                Pair(width, width * aspect)
            } else PageSize.forAspect(src.pixelW.toDouble(), src.pixelH.toDouble())
            val page = doc.createPage()
            page.width = w; page.height = h
            page.background = TemplateLibrary.withBackgroundColor(page.background, Color.WHITE)
            page.name = "$baseName ${i + 1}"
            page.objects.add(ImageObject.create(key, w, h, page.frameRect.center))
            val cmd = InsertPageCommand(index++, page, text)
            cmd.redo(doc)
            cmds.add(cmd)
        }
        if (cmds.isNotEmpty()) doc.recordApplied(CompositeCommand(text, cmds))
        return cmds.size
    }

    @Suppress("unused")
    private fun unused(a: Int, b: Int) = min(a, b)
}
