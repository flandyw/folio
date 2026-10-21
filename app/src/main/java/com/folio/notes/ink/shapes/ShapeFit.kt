package com.folio.notes.ink.shapes

import com.folio.notes.model.InkPoint
import kotlin.math.*

/** Plain geometry shared by shape recognition and its fitting. */
internal object ShapeMath {
    /** Euclidean length: `hypot` is exact for extremes drawings never reach, and many times slower. */
    fun norm(x: Float, y: Float) = sqrt(x * x + y * y)
    fun norm(x: Double, y: Double) = sqrt(x * x + y * y)
    fun distance(a: InkPoint, b: InkPoint) = norm(a.x - b.x, a.y - b.y)
    fun span(points: List<InkPoint>) =
        norm(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
    fun length(points: List<InkPoint>): Float {
        var length = 0f
        for (i in 1..points.lastIndex) length += distance(points[i - 1], points[i])
        return length
    }
    /** Distance from [p] to the segment [a]–[b]. */
    fun gap(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val squared = dx * dx + dy * dy
        if (squared == 0f) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / squared).coerceIn(0f, 1f)
        return norm(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }
    /** Signed turn at [i] of a closed path (positive is clockwise on screen). */
    fun turn(points: List<InkPoint>, i: Int): Float {
        val a = points[(i - 1 + points.size) % points.size]; val b = points[i]; val c = points[(i + 1) % points.size]
        return atan2((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x),
            (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y))
    }
    fun resample(raw: List<InkPoint>, step: Float): List<InkPoint> {
        val result = ArrayList<InkPoint>(); result += raw.first()
        var remaining = step
        for (i in 1..raw.lastIndex) {
            var a = raw[i - 1]; val b = raw[i]; var d = distance(a, b)
            while (d >= remaining && d > 0f) {
                val share = remaining / d
                a = InkPoint(a.x + (b.x - a.x) * share, a.y + (b.y - a.y) * share)
                result += a; d = distance(a, b); remaining = step
            }
            remaining -= d
        }
        result += raw.last()
        return result
    }
    fun simplify(points: List<InkPoint>, tolerance: Float): List<InkPoint> {
        if (points.size < 3) return points
        val index = (1 until points.lastIndex).maxBy { gap(points[it], points.first(), points.last()) }
        if (gap(points[index], points.first(), points.last()) <= tolerance) return listOf(points.first(), points.last())
        return simplify(points.subList(0, index + 1), tolerance).dropLast(1) + simplify(points.subList(index, points.size), tolerance)
    }
}

/**
 * Model fitting for hand-drawn shapes. Corner counting breaks as soon as a pen wobbles; fitting
 * the best k-sided outline for every k, then comparing errors, does not. All errors are plain
 * distances in page units, so callers compare them to the drawing's span.
 */
internal object ShapeFit {
    private const val INF = Double.POSITIVE_INFINITY

    // ---- Straight-sided outlines ------------------------------------------------------------

    /** Squared distance of the samples from the chord between two of them, in constant time. */
    private class Chords(points: List<InkPoint>) {
        private val x = DoubleArray(points.size)
        private val y = DoubleArray(points.size)
        private val sx = DoubleArray(points.size + 1)
        private val sy = DoubleArray(points.size + 1)
        private val sxx = DoubleArray(points.size + 1)
        private val syy = DoubleArray(points.size + 1)
        private val sxy = DoubleArray(points.size + 1)

        init {
            val ox = points[0].x.toDouble(); val oy = points[0].y.toDouble()
            for (i in points.indices) {
                x[i] = points[i].x - ox; y[i] = points[i].y - oy
                sx[i + 1] = sx[i] + x[i]; sy[i + 1] = sy[i] + y[i]
                sxx[i + 1] = sxx[i] + x[i] * x[i]; syy[i + 1] = syy[i] + y[i] * y[i]; sxy[i + 1] = sxy[i] + x[i] * y[i]
            }
        }

        fun cost(i: Int, j: Int): Double {
            val m = (j - i + 1).toDouble()
            val ax = sx[j + 1] - sx[i]; val ay = sy[j + 1] - sy[i]
            val qxx = sxx[j + 1] - sxx[i] - 2 * x[i] * ax + m * x[i] * x[i]
            val qyy = syy[j + 1] - syy[i] - 2 * y[i] * ay + m * y[i] * y[i]
            val qxy = sxy[j + 1] - sxy[i] - x[i] * ay - y[i] * ax + m * x[i] * y[i]
            val dx = x[j] - x[i]; val dy = y[j] - y[i]
            val length = ShapeMath.norm(dx, dy)
            if (length < 1e-9) return qxx + qyy
            val nx = -dy / length; val ny = dx / length
            return max(0.0, nx * nx * qxx + ny * ny * qyy + 2 * nx * ny * qxy)
        }
    }

    /** Sample indices (first to last) of a chain of straight segments, and its summed squared error. */
    class Chain(val vertices: IntArray, val cost: Double)

    /**
     * The best chains of 1..[maxSegments] straight segments from the first to the last sample,
     * indexed by segment count (null where there is no room for that many).
     */
    fun chains(points: List<InkPoint>, maxSegments: Int, minGap: Int): Array<Chain?> {
        val n = points.size
        val result = arrayOfNulls<Chain>(maxSegments + 1)
        if (n < 2) return result
        val chords = Chords(points)
        val best = Array(maxSegments + 1) { DoubleArray(n) { INF } }
        val from = Array(maxSegments + 1) { IntArray(n) { -1 } }
        best[0][0] = 0.0
        for (m in 1..maxSegments) for (j in m * minGap until n) {
            var cheapest = INF; var origin = -1
            for (i in (m - 1) * minGap..j - minGap) {
                val before = best[m - 1][i]
                if (before == INF) continue
                val cost = before + chords.cost(i, j)
                if (cost < cheapest) { cheapest = cost; origin = i }
            }
            best[m][j] = cheapest; from[m][j] = origin
        }
        for (m in 1..maxSegments) {
            if (best[m][n - 1] == INF) continue
            val vertices = IntArray(m + 1); vertices[m] = n - 1
            for (s in m downTo 1) vertices[s - 1] = from[s][vertices[s]]
            result[m] = Chain(vertices, best[m][n - 1])
        }
        return result
    }

    /** Indices of the corners of [points] that stray more than [tolerance] from a straight run (Douglas–Peucker). */
    fun corners(points: List<InkPoint>, tolerance: Float): IntArray {
        val keep = BooleanArray(points.size); keep[0] = true; keep[points.lastIndex] = true
        val pending = ArrayDeque<IntArray>(); pending.add(intArrayOf(0, points.lastIndex))
        while (pending.isNotEmpty()) {
            val (from, to) = pending.removeLast()
            var far = -1; var farthest = tolerance
            for (i in from + 1 until to) {
                val d = ShapeMath.gap(points[i], points[from], points[to])
                if (d > farthest) { farthest = d; far = i }
            }
            if (far < 0) continue
            keep[far] = true; pending.add(intArrayOf(from, far)); pending.add(intArrayOf(far, to))
        }
        return (points.indices).filter { keep[it] }.toIntArray()
    }

    /**
     * The best chain of [segments] straight segments through corners of [points], from the first
     * sample to the last. Unlike [chains] it measures true distance to each segment, so a stroke
     * that goes out along a line and comes back (an arrow's wing) cannot hide inside a chord.
     */
    fun cornerChain(points: List<InkPoint>, segments: Int, tolerance: Float): IntArray? {
        val c = corners(points, tolerance)
        // An arrow's shaft and head have few corners; a squiggle has many and is not worth searching.
        if (c.size < segments + 1 || c.size > 26) return null
        val memo = DoubleArray(c.size * c.size) { -1.0 }
        fun cost(a: Int, b: Int): Double {
            if (memo[a * c.size + b] >= 0.0) return memo[a * c.size + b]
            var sum = 0.0
            for (t in c[a]..c[b]) { val d = ShapeMath.gap(points[t], points[c[a]], points[c[b]]); sum += d * d }
            memo[a * c.size + b] = sum
            return sum
        }
        val best = Array(segments + 1) { DoubleArray(c.size) { INF } }
        val from = Array(segments + 1) { IntArray(c.size) { -1 } }
        best[0][0] = 0.0
        for (m in 1..segments) for (j in m until c.size) for (i in m - 1 until j) {
            val before = best[m - 1][i]
            if (before == INF) continue
            val total = before + cost(i, j)
            if (total < best[m][j]) { best[m][j] = total; from[m][j] = i }
        }
        if (best[segments][c.size - 1] == INF) return null
        val chain = IntArray(segments + 1); chain[segments] = c.size - 1
        for (s in segments downTo 1) chain[s - 1] = from[s][chain[s]]
        return IntArray(segments + 1) { c[chain[it]] }
    }

    /** A closed outline of straight sides: ring indices in drawing order, and summed squared error. */
    class Loop(val vertices: IntArray, val cost: Double)

    /** Ring samples where the path turns sharply, sharpest first. A closed fit has to start at a real corner. */
    fun corners(ring: List<InkPoint>, minTurn: Double = .5): List<Int> {
        val n = ring.size; val w = max(2, n / 32)
        val strength = DoubleArray(n) { i ->
            val a = ring[(i - w + n) % n]; val b = ring[i]; val c = ring[(i + w) % n]
            abs(atan2(((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)).toDouble(),
                ((b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y)).toDouble()))
        }
        return (0 until n).filter { i ->
            strength[i] >= minTurn && (1..w).all { strength[i] >= strength[(i - it + n) % n] && strength[i] > strength[(i + it) % n] }
        }.sortedByDescending { strength[it] }
    }

    /**
     * How much sharper a ring turns at the corners of [outline] than halfway along its sides:
     * a polygon is several times sharper there, a smooth curve is the same everywhere (1).
     */
    fun contrast(ring: List<InkPoint>, outline: List<InkPoint>): Double {
        val n = ring.size; val k = outline.size
        val w = max(2, n / (4 * k))
        fun angle(i: Int): Double {
            val a = ring[(i - w + n) % n]; val b = ring[i]; val c = ring[(i + w) % n]
            return abs(atan2(((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)).toDouble(),
                ((b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y)).toDouble()))
        }
        val at = outline.map { corner -> ring.indices.minBy { ShapeMath.distance(ring[it], corner) } }
        val corners = at.map { i -> (-1..1).maxOf { angle((i + it + n) % n) } }.average()
        val sides = at.indices.map { e ->
            val a = at[e]; val b = at[(e + 1) % k]
            angle((a + ((b - a + n) % n) / 2) % n)
        }.average()
        return corners / max(sides, .03)
    }

    /** How many of [outline]'s corners sit on one of the ring's sharp turns [peaks] (the rest are mid-curve guesses). */
    fun supported(ring: List<InkPoint>, outline: List<InkPoint>, peaks: List<Int>): Int {
        val n = ring.size; val reach = max(3, n / 30)
        return outline.count { corner ->
            val at = ring.indices.minBy { ShapeMath.distance(ring[it], corner) }
            peaks.any { val d = abs(it - at); min(d, n - d) <= reach }
        }
    }

    /** Best closed outlines of 3..[maxCorners] sides, starting a fit at each of [anchors]. */
    fun loops(ring: List<InkPoint>, maxCorners: Int, anchors: List<Int>): Array<Loop?> {
        val n = ring.size
        val result = arrayOfNulls<Loop>(maxCorners + 1)
        for (anchor in anchors) {
            val turned = ArrayList<InkPoint>(n + 1)
            for (i in 0..n) turned += ring[(anchor + i) % n]
            val chains = chains(turned, maxCorners, 2)
            for (k in 3..maxCorners) {
                val chain = chains[k] ?: continue
                if (result[k] == null || chain.cost < result[k]!!.cost)
                    result[k] = Loop(IntArray(k) { (anchor + chain.vertices[it]) % n }, chain.cost)
            }
        }
        return result
    }

    /**
     * Corners where the straight sides fitted through each run of samples meet. Sides ignore the
     * ends of their run, so a rounded corner does not drag them. A corner whose neighbouring
     * sides are nearly parallel stays on its sample.
     */
    fun refine(ring: List<InkPoint>, vertices: IntArray, span: Float): List<InkPoint> {
        val n = ring.size; val k = vertices.size
        val sides = (0 until k).map { e ->
            val a = vertices[e]; val b = vertices[(e + 1) % k]
            val count = (b - a + n) % n + 1
            val trim = if (count >= 10) (count * .18).toInt() else 0
            val run = (trim until count - trim).map { ring[(a + it) % n] }
            val mx = run.sumOf { it.x.toDouble() } / run.size; val my = run.sumOf { it.y.toDouble() } / run.size
            var xx = 0.0; var yy = 0.0; var xy = 0.0
            for (p in run) { val dx = p.x - mx; val dy = p.y - my; xx += dx * dx; yy += dy * dy; xy += dx * dy }
            val angle = if (run.size < 2 || xx + yy < 1e-9) atan2((ring[b].y - ring[a].y).toDouble(), (ring[b].x - ring[a].x).toDouble())
                else .5 * atan2(2 * xy, xx - yy)
            doubleArrayOf(mx, my, cos(angle), sin(angle))
        }
        return (0 until k).map { v ->
            val before = sides[(v - 1 + k) % k]; val after = sides[v]
            val sample = ring[vertices[v]]
            val cross = before[2] * after[3] - before[3] * after[2]
            if (abs(cross) < .2) return@map sample
            val t = ((after[0] - before[0]) * after[3] - (after[1] - before[1]) * after[2]) / cross
            val corner = InkPoint((before[0] + t * before[2]).toFloat(), (before[1] + t * before[3]).toFloat())
            if (ShapeMath.distance(corner, sample) > span * .15f) sample else corner
        }
    }

    /** RMS distance between a ring and a closed outline, looking both ways so neither can skip a part of the other. */
    fun outlineError(ring: List<InkPoint>, outline: List<InkPoint>): Double {
        val k = outline.size
        var toOutline = 0.0
        for (p in ring) {
            var d = Float.MAX_VALUE
            for (e in 0 until k) d = min(d, ShapeMath.gap(p, outline[e], outline[(e + 1) % k]))
            toOutline += d * d
        }
        val perimeter = (0 until k).sumOf { ShapeMath.distance(outline[it], outline[(it + 1) % k]).toDouble() }
        val step = max(1.0, perimeter / ring.size)
        var toRing = 0.0; var count = 0
        for (e in 0 until k) {
            val a = outline[e]; val b = outline[(e + 1) % k]
            val parts = max(1, (ShapeMath.distance(a, b) / step).toInt())
            for (s in 0 until parts) {
                val q = InkPoint(a.x + (b.x - a.x) * s / parts, a.y + (b.y - a.y) * s / parts)
                var d = Float.MAX_VALUE
                for (p in ring) d = min(d, ShapeMath.distance(p, q))
                toRing += d * d; count++
            }
        }
        return max(sqrt(toOutline / ring.size), sqrt(toRing / max(1, count)))
    }

    // ---- Ellipses ---------------------------------------------------------------------------

    /** An ellipse centred at ([cx], [cy]), radii along and across [angle], and its RMS distance error. */
    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float, val error: Double)

    /** Least-squares ellipse (Sampson distance) refined from the drawing's own principal axes. */
    fun ellipse(points: List<InkPoint>): Ellipse? {
        val n = points.size
        if (n < 8) return null
        val mx = points.sumOf { it.x.toDouble() } / n; val my = points.sumOf { it.y.toDouble() } / n
        var xx = 0.0; var yy = 0.0; var xy = 0.0
        for (p in points) { val x = p.x - mx; val y = p.y - my; xx += x * x; yy += y * y; xy += x * y }
        var angle = .5 * atan2(2 * xy, xx - yy)
        // A circle has no principal axis.
        if (ShapeMath.norm(xx - yy, 2 * xy) / (xx + yy).coerceAtLeast(1.0) < .12) angle = 0.0
        val c = cos(angle); val s = sin(angle)
        val xs = points.map { (it.x - mx) * c + (it.y - my) * s }; val ys = points.map { -(it.x - mx) * s + (it.y - my) * c }
        val cu = (xs.max() + xs.min()) / 2; val cv = (ys.max() + ys.min()) / 2
        val span = ShapeMath.span(points).toDouble()
        val v = doubleArrayOf(mx + cu * c - cv * s, my + cu * s + cv * c,
            max(1.0, (xs.max() - xs.min()) / 2), max(1.0, (ys.max() - ys.min()) / 2), angle)
        fun cost(p: DoubleArray): Double {
            if (p[2] < 1.0 || p[3] < 1.0) return INF
            val cc = cos(p[4]); val ss = sin(p[4]); var sum = 0.0
            for (q in points) {
                val x = (q.x - p[0]) * cc + (q.y - p[1]) * ss; val y = -(q.x - p[0]) * ss + (q.y - p[1]) * cc
                val f = (x / p[2]) * (x / p[2]) + (y / p[3]) * (y / p[3]) - 1
                val g = 2 * ShapeMath.norm(x / (p[2] * p[2]), y / (p[3] * p[3]))
                if (g > 1e-12) { val d = f / g; sum += d * d }
            }
            return sum
        }
        val best = descend(v, doubleArrayOf(span * .03, span * .03, span * .03, span * .03, .05), span * 2e-4, ::cost)
        if (!best.isFinite()) return null
        return Ellipse(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat(), sqrt(best / n))
    }

    /** Coordinate descent on [v] in place from step sizes [steps], halving until the first is below [minStep]. Returns the final cost. */
    private fun descend(v: DoubleArray, steps: DoubleArray, minStep: Double, cost: (DoubleArray) -> Double): Double {
        var best = cost(v)
        for (round in 0 until 80) {
            var improved = false
            for (i in v.indices) for (sign in doubleArrayOf(1.0, -1.0)) {
                val trial = v.copyOf(); trial[i] += sign * steps[i]
                val c = cost(trial)
                if (c < best) { best = c; trial.copyInto(v); improved = true }
            }
            if (!improved) { for (i in steps.indices) steps[i] *= .5; if (steps[0] < minStep) break }
        }
        return best
    }

    // ---- Rounded boxes ----------------------------------------------------------------------

    /** A rectangle of half-sides [hw] × [hh] turned by [angle] about its centre, with corners of [radius]. */
    class RoundedBox(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val radius: Float, val angle: Float, val error: Double)

    /**
     * Least-squares rounded rectangle (exact signed distance), so one fit covers a plain box
     * (radius 0), a card with soft corners, and a pill (radius half its thickness).
     */
    fun roundedBox(ring: List<InkPoint>): RoundedBox? {
        val n = ring.size
        if (n < 8) return null
        val mx = ring.sumOf { it.x.toDouble() } / n; val my = ring.sumOf { it.y.toDouble() } / n
        var xx = 0.0; var yy = 0.0; var xy = 0.0
        for (p in ring) { val x = p.x - mx; val y = p.y - my; xx += x * x; yy += y * y; xy += x * y }
        val angle = .5 * atan2(2 * xy, xx - yy)
        val c = cos(angle); val s = sin(angle)
        val xs = ring.map { (it.x - mx) * c + (it.y - my) * s }; val ys = ring.map { -(it.x - mx) * s + (it.y - my) * c }
        val cu = (xs.max() + xs.min()) / 2; val cv = (ys.max() + ys.min()) / 2
        val hw = max(2.0, (xs.max() - xs.min()) / 2); val hh = max(2.0, (ys.max() - ys.min()) / 2)
        val span = ShapeMath.span(ring).toDouble()
        val v = doubleArrayOf(mx + cu * c - cv * s, my + cu * s + cv * c, hw, hh, min(hw, hh) * .3, angle)
        fun cost(p: DoubleArray): Double {
            if (p[2] < 2.0 || p[3] < 2.0 || p[4] < 0.0 || p[4] > min(p[2], p[3])) return INF
            val cc = cos(p[5]); val ss = sin(p[5]); var sum = 0.0
            val ix = p[2] - p[4]; val iy = p[3] - p[4]
            for (q in ring) {
                val x = abs((q.x - p[0]) * cc + (q.y - p[1]) * ss) - ix; val y = abs(-(q.x - p[0]) * ss + (q.y - p[1]) * cc) - iy
                val d = ShapeMath.norm(max(x, 0.0), max(y, 0.0)) + min(max(x, y), 0.0) - p[4]
                sum += d * d
            }
            return sum
        }
        val best = descend(v, doubleArrayOf(span * .03, span * .03, span * .03, span * .03, span * .02, .05), span * 2e-4, ::cost)
        if (!best.isFinite()) return null
        return RoundedBox(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat(), v[5].toFloat(), sqrt(best / n))
    }

    /** The closed outline of [box], corners rounded with short arcs. */
    fun outline(box: RoundedBox, angle: Float): List<InkPoint> {
        val c = cos(angle); val s = sin(angle)
        val result = ArrayList<InkPoint>()
        fun add(x: Double, y: Double) { result += InkPoint((box.cx + x * c - y * s).toFloat(), (box.cy + x * s + y * c).toFloat()) }
        val r = box.radius.toDouble(); val ix = box.hw - r; val iy = box.hh - r
        val step = max(2.0, 2.0 * (box.hw + box.hh) / 40)
        // Corner centres clockwise from the top right, each with the angle its arc starts at.
        val centres = listOf(doubleArrayOf(ix, -iy, -90.0), doubleArrayOf(ix, iy, 0.0), doubleArrayOf(-ix, iy, 90.0), doubleArrayOf(-ix, -iy, 180.0))
        for ((k, corner) in centres.withIndex()) {
            // The straight side from the previous corner's end to this corner's start.
            val previous = centres[(k + 3) % 4]
            val from = doubleArrayOf(previous[0] + r * cos((previous[2] + 90) * PI / 180), previous[1] + r * sin((previous[2] + 90) * PI / 180))
            val to = doubleArrayOf(corner[0] + r * cos(corner[2] * PI / 180), corner[1] + r * sin(corner[2] * PI / 180))
            val parts = max(1, (ShapeMath.norm(to[0] - from[0], to[1] - from[1]) / step).toInt())
            for (i in 0 until parts) add(from[0] + (to[0] - from[0]) * i / parts, from[1] + (to[1] - from[1]) * i / parts)
            val arcParts = if (r < 1.0) 1 else 8
            for (i in 0 until arcParts) {
                val a = (corner[2] + 90.0 * i / arcParts) * PI / 180
                add(corner[0] + r * cos(a), corner[1] + r * sin(a))
            }
        }
        result += result.first()
        return result
    }

    // ---- Hearts -----------------------------------------------------------------------------

    /** A heart in a unit box centred on the origin: notch at the top, point at the bottom (y is down). */
    private val heartOutline: List<DoubleArray> by lazy {
        val raw = (0..96).map {
            val t = it * 2 * PI / 96
            doubleArrayOf(16 * sin(t).pow(3), -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)))
        }
        val minX = raw.minOf { it[0] }; val maxX = raw.maxOf { it[0] }; val minY = raw.minOf { it[1] }; val maxY = raw.maxOf { it[1] }
        raw.map { doubleArrayOf((it[0] - (minX + maxX) / 2) / (maxX - minX), (it[1] - (minY + maxY) / 2) / (maxY - minY)) }
    }

    /** A heart outline (closed, upright within about 30°) that fits [ring], and its RMS error. */
    class Heart(val outline: List<InkPoint>, val error: Double)

    fun heart(ring: List<InkPoint>, span: Float): Heart? {
        val gx = ring.sumOf { it.x.toDouble() } / ring.size; val gy = ring.sumOf { it.y.toDouble() } / ring.size
        var best: Heart? = null
        for (degrees in -30..30 step 6) {
            val psi = degrees * PI / 180
            val c = cos(-psi); val s = sin(-psi)
            val upright = ring.map { InkPoint((gx + (it.x - gx) * c - (it.y - gy) * s).toFloat(), (gy + (it.x - gx) * s + (it.y - gy) * c).toFloat()) }
            val minX = upright.minOf { it.x }; val maxX = upright.maxOf { it.x }; val minY = upright.minOf { it.y }; val maxY = upright.maxOf { it.y }
            val w = (maxX - minX).toDouble(); val h = (maxY - minY).toDouble()
            if (w < 1 || h < 1 || w / h !in .75..1.5) continue
            val ox = (minX + maxX) / 2.0; val oy = (minY + maxY) / 2.0
            val unit = upright.map { doubleArrayOf((it.x - ox) / w, (it.y - oy) / h) }
            var toHeart = 0.0
            for (q in unit) toHeart += nearest(q, heartOutline, closed = true).pow(2)
            var toRing = 0.0
            for (t in heartOutline) toRing += unit.minOf { ShapeMath.norm(it[0] - t[0], it[1] - t[1]) }.pow(2)
            val error = max(sqrt(toHeart / unit.size), sqrt(toRing / heartOutline.size)) * ShapeMath.norm(w, h) / span
            if (best != null && error >= best.error) continue
            val back = cos(psi); val forward = sin(psi)
            best = Heart(heartOutline.map {
                val x = ox + it[0] * w - gx; val y = oy + it[1] * h - gy
                InkPoint((gx + x * back - y * forward).toFloat(), (gy + x * forward + y * back).toFloat())
            }, error)
        }
        return best
    }

    // ---- Open symbols: arcs, square brackets and curly braces -------------------------------

    class Symbol(val kind: ShapeKind, val points: List<InkPoint>, val error: Double)

    /**
     * Compares a stroke whose ends are far apart with an arc, a square bracket and a curly brace
     * that share its ends. Drawn in a frame where the ends sit at (0,0) and (1,0) and the stroke
     * bulges towards positive v, so the shape may be turned and mirrored.
     */
    fun symbol(points: List<InkPoint>, span: Float): Symbol? {
        val a = points.first(); val b = points.last()
        val length = ShapeMath.distance(a, b).toDouble()
        if (length < span * .55f) return null
        val dx = (b.x - a.x) / length; val dy = (b.y - a.y) / length
        var nx = -dy; var ny = dx
        fun v(p: InkPoint) = (p.x - a.x) * nx + (p.y - a.y) * ny
        if (points.sumOf { v(it).toDouble() } < 0) { nx = -nx; ny = -ny }
        // A third of the samples describe the stroke as well as all of them.
        val canonical = points.filterIndexed { i, _ -> i % 3 == 0 || i == points.lastIndex }.map { doubleArrayOf(((it.x - a.x) * dx + (it.y - a.y) * dy) / length, ((it.x - a.x) * nx + (it.y - a.y) * ny) / length) }
        val depth = canonical.maxOf { it[1] }
        if (depth < .06 || depth > .72 || canonical.minOf { it[1] } < -.09 || canonical.minOf { it[0] } < -.2 || canonical.maxOf { it[0] } > 1.2) return null
        var best: Pair<ShapeKind, List<DoubleArray>>? = null; var bestError = INF
        fun consider(kind: ShapeKind, template: List<DoubleArray>) {
            var toTemplate = 0.0
            for (q in canonical) toTemplate += nearest(q, template, closed = false).pow(2)
            var toStroke = 0.0
            for (t in template) toStroke += nearest(t, canonical, closed = false).pow(2)
            val error = max(sqrt(toTemplate / canonical.size), sqrt(toStroke / template.size))
            if (error < bestError) { bestError = error; best = kind to template }
        }
        for (scale in doubleArrayOf(.9, 1.0, 1.1)) {
            val w = depth * scale
            consider(ShapeKind.ARC, arcTemplate(w))
            consider(ShapeKind.BRACKET, bracketTemplate(w))
            if (w <= .5) for (shaft in doubleArrayOf(.4, .5, .6)) consider(ShapeKind.BRACE, braceTemplate(w, shaft))
        }
        val (kind, template) = best ?: return null
        if (bestError > .03) return null
        return Symbol(kind, template.map { InkPoint((a.x + (it[0] * dx + it[1] * nx) * length).toFloat(), (a.y + (it[0] * dy + it[1] * ny) * length).toFloat()) }, bestError * length / span)
    }

    private fun arcTemplate(w: Double): List<DoubleArray> {
        val r = (.25 + w * w) / (2 * w); val centre = w - r
        val end = atan2(-centre, .5)
        return (0..48).map {
            val angle = (PI - end) + (end - (PI - end)) * it / 48
            doubleArrayOf(.5 + r * cos(angle), centre + r * sin(angle))
        }
    }

    private fun bracketTemplate(w: Double) = dense(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, w), doubleArrayOf(1.0, w), doubleArrayOf(1.0, 0.0)), .03)

    private fun braceTemplate(w: Double, shaft: Double): List<DoubleArray> {
        val r1 = w * shaft; val r2 = w - r1
        val half = ArrayList<DoubleArray>()
        for (i in 0..10) { val t = i * PI / 2 / 10; half += doubleArrayOf(r1 - r1 * cos(t), r1 * sin(t)) }
        half += doubleArrayOf(.5 - r2, r1)
        for (i in 0..10) { val t = -PI / 2 + i * PI / 2 / 10; half += doubleArrayOf(.5 - r2 + r2 * cos(t), w + r2 * sin(t)) }
        val mirrored = half.dropLast(1).asReversed().map { doubleArrayOf(1 - it[0], it[1]) }
        return dense(half + mirrored, .03)
    }

    private fun dense(path: List<DoubleArray>, step: Double): List<DoubleArray> {
        val result = ArrayList<DoubleArray>(); result += path.first()
        for (i in 1..path.lastIndex) {
            val a = path[i - 1]; val b = path[i]
            val parts = max(1, (ShapeMath.norm(b[0] - a[0], b[1] - a[1]) / step).toInt())
            for (s in 1..parts) result += doubleArrayOf(a[0] + (b[0] - a[0]) * s / parts, a[1] + (b[1] - a[1]) * s / parts)
        }
        return result
    }

    /** Distance from [q] to a polyline (or closed outline) of (u, v) points. */
    private fun nearest(q: DoubleArray, path: List<DoubleArray>, closed: Boolean): Double {
        var best = INF
        val last = if (closed) path.size else path.size - 1
        for (i in 0 until last) {
            val a = path[i]; val b = path[(i + 1) % path.size]
            val ex = b[0] - a[0]; val ey = b[1] - a[1]; val squared = ex * ex + ey * ey
            val t = if (squared == 0.0) 0.0 else (((q[0] - a[0]) * ex + (q[1] - a[1]) * ey) / squared).coerceIn(0.0, 1.0)
            best = min(best, ShapeMath.norm(q[0] - a[0] - t * ex, q[1] - a[1] - t * ey))
        }
        return best
    }
}
