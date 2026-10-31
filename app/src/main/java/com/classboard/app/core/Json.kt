package com.classboard.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

object Json {
    /** "#AARRGGBB" like Qt's QColor::HexArgb. */
    fun fromColor(c: Int): String = String.format(Locale.US, "#%08x", c)

    fun toColor(v: Any?, fallback: Int = 0xFFFFFFFF.toInt()): Int {
        val s = v as? String ?: return fallback
        return parseColor(s) ?: fallback
    }

    fun parseColor(s0: String): Int? {
        val s = s0.trim()
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        return try {
            when (hex.length) {
                6 -> (0xFF000000.toInt()) or hex.toLong(16).toInt()
                8 -> hex.toLong(16).toInt()
                3 -> {
                    val r = hex[0].toString().repeat(2)
                    val g = hex[1].toString().repeat(2)
                    val b = hex[2].toString().repeat(2)
                    (0xFF000000.toInt()) or "$r$g$b".toLong(16).toInt()
                }
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun fromPoint(p: Pt) = JSONArray().put(p.x).put(p.y)
    fun toPoint(v: Any?, fallback: Pt = Pt.ZERO): Pt {
        val a = v as? JSONArray ?: return fallback
        if (a.length() < 2) return fallback
        return Pt(a.optDouble(0, 0.0), a.optDouble(1, 0.0))
    }

    fun fromSize(w: Double, h: Double) = JSONArray().put(w).put(h)
    fun toSize(v: Any?, fw: Double, fh: Double): Pair<Double, Double> {
        val a = v as? JSONArray ?: return Pair(fw, fh)
        if (a.length() < 2) return Pair(fw, fh)
        return Pair(a.optDouble(0, fw), a.optDouble(1, fh))
    }

    fun fromPoints(pts: List<Pt>): JSONArray {
        val a = JSONArray()
        for (p in pts) {
            a.put(round2(p.x)); a.put(round2(p.y))
        }
        return a
    }

    fun toPoints(v: Any?): MutableList<Pt> {
        val a = v as? JSONArray ?: return mutableListOf()
        val out = ArrayList<Pt>(a.length() / 2)
        var i = 0
        while (i + 1 < a.length()) {
            out.add(Pt(a.optDouble(i, 0.0), a.optDouble(i + 1, 0.0)))
            i += 2
        }
        return out
    }

    fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    fun double(o: JSONObject, key: String, fallback: Double): Double {
        val v = o.opt(key) ?: return fallback
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }
}

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
