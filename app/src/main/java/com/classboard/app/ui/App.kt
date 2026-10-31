package com.classboard.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.FrameLayout
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.Document
import com.classboard.app.doc.DocumentListener
import com.classboard.app.doc.InsertPageCommand
import com.classboard.app.doc.CompositeCommand
import com.classboard.app.doc.Command
import com.classboard.app.doc.ObjectType
import com.classboard.app.doc.Page
import com.classboard.app.doc.TemplateLibrary
import com.classboard.app.doc.nowIso
import com.classboard.app.export.Exporters
import com.classboard.app.geometry.ConstructKind
import com.classboard.app.geometry.MeasureKind
import com.classboard.app.math.Calculator
import com.classboard.app.math.EquationObject
import com.classboard.app.math.GraphObject
import com.classboard.app.math.TableObject
import com.classboard.app.storage.AppSettings
import com.classboard.app.storage.AutosaveManager
import com.classboard.app.storage.ImportSizing
import com.classboard.app.storage.PageImport
import com.classboard.app.storage.PdfImporter
import com.classboard.app.storage.ProjectSerializer
import com.classboard.app.tools.EditOperations
import com.classboard.app.tools.ToolId
import com.classboard.app.tools.ToolSettings
import java.util.concurrent.Executors

/** Opens system file pickers and returns their result (implemented by the activity). */
interface FileBridge {
    fun pickDocuments(mimeTypes: Array<String>, multiple: Boolean, callback: (List<Uri>) -> Unit)
    fun createDocument(fileName: String, mimeType: String, callback: (Uri?) -> Unit)
    fun setFullScreen(on: Boolean)
    val isFullScreen: Boolean
}

/**
 * Application services: document, tools, board, ribbon, popovers and the controllers for
 * lessons, export and editing. Panels talk to the app only through this class.
 */
class App(val activity: Activity, val root: FrameLayout, val files: FileBridge) : DocumentListener {
    val t = Theme(activity.resources.displayMetrics.density)
    val settings = AppSettings(activity)
    val tools = ToolSettings()
    val doc = Document()
    val templates = TemplateLibrary(activity)
    val calculator = Calculator()
    val board: BoardView
    val ribbon: Ribbon
    val popovers: PopoverHost
    val toast: ToastView
    val selectionBar: SelectionBar
    val edit: EditOperations
    val autosave: AutosaveManager
    val editors: Editors
    val kit get() = PanelKit(activity, t)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var saving = false
    private var importing = false
    private var exporting = false
    /** Targets for popovers opened on a specific object. */
    var equationTarget: String? = null
    var propertiesTarget: String? = null
    var magicTargets: List<String> = emptyList()
    /** Formula text handed to the Equation panel (calculator, magic equation maker). */
    var pendingEquation = ""

    init {
        val sw = activity.resources.configuration.smallestScreenWidthDp
        val auto = when {
            sw >= 900 -> 1.15
            sw >= 600 -> 1.0
            else -> 0.78
        }
        t.uiScale = if (settings.uiScale > 0) settings.uiScale else auto
        t.ribbonScale = settings.ribbonScale
        settings.loadTools(tools)
        templates.load()
        doc.resetToNew(templates.find(settings.defaultTemplateId))

        board = BoardView(activity, doc, tools, t)
        root.addView(board, FrameLayout.LayoutParams(MATCH, MATCH))
        ribbon = Ribbon(activity, t)
        root.addView(ribbon, FrameLayout.LayoutParams(MATCH, WRAP, android.view.Gravity.BOTTOM))
        selectionBar = SelectionBar(activity, t)
        root.addView(selectionBar, FrameLayout.LayoutParams(WRAP, WRAP))
        toast = ToastView(activity, t)
        val tlp = FrameLayout.LayoutParams(WRAP, WRAP, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
        root.addView(toast, tlp)
        editors = Editors(this)
        popovers = PopoverHost(activity, t)
        root.addView(popovers, FrameLayout.LayoutParams(MATCH, MATCH))
        edit = EditOperations(activity, doc, board.selection)
        autosave = AutosaveManager(activity, doc)
        autosave.intervalSeconds = settings.autosaveSeconds
        autosave.onFailed = { toast.show("Autosave failed: $it", durationMs = 5000) }
        autosave.start()

        popovers.factory = { key -> Panels.create(this, key) }
        popovers.onOpened = { key -> ribbon.setMoreOpen(key == "more" || popovers.anchorView === ribbon.more) }
        popovers.onClosed = { ribbon.setMoreOpen(false) }

        board.input.palmEraseEnabled = settings.palmErase
        board.input.multiUserTouch = settings.multiUserTouch
        board.showPageFrame = settings.showPageFrame
        board.onEditRequested = { editObject(it) }
        board.onStatusText = { if (it.isNotEmpty()) toast.show(it, durationMs = 1200) }
        board.onInteractionFinished = { refreshSelectionBar() }
        board.onViewChanged = { ribbon.setZoomPercent(Math.round(board.view.zoom * 100).toInt()); refreshSelectionBar(); editors.relayout() }
        board.onTextEdit = { id, pos -> editors.beginText(id, pos) }
        board.onToast = { toast.show(it) }
        board.selection.listeners.add { refreshSelectionBar() }
        board.tools.listeners.add { updateRibbonState(); updateToolChip(); refreshSelectionBar() }
        board.instruments.onVisibilityChanged = { board.instruments.setInk(tools.ink()) }
        tools.listeners.add { ribbon.setPenColor(tools.penColor); updateToolChip() }
        board.selectTool.onSelectionEdited = { refreshSelectionBar() }
        board.graphTool.onGraphChanged = { if (popovers.currentKey == "function") popovers.refresh() }
        doc.addListener(this)

        buildSelectionBar()
        wireRibbon()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutChanged() }
        ribbon.setPenColor(tools.penColor)
        activateTool(ToolId.Pen)
        updateRibbonState()
    }

    private fun layoutChanged() {
        val rh = ribbon.height
        board.insetsBottom = rh.toDouble()
        popovers.bounds = Rect(0, 0, root.width, root.height - rh)
        val tlp = toast.layoutParams as FrameLayout.LayoutParams
        val want = rh + t.dp(16.0).toInt()
        if (tlp.bottomMargin != want) { tlp.bottomMargin = want; toast.layoutParams = tlp }
    }

    // ------------------------------------------------------------------------------ ribbon

    private fun wireRibbon() {
        ribbon.more.setOnClickListener { popovers.toggle("more", ribbon.more) }
        ribbon.pen.setOnClickListener { onCoreTool(ToolId.Pen, "pen", ribbon.pen) }
        ribbon.eraser.setOnClickListener { onCoreTool(ToolId.Eraser, "eraser", ribbon.eraser) }
        ribbon.select.setOnClickListener { onCoreTool(ToolId.Select, "select", ribbon.select) }
        ribbon.toolChip.setOnClickListener {
            val key = when (board.tools.activeId) {
                ToolId.Shape -> "shapes"
                ToolId.Text -> "text"
                ToolId.Measure -> "measure"
                ToolId.Construct -> "geometry"
                ToolId.Graph -> "function"
                else -> null
            }
            ribbon.setActiveTool(board.tools.activeId)
            if (key != null) popovers.toggle(key, ribbon.toolChip)
        }
        ribbon.undo.setOnClickListener { editors.commitAll(); doc.undo() }
        ribbon.redo.setOnClickListener { editors.commitAll(); doc.redo() }
        ribbon.file.setOnClickListener { popovers.toggle("lesson", ribbon.file) }
        ribbon.insert.setOnClickListener { popovers.toggle("insert", ribbon.insert) }
        ribbon.edit.setOnClickListener { popovers.toggle("edit", ribbon.edit) }
        ribbon.prev.setOnClickListener { doc.setCurrentPage(doc.currentPageIndex - 1) }
        ribbon.next.setOnClickListener { doc.setCurrentPage(doc.currentPageIndex + 1) }
        ribbon.pageIndicator.setOnClickListener { popovers.toggle("pages", ribbon.pageIndicator) }
        ribbon.newPage.setOnClickListener { addPage() }
        ribbon.pageMenu.setOnClickListener { popovers.toggle("pageactions", ribbon.pageMenu) }
        ribbon.zoom.setOnClickListener { popovers.toggle("view", ribbon.zoom) }
        ribbon.zoomIn.setOnClickListener { board.zoomStep(1) }
        ribbon.zoomOut.setOnClickListener { board.zoomStep(-1) }
        ribbon.fit.setOnClickListener { board.fitPage() }
        ribbon.fullScreen.setOnClickListener { toggleFullScreen() }
    }

    fun toggleFullScreen() {
        files.setFullScreen(!files.isFullScreen)
        settings.fullScreen = files.isFullScreen
        ribbon.setFullScreen(files.isFullScreen)
    }

    private fun onCoreTool(id: ToolId, key: String, anchor: View) {
        if (board.tools.activeId == id) {
            ribbon.setActiveTool(id)
            popovers.toggle(key, anchor)
            return
        }
        popovers.closeAll()
        activateTool(id)
    }

    fun activateTool(id: ToolId) {
        editors.commitAll()
        if (id != ToolId.Select && id != ToolId.Graph) board.selection.clear()
        board.setActive(id)
        ribbon.setActiveTool(id)
        updateToolChip()
    }

    fun updateToolChip() {
        var icon = ""
        var label = ""
        when (board.tools.activeId) {
            ToolId.Shape -> { icon = "shapes"; label = tools.shapeKind.label }
            ToolId.Text -> { icon = "text"; label = "Text" }
            ToolId.Measure -> when (tools.measureKind) {
                MeasureKind.Distance -> { icon = "measure-distance"; label = "Distance" }
                MeasureKind.Angle -> { icon = "measure-angle"; label = "Angle" }
                MeasureKind.Slope -> { icon = "measure-slope"; label = "Slope" }
                MeasureKind.Area -> { icon = "measure-area"; label = "Area" }
            }
            ToolId.Construct -> when (tools.constructKind) {
                ConstructKind.Point -> { icon = "point"; label = "Point" }
                ConstructKind.Segment -> { icon = "segment"; label = "Segment" }
                ConstructKind.Line -> { icon = "line"; label = "Line" }
                ConstructKind.Ray -> { icon = "ray"; label = "Ray" }
                ConstructKind.Vector -> { icon = "vector"; label = "Vector" }
            }
            ToolId.Graph -> { icon = "function"; label = "Graph" }
            else -> {}
        }
        ribbon.setToolChip(icon, label)
        ribbon.setActiveTool(board.tools.activeId)
    }

    fun updateRibbonState() {
        ribbon.setUndoRedo(doc.canUndo, doc.canRedo)
        ribbon.setPageInfo(doc.currentPageIndex, doc.pageCount)
        ribbon.setActiveTool(board.tools.activeId)
        ribbon.setFullScreen(files.isFullScreen)
    }

    override fun onHistoryChanged() { updateRibbonState(); refreshSelectionBar() }
    override fun onPagesChanged() = updateRibbonState()
    override fun onCurrentPageChanged(index: Int) { editors.commitAll(); updateRibbonState() }
    override fun onPageContentChanged(pageId: String) = refreshSelectionBar()

    fun addPage() = doc.addPage(doc.currentPageIndex)

    // ------------------------------------------------------------------------ selection bar

    private fun buildSelectionBar() {
        selectionBar.add("edit", "edit", "Edit") { editSelected() }
        selectionBar.add("precision", "precision", "Exact values") { editSelected() }
        selectionBar.add("magic", "magic", "Magic Equation Maker") { openMagicEquation() }
        selectionBar.add("color", "palette", "Colour") { v -> popovers.open("color", v) }
        selectionBar.add("front", "bring-front", "Bring to front") { edit.bringToFront() }
        selectionBar.add("back", "send-back", "Send to back") { edit.sendToBack() }
        selectionBar.add("duplicate", "duplicate", "Duplicate") { edit.duplicateSelection() }
        selectionBar.add("copy", "copy", "Copy") { edit.copySelection(); toast.show("Copied", durationMs = 1200) }
        selectionBar.add("delete", "trash", "Delete", danger = true) { edit.deleteSelection() }
    }

    private fun editSelected() {
        val ids = board.selection.ids
        if (ids.size == 1) editObject(ids[0])
    }

    fun refreshSelectionBar() {
        main.post {
            val sel = board.selection
            val active = board.tools.activeId == ToolId.Select && !sel.isEmpty && !board.tools.hasActivePointers && !board.selectTool.isDragging
            if (!active) { selectionBar.visibility = View.GONE; return@post }
            val page = doc.currentPage
            var editable = false
            var colorable = false
            var precise = false
            var handwriting = true
            for (id in sel.ids) {
                val o = page.objectById(id) ?: continue
                if (sel.count == 1 && o.isEditable()) editable = true
                if (sel.count == 1 && PropertiesPanel.supports(o)) precise = true
                if (o.color() != null) colorable = true
                if (o.type != ObjectType.Stroke) handwriting = false
            }
            selectionBar.show("edit", editable)
            selectionBar.show("precision", precise)
            selectionBar.show("magic", handwriting)
            selectionBar.show("color", colorable)
            val r = board.selectTool.selectionViewBounds() ?: run { selectionBar.visibility = View.GONE; return@post }
            selectionBar.place(r, root.width, root.height, ribbon.height)
        }
    }

    // ------------------------------------------------------------------------------ editing

    fun pageRectToRoot(r: RectD): Rect {
        val a = board.view.pageToView(r.topLeft)
        val b = board.view.pageToView(r.bottomRight)
        return Rect(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt())
    }

    fun editObject(id: String) {
        val page = doc.currentPage
        val o = page.objectById(id) ?: return
        when (o) {
            is com.classboard.app.doc.TextObject -> { activateTool(ToolId.Text); editors.beginText(id, o.position) }
            is EquationObject -> { equationTarget = id; popovers.openAt("equation", pageRectToRoot(o.sceneBounds())) }
            is GraphObject -> {
                activateTool(ToolId.Graph)
                board.selection.setSingle(id)
                ribbon.post { popovers.open("function", ribbon.toolChip) }
            }
            is TableObject -> {
                val cell = o.cellAt(o.mapFromPage(board.lastPressPage)) ?: Pair(if (o.header && o.rows > 1) 1 else 0, 0)
                editors.beginCell(id, cell.first, cell.second)
            }
            else -> if (PropertiesPanel.supports(o)) { propertiesTarget = id; popovers.openAt("properties", pageRectToRoot(o.sceneBounds())) }
        }
    }

    fun openMagicEquation() {
        val page = doc.currentPage
        val ids = board.selection.ids.toList()
        if (ids.isEmpty() || ids.any { page.objectById(it)?.type != ObjectType.Stroke }) {
            toast.show("Select your handwriting with SELECT, then tap ✨ Magic Equation Maker.", durationMs = 3500)
            return
        }
        var bounds: RectD? = null
        for (i in ids) page.objectById(i)?.let { bounds = bounds?.union(it.sceneBounds()) ?: it.sceneBounds() }
        magicTargets = ids
        popovers.openAt("magic", pageRectToRoot(bounds ?: return))
    }

    fun confirm(title: String, message: String, actions: List<ConfirmOverlay.Action>) {
        var overlay: View? = null
        overlay = ConfirmOverlay(activity, t, title, message, actions) { overlay?.let { root.removeView(it) } }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun confirmClearPage() {
        popovers.closeAll()
        val page = doc.currentPage
        if (page.objects.isEmpty()) { toast.show("This page is already empty.", durationMs = 2000); return }
        confirm("Clear this page?", "Everything on page ${doc.currentPageIndex + 1} is removed; the page itself stays. You can undo this.", listOf(
            ConfirmOverlay.Action("Cancel"),
            ConfirmOverlay.Action("Clear page", primary = true, danger = true) {
                val p = doc.currentPage
                p.objects.clear()
                doc.commit(p, "Clear page")
            },
        ))
    }

    fun deleteCurrentPage() {
        if (doc.pageCount <= 1) { toast.show("A lesson needs at least one page. Use Clear page to empty it.", durationMs = 3000); return }
        doc.deletePage(doc.currentPageIndex)
        toast.show("Page deleted.", "Undo") { doc.undo() }
    }

    fun duplicatePage(index: Int = doc.currentPageIndex) {
        val p = doc.page(index) ?: return
        doc.push(InsertPageCommand(index + 1, p.clone(true), "Duplicate page"))
    }

    // ------------------------------------------------------------------------------ lessons

    private fun displayNameOf(uri: Uri): String {
        var name: String? = null
        try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) { }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Lesson"
    }

    fun confirmDiscard(proceed: () -> Unit) {
        editors.commitAll()
        if (!doc.isModified) { proceed(); return }
        confirm("Save changes?", "“${doc.displayName()}” has changes that are not saved yet.", listOf(
            ConfirmOverlay.Action("Cancel"),
            ConfirmOverlay.Action("Don't save", danger = true) { proceed() },
            ConfirmOverlay.Action("Save", primary = true) { save { ok -> if (ok) proceed() } },
        ))
    }

    fun newLesson() = confirmDiscard {
        doc.resetToNew(templates.find(settings.defaultTemplateId))
        autosave.discard()
        toast.show("New lesson", durationMs = 1500)
    }

    fun open() = confirmDiscard {
        files.pickDocuments(arrayOf("application/octet-stream", "application/x-classboard", "*/*"), false) { uris ->
            uris.firstOrNull()?.let { openUri(it) }
        }
    }

    fun openUri(uri: Uri) {
        val name = displayNameOf(uri)
        toast.show("Opening $name", durationMs = 60000)
        worker.execute {
            try {
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("The file could not be read.")
                val contents = ProjectSerializer.deserialize(bytes)
                main.post {
                    doc.setContents(contents)
                    doc.fileUri = uri.toString()
                    doc.fileName = name
                    settings.addRecentFile(uri.toString(), name)
                    autosave.discard()
                    if (contents.skippedObjects > 0) toast.show("Opened. ${contents.skippedObjects} item(s) from a newer ClassBoard version were skipped.", durationMs = 6000)
                    else toast.show("Opened ${name.removeSuffix(".classboard")}", durationMs = 2000)
                }
            } catch (e: Exception) {
                main.post {
                    toast.show("Could not open the lesson: ${e.message}", durationMs = 6000)
                    settings.removeRecentFile(uri.toString())
                }
            }
        }
    }

    fun save(done: ((Boolean) -> Unit)? = null) {
        editors.commitAll()
        val uri = doc.fileUri
        if (uri == null) { saveAs(done); return }
        writeTo(Uri.parse(uri), doc.fileName ?: "Lesson.classboard", done, fallbackToSaveAs = true)
    }

    fun saveAs(done: ((Boolean) -> Unit)? = null) {
        editors.commitAll()
        val base = doc.displayName().ifEmpty { "Lesson" }
        files.createDocument("$base.classboard", "application/octet-stream") { uri ->
            if (uri == null) { done?.invoke(false); return@createDocument }
            writeTo(uri, displayNameOf(uri), done, fallbackToSaveAs = false)
        }
    }

    private fun writeTo(uri: Uri, name: String, done: ((Boolean) -> Unit)?, fallbackToSaveAs: Boolean) {
        if (saving) { done?.invoke(false); return }
        saving = true
        doc.metadata.modified = nowIso()
        val contents = doc.copyContents()
        val revisionAtSave = doc.revision
        toast.show("Saving", durationMs = 60000)
        worker.execute {
            val error = try {
                val bytes = ProjectSerializer.serialize(contents)
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("The file could not be written.")
                null
            } catch (e: Exception) { e.message ?: "error" }
            main.post {
                saving = false
                if (error == null) {
                    doc.fileUri = uri.toString()
                    doc.fileName = name
                    if (doc.revision == revisionAtSave) doc.markSaved()
                    settings.addRecentFile(uri.toString(), name)
                    autosave.discard()
                    toast.show("Saved $name", durationMs = 1800)
                    done?.invoke(true)
                } else if (fallbackToSaveAs) {
                    saveAs(done)
                } else {
                    toast.show("Could not save: $error", durationMs = 7000)
                    done?.invoke(false)
                }
            }
        }
    }

    fun importLesson() {
        files.pickDocuments(arrayOf("*/*"), false) { uris ->
            val uri = uris.firstOrNull() ?: return@pickDocuments
            worker.execute {
                try {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("unreadable")
                    val contents = ProjectSerializer.deserialize(bytes)
                    main.post {
                        doc.images.mergeFrom(contents.images)
                        var index = doc.currentPageIndex + 1
                        val cmds = ArrayList<Command>()
                        for (p in contents.pages) {
                            p.id = com.classboard.app.core.Ids.newId()
                            val c = InsertPageCommand(index++, p, "Import lesson")
                            c.redo(doc)
                            cmds.add(c)
                        }
                        if (cmds.isNotEmpty()) doc.recordApplied(CompositeCommand("Import lesson", cmds))
                        toast.show("Imported ${cmds.size} page(s)", durationMs = 2500)
                    }
                } catch (e: Exception) {
                    main.post { toast.show("Could not import: ${e.message}", durationMs = 6000) }
                }
            }
        }
    }

    fun importImages() {
        files.pickDocuments(arrayOf("image/*"), true) { uris -> if (uris.isNotEmpty()) insertImageUris(uris, board.viewCenterInPage()) }
    }

    fun insertImageUris(uris: List<Uri>, center: Pt) {
        val page = doc.currentPage
        var offset = Pt.ZERO
        val failed = ArrayList<String>()
        var bounds: RectD? = null
        var added = 0
        for (uri in uris) {
            val bytes = try { activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            val key = bytes?.let { doc.images.addEncoded(it) }
            if (key == null) { failed.add(displayNameOf(uri)); continue }
            val o = edit.imageObject(key, center + offset)
            page.objects.add(o)
            bounds = bounds?.union(o.sceneBounds()) ?: o.sceneBounds()
            offset += Pt(40.0, 40.0)
            added++
        }
        if (added > 0) {
            doc.commit(page, "Insert image")
            bounds?.let { board.ensureVisible(it) }
        }
        if (failed.isNotEmpty()) toast.show("Could not read: ${failed.joinToString(", ")}", durationMs = 5000)
    }

    fun importPdf() {
        files.pickDocuments(arrayOf("application/pdf"), false) { uris -> uris.firstOrNull()?.let { importPdfUri(it) } }
    }

    fun importPdfUri(uri: Uri) {
        if (importing) { toast.show("An import is already running.", durationMs = 2500); return }
        importing = true
        val name = displayNameOf(uri).removeSuffix(".pdf").removeSuffix(".PDF")
        toast.show("Converting $name", durationMs = 120000)
        worker.execute {
            try {
                val pages = PdfImporter.render(activity, uri) { done, total -> main.post { toast.show("Converting $name ($done / $total)", durationMs = 120000) } }
                main.post {
                    importing = false
                    val added = PageImport.insertPages(doc, doc.currentPageIndex, pages, name, ImportSizing.Physical, "Import PDF")
                    toast.show("Imported $added page(s). Choose a dark pen colour to write on them.", durationMs = 4000)
                    board.fitPage()
                }
            } catch (e: Exception) {
                main.post { importing = false; toast.show(e.message ?: "Nothing could be imported.", durationMs = 8000) }
            }
        }
    }

    fun importPresentation() {
        confirm("PowerPoint import", "Android has no office suite to convert slides offline. In PowerPoint, Google Slides or WPS, choose “Save as PDF” (or Export → PDF), then import the PDF here: every slide becomes a page.", listOf(
            ConfirmOverlay.Action("Cancel"),
            ConfirmOverlay.Action("Import PDF", primary = true) { importPdf() },
        ))
    }

    fun checkRecovery() {
        val info = autosave.findRecoverable() ?: return
        val when0 = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(info.savedAt))
        toast.show("ClassBoard closed unexpectedly. Restore “${info.title}” ($when0)?", "Restore", durationMs = 15000) {
            try {
                val contents = ProjectSerializer.deserialize(info.file.readBytes())
                doc.setContents(contents)
                doc.fileUri = info.uri.ifEmpty { null }
                doc.fileName = info.name.ifEmpty { null }
                doc.markSaved()
                doc.commit(doc.currentPage, "Restore") // marks the document as modified
                toast.show("Lesson restored. Save it to keep it.", durationMs = 4000)
            } catch (e: Exception) {
                toast.show("Could not restore: ${e.message}", durationMs = 6000)
            }
        }
    }

    // ------------------------------------------------------------------------------- export

    enum class ExportFormat(val ext: String, val mime: String) { Pdf("pdf", "application/pdf"), Pptx("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"), Png("png", "image/png") }

    fun exportPages(format: ExportFormat, indices0: List<Int>?) {
        editors.commitAll()
        if (exporting) { toast.show("An export is already running.", durationMs = 2500); return }
        val indices = indices0 ?: doc.pages.indices.toList()
        var base = doc.displayName()
        if (format == ExportFormat.Png && indices.size == 1) base += " - page ${indices[0] + 1}"
        files.createDocument("$base.${format.ext}", format.mime) { uri ->
            if (uri == null) return@createDocument
            exporting = true
            val contents = doc.copyContents(indices)
            val snapshot = Document()
            snapshot.setContents(contents)
            val title = doc.displayName()
            val label = "Exporting ${displayNameOf(uri)}"
            toast.show(label, durationMs = 120000)
            worker.execute {
                val error = try {
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        when (format) {
                            ExportFormat.Pdf -> Exporters.exportPdf(snapshot, snapshot.pages, out) { d, n -> main.post { toast.show("$label ${d * 100 / n} %", durationMs = 120000) } }
                            ExportFormat.Pptx -> Exporters.exportPptx(snapshot, snapshot.pages, title, out) { d, n -> main.post { toast.show("$label ${d * 100 / n} %", durationMs = 120000) } }
                            ExportFormat.Png -> Exporters.exportPng(snapshot, snapshot.pages[0], out)
                        }
                    } ?: throw IllegalStateException("The file could not be written.")
                    null
                } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
                main.post {
                    exporting = false
                    if (error == null) {
                        toast.show("Exported ${displayNameOf(uri)}", "Open", durationMs = 8000) {
                            try {
                                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, format.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (e: Exception) { toast.show("No app can open this file.") }
                        }
                    } else toast.show("Export failed: $error", durationMs = 7000)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ settings

    fun applyUiScale(scale: Double) {
        val sw = activity.resources.configuration.smallestScreenWidthDp
        t.uiScale = if (scale > 0) scale else if (sw >= 900) 1.15 else if (sw >= 600) 1.0 else 0.78
        popovers.closeAll()
        rebuildChrome()
    }

    fun applyRibbonScale(scale: Double) {
        t.ribbonScale = scale.coerceIn(0.6, 1.5)
        ribbon.requestLayout()
        ribbon.post { ribbon.forEachChild { it.requestLayout(); it.invalidate() } }
    }

    private fun rebuildChrome() {
        ribbon.forEachChild { it.requestLayout(); it.invalidate() }
        ribbon.requestLayout()
        selectionBar.forEachChild { it.requestLayout(); it.invalidate() }
        board.invalidate()
    }

    fun onPause() {
        settings.saveTools(tools)
        editors.commitAll()
        autosave.saveNowIfNeeded()
    }

    fun currentPage(): Page = doc.currentPage
}

fun android.view.ViewGroup.forEachChild(f: (View) -> Unit) { for (i in 0 until childCount) f(getChildAt(i)) }
