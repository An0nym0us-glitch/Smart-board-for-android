package com.classboard.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 2D point / vector in double precision (page units). */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Double) = Pt(x * s, y * s)
    operator fun div(s: Double) = Pt(x / s, y / s)
    operator fun unaryMinus() = Pt(-x, -y)
    fun length() = hypot(x, y)
    fun manhattan() = abs(x) + abs(y)

    companion object {
        val ZERO = Pt(0.0, 0.0)
    }
}

/** Axis-aligned rectangle in double precision. */
data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = Pt((left + right) / 2, (top + bottom) / 2)
    val isEmpty get() = width <= 0.0 || height <= 0.0
    val topLeft get() = Pt(left, top)
    val topRight get() = Pt(right, top)
    val bottomLeft get() = Pt(left, bottom)
    val bottomRight get() = Pt(right, bottom)

    fun contains(p: Pt) = p.x >= left && p.x <= right && p.y >= top && p.y <= bottom
    fun contains(r: RectD) = r.left >= left && r.right <= right && r.top >= top && r.bottom <= bottom
    fun intersects(r: RectD) = r.left <= right && r.right >= left && r.top <= bottom && r.bottom >= top
    fun inflated(m: Double) = RectD(left - m, top - m, right + m, bottom + m)
    fun translated(d: Pt) = RectD(left + d.x, top + d.y, right + d.x, bottom + d.y)
    fun union(r: RectD): RectD {
        if (isEmptyRect()) return r
        if (r.isEmptyRect()) return this
        return RectD(min(left, r.left), min(top, r.top), max(right, r.right), max(bottom, r.bottom))
    }
    private fun isEmptyRect() = width < 0 || height < 0 || (width == 0.0 && height == 0.0 && left == 0.0 && top == 0.0)
    fun normalized() = RectD(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
    fun corners() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun toRectF() = android.graphics.RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        val EMPTY = RectD(0.0, 0.0, 0.0, 0.0)
        fun fromCenter(c: Pt, w: Double, h: Double) = RectD(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
        fun ofPoints(a: Pt, b: Pt) = RectD(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        fun xywh(x: Double, y: Double, w: Double, h: Double) = RectD(x, y, x + w, y + h)
    }
}

object Geom {
    const val PI = Math.PI

    fun degToRad(d: Double) = d * PI / 180.0
    fun radToDeg(r: Double) = r * 180.0 / PI

    fun distance(a: Pt, b: Pt) = (b - a).length()
    fun dot(a: Pt, b: Pt) = a.x * b.x + a.y * b.y
    fun cross(a: Pt, b: Pt) = a.x * b.y - a.y * b.x
    fun lerp(a: Pt, b: Pt, t: Double) = a + (b - a) * t
    fun midpoint(a: Pt, b: Pt) = (a + b) * 0.5
    fun normalized(v: Pt): Pt {
        val l = v.length()
        return if (l > 1e-12) v / l else Pt.ZERO
    }
    fun perpendicular(v: Pt) = Pt(-v.y, v.x)

    /** Rotates v by degrees (clockwise on screen, y grows downwards). */
    fun rotated(v: Pt, degrees: Double): Pt {
        val r = degToRad(degrees)
        val c = cos(r)
        val s = sin(r)
        return Pt(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    fun closestPointOnSegment(p: Pt, a: Pt, b: Pt): Pt {
        val ab = b - a
        val len2 = dot(ab, ab)
        if (len2 <= 1e-12) return a
        val t = (dot(p - a, ab) / len2).coerceIn(0.0, 1.0)
        return a + ab * t
    }

    fun distanceToSegment(p: Pt, a: Pt, b: Pt) = distance(p, closestPointOnSegment(p, a, b))

    fun projectOntoLine(p: Pt, origin: Pt, dir: Pt): Pt {
        val d = normalized(dir)
        return origin + d * dot(p - origin, d)
    }

    fun normalizeDegrees(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0) r += 360.0
        return r
    }

    fun angleDeg(v: Pt) = normalizeDegrees(radToDeg(atan2(v.y, v.x)))

    fun angleDifference(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d <= -180.0) d += 360.0 else if (d > 180.0) d -= 360.0
        return d
    }

    fun snapAngle(deg: Double, step: Double, tolerance: Double): Pair<Double, Boolean> {
        val nearest = round(deg / step) * step
        val ok = abs(nearest - deg) <= tolerance
        return Pair(if (ok) nearest else deg, ok)
    }

    /** Parameter interval [t0,t1] of segment a->b inside the circle, or null. */
    fun segmentCircleInterval(a: Pt, b: Pt, center: Pt, radius: Double): Pair<Double, Double>? {
        val d = b - a
        val f = a - center
        val aa = dot(d, d)
        val r2 = radius * radius
        if (aa <= 1e-12) return if (dot(f, f) <= r2) Pair(0.0, 1.0) else null
        val bb = 2.0 * dot(f, d)
        val cc = dot(f, f) - r2
        val disc = bb * bb - 4 * aa * cc
        if (disc < 0) return null
        val sq = sqrt(disc)
        val s0 = (-bb - sq) / (2 * aa)
        val s1 = (-bb + sq) / (2 * aa)
        if (s1 < 0 || s0 > 1) return null
        val t0 = max(0.0, s0)
        val t1 = min(1.0, s1)
        return if (t1 >= t0) Pair(t0, t1) else null
    }

    fun polygonArea(poly: List<Pt>): Double {
        val n = poly.size
        if (n < 3) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val p = poly[i]
            val q = poly[(i + 1) % n]
            sum += p.x * q.y - q.x * p.y
        }
        return abs(sum) * 0.5
    }

    fun polylineLength(points: List<Pt>, closed: Boolean = false): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distance(points[i - 1], points[i])
        if (closed && points.size > 2) total += distance(points.last(), points.first())
        return total
    }

    /** Ramer-Douglas-Peucker: indices of retained points. */
    fun simplifyIndices(points: List<Pt>, epsilon: Double): List<Int> {
        val n = points.size
        if (n <= 2) return (0 until n).toList()
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(Pair(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(points[i], points[a], points[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > epsilon && idx > 0) {
                keep[idx] = true
                stack.addLast(Pair(a, idx))
                stack.addLast(Pair(idx, b))
            }
        }
        return (0 until n).filter { keep[it] }
    }

    fun boundingRect(points: List<Pt>): RectD {
        if (points.isEmpty()) return RectD.EMPTY
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return RectD(minX, minY, maxX, maxY)
    }

    fun niceNumber(value: Double, round: Boolean): Double {
        if (value <= 0) return 1.0
        val exponent = floor(log10(value))
        val fraction = value / 10.0.pow(exponent)
        val nice = if (round) {
            when {
                fraction < 1.5 -> 1.0
                fraction < 3.0 -> 2.0
                fraction < 7.0 -> 5.0
                else -> 10.0
            }
        } else {
            when {
                fraction <= 1.0 -> 1.0
                fraction <= 2.0 -> 2.0
                fraction <= 5.0 -> 5.0
                else -> 10.0
            }
        }
        return nice * 10.0.pow(exponent)
    }

    /** Number with at most maxDecimals, trailing zeros trimmed, proper minus sign. */
    fun formatNumber(value: Double, maxDecimals: Int = 2): String {
        if (!value.isFinite()) return "undefined"
        var v = value
        if (abs(v) < 0.5 * 10.0.pow(-maxDecimals)) v = 0.0
        var s = String.format(java.util.Locale.US, "%.${maxDecimals}f", v)
        if (s.contains('.')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        if (s == "-0") s = "0"
        if (s.startsWith("-")) s = "−" + s.substring(1)
        return s
    }

    /** Point-in-polygon (odd-even). */
    fun polygonContains(poly: List<Pt>, p: Pt): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun segmentsIntersect(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
        val d1 = cross(b - a, c - a)
        val d2 = cross(b - a, d - a)
        val d3 = cross(d - c, a - c)
        val d4 = cross(d - c, b - c)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }
}
