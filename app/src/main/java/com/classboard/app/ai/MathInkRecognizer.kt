package com.classboard.app.ai

import com.classboard.app.core.Geom
import com.classboard.app.core.Pt
import com.classboard.app.core.RectD
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

typealias Strokes = List<List<Pt>>

class EquationCandidate(val latex: String, val confidence: Double)

/**
 * Built-in offline recogniser for simple handwritten maths: strokes are grouped into symbols,
 * fraction bars and equals signs are found geometrically, other symbols are classified with a $P
 * point-cloud matcher against templates of 0–9, a b c n x y, + and parentheses. Superscripts and
 * fractions become LaTeX. Conservative: uncertain input is reported as failure unless relaxed.
 */
class MathInkRecognizer(private val minScore: Double = 0.6, private val relaxed: Boolean = false) {
    private class Template(val symbol: String, val cloud: Array<DoubleArray>, val inkRatio: Double)
    private class StrokeInfo(val points: List<Pt>, val box: RectD, val length: Double)
    private class Item(var latex: String, var box: RectD, var score: Double = 1.0, var base: Boolean = true)

    fun recognize(strokes0: Strokes): List<EquationCandidate> {
        val strokes = strokes0.filter { it.isNotEmpty() }.map { StrokeInfo(it, Geom.boundingRect(it), Geom.polylineLength(it)) }
        if (strokes.isEmpty()) return emptyList()
        val conf = doubleArrayOf(1.0)
        val latex = recognizeRegion(strokes, conf)
        if (latex.isEmpty()) return emptyList()
        if (!relaxed && conf[0] < minScore) return emptyList()
        return listOf(EquationCandidate(latex, conf[0]))
    }

    fun classifySymbol(strokes: Strokes): Pair<String, Double> {
        val cloud = makeCloud(strokes)
        var best = Double.MAX_VALUE
        var match: Template? = null
        for (t in templates) {
            val d = greedyMatch(cloud, t.cloud, best)
            if (d < best) { best = d; match = t }
        }
        var inkFactor = 1.0
        if (match != null && match.inkRatio > 0) {
            val r = inkRatio(strokes)
            val f = min(r / match.inkRatio, match.inkRatio / max(r, 1e-9))
            if (f < 0.6) inkFactor = f
        }
        val score = (1.0 - best / 3.5).coerceIn(0.0, 1.0) * inkFactor
        return Pair(match?.symbol ?: "", score)
    }

    private fun isFlat(r: RectD) = r.width > 2.5 * max(r.height, 1e-6)
    private fun isBar(s: StrokeInfo) = s.box.width > 2.5 * max(s.box.height, 1e-6) && s.length < 1.3 * hypot(s.box.width, s.box.height) + 1e-6

    private fun strokesCross(a: List<Pt>, b: List<Pt>): Boolean {
        for (i in 1 until a.size) for (j in 1 until b.size) if (Geom.segmentsIntersect(a[i - 1], a[i], b[j - 1], b[j])) return true
        return false
    }

    private fun overlapRatio(a: RectD, b: RectD): Double {
        val inter = min(a.right, b.right) - max(a.left, b.left)
        return inter / max(1e-6, min(a.width, b.width))
    }

    private fun recognizeRegion(strokes: List<StrokeInfo>, conf: DoubleArray): String {
        val c = doubleArrayOf(1.0)
        val items = buildItems(strokes, c)
        conf[0] = min(conf[0], c[0])
        return layoutItems(items)
    }

    private fun buildItems(strokes0: List<StrokeInfo>, conf: DoubleArray): List<Item> {
        if (strokes0.isEmpty()) return emptyList()
        var all: RectD? = null
        for (s in strokes0) all = all?.union(s.box) ?: s.box
        val tall = strokes0.filter { !isFlat(it.box) }
        val height = max(if (tall.isNotEmpty()) tall.sumOf { it.box.height } / tall.size else all!!.height, 1.0)
        // 1. Fraction bars, widest first.
        for (bar in strokes0.sortedByDescending { it.box.width }) {
            val b = bar.box
            if (!isBar(bar) || b.width < 0.6 * height) continue
            val above = ArrayList<StrokeInfo>()
            val below = ArrayList<StrokeInfo>()
            val rest = ArrayList<StrokeInfo>()
            val margin = 0.1 * b.width
            for (s in strokes0) {
                if (s === bar) continue
                val r = s.box
                val within = r.center.x >= b.left - margin && r.center.x <= b.right + margin
                if (within && r.bottom <= b.center.y + 0.1 * height) above.add(s)
                else if (within && r.top >= b.center.y - 0.1 * height) below.add(s)
                else rest.add(s)
            }
            val equalsLike = (above.size == 1 && isBar(above[0]) && below.isEmpty()) || (below.size == 1 && isBar(below[0]) && above.isEmpty())
            if (above.isEmpty() || below.isEmpty() || equalsLike) continue
            val c1 = doubleArrayOf(1.0)
            val c2 = doubleArrayOf(1.0)
            val num = recognizeRegion(above, c1)
            val den = recognizeRegion(below, c2)
            var box = b
            above.forEach { box = box.union(it.box) }
            below.forEach { box = box.union(it.box) }
            val frac = Item("\\frac{$num}{$den}", box, min(c1[0], c2[0]))
            val items = buildItems(rest, conf).toMutableList()
            items.add(frac)
            items.sortBy { it.box.center.x }
            conf[0] = min(conf[0], frac.score)
            return items
        }
        // 2. Group strokes into symbols.
        val strokes = strokes0.sortedBy { it.box.left }
        val groups = ArrayList<ArrayList<StrokeInfo>>()
        val boxes = ArrayList<RectD>()
        for (s in strokes) {
            var target = -1
            for (g in groups.indices) {
                val flatPair = isBar(s) && groups[g].all { isBar(it) }
                val overlap = overlapRatio(s.box, boxes[g])
                val cross = groups[g].any { strokesCross(s.points, it.points) }
                if (flatPair && overlap > 0.4 && abs(s.box.center.y - boxes[g].center.y) < 0.9 * height) { target = g; break }
                if (!flatPair && (cross || overlap > 0.55)) { target = g; break }
            }
            if (target < 0) { groups.add(arrayListOf(s)); boxes.add(s.box) }
            else { groups[target].add(s); boxes[target] = boxes[target].union(s.box) }
        }
        // 3. Classify.
        val items = ArrayList<Item>()
        for (g in groups.indices) {
            val group = groups[g]
            val box = boxes[g]
            val item = Item("", box)
            when {
                max(box.width, box.height) < 0.18 * height -> { item.latex = "."; item.base = false }
                group.size == 1 && isBar(group[0]) -> { item.latex = "-"; item.base = false }
                group.size == 2 && isBar(group[0]) && isBar(group[1]) -> { item.latex = "="; item.base = false }
                else -> {
                    val (sym, score) = classifySymbol(group.map { it.points })
                    item.latex = sym
                    item.score = score
                    item.base = sym != "+" && sym != "("
                }
            }
            items.add(item)
            conf[0] = min(conf[0], item.score)
        }
        items.sortBy { it.box.center.x }
        return items
    }

    private fun layoutItems(items: List<Item>): String {
        val heights = items.filter { it.base }.map { it.box.height }.sorted()
        val lineHeight = if (heights.isEmpty()) 1.0 else heights[min(heights.size * 3 / 4, heights.size - 1)]
        val bottoms = items.filter { it.base }.map { it.box.bottom }.sorted()
        val baseline = if (bottoms.isEmpty()) 0.0 else bottoms[bottoms.size / 2]
        val out = StringBuilder()
        var i = 0
        while (i < items.size) {
            val base = items[i]
            out.append(base.latex)
            i++
            if (!base.base) continue
            val sup = StringBuilder()
            while (i < items.size) {
                val it = items[i]
                val raised = it.box.bottom < baseline - 0.45 * lineHeight && it.box.center.y < base.box.center.y - 0.2 * base.box.height
                val smaller = it.box.height < 0.8 * lineHeight
                val after = it.box.left > base.box.left + 0.3 * base.box.width
                val high = it.box.bottom < base.box.top + 0.3 * base.box.height
                if (!(raised && (smaller || high) && after)) break
                sup.append(it.latex)
                i++
            }
            if (sup.isNotEmpty()) out.append("^{").append(sup).append('}')
        }
        return out.toString()
    }

    companion object {
        private const val N = 32

        private fun pathLength(strokes: Strokes): Double = strokes.sumOf { Geom.polylineLength(it) }

        private fun resample(strokes: Strokes, n: Int): Array<DoubleArray> {
            val out = ArrayList<DoubleArray>()
            val total = pathLength(strokes)
            if (total <= 1e-9) {
                val p = strokes.firstOrNull()?.firstOrNull() ?: Pt.ZERO
                return Array(n) { doubleArrayOf(p.x, p.y) }
            }
            val interval = total / (n - 1)
            var acc = 0.0
            loop@ for (stroke in strokes) {
                if (stroke.isEmpty()) continue
                if (out.isEmpty()) out.add(doubleArrayOf(stroke[0].x, stroke[0].y))
                var prev = stroke[0]
                for (i in 1 until stroke.size) {
                    val cur = stroke[i]
                    var d = Geom.distance(prev, cur)
                    while (acc + d >= interval && d > 0) {
                        val t = (interval - acc) / d
                        val q = prev + (cur - prev) * t
                        out.add(doubleArrayOf(q.x, q.y))
                        prev = q
                        d = Geom.distance(prev, cur)
                        acc = 0.0
                        if (out.size >= n) break
                    }
                    acc += d
                    prev = cur
                    if (out.size >= n) break@loop
                }
            }
            val last = strokes.lastOrNull()?.lastOrNull() ?: Pt(out.last()[0], out.last()[1])
            while (out.size < n) out.add(doubleArrayOf(last.x, last.y))
            return out.take(n).toTypedArray()
        }

        private fun normalize(c: Array<DoubleArray>) {
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in c) { minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1]) }
            val size = maxOf(maxX - minX, maxY - minY, 1e-9)
            var cx = 0.0; var cy = 0.0
            for (p in c) { p[0] = (p[0] - minX) / size; p[1] = (p[1] - minY) / size; cx += p[0]; cy += p[1] }
            cx /= c.size; cy /= c.size
            for (p in c) { p[0] -= cx; p[1] -= cy }
        }

        private fun makeCloud(strokes: Strokes): Array<DoubleArray> { val c = resample(strokes, N); normalize(c); return c }

        private fun cloudDistance(a: Array<DoubleArray>, b: Array<DoubleArray>, start: Int, bound: Double): Double {
            val n = a.size
            val matched = BooleanArray(n)
            var sum = 0.0
            var i = start
            do {
                var best = Double.MAX_VALUE
                var index = -1
                for (j in 0 until n) {
                    if (matched[j]) continue
                    val dx = a[i][0] - b[j][0]
                    val dy = a[i][1] - b[j][1]
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < best) { best = d; index = j }
                }
                matched[index] = true
                val weight = 1.0 - ((i - start + n) % n).toDouble() / n
                sum += weight * best
                if (sum >= bound) return sum
                i = (i + 1) % n
            } while (i != start)
            return sum
        }

        private fun greedyMatch(a: Array<DoubleArray>, b: Array<DoubleArray>, bound: Double): Double {
            val n = a.size
            val step = max(1, floor(n.toDouble().pow(0.5)).toInt())
            var best = bound
            var i = 0
            while (i < n) {
                best = min(best, cloudDistance(a, b, i, best))
                best = min(best, cloudDistance(b, a, i, best))
                i += step
            }
            return best
        }

        private fun inkRatio(strokes: Strokes): Double {
            var box: RectD? = null
            var len = 0.0
            for (s in strokes) {
                if (s.isEmpty()) continue
                val b = Geom.boundingRect(s)
                box = box?.union(b) ?: b
                len += Geom.polylineLength(s)
            }
            val diag = box?.let { hypot(it.width, it.height) } ?: 0.0
            return if (diag > 1e-9) len / diag else 0.0
        }

        private fun poly(vararg p: Pair<Int, Int>) = p.map { Pt(it.first.toDouble(), it.second.toDouble()) }

        private fun curve(vararg control: Pair<Int, Int>): List<Pt> {
            val c = control.map { Pt(it.first.toDouble(), it.second.toDouble()) }
            if (c.size < 3) return c
            val out = ArrayList<Pt>()
            for (i in 0 until c.size - 1) {
                val p0 = c[max(0, i - 1)]; val p1 = c[i]; val p2 = c[i + 1]; val p3 = c[min(c.size - 1, i + 2)]
                for (k in 0 until 8) {
                    val t = k / 8.0; val t2 = t * t; val t3 = t2 * t
                    out.add(((p1 * 2.0) + (p2 - p0) * t + (p0 * 2.0 - p1 * 5.0 + p2 * 4.0 - p3) * t2 + (p1 * 3.0 - p0 - p2 * 3.0 + p3) * t3) * 0.5)
                }
            }
            out.add(c.last())
            return out
        }

        private fun ellipse(cx: Int, cy: Int, rx: Double, ry: Double, start: Double, sweep: Double): List<Pt> =
            (0..36).map { i -> val a = Geom.degToRad(start + sweep * i / 36); Pt(cx + rx * cos(a), cy - ry * sin(a)) }

        private val templates: List<Template> by lazy {
            val defs = ArrayList<Pair<String, Strokes>>()
            fun add(s: String, vararg strokes: List<Pt>) = defs.add(Pair(s, strokes.toList()))
            add("0", ellipse(50, 50, 32.0, 48.0, 90.0, 360.0))
            add("0", ellipse(50, 50, 26.0, 48.0, 80.0, -360.0))
            add("1", poly(50 to 0, 50 to 100))
            add("1", poly(28 to 22, 50 to 0, 50 to 100))
            add("1", poly(28 to 22, 50 to 0, 50 to 100), poly(28 to 100, 72 to 100))
            add("2", curve(18 to 28, 30 to 6, 52 to 0, 74 to 8, 80 to 28, 70 to 50, 45 to 72, 18 to 100), poly(18 to 100, 86 to 100))
            add("2", curve(18 to 28, 30 to 6, 52 to 0, 74 to 8, 80 to 28, 70 to 50, 45 to 72, 18 to 100, 50 to 100, 86 to 100))
            add("3", curve(20 to 12, 48 to 0, 76 to 10, 76 to 32, 48 to 48, 78 to 60, 82 to 84, 56 to 100, 20 to 90))
            add("3", curve(22 to 6, 50 to 0, 72 to 14, 62 to 36, 44 to 46), curve(44 to 46, 74 to 58, 80 to 82, 54 to 100, 22 to 94))
            add("4", poly(62 to 0, 14 to 66, 86 to 66), poly(66 to 22, 66 to 100))
            add("4", poly(20 to 0, 16 to 60, 86 to 60), poly(66 to 0, 66 to 100))
            add("4", poly(62 to 0, 14 to 66, 86 to 66, 66 to 66, 66 to 22, 66 to 100))
            add("5", poly(78 to 0, 26 to 0), curve(26 to 0, 22 to 44, 50 to 38, 78 to 52, 80 to 80, 56 to 100, 20 to 92))
            add("5", curve(26 to 0, 22 to 44, 50 to 38, 78 to 52, 80 to 80, 56 to 100, 20 to 92), poly(26 to 0, 78 to 0))
            add("5", curve(78 to 0, 26 to 0, 22 to 44, 50 to 38, 78 to 52, 80 to 80, 56 to 100, 20 to 92))
            add("6", curve(72 to 4, 44 to 0, 24 to 22, 16 to 56, 22 to 86, 46 to 100, 72 to 90, 76 to 66, 54 to 50, 30 to 54, 18 to 70))
            add("7", poly(14 to 0, 86 to 0, 38 to 100))
            add("7", poly(14 to 0, 86 to 0, 38 to 100), poly(36 to 50, 76 to 50))
            add("8", curve(68 to 12, 50 to 0, 28 to 10, 30 to 32, 50 to 50, 72 to 68, 70 to 90, 50 to 100, 28 to 90, 30 to 68, 50 to 50, 70 to 32, 68 to 12))
            add("8", ellipse(50, 25, 22.0, 25.0, 90.0, 360.0), ellipse(50, 75, 26.0, 25.0, 90.0, 360.0))
            add("9", curve(76 to 20, 56 to 0, 30 to 4, 22 to 24, 34 to 44, 60 to 42, 76 to 20), poly(76 to 20, 74 to 60, 70 to 100))
            add("9", curve(76 to 20, 56 to 0, 30 to 4, 22 to 24, 34 to 44, 60 to 42, 76 to 20, 76 to 60, 66 to 92, 44 to 100))
            add("x", poly(15 to 15, 85 to 85), poly(85 to 15, 15 to 85))
            add("x", curve(10 to 20, 30 to 15, 50 to 50, 70 to 85, 90 to 80), poly(85 to 15, 15 to 85))
            add("x", poly(25 to 0, 75 to 100), poly(75 to 0, 25 to 100))
            add("x", poly(0 to 20, 100 to 80), poly(100 to 20, 0 to 80))
            add("y", poly(20 to 0, 52 to 56), poly(82 to 0, 30 to 100))
            add("y", curve(20 to 0, 24 to 36, 48 to 50, 74 to 38, 78 to 0, 76 to 70, 58 to 100, 30 to 94))
            add("a", curve(74 to 24, 50 to 8, 24 to 24, 18 to 56, 40 to 82, 66 to 74, 74 to 44), poly(76 to 14, 78 to 86))
            add("a", curve(74 to 24, 50 to 8, 24 to 24, 18 to 56, 40 to 82, 66 to 74, 74 to 44, 75 to 14, 77 to 60, 82 to 86))
            add("b", poly(26 to 0, 26 to 100), curve(26 to 62, 50 to 44, 76 to 58, 78 to 84, 52 to 100, 26 to 94))
            add("b", curve(26 to 0, 26 to 50, 26 to 100, 30 to 72, 54 to 54, 78 to 68, 70 to 96, 40 to 98, 26 to 92))
            add("c", curve(78 to 20, 56 to 4, 28 to 14, 16 to 46, 26 to 78, 52 to 92, 80 to 80))
            add("n", curve(20 to 20, 20 to 60, 20 to 100, 22 to 50, 42 to 26, 66 to 26, 78 to 46, 80 to 100))
            add("n", poly(20 to 20, 20 to 100), curve(20 to 50, 42 to 26, 66 to 26, 78 to 46, 80 to 100))
            add("+", poly(50 to 8, 50 to 92), poly(8 to 50, 92 to 50))
            add("(", curve(66 to 0, 42 to 22, 32 to 50, 42 to 78, 66 to 100))
            add("(", curve(58 to 0, 46 to 24, 42 to 50, 46 to 76, 58 to 100))
            add(")", curve(34 to 0, 58 to 22, 68 to 50, 58 to 78, 34 to 100))
            add(")", curve(42 to 0, 54 to 24, 58 to 50, 54 to 76, 42 to 100))
            val out = ArrayList<Template>()
            for ((sym, strokes) in defs) for (sx in doubleArrayOf(1.0, 0.75, 1.3)) {
                val variant = strokes.map { s -> s.map { Pt(50.0 + (it.x - 50.0) * sx, it.y) } }
                out.add(Template(sym, makeCloud(variant), inkRatio(variant)))
            }
            out
        }
    }
}
