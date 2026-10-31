package com.classboard.app.doc

import com.classboard.app.core.Pt
import com.classboard.app.math.CoordinateSystem
import com.classboard.app.math.MeasureScale

/** A reversible document operation; all edits go through commands (one undo history). */
interface Command {
    val text: String
    /** Page to navigate to on undo/redo (null for document-wide). */
    val pageId: String?
    fun undo(doc: Document)
    fun redo(doc: Document)
}

/** Page content before/after (frozen object lists). */
class ContentCommand(override val pageId: String, val before: List<BoardObject>, val after: List<BoardObject>, override val text: String) : Command {
    override fun undo(doc: Document) = doc.applySnapshot(pageId, before)
    override fun redo(doc: Document) = doc.applySnapshot(pageId, after)
}

class CompositeCommand(override val text: String, val children: List<Command>) : Command {
    override val pageId: String? get() = children.firstNotNullOfOrNull { it.pageId }
    override fun undo(doc: Document) { for (c in children.asReversed()) c.undo(doc) }
    override fun redo(doc: Document) { for (c in children) c.redo(doc) }
}

data class PageProps(val name: String, val background: TemplateSpec, val width: Double, val height: Double)

class PagePropsCommand(override val pageId: String, val before: PageProps, val after: PageProps, override val text: String) : Command {
    override fun undo(doc: Document) = doc.applyPageProps(pageId, before)
    override fun redo(doc: Document) = doc.applyPageProps(pageId, after)
}

class InsertPageCommand(val index: Int, page: Page, override val text: String) : Command {
    private val frozen = page.clone(false)
    override val pageId: String? get() = frozen.id
    override fun undo(doc: Document) = doc.removePageAt(doc.indexOfPage(frozen.id))
    override fun redo(doc: Document) = doc.insertPageAt(index, frozen.clone(false))
}

class RemovePageCommand(val index: Int, page: Page, override val text: String) : Command {
    private val frozen = page.clone(false)
    override val pageId: String? get() = null
    override fun undo(doc: Document) { doc.insertPageAt(index, frozen.clone(false)); doc.setCurrentPage(index) }
    override fun redo(doc: Document) = doc.removePageAt(doc.indexOfPage(frozen.id))
}

class MovePageCommand(val from: Int, val to: Int, override val text: String = "Move page") : Command {
    override val pageId: String? get() = null
    override fun undo(doc: Document) = doc.movePageRaw(to, from)
    override fun redo(doc: Document) = doc.movePageRaw(from, to)
}

class ScaleCommand(val before: MeasureScale, val after: MeasureScale) : Command {
    override val text = "Change scale"
    override val pageId: String? get() = null
    override fun undo(doc: Document) = doc.setScaleRaw(before)
    override fun redo(doc: Document) = doc.setScaleRaw(after)
}

interface DocumentListener {
    fun onPageContentChanged(pageId: String) {}
    fun onPagesChanged() {}
    fun onCurrentPageChanged(index: Int) {}
    fun onHistoryChanged() {}
    fun onDocumentReset() {}
    fun onCoordinatesChanged() {}
}

data class DocumentMetadata(
    var title: String = "",
    var author: String = "",
    var created: String = nowIso(),
    var modified: String = nowIso(),
)

fun nowIso(): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date())
}

/** Everything needed to (re)build a document, produced by deserialisation. */
class DocumentContents(
    val pages: MutableList<Page> = ArrayList(),
    val images: ImageStore = ImageStore(),
    var coordinates: CoordinateSystem = CoordinateSystem.default(),
    var metadata: DocumentMetadata = DocumentMetadata(),
    var defaultTemplate: TemplateSpec = TemplateSpec(),
    var defaultPageWidth: Double = Page.DEFAULT_WIDTH,
    var defaultPageHeight: Double = Page.DEFAULT_HEIGHT,
    var currentPage: Int = 0,
    var skippedObjects: Int = 0,
)

/** A lesson: ordered pages, shared assets and a single undo history. */
class Document {
    val pages = ArrayList<Page>()
    var currentPageIndex = 0
        private set
    val images = ImageStore()
    var coordinates: CoordinateSystem = CoordinateSystem.default()
        private set
    var metadata = DocumentMetadata()
    var defaultTemplate = TemplateSpec()
    var defaultPageWidth = Page.DEFAULT_WIDTH
    var defaultPageHeight = Page.DEFAULT_HEIGHT
    /** Where the lesson was opened from / saved to (content URI string or file path). */
    var fileUri: String? = null
    var fileName: String? = null
    var revision = 0L
        private set

    private val listeners = ArrayList<DocumentListener>()
    private val undoStack = ArrayList<Command>()
    private var undoIndex = 0
    private var cleanIndex = 0
    private val limit = 300
    /** Frozen object lists matching the last committed state of each page. */
    private val committed = HashMap<String, List<BoardObject>>()

    init {
        pages.add(createPage())
        freezeAll()
    }

    fun addListener(l: DocumentListener) { listeners.add(l) }
    fun removeListener(l: DocumentListener) { listeners.remove(l) }

    val pageCount get() = pages.size
    fun page(i: Int): Page? = pages.getOrNull(i)
    val currentPage: Page get() = pages[currentPageIndex.coerceIn(0, pages.size - 1)]
    fun pageById(id: String) = pages.firstOrNull { it.id == id }
    fun indexOfPage(id: String) = pages.indexOfFirst { it.id == id }

    fun setCurrentPage(i: Int) {
        val idx = i.coerceIn(0, pages.size - 1)
        if (idx == currentPageIndex) return
        currentPageIndex = idx
        listeners.toList().forEach { it.onCurrentPageChanged(idx) }
    }

    fun createPage(): Page {
        val p = Page()
        p.background = defaultTemplate
        p.width = defaultPageWidth
        p.height = defaultPageHeight
        return p
    }

    /** The global system as it applies to a page (origin at the page centre). */
    fun coordinatesFor(page: Page?): CoordinateSystem {
        if (page == null) return coordinates
        val c = page.frameRect.center
        return coordinates.translated(Pt(c.x - Page.DEFAULT_WIDTH / 2, c.y - Page.DEFAULT_HEIGHT / 2))
    }

    /** Coordinate system for page points: a graph's own system when all points lie inside it. */
    fun resolveCoordinates(page: Page, points: List<Pt>, exclude: BoardObject? = null): CoordinateSystem {
        val global = coordinatesFor(page)
        if (points.isEmpty()) return global
        for (o in page.objects.asReversed()) {
            if (o === exclude) continue
            val cs = o.mathCoordinateSystem() ?: continue
            if (points.all { o.hitTest(it, 0.0) }) return cs
        }
        return global
    }

    // ------------------------------------------------------------------ history

    val canUndo get() = undoIndex > 0
    val canRedo get() = undoIndex < undoStack.size
    val isModified get() = undoIndex != cleanIndex
    fun undoText() = if (canUndo) undoStack[undoIndex - 1].text else ""

    private fun record(cmd: Command) {
        while (undoStack.size > undoIndex) undoStack.removeAt(undoStack.size - 1)
        if (cleanIndex > undoIndex) cleanIndex = -1
        undoStack.add(cmd)
        undoIndex++
        while (undoStack.size > limit) {
            undoStack.removeAt(0)
            undoIndex--
            cleanIndex--
        }
        bump()
        listeners.toList().forEach { it.onHistoryChanged() }
    }

    /** Records a command whose effect has already been applied. */
    fun recordApplied(cmd: Command) = record(cmd)

    /** Executes and records a command. */
    fun push(cmd: Command) {
        cmd.redo(this)
        record(cmd)
    }

    /**
     * Records the current (already applied) content of a page as one undo step. Objects whose
     * ids are in [modified] were changed in place and get a fresh frozen copy.
     */
    fun commit(page: Page, text: String, modified: Collection<String> = emptyList()): Command? {
        val before = committed[page.id] ?: emptyList()
        val beforeById = before.associateBy { it.id }
        val mod = modified.toHashSet()
        var changed = before.size != page.objects.size || mod.isNotEmpty()
        val after = page.objects.mapIndexed { i, o ->
            val frozen = beforeById[o.id]
            if (frozen != null && o.id !in mod) {
                if (i >= before.size || before[i].id != o.id) changed = true
                frozen
            } else {
                changed = true
                o.clone()
            }
        }
        if (!changed) return null
        committed[page.id] = after
        page.touch()
        val cmd = ContentCommand(page.id, before, after, text)
        record(cmd)
        listeners.toList().forEach { it.onPageContentChanged(page.id) }
        return cmd
    }

    /** Commits several pages in one undo step. */
    fun commitPages(pagesToCommit: List<Page>, text: String) {
        val cmds = ArrayList<Command>()
        for (p in pagesToCommit) {
            val before = committed[p.id] ?: emptyList()
            val after = p.objects.map { it.clone() }
            committed[p.id] = after
            p.touch()
            cmds.add(ContentCommand(p.id, before, after, text))
        }
        if (cmds.isEmpty()) return
        record(CompositeCommand(text, cmds))
        for (p in pagesToCommit) listeners.toList().forEach { it.onPageContentChanged(p.id) }
    }

    /** Throws away uncommitted live changes of a page. */
    fun revert(page: Page) {
        committed[page.id]?.let { applySnapshot(page.id, it) }
    }

    fun undo() {
        if (!canUndo) return
        val cmd = undoStack[--undoIndex]
        cmd.undo(this)
        navigateTo(cmd)
        bump()
        listeners.toList().forEach { it.onHistoryChanged() }
    }

    fun redo() {
        if (!canRedo) return
        val cmd = undoStack[undoIndex++]
        cmd.redo(this)
        navigateTo(cmd)
        bump()
        listeners.toList().forEach { it.onHistoryChanged() }
    }

    private fun navigateTo(cmd: Command) {
        val id = cmd.pageId ?: return
        val i = indexOfPage(id)
        if (i >= 0) setCurrentPage(i)
    }

    fun markSaved() {
        cleanIndex = undoIndex
        listeners.toList().forEach { it.onHistoryChanged() }
    }

    private fun bump() { revision++ }

    // ------------------------------------------------------------- low level (commands)

    fun applySnapshot(pageId: String, snap: List<BoardObject>) {
        val p = pageById(pageId) ?: return
        p.objects.clear()
        for (o in snap) p.objects.add(o.clone())
        committed[pageId] = snap
        p.touch()
        listeners.toList().forEach { it.onPageContentChanged(pageId) }
    }

    fun applyPageProps(pageId: String, props: PageProps) {
        val p = pageById(pageId) ?: return
        p.name = props.name
        p.background = props.background
        p.width = props.width
        p.height = props.height
        p.touch()
        listeners.toList().forEach { it.onPageContentChanged(pageId) }
        listeners.toList().forEach { it.onPagesChanged() }
    }

    fun propsOf(p: Page) = PageProps(p.name, p.background, p.width, p.height)

    /** Changes page properties of several pages in one undo step. */
    fun changePages(targets: List<Page>, text: String, change: (Page) -> PageProps) {
        val cmds = targets.map { p -> PagePropsCommand(p.id, propsOf(p), change(p), text) }
        if (cmds.isEmpty()) return
        push(if (cmds.size == 1) cmds[0] else CompositeCommand(text, cmds))
    }

    fun insertPageAt(index: Int, page: Page) {
        val i = index.coerceIn(0, pages.size)
        pages.add(i, page)
        committed[page.id] = page.objects.map { it.clone() }
        if (i <= currentPageIndex && pages.size > 1) currentPageIndex++
        listeners.toList().forEach { it.onPagesChanged() }
        setCurrentPageForce(i)
    }

    fun removePageAt(index: Int) {
        if (index !in pages.indices || pages.size <= 1) return
        val p = pages.removeAt(index)
        committed.remove(p.id)
        if (currentPageIndex >= pages.size) currentPageIndex = pages.size - 1
        else if (index < currentPageIndex) currentPageIndex--
        listeners.toList().forEach { it.onPagesChanged() }
        listeners.toList().forEach { it.onCurrentPageChanged(currentPageIndex) }
    }

    fun movePageRaw(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val cur = currentPage
        val p = pages.removeAt(from)
        pages.add(to, p)
        currentPageIndex = pages.indexOf(cur)
        listeners.toList().forEach { it.onPagesChanged() }
        listeners.toList().forEach { it.onCurrentPageChanged(currentPageIndex) }
    }

    private fun setCurrentPageForce(i: Int) {
        currentPageIndex = i.coerceIn(0, pages.size - 1)
        listeners.toList().forEach { it.onCurrentPageChanged(currentPageIndex) }
    }

    fun setScaleRaw(s: MeasureScale) {
        coordinates = coordinates.withScale(s)
        for (p in pages) p.touch()
        listeners.toList().forEach { it.onCoordinatesChanged() }
        listeners.toList().forEach { l -> pages.forEach { l.onPageContentChanged(it.id) } }
    }

    // ------------------------------------------------------------------ high level page ops

    fun addPage(after: Int = currentPageIndex, page: Page = createPage(), text: String = "New page") {
        push(InsertPageCommand(after + 1, page, text))
    }

    fun deletePage(index: Int) {
        val p = page(index) ?: return
        if (pages.size <= 1) {
            // Deleting the only page clears it instead.
            p.objects.clear()
            commit(p, "Delete page")
            return
        }
        push(RemovePageCommand(index, p, "Delete page"))
    }

    fun movePage(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        push(MovePageCommand(from, to))
    }

    // ------------------------------------------------------------------ whole document

    private fun freezeAll() {
        committed.clear()
        for (p in pages) committed[p.id] = p.objects.map { it.clone() }
    }

    fun resetToNew(background: TemplateSpec) {
        undoStack.clear(); undoIndex = 0; cleanIndex = 0
        pages.clear()
        images.clear()
        defaultTemplate = background
        defaultPageWidth = Page.DEFAULT_WIDTH
        defaultPageHeight = Page.DEFAULT_HEIGHT
        coordinates = CoordinateSystem.default()
        metadata = DocumentMetadata()
        pages.add(createPage())
        currentPageIndex = 0
        fileUri = null
        fileName = null
        freezeAll()
        bump()
        notifyReset()
    }

    fun setContents(c: DocumentContents) {
        undoStack.clear(); undoIndex = 0; cleanIndex = 0
        pages.clear()
        pages.addAll(c.pages)
        if (pages.isEmpty()) pages.add(createPage())
        images.clear()
        images.mergeFrom(c.images)
        coordinates = c.coordinates
        metadata = c.metadata
        defaultTemplate = c.defaultTemplate
        defaultPageWidth = c.defaultPageWidth
        defaultPageHeight = c.defaultPageHeight
        currentPageIndex = c.currentPage.coerceIn(0, pages.size - 1)
        freezeAll()
        bump()
        notifyReset()
    }

    private fun notifyReset() {
        listeners.toList().forEach { it.onDocumentReset() }
        listeners.toList().forEach { it.onPagesChanged() }
        listeners.toList().forEach { it.onCurrentPageChanged(currentPageIndex) }
        listeners.toList().forEach { it.onHistoryChanged() }
    }

    /** Deep copy for saving / exporting on another thread. */
    fun copyContents(pageIndices: List<Int>? = null): DocumentContents {
        val c = DocumentContents()
        val idx = pageIndices ?: pages.indices.toList()
        for (i in idx) pages.getOrNull(i)?.let { c.pages.add(it.clone(false)) }
        c.images.mergeFrom(images)
        c.coordinates = coordinates
        c.metadata = metadata.copy()
        c.defaultTemplate = defaultTemplate
        c.defaultPageWidth = defaultPageWidth
        c.defaultPageHeight = defaultPageHeight
        c.currentPage = currentPageIndex
        return c
    }

    fun displayName(): String = fileName?.removeSuffix(".classboard") ?: "Untitled lesson"
}
