package com.classboard.app.doc

import com.classboard.app.core.Geom
import kotlin.math.abs

/** Logical page sizes in document units (40 units = 1 cm). */
object PageSize {
    const val UNITS_PER_CM = 40.0

    enum class Preset(val id: String, val label: String, val wCm: Double, val hCm: Double) {
        Board16x9("board16x9", "Classroom board 16:9", 48.0, 27.0),
        Board4x3("board4x3", "4:3", 36.0, 27.0),
        A4("a4", "A4", 29.7, 21.0),
        A3("a3", "A3", 42.0, 29.7),
        Custom("custom", "Custom", 48.0, 27.0),
    }

    val presets = listOf(Preset.Board16x9, Preset.Board4x3, Preset.A4, Preset.A3)

    fun sizeFor(p: Preset, portrait: Boolean): Pair<Double, Double> {
        val w = p.wCm * UNITS_PER_CM
        val h = p.hCm * UNITS_PER_CM
        return if (portrait) Pair(h, w) else Pair(w, h)
    }

    fun fromCentimetres(wCm: Double, hCm: Double) =
        Pair(wCm.coerceIn(5.0, 500.0) * UNITS_PER_CM, hCm.coerceIn(5.0, 500.0) * UNITS_PER_CM)

    private fun near(a: Pair<Double, Double>, w: Double, h: Double) = abs(a.first - w) < 0.5 && abs(a.second - h) < 0.5

    fun presetOf(w: Double, h: Double): Preset {
        for (p in presets) if (near(sizeFor(p, false), w, h) || near(sizeFor(p, true), w, h)) return p
        return Preset.Custom
    }

    fun isPortrait(w: Double, h: Double) = h > w

    fun describe(w: Double, h: Double): String {
        val p = presetOf(w, h)
        val portrait = isPortrait(w, h)
        return when (p) {
            Preset.A4, Preset.A3 -> if (portrait) "${p.label} portrait" else "${p.label} landscape"
            Preset.Custom -> "Custom ${Geom.formatNumber(w / UNITS_PER_CM, 1)} × ${Geom.formatNumber(h / UNITS_PER_CM, 1)} cm"
            else -> if (portrait) "${p.label} (portrait)" else p.label
        }
    }

    fun forAspect(width: Double, height: Double): Pair<Double, Double> {
        val board = sizeFor(Preset.Board16x9, false)
        if (width <= 0 || height <= 0) return board
        val long = board.first
        return if (width >= height) Pair(long, long * height / width) else Pair(long * width / height, long)
    }
}
