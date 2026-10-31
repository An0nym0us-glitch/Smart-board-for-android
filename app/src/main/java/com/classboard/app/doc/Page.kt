package com.classboard.app.doc

import com.classboard.app.core.Ids
import com.classboard.app.core.Json
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import org.json.JSONArray
import org.json.JSONObject

/** A page of a lesson: objects in z-order on a template background. */
class Page {
    var id: String = Ids.newId()
    var name: String = ""
    var background = TemplateSpec()
    var width = DEFAULT_WIDTH
    var height = DEFAULT_HEIGHT
    val objects = ArrayList<BoardObject>()
    var revision = 0L
        private set

    fun touch() { revision++ }

    val frameRect get() = RectD(0.0, 0.0, width, height)

    fun objectById(id: String) = objects.firstOrNull { it.id == id }
    fun indexOf(id: String) = objects.indexOfFirst { it.id == id }

    fun topmostAt(p: Pt, tol: Double): BoardObject? = objects.lastOrNull { it.hitTest(p, tol) }
    fun topmostEnclosing(p: Pt): BoardObject? = objects.lastOrNull { it.enclosesPoint(p) }
    fun objectsIntersecting(r: RectD) = objects.filter { it.sceneBounds().intersects(r) }

    fun contentBounds(): RectD? {
        var r: RectD? = null
        for (o in objects) r = r?.union(o.sceneBounds()) ?: o.sceneBounds()
        return r
    }

    /** The frame grown (keeping aspect ratio) to include all content. */
    fun exportRect(): RectD {
        val frame = frameRect
        val content = contentBounds() ?: return frame
        if (frame.contains(content)) return frame
        var r = frame.union(content)
        val aspect = frame.width / frame.height
        r = if (r.width / r.height > aspect) {
            val h = r.width / aspect
            RectD(r.left, r.top - (h - r.height) / 2, r.right, r.bottom + (h - r.height) / 2)
        } else {
            val w = r.height * aspect
            RectD(r.left - (w - r.width) / 2, r.top, r.right + (w - r.width) / 2, r.bottom)
        }
        return r
    }

    fun insertObject(index: Int, o: BoardObject) {
        objects.add(index.coerceIn(0, objects.size), o)
        touch()
    }

    fun takeObject(index: Int): BoardObject? {
        if (index !in objects.indices) return null
        touch()
        return objects.removeAt(index)
    }

    fun replaceObject(index: Int, o: BoardObject): BoardObject? {
        if (index !in objects.indices) return null
        val old = objects[index]
        objects[index] = o
        touch()
        return old
    }

    fun clone(freshIds: Boolean): Page {
        val p = Page()
        p.id = if (freshIds) Ids.newId() else id
        p.name = name
        p.background = background
        p.width = width
        p.height = height
        for (o in objects) {
            val c = o.clone()
            if (freshIds) c.id = Ids.newId()
            p.objects.add(c)
        }
        return p
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        if (name.isNotEmpty()) o.put("name", name)
        o.put("size", Json.fromSize(width, height))
        o.put("template", background.toJson())
        val arr = JSONArray()
        for (obj in objects) arr.put(obj.toJson())
        o.put("objects", arr)
        return o
    }

    companion object {
        const val DEFAULT_WIDTH = 1920.0
        const val DEFAULT_HEIGHT = 1080.0

        /** Parses a page; returns the page and the number of skipped objects. */
        fun fromJson(o: JSONObject): Pair<Page, Int> {
            val p = Page()
            val i = o.optString("id", "")
            if (i.isNotEmpty()) p.id = i
            p.name = o.optString("name", "")
            val (w, h) = Json.toSize(o.opt("size"), DEFAULT_WIDTH, DEFAULT_HEIGHT)
            if (w >= 100 && h >= 100) { p.width = w; p.height = h }
            p.background = TemplateSpec.fromJson(o.optJSONObject("template"))
            val arr = o.optJSONArray("objects") ?: o.optJSONArray("items") ?: JSONArray()
            var skipped = 0
            for (k in 0 until arr.length()) {
                val obj = arr.optJSONObject(k)?.let { ObjectFactory.fromJson(it) }
                if (obj != null) p.objects.add(obj) else skipped++
            }
            return Pair(p, skipped)
        }
    }
}
