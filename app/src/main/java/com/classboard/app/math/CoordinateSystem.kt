package com.classboard.app.math

import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2

/** 2D affine transform with Qt's QTransform conventions: x' = m11 x + m21 y + dx, y' = m12 x + m22 y + dy. */
data class Affine(
    val m11: Double = 1.0, val m12: Double = 0.0,
    val m21: Double = 0.0, val m22: Double = 1.0,
    val dx: Double = 0.0, val dy: Double = 0.0,
) {
    fun map(p: Pt) = Pt(m11 * p.x + m21 * p.y + dx, m12 * p.x + m22 * p.y + dy)

    /** this applied first, then o. */
    fun then(o: Affine) = Affine(
        m11 * o.m11 + m12 * o.m21, m11 * o.m12 + m12 * o.m22,
        m21 * o.m11 + m22 * o.m21, m21 * o.m12 + m22 * o.m22,
        dx * o.m11 + dy * o.m21 + o.dx, dx * o.m12 + dy * o.m22 + o.dy,
    )

    fun inverted(): Affine? {
        val det = m11 * m22 - m12 * m21
        if (abs(det) < 1e-15) return null
        val i11 = m22 / det
        val i12 = -m12 / det
        val i21 = -m21 / det
        val i22 = m11 / det
        val idx = -(dx * i11 + dy * i21)
        val idy = -(dx * i12 + dy * i22)
        return Affine(i11, i12, i21, i22, idx, idy)
    }

    companion object {
        fun translate(x: Double, y: Double) = Affine(dx = x, dy = y)
        fun scale(sx: Double, sy: Double) = Affine(m11 = sx, m22 = sy)
    }
}

object Units {
    fun metresPer(unit: String): Double = when (unit.trim().lowercase()) {
        "mm" -> 0.001
        "cm" -> 0.01
        "m" -> 1.0
        "km" -> 1000.0
        "in" -> 0.0254
        "ft" -> 0.3048
        "yd" -> 0.9144
        "mi" -> 1609.344
        else -> 0.0
    }

    fun isLengthUnit(u: String) = metresPer(u) > 0
    val lengthUnits = listOf("mm", "cm", "m", "km", "in", "ft", "yd", "mi")
    fun convert(v: Double, from: String, to: String): Double {
        val a = metresPer(from)
        val b = metresPer(to)
        return if (a <= 0 || b <= 0) v else v * a / b
    }
}

/** Drawing scale: "boardValue boardUnit = realValue realUnit", e.g. 10 cm = 1 km. */
data class MeasureScale(
    val boardValue: Double = 1.0,
    val boardUnit: String = "cm",
    val realValue: Double = 1.0,
    val realUnit: String = "cm",
) {
    val isValid get() = boardValue > 0 && realValue > 0 && boardValue.isFinite() && realValue.isFinite() &&
        Units.isLengthUnit(boardUnit) && Units.isLengthUnit(realUnit)

    fun factor(): Double = if (!isValid) 1.0 else (realValue * Units.metresPer(realUnit)) / (boardValue * Units.metresPer(boardUnit))
    val isIdentity get() = !isValid || (abs(factor() - 1.0) < 1e-12 && boardUnit == realUnit)

    fun toReal(boardLength: Double, boardLengthUnit: String): Double {
        if (!isValid) return boardLength
        val metres = boardLength * Units.metresPer(boardLengthUnit) * factor()
        return metres / Units.metresPer(realUnit)
    }

    fun toBoard(realLength: Double, boardLengthUnit: String): Double {
        if (!isValid || Units.metresPer(boardLengthUnit) <= 0) return realLength
        val metres = realLength * Units.metresPer(realUnit) / factor()
        return metres / Units.metresPer(boardLengthUnit)
    }

    fun text() = "${Geom.formatNumber(boardValue, 4)} $boardUnit = ${Geom.formatNumber(realValue, 4)} $realUnit"

    fun toJson(): JSONObject = JSONObject().put("board", boardValue).put("boardUnit", boardUnit)
        .put("real", realValue).put("realUnit", realUnit)

    companion object {
        fun fromJson(o: JSONObject?): MeasureScale {
            if (o == null) return MeasureScale()
            val s = MeasureScale(o.optDouble("board", 1.0), o.optString("boardUnit", "cm"), o.optDouble("real", 1.0), o.optString("realUnit", "cm"))
            return if (s.isValid) s else MeasureScale()
        }
    }
}

/** Maps page coordinates (y down) to mathematical coordinates (units, y up). */
class CoordinateSystem private constructor(
    val pageToMath: Affine,
    val mathToPage: Affine,
    val unitLabel: String,
    scale0: MeasureScale,
) {
    var scale: MeasureScale = scale0
        private set

    fun withScale(s: MeasureScale) = CoordinateSystem(pageToMath, mathToPage, unitLabel, if (s.isValid) s else MeasureScale())

    fun toMath(p: Pt) = pageToMath.map(p)
    fun toPage(m: Pt) = mathToPage.map(m)

    fun mathDistance(a: Pt, b: Pt) = Geom.distance(toMath(a), toMath(b))

    fun mathSlope(a: Pt, b: Pt): Double? {
        val ma = toMath(a)
        val mb = toMath(b)
        val dx = mb.x - ma.x
        if (abs(dx) < 1e-9) return null
        return (mb.y - ma.y) / dx
    }

    fun mathAngle(a: Pt, b: Pt): Double {
        val d = toMath(b) - toMath(a)
        return Geom.normalizeDegrees(Geom.radToDeg(atan2(d.y, d.x)))
    }

    fun mathArea(poly: List<Pt>) = Geom.polygonArea(poly.map { toMath(it) })

    val usesScale get() = Units.isLengthUnit(unitLabel) && !scale.isIdentity
    val realUnitLabel get() = if (usesScale) scale.realUnit else unitLabel
    fun toReal(l: Double) = if (usesScale) scale.toReal(l, unitLabel) else l
    fun fromReal(l: Double) = if (usesScale) scale.toBoard(l, unitLabel) else l
    fun toRealArea(a: Double): Double { val f = toReal(1.0); return a * f * f }

    fun formatLength(l: Double, decimals: Int = 2): String {
        val u = realUnitLabel
        val v = Geom.formatNumber(toReal(l), decimals)
        return if (u.isEmpty()) v else "$v $u"
    }

    fun formatArea(a: Double, decimals: Int = 2): String {
        val u = realUnitLabel
        val v = Geom.formatNumber(toRealArea(a), decimals)
        return if (u.isEmpty()) v else "$v $u²"
    }

    fun translated(delta: Pt): CoordinateSystem {
        if (delta.x == 0.0 && delta.y == 0.0) return this
        return fromTransform(Affine.translate(-delta.x, -delta.y).then(pageToMath), unitLabel).withScale(scale)
    }

    fun pxPerUnit() = Geom.distance(toPage(Pt(0.0, 0.0)), toPage(Pt(1.0, 0.0)))
    fun originPx() = toPage(Pt(0.0, 0.0))

    fun toJson(): JSONObject {
        val t = pageToMath
        val o = JSONObject()
        o.put("m", JSONArray().put(t.m11).put(t.m12).put(t.m21).put(t.m22).put(t.dx).put(t.dy))
        o.put("unit", unitLabel)
        if (!scale.isIdentity) o.put("scale", scale.toJson())
        return o
    }

    companion object {
        const val UNITS_PER_CM = 40.0

        fun default(): CoordinateSystem = global(Pt(960.0, 540.0), UNITS_PER_CM, "cm")

        fun global(originPx: Pt, pxPerUnit0: Double, unitLabel: String): CoordinateSystem {
            val ppu = if (pxPerUnit0 <= 0) 40.0 else pxPerUnit0
            val t = Affine.translate(-originPx.x, -originPx.y).then(Affine.scale(1.0 / ppu, -1.0 / ppu))
            return fromTransform(t, unitLabel)
        }

        fun fromTransform(pageToMath: Affine, unitLabel: String): CoordinateSystem {
            val inv = pageToMath.inverted() ?: Affine()
            return CoordinateSystem(pageToMath, inv, unitLabel, MeasureScale())
        }

        fun fromJson(o: JSONObject?): CoordinateSystem {
            val m = o?.optJSONArray("m")
            if (o == null || m == null || m.length() != 6) return default()
            val t = Affine(m.optDouble(0), m.optDouble(1), m.optDouble(2), m.optDouble(3), m.optDouble(4), m.optDouble(5))
            val cs = fromTransform(t, o.optString("unit", "cm"))
            return if (o.has("scale")) cs.withScale(MeasureScale.fromJson(o.optJSONObject("scale"))) else cs
        }
    }
}
