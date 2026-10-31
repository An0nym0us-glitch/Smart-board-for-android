package com.classboard.app.export

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.classboard.app.core.RectD
import com.classboard.app.doc.Document
import com.classboard.app.doc.Page
import com.classboard.app.doc.PageSize
import com.classboard.app.render.PageRenderer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** PDF (vector, real page sizes), PNG and PowerPoint exports of complete pages. */
object Exporters {
    /** Parses "1-3, 5" into zero-based page indices; null when invalid. */
    fun parseRange(text: String, pageCount: Int): List<Int>? {
        val parts = text.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = sortedSetOf<Int>()
        val re = Regex("^(\\d*)\\s*-\\s*(\\d*)$")
        for (part in parts) {
            val m = re.find(part)
            val a: Int
            val b: Int
            if (m != null) {
                a = m.groupValues[1].ifEmpty { "1" }.toInt()
                b = m.groupValues[2].ifEmpty { pageCount.toString() }.toInt()
            } else {
                a = part.toIntOrNull() ?: return null
                b = a
            }
            if (a < 1 || b < a || b > pageCount) return null
            for (i in a..b) out.add(i - 1)
        }
        return out.toList()
    }

    private fun pointsFor(units: Double) = units / PageSize.UNITS_PER_CM / 2.54 * 72.0

    /** Vector PDF: every page at its own physical size. */
    fun exportPdf(doc: Document, pages: List<Page>, out: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val pdf = PdfDocument()
        try {
            for ((i, page) in pages.withIndex()) {
                val area = page.exportRect()
                val wPt = max(1, pointsFor(area.width).roundToInt())
                val hPt = max(1, pointsFor(area.height).roundToInt())
                val info = PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create()
                val p = pdf.startPage(info)
                val c = p.canvas
                val s = min(wPt / area.width, hPt / area.height)
                c.scale(s.toFloat(), s.toFloat())
                c.translate((-area.left).toFloat(), (-area.top).toFloat())
                PageRenderer.render(c, page, area, s * 2.0, doc.images, doc.coordinatesFor(page), exporting = true)
                pdf.finishPage(p)
                progress(i + 1, pages.size)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    /** The complete page as a bitmap whose long side has [longSide] pixels. */
    fun renderPage(doc: Document, page: Page, longSide: Int): Bitmap {
        val area: RectD = page.exportRect()
        val (w, h) = if (area.width >= area.height) Pair(longSide, max(1, (longSide * area.height / area.width).roundToInt()))
        else Pair(max(1, (longSide * area.width / area.height).roundToInt()), longSide)
        return PageRenderer.renderToBitmap(doc, page, area, w, h, true)
    }

    fun exportPng(doc: Document, page: Page, out: OutputStream) {
        val bmp = renderPage(doc, page, 3000)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
    }

    // ------------------------------------------------------------------------------- PPTX

    private const val SLIDE_CX = 12192000L
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private const val EMPTY_GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun rels(items: List<Pair<String, String>>): String {
        val sb = StringBuilder(XML)
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        items.forEachIndexed { i, (type, target) -> sb.append("<Relationship Id=\"rId${i + 1}\" Type=\"$type\" Target=\"$target\"/>") }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private val THEME = XML + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"ClassBoard\"><a:themeElements>" +
        "<a:clrScheme name=\"ClassBoard\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1F2B26\"/></a:dk2><a:lt2><a:srgbClr val=\"E9EEEB\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"5FD3A5\"/></a:accent1><a:accent2><a:srgbClr val=\"4FB3FF\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"FFD54F\"/></a:accent3><a:accent4><a:srgbClr val=\"EF5350\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"BA68C8\"/></a:accent5><a:accent6><a:srgbClr val=\"81C784\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"4FB3FF\"/></a:hlink><a:folHlink><a:srgbClr val=\"BA68C8\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"ClassBoard\"><a:majorFont><a:latin typeface=\"Segoe UI\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Segoe UI\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"ClassBoard\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"25400\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"38100\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>" +
        "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"

    class Slide(val png: ByteArray, val w: Int, val h: Int, val background: Int)

    fun buildPptx(slides: List<Slide>, title: String, out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun add(name: String, text: String) = add(zip, name, text.toByteArray(Charsets.UTF_8), true)
        val n = slides.size
        val firstW = slides.firstOrNull()?.w?.takeIf { it > 0 } ?: 16
        val firstH = slides.firstOrNull()?.h?.takeIf { it > 0 } ?: 9
        val slideCx = SLIDE_CX
        val slideCy = (SLIDE_CX.toDouble() * firstH / firstW).roundToLong().coerceIn(914400L, 51206400L)
        val types = StringBuilder(XML)
        types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
            .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
            .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
            .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
            .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
            .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
            .append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        for (i in 1..n) types.append("<Override PartName=\"/ppt/slides/slide$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        types.append("</Types>")
        add("[Content_Types].xml", types.toString())
        add("_rels/.rels", rels(listOf(
            REL + "officeDocument" to "ppt/presentation.xml",
            "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" to "docProps/core.xml",
            REL + "extended-properties" to "docProps/app.xml",
        )))
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
        val now = fmt.format(Date())
        add("docProps/core.xml", XML + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${esc(title)}</dc:title><dc:creator>ClassBoard</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>")
        add("docProps/app.xml", XML + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>ClassBoard</Application><Slides>$n</Slides></Properties>")
        val pres = StringBuilder(XML)
        pres.append("<p:presentation $NS saveSubsetFonts=\"1\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
        for (i in 0 until n) pres.append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 3}\"/>")
        pres.append("</p:sldIdLst><p:sldSz cx=\"$slideCx\" cy=\"$slideCy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        add("ppt/presentation.xml", pres.toString())
        val presRels = mutableListOf(REL + "slideMaster" to "slideMasters/slideMaster1.xml", REL + "theme" to "theme/theme1.xml")
        for (i in 1..n) presRels.add(REL + "slide" to "slides/slide$i.xml")
        add("ppt/_rels/presentation.xml.rels", rels(presRels))
        add("ppt/slideMasters/slideMaster1.xml", XML + "<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
            "<p:spTree>$EMPTY_GROUP</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" " +
            "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle>" +
            "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"3200\"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>")
        add("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels(listOf(REL + "slideLayout" to "../slideLayouts/slideLayout1.xml", REL + "theme" to "../theme/theme1.xml")))
        add("ppt/slideLayouts/slideLayout1.xml", XML + "<p:sldLayout $NS type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$EMPTY_GROUP</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        add("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels(listOf(REL + "slideMaster" to "../slideMasters/slideMaster1.xml")))
        add("ppt/theme/theme1.xml", THEME)
        for (i in 1..n) {
            val s = slides[i - 1]
            var cx = slideCx
            var cy = (cx.toDouble() * s.h / s.w).roundToLong()
            if (cy > slideCy) { cy = slideCy; cx = (cy.toDouble() * s.w / s.h).roundToLong() }
            val x = (slideCx - cx) / 2
            val y = (slideCy - cy) / 2
            val bg = String.format("%06X", s.background and 0xFFFFFF)
            add("ppt/slides/slide$i.xml", XML + "<p:sld $NS><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$bg\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$EMPTY_GROUP" +
                "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $i\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>" +
                "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
            add("ppt/slides/_rels/slide$i.xml.rels", rels(listOf(REL + "slideLayout" to "../slideLayouts/slideLayout1.xml", REL + "image" to "../media/image$i.png")))
            add(zip, "ppt/media/image$i.png", s.png, false)
        }
        zip.finish()
        zip.flush()
    }

    private fun add(zip: ZipOutputStream, name: String, data: ByteArray, compress: Boolean) {
        val e = ZipEntry(name)
        if (!compress) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = CRC32().also { it.update(data) }.value
        }
        zip.putNextEntry(e)
        zip.write(data)
        zip.closeEntry()
    }

    fun exportPptx(doc: Document, pages: List<Page>, title: String, out: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val slides = ArrayList<Slide>()
        for ((i, p) in pages.withIndex()) {
            val bmp = renderPage(doc, p, 2560)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            slides.add(Slide(bos.toByteArray(), bmp.width, bmp.height, p.background.background))
            bmp.recycle()
            progress(i + 1, pages.size + 1)
        }
        buildPptx(slides, title, out)
    }
}
