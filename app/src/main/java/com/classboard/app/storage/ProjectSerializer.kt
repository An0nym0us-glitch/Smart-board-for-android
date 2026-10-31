package com.classboard.app.storage

import com.classboard.app.core.Json
import com.classboard.app.doc.DocumentContents
import com.classboard.app.doc.DocumentMetadata
import com.classboard.app.doc.Page
import com.classboard.app.doc.TemplateSpec
import com.classboard.app.math.CoordinateSystem
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Reads and writes `.classboard` lessons (compatible with the desktop app). */
object ProjectSerializer {
    const val FORMAT_VERSION = 1
    const val APP_VERSION = "0.3.1"
    private const val DOCUMENT_ENTRY = "document.json"
    private const val IMAGE_PREFIX = "images/"

    class LoadException(msg: String) : Exception(msg)

    private fun referencedImages(c: DocumentContents): Set<String> {
        val keys = HashSet<String>()
        for (p in c.pages) {
            if (p.background.imageKey.isNotEmpty()) keys.add(p.background.imageKey)
            for (o in p.objects) o.collectImageKeys(keys)
        }
        if (c.defaultTemplate.imageKey.isNotEmpty()) keys.add(c.defaultTemplate.imageKey)
        return keys
    }

    fun documentToJson(c: DocumentContents): JSONObject {
        val root = JSONObject()
        root.put("format", "classboard")
        root.put("formatVersion", FORMAT_VERSION)
        root.put("application", "ClassBoard $APP_VERSION (Android)")
        root.put("metadata", JSONObject().put("title", c.metadata.title).put("author", c.metadata.author)
            .put("created", c.metadata.created).put("modified", c.metadata.modified))
        root.put("coordinates", c.coordinates.toJson())
        root.put("defaultTemplate", c.defaultTemplate.toJson())
        root.put("defaultPageSize", Json.fromSize(c.defaultPageWidth, c.defaultPageHeight))
        root.put("currentPage", c.currentPage)
        val pages = JSONArray()
        for (p in c.pages) pages.put(p.toJson())
        root.put("pages", pages)
        return root
    }

    fun documentFromJson(root: JSONObject, out: DocumentContents): Boolean {
        val meta = root.optJSONObject("metadata")
        out.metadata = DocumentMetadata(
            title = meta?.optString("title", "") ?: "",
            author = meta?.optString("author", "") ?: "",
            created = meta?.optString("created", "") ?: "",
            modified = meta?.optString("modified", "") ?: "",
        )
        if (root.has("coordinates")) out.coordinates = CoordinateSystem.fromJson(root.optJSONObject("coordinates"))
        out.defaultTemplate = TemplateSpec.fromJson(root.optJSONObject("defaultTemplate"))
        val (w, h) = Json.toSize(root.opt("defaultPageSize"), Page.DEFAULT_WIDTH, Page.DEFAULT_HEIGHT)
        if (w >= 100 && h >= 100) { out.defaultPageWidth = w; out.defaultPageHeight = h }
        out.currentPage = root.optInt("currentPage", 0)
        out.pages.clear()
        val arr = root.optJSONArray("pages") ?: JSONArray()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val po = arr.optJSONObject(i) ?: continue
            val (p, s) = Page.fromJson(po)
            out.pages.add(p)
            skipped += s
        }
        out.skippedObjects = skipped
        return out.pages.isNotEmpty()
    }

    fun serialize(c: DocumentContents): ByteArray {
        val container = Container()
        container.add(DOCUMENT_ENTRY, documentToJson(c).toString().toByteArray(Charsets.UTF_8), true)
        for (key in referencedImages(c)) {
            val bytes = c.images.encodedData(key) ?: continue
            container.add(IMAGE_PREFIX + key + "." + c.images.suffix(key), bytes, false)
        }
        return container.write()
    }

    fun deserialize(bytes: ByteArray): DocumentContents {
        val container = Container()
        try {
            container.read(bytes)
        } catch (e: Container.FormatException) {
            throw LoadException(e.message ?: "The lesson file is damaged.")
        }
        val entry = container.find(DOCUMENT_ENTRY) ?: throw LoadException("The lesson file does not contain a document.")
        val root = try {
            JSONObject(String(entry.data, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw LoadException("The lesson document is damaged: ${e.message}")
        }
        if (root.optString("format") != "classboard") throw LoadException("This is not a ClassBoard lesson.")
        migrate(root)
        val contents = DocumentContents()
        for (e in container.entries) if (e.name.startsWith(IMAGE_PREFIX)) contents.images.addEncoded(e.data)
        if (!documentFromJson(root, contents)) throw LoadException("The lesson does not contain any pages.")
        return contents
    }

    private fun migrate(root: JSONObject) {
        var version = root.optInt("formatVersion", 0)
        if (version > FORMAT_VERSION) return
        while (version < FORMAT_VERSION) {
            when (version) {
                0 -> {
                    val pages = root.optJSONArray("pages") ?: JSONArray()
                    for (i in 0 until pages.length()) {
                        val p = pages.optJSONObject(i) ?: continue
                        if (p.has("items") && !p.has("objects")) {
                            p.put("objects", p.opt("items"))
                            p.remove("items")
                        }
                    }
                }
                else -> throw LoadException("Unsupported lesson format version $version.")
            }
            version++
            root.put("formatVersion", version)
        }
    }
}
