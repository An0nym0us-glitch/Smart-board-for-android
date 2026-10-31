package com.classboard.app.doc

import android.content.Context
import com.classboard.app.core.Ids
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Built-in background templates plus the teacher's own (saved in the app's files). */
class TemplateLibrary(private val context: Context) {
    var templates: List<TemplateSpec> = builtins()
        private set
    private var custom: List<TemplateSpec> = emptyList()

    private val dir get() = File(context.filesDir, "templates").also { it.mkdirs() }

    fun load() {
        custom = try {
            val f = File(dir, "custom.json")
            if (!f.exists()) emptyList() else {
                val arr = JSONObject(f.readText()).optJSONArray("templates") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> TemplateSpec.fromJson(o) } }
            }
        } catch (e: Exception) { emptyList() }
        templates = builtins() + custom
    }

    fun find(id: String) = templates.firstOrNull { it.id == id } ?: templates.first()
    fun isCustom(id: String) = custom.any { it.id == id }

    private fun saveCustom() {
        val arr = JSONArray()
        custom.forEach { arr.put(it.toJson()) }
        File(dir, "custom.json").writeText(JSONObject().put("templates", arr).toString())
    }

    fun addCustom(spec0: TemplateSpec, imageBytes: ByteArray?): TemplateSpec {
        var spec = spec0
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val key = ImageStore.sha1(imageBytes)
            File(dir, "$key.img").writeBytes(imageBytes)
            spec = spec.copy(imageKey = key, kind = TemplateKind.Image)
        }
        if (spec.id.isEmpty() || templates.any { it.id == spec.id }) spec = spec.copy(id = "custom-" + Ids.newId().take(8))
        custom = custom + spec
        saveCustom()
        load()
        return spec
    }

    fun removeCustom(id: String): Boolean {
        if (custom.none { it.id == id }) return false
        custom = custom.filter { it.id != id }
        saveCustom()
        load()
        return true
    }

    /** Makes sure an image template's picture is in the document store. */
    fun ensureImage(spec: TemplateSpec, store: ImageStore) {
        if (spec.kind != TemplateKind.Image || spec.imageKey.isEmpty() || store.contains(spec.imageKey)) return
        val f = File(dir, spec.imageKey + ".img")
        if (f.exists()) store.addEncoded(f.readBytes())
    }

    companion object {
        private fun c(s: String) = com.classboard.app.core.Json.parseColor(s)!!

        fun builtins(): List<TemplateSpec> = listOf(
            TemplateSpec("blackboard", "Blackboard", TemplateKind.Blank, c("#ff1f2b26"), c("#28ffffff"), c("#5affffff"), 40.0, 5),
            TemplateSpec("slate", "Slate", TemplateKind.Blank, c("#ff1b1f24"), c("#28ffffff"), c("#5affffff"), 40.0, 5),
            TemplateSpec("whiteboard", "Whiteboard", TemplateKind.Blank, c("#fff7f7f2"), c("#30000000"), c("#60000000"), 40.0, 5),
            TemplateSpec("grid", "Grid", TemplateKind.Grid, c("#ff1f2b26"), c("#22ffffff"), c("#44ffffff"), 40.0, 5),
            TemplateSpec("dots", "Dot grid", TemplateKind.Dots, c("#ff1f2b26"), c("#22ffffff"), c("#66ffffff"), 40.0, 5),
            TemplateSpec("ruled", "Ruled paper", TemplateKind.Ruled, c("#ff1f2b26"), c("#33ffffff"), c("#80ef9a9a"), 56.0, 0),
            TemplateSpec("graph", "Graph paper", TemplateKind.GraphPaper, c("#ff1f2b26"), c("#18ffffff"), c("#40ffffff"), 8.0, 5),
            TemplateSpec("music", "Music staff", TemplateKind.MusicStaff, c("#ff1f2b26"), c("#90ffffff"), c("#90ffffff"), 14.0, 0),
            TemplateSpec("coordinate", "Coordinate plane", TemplateKind.CoordinatePlane, c("#ff1f2b26"), c("#20ffffff"), c("#40ffffff"), 40.0, 5),
            TemplateSpec("white-grid", "White grid", TemplateKind.Grid, c("#fff7f7f2"), c("#22000000"), c("#44000000"), 40.0, 5),
        )

        fun withBackgroundColor(spec: TemplateSpec, color: Int): TemplateSpec {
            val bg = color or 0xFF000000.toInt()
            var out = spec.copy(background = bg, id = String.format("color-%06x", bg and 0xFFFFFF), name = "Custom colour")
            val dark = out.isDark
            out = out.copy(
                lineColor = if (dark) 0x28FFFFFF else 0x2D002850,
                accentColor = if (dark) 0x5AFFFFFF else 0x64002850,
            )
            return out
        }
    }
}
