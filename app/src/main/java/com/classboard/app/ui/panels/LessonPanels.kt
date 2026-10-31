package com.classboard.app.ui.panels

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.view.View
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.CompositeCommand
import com.classboard.app.doc.PagePropsCommand
import com.classboard.app.doc.TemplateKind
import com.classboard.app.doc.TemplateRenderer
import com.classboard.app.doc.TemplateSpec
import com.classboard.app.math.CoordinateSystem
import com.classboard.app.ui.App
import com.classboard.app.ui.Tile
import com.classboard.app.ui.TileGrid
import com.classboard.app.export.Exporters

object LessonPanels {
    private fun App.closeThen(f: () -> Unit): () -> Unit = { popovers.closeAll(); f() }

    fun lesson(app: App): View {
        val k = app.kit
        val l = k.layout()
        l.addView(k.tiles(3, listOf(
            Tile("file-new", "New", action = app.closeThen { app.newLesson() }),
            Tile("folder-open", "Open", action = app.closeThen { app.open() }),
            Tile("save", "Save", action = app.closeThen { app.save() }),
            Tile("save-as", "Save as", action = app.closeThen { app.saveAs() }),
            Tile("import", "Import", action = { app.popovers.push("import") }),
            Tile("export", "Export", action = { app.popovers.push("export") }),
        )))
        val recent = app.settings.recentFiles()
        if (recent.isNotEmpty()) {
            l.addView(k.section("Recent lessons"))
            for ((uri, name) in recent) {
                l.addView(k.rowButton("file", name.removeSuffix(".classboard")) {
                    app.popovers.closeAll()
                    app.confirmDiscard { app.openUri(Uri.parse(uri)) }
                })
            }
        }
        var status = app.doc.fileName ?: "Not saved yet"
        if (app.doc.isModified) status += " · unsaved changes (autosave protects them)"
        l.addView(k.hint(status))
        return l
    }

    @SuppressLint("ViewConstructor")
    private class TemplateTile(val app: App, val spec: TemplateSpec, var checked: Boolean) : View(app.activity) {
        private val t = app.t
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isClickable = true; contentDescription = spec.name }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(t.dp(128.0).toInt(), w), t.dp(100.0).toInt())

        override fun onDraw(c: Canvas) {
            val tw = width - t.dp(8.0)
            val thumb = RectF(t.dp(4.0), t.dp(4.0), t.dp(4.0) + tw, t.dp(4.0) + tw * 9f / 16f)
            val frame = RectD(0.0, 0.0, 1920.0, 1080.0)
            c.save()
            val clip = Path()
            clip.addRoundRect(thumb, t.dp(8.0), t.dp(8.0), Path.Direction.CW)
            c.clipPath(clip)
            c.translate(thumb.left, thumb.top)
            val s = thumb.width() / 1920.0
            c.scale(s.toFloat(), s.toFloat())
            val cs = CoordinateSystem.global(frame.center, 40.0, "cm")
            TemplateRenderer.paint(c, spec, frame, s * 2.5, app.doc.images, cs, frame)
            c.restore()
            paint.style = Paint.Style.STROKE
            paint.color = if (checked) t.accent else t.popoverBorder
            paint.strokeWidth = t.dp(if (checked) 3.0 else 1.0)
            c.drawRoundRect(thumb, t.dp(8.0), t.dp(8.0), paint)
            paint.style = Paint.Style.FILL
            paint.color = if (checked) t.accent else t.text
            paint.textSize = t.dp(t.smallFontSize)
            paint.typeface = if (checked) t.typefaceBold else t.typeface
            paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            val cy = (thumb.bottom + height) / 2
            c.drawText(spec.name, width / 2f, cy - (fm.ascent + fm.descent) / 2, paint)
        }
    }

    fun templates(app: App): View {
        val k = app.kit
        val l = k.layout()
        val doc = app.doc
        var scope = 0
        l.addView(k.section("Apply to"))
        l.addView(k.segmented(listOf("" to "This page", "" to "All pages", "" to "New pages"), 0) { scope = it })
        for (spec in app.templates.templates) app.templates.ensureImage(spec, doc.images)
        val grid = TileGrid(app.activity, 4, app.t.dp(8.0).toInt())
        val currentId = doc.currentPage.background.id
        for (spec in app.templates.templates) {
            val tile = TemplateTile(app, spec, spec.id == currentId)
            tile.setOnClickListener {
                app.templates.ensureImage(spec, doc.images)
                when (scope) {
                    0 -> doc.changePages(listOf(doc.currentPage), "Change background") { p -> doc.propsOf(p).copy(background = spec) }
                    1 -> doc.changePages(doc.pages.toList(), "Change all backgrounds") { p -> doc.propsOf(p).copy(background = spec) }
                    else -> {
                        doc.defaultTemplate = spec
                        app.settings.defaultTemplateId = spec.id
                        app.toast.show("New pages will use “${spec.name}”.", durationMs = 2500)
                    }
                }
                app.popovers.closeAll()
            }
            grid.addView(tile)
        }
        l.addView(grid)
        l.addView(k.section("Custom"))
        l.addView(k.row(
            k.pill("image", "Background from image") {
                app.files.pickDocuments(arrayOf("image/*"), false) { uris ->
                    val uri = uris.firstOrNull() ?: return@pickDocuments
                    val bytes = try { app.activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
                    if (bytes == null) { app.toast.show("Could not read the image."); return@pickDocuments }
                    app.templates.addCustom(TemplateSpec(id = "", name = "My picture", background = 0xFFFFFFFF.toInt()), bytes)
                    app.popovers.refresh()
                }
            },
            k.pill("save", "Save this background") {
                val spec = doc.currentPage.background
                val bytes = if (spec.kind == TemplateKind.Image) doc.images.encodedData(spec.imageKey) else null
                app.templates.addCustom(spec.copy(id = "", name = "My ${spec.name}"), bytes)
                app.popovers.refresh()
            },
        ))
        return l
    }

    fun import(app: App): View {
        val k = app.kit
        val l = k.layout()
        l.addView(k.tiles(5, listOf(
            Tile("image", "Images", action = app.closeThen { app.importImages() }),
            Tile("file", "Lesson pages", action = app.closeThen { app.importLesson() }),
            Tile("pdf", "PDF", action = app.closeThen { app.importPdf() }),
            Tile("pptx", "PowerPoint", action = app.closeThen { app.importPresentation() }),
            Tile("paste", "Clipboard", enabled = app.edit.canPaste(), action = app.closeThen {
                if (!app.edit.paste(app.board.viewCenterInPage())) app.toast.show("The clipboard is empty.", durationMs = 2000)
            }),
        )))
        l.addView(k.hint("You can also share images and PDFs from other apps to ClassBoard."))
        return l
    }

    fun export(app: App): View {
        val k = app.kit
        val l = k.layout()
        l.addView(k.tiles(5, listOf(
            Tile("save", "Save lesson", action = app.closeThen { app.save() }),
            Tile("save-as", "Save as", action = app.closeThen { app.saveAs() }),
            Tile("pdf", "PDF", action = { app.popovers.push("pdf") }),
            Tile("pptx", "PowerPoint", action = { app.popovers.push("pptx") }),
            Tile("image", "Page as PNG", action = app.closeThen { app.exportPages(App.ExportFormat.Png, listOf(app.doc.currentPageIndex)) }),
        )))
        l.addView(k.hint("Lessons are saved as .classboard files and can be opened with ClassBoard on Android and on Windows."))
        return l
    }

    fun exportPages(app: App, format: App.ExportFormat): View {
        val k = app.kit
        val l = k.layout()
        val pdf = format == App.ExportFormat.Pdf
        l.addView(k.section("Import"))
        l.addView(k.row(k.pill("import", if (pdf) "Import PDF…" else "Import PowerPoint…") {
            app.popovers.closeAll()
            if (pdf) app.importPdf() else app.importPresentation()
        }))
        l.addView(k.section("Export"))
        fun run(pages: List<Int>?) { app.popovers.closeAll(); app.exportPages(format, pages) }
        l.addView(k.row(
            k.pill("page", "Current page") { run(listOf(app.doc.currentPageIndex)) },
            k.pill("pages", "Whole lesson (${app.doc.pageCount} pages)", primary = true) { run(null) },
        ))
        l.addView(k.section("Selected pages"))
        val range = k.input("e.g. 1-3, 5")
        l.addView(k.inputRow(range, k.pill("export", "Export") {
            val pages = Exporters.parseRange(range.text.toString(), app.doc.pageCount)
            if (pages == null) app.toast.show("Enter pages like 1-3, 5 (the lesson has ${app.doc.pageCount} pages).", durationMs = 3500) else run(pages)
        }))
        l.addView(k.hint(if (pdf) "Text, shapes, formulas, graphs and ink stay sharp (vector PDF)." else "Each page becomes a slide showing the complete page in high resolution."))
        return l
    }

    fun settings(app: App): View {
        val k = app.kit
        val l = k.layout()
        val s = app.settings
        l.addView(k.section("Display"))
        l.addView(k.slider("Interface size", 0.6, 2.0, app.t.uiScale, 0.05, format = { "${Math.round(it * 100)} %" }) { v, final ->
            if (final) { s.uiScale = v; app.applyUiScale(v) }
        })
        l.addView(k.row(k.pill("reset", "Automatic size") { s.uiScale = 0.0; app.applyUiScale(0.0) }))
        val ribbon = k.slider("Ribbon size", 0.6, 1.5, s.ribbonScale, 0.05, format = { "${Math.round(it * 100)} %" }) { v, final ->
            app.applyRibbonScale(v)
            if (final) s.ribbonScale = v
        }
        l.addView(ribbon)
        l.addView(k.row(k.pill("reset", "Normal ribbon size") { s.ribbonScale = 1.0; ribbon.value = 1.0; app.applyRibbonScale(1.0) }))
        l.addView(k.toggle("Show page frame", "Outline of the area used for export and slides", s.showPageFrame) { s.showPageFrame = it; app.board.showPageFrame = it })
        l.addView(k.toggle("Full screen", "Hide the Android status and navigation bars", app.files.isFullScreen) { if (it != app.files.isFullScreen) app.toggleFullScreen() })
        l.addView(k.section("Touch and pen"))
        l.addView(k.toggle("Palm and three-finger wipe", "", s.palmErase) { s.palmErase = it; app.board.input.palmEraseEnabled = it })
        l.addView(k.toggle("Every finger draws", "Turns two-finger zoom off, so fingers close together also draw. Several people can always write at once.", s.multiUserTouch) {
            s.multiUserTouch = it; app.board.input.multiUserTouch = it
        })
        l.addView(k.slider("Ink smoothing", 0.0, 1.0, app.tools.smoothing, 0.05, format = { "${Math.round(it * 100)} %" }) { v, _ -> app.tools.smoothing = v })
        l.addView(k.section("Safety"))
        l.addView(k.slider("Autosave every", 15.0, 600.0, s.autosaveSeconds.toDouble(), 15.0, format = {
            val sec = it.toInt()
            if (sec < 60) "$sec s" else "${sec / 60} min ${sec % 60} s"
        }) { v, final -> if (final) { s.autosaveSeconds = v.toInt(); app.autosave.intervalSeconds = v.toInt(); app.autosave.start() } })
        l.addView(k.section("About"))
        l.addView(k.hint("ClassBoard ${com.classboard.app.storage.ProjectSerializer.APP_VERSION} for Android — open-source digital classroom board. Works fully offline. Licensed under the MIT License. Lessons (.classboard) are compatible with ClassBoard for Windows."))
        return l
    }

    @Suppress("unused")
    private fun unused(): Any = listOf(Pt.ZERO, CompositeCommand("", emptyList()), PagePropsCommand::class)
}
