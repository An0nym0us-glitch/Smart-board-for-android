package com.classboard.app.storage

import android.content.Context
import com.classboard.app.doc.StrokeStyle
import com.classboard.app.doc.TextFormat
import com.classboard.app.tools.EraserMode
import com.classboard.app.tools.SelectMode
import com.classboard.app.tools.ShapeFill
import com.classboard.app.tools.ToolSettings
import org.json.JSONArray

/** Persistent preferences (SharedPreferences). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("classboard", Context.MODE_PRIVATE)

    var uiScale: Double
        get() = prefs.getFloat("uiScale", 0f).toDouble()
        set(v) = prefs.edit().putFloat("uiScale", v.toFloat()).apply()
    var ribbonScale: Double
        get() = prefs.getFloat("ribbonScale", 1f).toDouble()
        set(v) = prefs.edit().putFloat("ribbonScale", v.coerceIn(0.6, 1.5).toFloat()).apply()
    var autosaveSeconds: Int
        get() = prefs.getInt("autosaveSeconds", 60)
        set(v) = prefs.edit().putInt("autosaveSeconds", v.coerceIn(15, 600)).apply()
    var palmErase: Boolean
        get() = prefs.getBoolean("palmErase", true)
        set(v) = prefs.edit().putBoolean("palmErase", v).apply()
    var multiUserTouch: Boolean
        get() = prefs.getBoolean("multiUserTouch", false)
        set(v) = prefs.edit().putBoolean("multiUserTouch", v).apply()
    var showPageFrame: Boolean
        get() = prefs.getBoolean("showPageFrame", false)
        set(v) = prefs.edit().putBoolean("showPageFrame", v).apply()
    var fullScreen: Boolean
        get() = prefs.getBoolean("fullScreen", true)
        set(v) = prefs.edit().putBoolean("fullScreen", v).apply()
    var defaultTemplateId: String
        get() = prefs.getString("defaultTemplate", "blackboard") ?: "blackboard"
        set(v) = prefs.edit().putString("defaultTemplate", v).apply()

    /** Recent lessons as (uri, display name). */
    fun recentFiles(): List<Pair<String, String>> {
        val arr = try { JSONArray(prefs.getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONArray(it)?.let { a -> Pair(a.optString(0), a.optString(1)) } }
    }

    fun addRecentFile(uri: String, name: String) {
        val list = recentFiles().filter { it.first != uri }.toMutableList()
        list.add(0, Pair(uri, name))
        val arr = JSONArray()
        list.take(10).forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    fun removeRecentFile(uri: String) {
        val arr = JSONArray()
        recentFiles().filter { it.first != uri }.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    fun loadTools(t: ToolSettings) {
        t.penColor = prefs.getInt("penColor", t.penColor)
        t.penStyle = StrokeStyle.fromKey(prefs.getString("penStyle", "pen"))
        t.basePenWidth = prefs.getFloat("penWidth", 4f).toDouble()
        t.highlighterWidth = prefs.getFloat("highlighterWidth", 22f).toDouble()
        t.pressureEnabled = prefs.getBoolean("pressure", true)
        t.shapeRecognition = prefs.getBoolean("shapeRecognition", false)
        t.eraserMode = EraserMode.values().getOrElse(prefs.getInt("eraserMode", 2)) { EraserMode.Area }
        t.eraserSize = prefs.getFloat("eraserSize", 48f).toDouble()
        t.selectMode = SelectMode.values().getOrElse(prefs.getInt("selectMode", 0)) { SelectMode.Rectangle }
        t.shapeFill = ShapeFill.values().getOrElse(prefs.getInt("shapeFill", 0)) { ShapeFill.None }
        t.polygonSides = prefs.getInt("polygonSides", 5)
        val f = TextFormat()
        t.textFormat = f.copy(
            pixelSize = prefs.getInt("textSize", f.pixelSize), bold = prefs.getBoolean("textBold", false),
            italic = prefs.getBoolean("textItalic", false), underline = prefs.getBoolean("textUnderline", false),
            color = prefs.getInt("textColor", f.color), alignment = prefs.getInt("textAlign", f.alignment),
        )
        t.showCoordinates = prefs.getBoolean("showCoordinates", true)
        t.snapToGrid = prefs.getBoolean("snapToGrid", true)
    }

    fun saveTools(t: ToolSettings) {
        prefs.edit()
            .putInt("penColor", t.penColor).putString("penStyle", t.penStyle.key)
            .putFloat("penWidth", t.basePenWidth.toFloat()).putFloat("highlighterWidth", t.highlighterWidth.toFloat())
            .putBoolean("pressure", t.pressureEnabled).putBoolean("shapeRecognition", t.shapeRecognition)
            .putInt("eraserMode", t.eraserMode.ordinal).putFloat("eraserSize", t.eraserSize.toFloat())
            .putInt("selectMode", t.selectMode.ordinal).putInt("shapeFill", t.shapeFill.ordinal).putInt("polygonSides", t.polygonSides)
            .putInt("textSize", t.textFormat.pixelSize).putBoolean("textBold", t.textFormat.bold)
            .putBoolean("textItalic", t.textFormat.italic).putBoolean("textUnderline", t.textFormat.underline)
            .putInt("textColor", t.textFormat.color).putInt("textAlign", t.textFormat.alignment)
            .putBoolean("showCoordinates", t.showCoordinates).putBoolean("snapToGrid", t.snapToGrid)
            .apply()
    }
}
