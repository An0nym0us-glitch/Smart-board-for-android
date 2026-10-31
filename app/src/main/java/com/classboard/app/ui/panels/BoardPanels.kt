package com.classboard.app.ui.panels

import android.view.View
import android.widget.LinearLayout
import com.classboard.app.core.Geom
import com.classboard.app.doc.Document
import com.classboard.app.doc.PageSize
import com.classboard.app.doc.TemplateLibrary
import com.classboard.app.geometry.Instrument
import com.classboard.app.math.MeasureScale
import com.classboard.app.math.Units
import com.classboard.app.doc.ScaleCommand
import com.classboard.app.tools.ToolId
import com.classboard.app.ui.App
import com.classboard.app.ui.BoardView
import com.classboard.app.ui.Tile
import com.classboard.app.ui.vbox
import kotlin.math.abs

object BoardPanels {
    private fun App.closeThen(f: () -> Unit): () -> Unit = { popovers.closeAll(); f() }

    fun more(app: App): View {
        val k = app.kit
        val l = k.layout()
        val layer = app.board.instruments
        fun push(key: String): () -> Unit = { app.popovers.push(key) }
        fun instrument(kind: Instrument.Kind): () -> Unit = { app.board.showInstrument(kind); app.activateTool(ToolId.Pen); app.popovers.closeAll() }
        l.addView(k.tiles(4, listOf(
            Tile("file", "File", action = push("lesson")),
            Tile("shapes", "Shapes", action = push("shapes")),
            Tile("geometry", "Geometry", action = push("geometry")),
            Tile("equation", "Equation", action = { app.equationTarget = null; app.popovers.push("equation") }),
            Tile("function", "Function / Graph", action = { app.activateTool(ToolId.Graph); app.popovers.push("function") }),
            Tile("ruler", "Ruler", layer.isShown(Instrument.Kind.Ruler), action = instrument(Instrument.Kind.Ruler)),
            Tile("protractor", "Protractor", layer.isShown(Instrument.Kind.Protractor), action = instrument(Instrument.Kind.Protractor)),
            Tile("set-square", "Set Square", layer.isShown(Instrument.Kind.SetSquare), action = instrument(Instrument.Kind.SetSquare)),
            Tile("compass", "Compass", layer.isShown(Instrument.Kind.Compass), action = instrument(Instrument.Kind.Compass)),
            Tile("measure-distance", "Measurements", action = push("measure")),
            Tile("calculator", "Calculator", action = push("calculator")),
            Tile("image", "Images", action = app.closeThen { app.importImages() }),
            Tile("text", "Text", action = { app.activateTool(ToolId.Text); app.popovers.push("text") }),
            Tile("table", "Table", action = push("table")),
            Tile("template", "Templates", action = push("templates")),
            Tile("import", "Import", action = push("import")),
            Tile("pdf", "PDF", action = push("pdf")),
            Tile("pptx", "PPT / PPTX", action = push("pptx")),
            Tile("export", "Export", action = push("export")),
            Tile("settings", "Settings", action = push("settings")),
        )))
        return l
    }

    fun insert(app: App): View {
        val k = app.kit
        val l = k.layout()
        fun withTool(tool: ToolId, key: String): () -> Unit = { app.activateTool(tool); app.popovers.push(key) }
        l.addView(k.tiles(4, listOf(
            Tile("image", "Image", action = app.closeThen { app.importImages() }),
            Tile("shapes", "Shape", action = withTool(ToolId.Shape, "shapes")),
            Tile("text", "Text", action = withTool(ToolId.Text, "text")),
            Tile("function", "Graph", action = withTool(ToolId.Graph, "function")),
            Tile("equation", "Equation", action = { app.equationTarget = null; app.popovers.push("equation") }),
            Tile("table", "Table", action = { app.popovers.push("table") }),
            Tile("pdf", "PDF", action = app.closeThen { app.importPdf() }),
            Tile("pptx", "PowerPoint", action = app.closeThen { app.importPresentation() }),
        )))
        l.addView(k.hint("PDF pages become board pages with their own size, so nothing is cropped or stretched."))
        return l
    }

    fun edit(app: App): View {
        val k = app.kit
        val l = k.layout()
        val sel = app.edit.hasSelection
        l.addView(k.tiles(4, listOf(
            Tile("undo", "Undo", enabled = app.doc.canUndo, action = { app.doc.undo(); app.popovers.refresh() }),
            Tile("redo", "Redo", enabled = app.doc.canRedo, action = { app.doc.redo(); app.popovers.refresh() }),
            Tile("cut", "Cut", enabled = sel, action = app.closeThen { app.edit.cutSelection() }),
            Tile("copy", "Copy", enabled = sel, action = app.closeThen { app.edit.copySelection() }),
            Tile("paste", "Paste", enabled = app.edit.canPaste(), action = app.closeThen { if (app.edit.paste(app.board.viewCenterInPage())) app.activateTool(ToolId.Select) }),
            Tile("duplicate", "Duplicate", enabled = sel, action = app.closeThen { app.edit.duplicateSelection() }),
            Tile("trash", "Delete", enabled = sel, action = app.closeThen { app.edit.deleteSelection() }),
            Tile("select-all", "Select all", action = app.closeThen { app.activateTool(ToolId.Select); app.edit.selectAll() }),
        )))
        if (!sel) l.addView(k.hint("Select objects with SELECT to cut, copy, duplicate or delete them."))
        return l
    }

    fun pageActions(app: App): View {
        val k = app.kit
        val l = k.layout()
        val doc = app.doc
        val page = doc.currentPage
        l.addView(k.hint("Page ${doc.currentPageIndex + 1} of ${doc.pageCount} · ${PageSize.describe(page.width, page.height)}"))
        fun action(icon: String, title: String, desc: String, danger: Boolean = false, enabled: Boolean = true, f: () -> Unit): View {
            val box = vbox(app.activity, app.t.dp(2.0).toInt())
            box.addView(k.rowButton(icon, title, danger, enabled, f))
            box.addView(k.hint(desc))
            return box
        }
        val grid = com.classboard.app.ui.TileGrid(app.activity, 2, app.t.dp(8.0).toInt())
        grid.addView(action("page-add", "New page", "Adds a blank page after this one.", f = app.closeThen { app.addPage() }))
        grid.addView(action("duplicate", "Duplicate page", "Adds a copy of this page.", f = app.closeThen { app.duplicatePage() }))
        grid.addView(action("page-clear", "Clear page", "Removes the contents. The page stays.", true, page.objects.isNotEmpty()) { app.confirmClearPage() })
        grid.addView(action("page-delete", "Delete page", "Removes the whole page from the lesson.", true, doc.pageCount > 1) { app.deleteCurrentPage(); app.popovers.closeAll() })
        l.addView(grid)
        l.addView(k.section("Page setup"))
        l.addView(k.row(
            k.pill("page-size", "Page size") { app.popovers.push("pagesize") },
            k.pill("palette", "Background") { app.popovers.push("background") },
            k.pill("template", "Templates") { app.popovers.push("templates") },
            k.pill("pages", "All pages") { app.popovers.push("pages") },
        ))
        return l
    }

    fun pageSize(app: App): View {
        val k = app.kit
        val l = k.layout()
        val doc = app.doc
        val page = doc.currentPage
        l.addView(k.hint("This page: ${PageSize.describe(page.width, page.height)}"))
        var preset = PageSize.presetOf(page.width, page.height)
        var portrait = PageSize.isPortrait(page.width, page.height)
        var cw = if (preset == PageSize.Preset.Custom) page.width / PageSize.UNITS_PER_CM else 30.0
        var ch = if (preset == PageSize.Preset.Custom) page.height / PageSize.UNITS_PER_CM else 20.0
        var scope = 0
        l.addView(k.section("Size"))
        val custom = vbox(app.activity, app.t.dp(8.0).toInt())
        val labels = PageSize.presets.map { it.label } + "Custom"
        val currentIdx = if (preset == PageSize.Preset.Custom) PageSize.presets.size else PageSize.presets.indexOf(preset)
        l.addView(k.choices(labels, currentIdx, 3) { i ->
            preset = if (i >= PageSize.presets.size) PageSize.Preset.Custom else PageSize.presets[i]
            custom.visibility = if (preset == PageSize.Preset.Custom) View.VISIBLE else View.GONE
        })
        l.addView(k.segmented(listOf("" to "Landscape", "" to "Portrait"), if (portrait) 1 else 0) { portrait = it == 1 })
        custom.addView(k.numberField("Width", "cm", cw) { cw = it })
        custom.addView(k.numberField("Height", "cm", ch) { ch = it })
        custom.visibility = if (preset == PageSize.Preset.Custom) View.VISIBLE else View.GONE
        l.addView(custom)
        l.addView(k.section("Apply to"))
        l.addView(k.segmented(listOf("" to "This page", "" to "All pages", "" to "New pages"), 0) { scope = it })
        l.addView(k.row(k.pill("check", "Apply page size", primary = true) {
            val (w, h) = if (preset == PageSize.Preset.Custom) {
                val (a, b) = if (portrait && cw > ch) Pair(ch, cw) else Pair(cw, ch)
                PageSize.fromCentimetres(a, b)
            } else PageSize.sizeFor(preset, portrait)
            when (scope) {
                0 -> doc.changePages(listOf(doc.currentPage), "Change page size") { p -> doc.propsOf(p).copy(width = w, height = h) }
                1 -> { doc.changePages(doc.pages.toList(), "Change page size") { p -> doc.propsOf(p).copy(width = w, height = h) }; doc.defaultPageWidth = w; doc.defaultPageHeight = h }
                else -> { doc.defaultPageWidth = w; doc.defaultPageHeight = h; app.toast.show("New pages will be ${PageSize.describe(w, h)}.", durationMs = 2500) }
            }
            app.popovers.closeAll()
            app.board.fitPage()
        }))
        l.addView(k.hint("Sizes are logical (1 cm on the page is 1 cm in measurements) and do not depend on the screen. Exports use the full page."))
        return l
    }

    fun background(app: App): View {
        val k = app.kit
        val l = k.layout()
        val doc = app.doc
        var all = false
        fun apply(c: Int) {
            val targets = if (all) doc.pages.toList() else listOf(doc.currentPage)
            doc.changePages(targets, "Change background colour") { p -> doc.propsOf(p).copy(background = TemplateLibrary.withBackgroundColor(p.background, c)) }
        }
        l.addView(k.section("Apply to"))
        l.addView(k.segmented(listOf("" to "This page", "" to "All pages"), 0) { all = it == 1 })
        l.addView(k.section("Colour"))
        val named = listOf("White" to 0xFFFFFFFF.toInt(), "Black" to 0xFF000000.toInt(), "Light grey" to 0xFFE6E6E6.toInt(), "Blackboard" to 0xFF1F2B26.toInt())
        l.addView(k.row(*named.map { (label, c) -> k.pill("", label) { apply(c) }.also { it.indicatorColor = c } }.toTypedArray()))
        val colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFF5F0E1.toInt(), 0xFFE8F4FD.toInt(), 0xFFE8F5E9.toInt(), 0xFFFFF8E1.toInt(), 0xFFFCE4EC.toInt(), 0xFFD9D9D9.toInt(), 0xFF9E9E9E.toInt(),
            0xFF000000.toInt(), 0xFF263238.toInt(), 0xFF0D1B2A.toInt(), 0xFF1B3A2F.toInt(), 0xFF1F2B26.toInt(), 0xFF3E2723.toInt(), 0xFF1A237E.toInt(), 0xFF4A148C.toInt())
        l.addView(k.swatches(colors, doc.currentPage.background.background, 8) { apply(it) })
        l.addView(k.section("Custom colour"))
        val hex = k.input("#RRGGBB")
        hex.setText(String.format("#%06x", doc.currentPage.background.background and 0xFFFFFF))
        l.addView(k.inputRow(hex, k.pill("check", "Apply") {
            val c = com.classboard.app.core.Json.parseColor(hex.text.toString().trim())
            if (c == null) app.toast.show("Enter a colour like #20304A.", durationMs = 2500) else apply(c)
        }))
        l.addView(k.hint("The background belongs to the page: it is saved, exported and always stays behind the content. Grid and line patterns are kept."))
        return l
    }

    fun view(app: App): View {
        val k = app.kit
        val l = k.layout()
        val zoom = app.board.view.zoom
        l.addView(k.section("Zoom ${Math.round(zoom * 100)} %"))
        val presets = BoardView.ZOOM_PRESETS
        val current = presets.indexOfFirst { abs(it - zoom) < 1e-3 }
        l.addView(k.choices(presets.map { "${Math.round(it * 100)} %" }, current, 5) { i -> app.board.setZoom(presets[i]); app.popovers.refresh() })
        l.addView(k.row(
            k.pill("zoom-out", "Zoom out") { app.board.zoomStep(-1); app.popovers.refresh() },
            k.pill("zoom-in", "Zoom in") { app.board.zoomStep(1); app.popovers.refresh() },
            k.pill("", "Reset 100 %") { app.board.setZoom(1.0); app.popovers.refresh() },
        ))
        l.addView(k.row(
            k.pill("fit-page", "Fit page", action = app.closeThen { app.board.fitPage() }),
            k.pill("fit-width", "Fit width", action = app.closeThen { app.board.fitWidth() }),
            k.pill("fullscreen", "Full screen", action = app.closeThen { app.toggleFullScreen() }),
        ))
        l.addView(k.hint("100 % is the lesson's own scale. Zoom changes only the view: sizes, measurements and exports stay the same."))
        return l
    }

    fun scale(app: App): View {
        val k = app.kit
        val l = k.layout()
        val doc = app.doc
        val current = doc.coordinates.scale
        var state = current
        fun apply(s: MeasureScale) {
            if (!s.isValid) { app.toast.show("Both distances must be greater than zero.", durationMs = 2500); return }
            if (s == doc.coordinates.scale) return
            doc.push(ScaleCommand(doc.coordinates.scale, s))
            app.popovers.refresh()
        }
        l.addView(k.hint(if (current.isIdentity) "No scale: measurements show board lengths." else "Current scale: ${current.text()}"))
        l.addView(k.section("Quick scales"))
        val presets = listOf("None (1 : 1)" to MeasureScale(), "10 cm = 1 m" to MeasureScale(10.0, "cm", 1.0, "m"), "1 cm = 10 m" to MeasureScale(1.0, "cm", 10.0, "m"),
            "10 cm = 1 km" to MeasureScale(10.0, "cm", 1.0, "km"), "5 cm = 1 km" to MeasureScale(5.0, "cm", 1.0, "km"), "1 cm = 1 km" to MeasureScale(1.0, "cm", 1.0, "km"))
        val idx = presets.indexOfFirst { it.second == current || (it.second.isIdentity && current.isIdentity) }
        l.addView(k.choices(presets.map { it.first }, idx, 3) { apply(presets[it].second) })
        l.addView(k.section("Custom scale"))
        l.addView(k.numberField("On the board", "", state.boardValue) { state = state.copy(boardValue = it) })
        val boardUnits = listOf("mm", "cm", "m", "in")
        l.addView(k.choices(boardUnits, boardUnits.indexOf(state.boardUnit), 4) { state = state.copy(boardUnit = boardUnits[it]) })
        l.addView(k.numberField("In reality", "", state.realValue) { state = state.copy(realValue = it) })
        l.addView(k.choices(Units.lengthUnits, Units.lengthUnits.indexOf(state.realUnit), 4) { state = state.copy(realUnit = Units.lengthUnits[it]) })
        l.addView(k.row(k.pill("check", "Apply scale", primary = true) { apply(state) }))
        l.addView(k.hint("Lengths, areas, perimeters and vectors are shown in real units. Board distances are lesson units, independent of zoom and screen."))
        return l
    }

    @Suppress("unused")
    private fun unused(d: Document, ll: LinearLayout) = Geom.formatNumber(0.0)
}
