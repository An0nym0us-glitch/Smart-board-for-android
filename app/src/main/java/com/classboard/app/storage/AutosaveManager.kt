package com.classboard.app.storage

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.classboard.app.doc.Document
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Keeps a recovery copy of the lesson while it has unsaved changes. */
class AutosaveManager(private val context: Context, private val doc: Document) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    var intervalSeconds = 60
    private var lastSavedRevision = -1L
    var onFailed: ((String) -> Unit)? = null

    private val dir get() = File(context.filesDir, "recovery").also { it.mkdirs() }
    private val tick = object : Runnable {
        override fun run() {
            saveNowIfNeeded()
            handler.postDelayed(this, intervalSeconds * 1000L)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, intervalSeconds * 1000L)
    }

    fun stop() = handler.removeCallbacks(tick)

    fun saveNowIfNeeded() {
        if (!doc.isModified) return
        if (doc.revision == lastSavedRevision) return
        saveNow()
    }

    fun saveNow() {
        lastSavedRevision = doc.revision
        val contents = doc.copyContents()
        val title = doc.displayName()
        val uri = doc.fileUri ?: ""
        val name = doc.fileName ?: ""
        executor.execute {
            try {
                val bytes = ProjectSerializer.serialize(contents)
                val tmp = File(dir, "recovery.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(dir, "recovery.classboard"))
                File(dir, "recovery.json").writeText(JSONObject().put("title", title).put("uri", uri).put("name", name)
                    .put("savedAt", System.currentTimeMillis()).toString())
            } catch (e: Exception) {
                handler.post { onFailed?.invoke(e.message ?: "error") }
            }
        }
    }

    fun discard() {
        lastSavedRevision = doc.revision
        executor.execute {
            File(dir, "recovery.classboard").delete()
            File(dir, "recovery.json").delete()
        }
    }

    class RecoveryInfo(val title: String, val uri: String, val name: String, val savedAt: Long, val file: File)

    fun findRecoverable(): RecoveryInfo? {
        val f = File(dir, "recovery.classboard")
        val info = File(dir, "recovery.json")
        if (!f.exists()) return null
        return try {
            val o = if (info.exists()) JSONObject(info.readText()) else JSONObject()
            RecoveryInfo(o.optString("title", "Lesson"), o.optString("uri", ""), o.optString("name", ""), o.optLong("savedAt", f.lastModified()), f)
        } catch (e: Exception) { null }
    }
}
