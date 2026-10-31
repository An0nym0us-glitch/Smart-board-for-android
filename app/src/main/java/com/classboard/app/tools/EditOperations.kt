package com.classboard.app.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import com.classboard.app.core.Ids
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import com.classboard.app.doc.BoardObject
import com.classboard.app.doc.Document
import com.classboard.app.doc.ImageObject
import com.classboard.app.doc.ObjectFactory
import com.classboard.app.doc.TextObject
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** Delete, duplicate, copy/cut/paste, select all, arrange and recolour the selection. */
class EditOperations(private val context: Context, private val doc: Document, private val selection: SelectionModel) {
    private var pasteCount = 0

    val hasSelection get() = !selection.isEmpty

    private fun selected(): List<BoardObject> {
        val page = doc.currentPage
        return selection.ids.mapNotNull { page.objectById(it) }
    }

    fun deleteSelection() {
        val page = doc.currentPage
        val objs = selected()
        if (objs.isEmpty()) return
        selection.clear()
        page.objects.removeAll(objs.toSet())
        doc.commit(page, "Delete")
    }

    fun duplicateSelection() {
        val page = doc.currentPage
        val copies = selected().map { o -> o.clone().also { it.id = Ids.newId(); it.position = o.position + Pt(32.0, 32.0) } }
        if (copies.isEmpty()) return
        page.objects.addAll(copies)
        doc.commit(page, "Duplicate")
        selection.set(copies.map { it.id })
    }

    fun copySelection() {
        val objs = selected()
        if (objs.isEmpty()) return
        pasteCount = 0
        val arr = JSONArray()
        val keys = HashSet<String>()
        for (o in objs) { arr.put(o.toJson()); o.collectImageKeys(keys) }
        val images = JSONObject()
        for (k in keys) doc.images.encodedData(k)?.let { images.put(k, JSONObject().put("data", Base64.encodeToString(it, Base64.NO_WRAP))) }
        internalClipboard = JSONObject().put("objects", arr).put("images", images).put("page", doc.currentPage.id).toString()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = objs.filterIsInstance<TextObject>().joinToString("\n") { it.text }.ifEmpty { "ClassBoard objects" }
        cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    fun cutSelection() { copySelection(); deleteSelection() }

    fun canPaste(): Boolean {
        if (internalClipboard != null) return true
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.hasPrimaryClip()
    }

    /** Pastes at the given page centre; returns true if something was inserted. */
    fun paste(center: Pt): Boolean {
        val page = doc.currentPage
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val ours = clip?.description?.label == CLIP_LABEL
        val objects = ArrayList<BoardObject>()
        var text = "Paste"
        val internal = internalClipboard
        if (internal != null && (ours || clip == null)) {
            val root = JSONObject(internal)
            root.optJSONObject("images")?.let { imgs ->
                for (k in imgs.keys()) {
                    val data = imgs.optJSONObject(k)?.optString("data") ?: continue
                    doc.images.addEncoded(Base64.decode(data, Base64.DEFAULT))
                }
            }
            val samePage = root.optString("page") == page.id
            var offset = Pt.ZERO
            if (samePage) { pasteCount++; offset = Pt(32.0, 32.0) * pasteCount.toDouble() }
            var bounds: RectD? = null
            val arr = root.optJSONArray("objects") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)?.let { ObjectFactory.fromJson(it) } ?: continue
                o.id = Ids.newId()
                bounds = bounds?.union(o.sceneBounds()) ?: o.sceneBounds()
                objects.add(o)
            }
            if (!samePage && bounds != null) offset = center - bounds.center
            objects.forEach { it.position = it.position + offset }
        } else if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val uri = item.uri
            if (uri != null) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val key = bytes?.let { doc.images.addEncoded(it) }
                    if (key != null) { objects.add(imageObject(key, center)); text = "Paste image" }
                } catch (e: Exception) { }
            }
            if (objects.isEmpty()) {
                val s = item.coerceToText(context)?.toString()?.trim() ?: ""
                if (s.isNotEmpty()) {
                    val fmt = com.classboard.app.doc.TextFormat()
                    val t = TextObject.create(s, Pt.ZERO, fmt)
                    t.position = center
                    objects.add(t)
                    text = "Paste text"
                }
            }
        }
        if (objects.isEmpty()) return false
        page.objects.addAll(objects)
        doc.commit(page, text)
        selection.set(objects.map { it.id })
        return true
    }

    fun imageObject(key: String, center: Pt): ImageObject {
        val (w0, h0) = doc.images.originalSize(key) ?: Pair(400, 300)
        val maxSide = 900.0
        val s = min(1.0, maxSide / maxOf(w0, h0).toDouble())
        return ImageObject.create(key, w0 * s, h0 * s, center)
    }

    fun selectAll() = selection.set(doc.currentPage.objects.map { it.id })

    fun bringToFront() {
        val page = doc.currentPage
        val objs = page.objects.filter { selection.contains(it.id) }
        if (objs.isEmpty()) return
        page.objects.removeAll(objs.toSet())
        page.objects.addAll(objs)
        doc.commit(page, "Bring to front")
    }

    fun sendToBack() {
        val page = doc.currentPage
        val objs = page.objects.filter { selection.contains(it.id) }
        if (objs.isEmpty()) return
        page.objects.removeAll(objs.toSet())
        page.objects.addAll(0, objs)
        doc.commit(page, "Send to back")
    }

    fun setSelectionColor(color: Int) {
        val page = doc.currentPage
        val changed = selected().filter { it.setColor(color) }
        if (changed.isNotEmpty()) doc.commit(page, "Change colour", changed.map { it.id })
    }

    companion object {
        const val CLIP_LABEL = "ClassBoard"
        private var internalClipboard: String? = null
    }
}
