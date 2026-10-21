package com.folio.notes.ink

import com.folio.notes.model.InkPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The filled shape of a variable-width pen stroke: exactly what a round nib of changing size sweeps
 * along the centreline (the union of a disc at every point and the tangent hull between neighbours).
 *
 * It is traced as one closed contour per stroke, the way a calligraphic outline is drawn by hand: down
 * the right edge, round the end cap, back up the left edge and round the start cap. Where the stroke
 * turns, the outer edge follows an arc of the nib and the inner edge is trimmed where its two sides
 * cross, so a stroke of a few hundred samples is one polygon of roughly twice as many vertices instead
 * of a disc and a hull per sample. Fewer, longer edges rasterize faster and anti-alias cleanly.
 *
 * Where the inner edge would fold back on itself (a turn tighter than the nib, a cusp, a sample that
 * one disc swallows) the contour is ended with a round cap and a new one starts, so no piece ever
 * contains a reversed loop. Every contour is wound the same way (positive shoelace area in page
 * coordinates), so a non-zero fill paints the union once: loops, crossings and overlapping pieces
 * fill solidly, translucent ink never darkens, and the hole of an "o" stays open. Pure: it emits
 * polygons to a [Sink].
 */
internal object InkOutline {
    interface Sink {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun close()
    }

    /** A disc's largest flat-side error, in page units. */
    private const val TOLERANCE = .015f
    /** Samples closer than this (page units) are one sample. */
    private const val SAME_POINT = 1e-4f
    /** How far apart (page units) two sides may meet at an inner joint and still simply be joined. */
    private const val JOINT_SLACK = .004f
    /** A vertex this close (page units) to the line through its neighbours is dropped. */
    private const val COLLINEAR = .002f
    /** Side lines this close to parallel (|sin| of the angle between them) meet without an arc or a trim. */
    private const val PARALLEL = 1e-4f

    /** [radius] gives each centre point's radius in page units. */
    fun build(centre: List<InkPoint>, radius: (Int) -> Float, sink: Sink) {
        if (centre.isEmpty()) return
        // Collapse coincident samples, keeping the larger nib, so every segment has a direction.
        var xs = FloatArray(centre.size); var ys = FloatArray(centre.size); var rs = FloatArray(centre.size)
        var n = 0
        for (i in centre.indices) {
            val p = centre[i]; val r = radius(i).coerceAtLeast(0f)
            if (n > 0 && hypot(p.x - xs[n - 1], p.y - ys[n - 1]) <= SAME_POINT) {
                if (r > rs[n - 1]) rs[n - 1] = r
                continue
            }
            xs[n] = p.x; ys[n] = p.y; rs[n] = r; n++
        }
        if (n == 1) { disc(xs[0], ys[0], rs[0], sink); return }
        Builder(xs, ys, rs, n, Thin(sink)).emit()
    }

    /**
     * Passes a contour on without its redundant vertices: repeats, and points that lie on the line
     * between their neighbours (straight even runs emit both sides of every joint).
     */
    private class Thin(val out: Sink) : Sink {
        private var open = false
        private var count = 0
        private var ax = 0f; private var ay = 0f // last vertex sent on
        private var bx = 0f; private var by = 0f // pending vertex, not yet sent
        override fun moveTo(x: Float, y: Float) {
            flush(); out.moveTo(x, y); ax = x; ay = y; count = 1; open = true
        }
        override fun lineTo(x: Float, y: Float) {
            if (count >= 2 && hypot(x - bx, y - by) <= SAME_POINT) return
            if (count == 1) {
                if (hypot(x - ax, y - ay) <= SAME_POINT) return
                bx = x; by = y; count = 2; return
            }
            // Drop the pending vertex when it sits on the segment from the last sent one to this one.
            val dx = x - ax; val dy = y - ay
            val len = hypot(dx, dy)
            val off = if (len == 0f) hypot(bx - ax, by - ay) else abs((bx - ax) * dy - (by - ay) * dx) / len
            val along = (bx - ax) * dx + (by - ay) * dy
            if (off > COLLINEAR || along < 0f || along > len * len) { out.lineTo(bx, by); ax = bx; ay = by }
            bx = x; by = y
        }
        private fun flush() { if (open && count >= 2) out.lineTo(bx, by); count = 0 }
        override fun close() { flush(); if (open) out.close(); open = false }
    }

    private class Builder(
        val x: FloatArray, val y: FloatArray, val r: FloatArray, val n: Int, val sink: Sink
    ) {
        val segments = n - 1
        /** Unit vectors from each segment's centres to its tangent points, right (m) and left (p) of travel. */
        val mx = FloatArray(segments); val my = FloatArray(segments)
        val px = FloatArray(segments); val py = FloatArray(segments)
        /** A segment one of whose discs holds the other: it is drawn as that disc alone. */
        val swallowed = BooleanArray(segments)
        /** Joint j (between segments j-1 and j) ends one contour and starts the next. */
        val split = BooleanArray(n)
        /** Where each side of each segment starts and ends after its joints are trimmed (0..1 along it). */
        val rightFrom = FloatArray(segments); val rightTo = FloatArray(segments)
        val leftFrom = FloatArray(segments); val leftTo = FloatArray(segments)

        init {
            for (i in 0 until segments) {
                val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]
                val d = hypot(dx, dy)
                val ux = dx / d; val uy = dy / d
                val nx = -uy; val ny = ux
                if (d <= abs(r[i] - r[i + 1]) + SAME_POINT) {
                    swallowed[i] = true; split[i] = true; split[i + 1] = true
                    continue
                }
                // The tangent hull of two discs leans by asin((r1 - r2) / d) as the nib grows or shrinks.
                val sa = (r[i] - r[i + 1]) / d
                val ca = sqrt((1f - sa * sa).coerceAtLeast(0f))
                mx[i] = ux * sa - nx * ca; my[i] = uy * sa - ny * ca
                px[i] = ux * sa + nx * ca; py[i] = uy * sa + ny * ca
            }
        }

        // Side segment ends: right side of segment i runs R(i,0) → R(i,1), the left side L(i,0) → L(i,1).
        fun rx(i: Int, t: Float) = lerp(x[i] + mx[i] * r[i], x[i + 1] + mx[i] * r[i + 1], t)
        fun ry(i: Int, t: Float) = lerp(y[i] + my[i] * r[i], y[i + 1] + my[i] * r[i + 1], t)
        fun lx(i: Int, t: Float) = lerp(x[i] + px[i] * r[i], x[i + 1] + px[i] * r[i + 1], t)
        fun ly(i: Int, t: Float) = lerp(y[i] + py[i] * r[i], y[i + 1] + py[i] * r[i + 1], t)

        /**
         * Where the side lines before and after joint j cross, as (t along segment j-1, s along segment j),
         * or null when they are parallel. [right] picks the side.
         */
        private val hit = FloatArray(2)
        fun cross(j: Int, right: Boolean): FloatArray? {
            val a = j - 1; val b = j
            val ax = if (right) rx(a, 0f) else lx(a, 0f); val ay = if (right) ry(a, 0f) else ly(a, 0f)
            val adx = (if (right) rx(a, 1f) else lx(a, 1f)) - ax; val ady = (if (right) ry(a, 1f) else ly(a, 1f)) - ay
            val bx = if (right) rx(b, 0f) else lx(b, 0f); val by = if (right) ry(b, 0f) else ly(b, 0f)
            val bdx = (if (right) rx(b, 1f) else lx(b, 1f)) - bx; val bdy = (if (right) ry(b, 1f) else ly(b, 1f)) - by
            val den = adx * bdy - ady * bdx
            val scale = hypot(adx, ady) * hypot(bdx, bdy)
            if (scale == 0f || abs(den) <= PARALLEL * scale) return null
            val ex = bx - ax; val ey = by - ay
            hit[0] = (ex * bdy - ey * bdx) / den
            hit[1] = (ex * ady - ey * adx) / den
            return hit
        }

        /** True when the side turns outwards at joint j (it needs an arc), false when inwards (it is trimmed). */
        fun convex(j: Int, right: Boolean): Boolean {
            val a = j - 1; val b = j
            val adx: Float; val ady: Float; val bdx: Float; val bdy: Float
            if (right) {
                adx = rx(a, 1f) - rx(a, 0f); ady = ry(a, 1f) - ry(a, 0f)
                bdx = rx(b, 1f) - rx(b, 0f); bdy = ry(b, 1f) - ry(b, 0f)
            } else {
                adx = lx(a, 1f) - lx(a, 0f); ady = ly(a, 1f) - ly(a, 0f)
                bdx = lx(b, 1f) - lx(b, 0f); bdy = ly(b, 1f) - ly(b, 0f)
            }
            val turn = adx * bdy - ady * bdx
            // The right side is walked forwards and the left backwards, both anticlockwise round the ink.
            return if (right) turn > 0f else turn < 0f
        }

        /** Anticlockwise angle of the nib between the two tangent points at joint j, in traversal order. */
        fun sweep(j: Int, right: Boolean): Float {
            val from: Float; val to: Float
            if (right) { from = atan2(my[j - 1], mx[j - 1]); to = atan2(my[j], mx[j]) }
            else { from = atan2(py[j], px[j]); to = atan2(py[j - 1], px[j - 1]) }
            var sweep = to - from
            while (sweep < 0f) sweep += (2.0 * PI).toFloat()
            while (sweep >= (2.0 * PI).toFloat()) sweep -= (2.0 * PI).toFloat()
            return sweep
        }

        /** True when the sides before and after joint j turn by no more than a right angle. */
        fun forward(j: Int, right: Boolean): Boolean {
            val a = j - 1; val b = j
            return if (right) (rx(a, 1f) - rx(a, 0f)) * (rx(b, 1f) - rx(b, 0f)) + (ry(a, 1f) - ry(a, 0f)) * (ry(b, 1f) - ry(b, 0f)) > 0f
            else (lx(a, 1f) - lx(a, 0f)) * (lx(b, 1f) - lx(b, 0f)) + (ly(a, 1f) - ly(a, 0f)) * (ly(b, 1f) - ly(b, 0f)) > 0f
        }

        /** Trims every inner joint, and splits the contour wherever a side would fold back on itself. */
        fun plan() {
            for (i in 0 until segments) { rightFrom[i] = 0f; rightTo[i] = 1f; leftFrom[i] = 0f; leftTo[i] = 1f }
            for (j in 1 until n - 1) {
                if (split[j]) continue
                for (right in booleanArrayOf(true, false)) {
                    // Sides that meet within a hair (a slight change of width, no real turn) just join.
                    val gap = if (right) hypot(rx(j - 1, 1f) - rx(j, 0f), ry(j - 1, 1f) - ry(j, 0f))
                        else hypot(lx(j - 1, 1f) - lx(j, 0f), ly(j - 1, 1f) - ly(j, 0f))
                    if (gap <= JOINT_SLACK) continue
                    if (convex(j, right)) {
                        // An outer arc of more than half the nib is a hairpin, which reads better as two caps.
                        if (sweep(j, right) > PI.toFloat()) { split[j] = true; break }
                        continue
                    }
                    val h = cross(j, right) ?: continue
                    val t = h[0]; val s = h[1]
                    // Trimming may take at most half of either side: any more means the turn is tighter than the
                    // nib there (the inner edge would fold), or an earlier cap could poke through. Cut instead.
                    if (t !in .5f..1f || s !in 0f..5f / 10f || !forward(j, right)) { split[j] = true; break }
                    if (right) { rightTo[j - 1] = t; rightFrom[j] = s } else { leftTo[j - 1] = t; leftFrom[j] = s }
                }
            }
            // A side trimmed from both ends past itself has folded across a run of short segments: cut there.
            var changed = true
            while (changed) {
                changed = false
                for (i in 0 until segments) {
                    if (swallowed[i]) continue
                    if (rightFrom[i] > rightTo[i] || leftFrom[i] > leftTo[i]) {
                        val j = if (!split[i + 1] && i + 1 < n - 1) i + 1 else i
                        if (split[j]) continue
                        split[j] = true; changed = true
                        if (j - 1 >= 0) { rightTo[j - 1] = 1f; leftTo[j - 1] = 1f }
                        if (j < segments) { rightFrom[j] = 0f; leftFrom[j] = 0f }
                    }
                }
            }
            for (j in 0 until n) if (split[j]) {
                if (j - 1 >= 0) { rightTo[j - 1] = 1f; leftTo[j - 1] = 1f }
                if (j < segments) { rightFrom[j] = 0f; leftFrom[j] = 0f }
            }
        }

        fun emit() {
            plan()
            var start = 0
            while (start < segments) {
                if (swallowed[start]) {
                    val i = start
                    if (r[i + 1] > r[i]) disc(x[i + 1], y[i + 1], r[i + 1], sink) else disc(x[i], y[i], r[i], sink)
                    start++
                    continue
                }
                var end = start
                while (end + 1 < segments && !split[end + 1] && !swallowed[end + 1]) end++
                contour(start, end)
                start = end + 1
            }
        }

        /** One closed contour over segments [s]..[e]: right side forwards, end cap, left side back, start cap. */
        fun contour(s: Int, e: Int) {
            sink.moveTo(rx(s, 0f), ry(s, 0f))
            for (j in s + 1..e) {
                val a = j - 1
                if (convex(j, true) && sweep(j, true) <= PI.toFloat()) {
                    sink.lineTo(rx(a, 1f), ry(a, 1f))
                    arc(x[j], y[j], r[j], rx(a, 1f), ry(a, 1f), rx(j, 0f), ry(j, 0f))
                } else {
                    sink.lineTo(rx(a, rightTo[a]), ry(a, rightTo[a]))
                    if (rightFrom[j] != 0f || rightTo[a] != 1f) continue
                    sink.lineTo(rx(j, 0f), ry(j, 0f))
                }
            }
            sink.lineTo(rx(e, 1f), ry(e, 1f))
            arc(x[e + 1], y[e + 1], r[e + 1], rx(e, 1f), ry(e, 1f), lx(e, 1f), ly(e, 1f))
            for (j in e downTo s + 1) {
                val a = j - 1
                if (convex(j, false) && sweep(j, false) <= PI.toFloat()) {
                    sink.lineTo(lx(j, 0f), ly(j, 0f))
                    arc(x[j], y[j], r[j], lx(j, 0f), ly(j, 0f), lx(a, 1f), ly(a, 1f))
                } else {
                    sink.lineTo(lx(j, leftFrom[j]), ly(j, leftFrom[j]))
                    if (leftFrom[j] != 0f || leftTo[a] != 1f) continue
                    sink.lineTo(lx(a, 1f), ly(a, 1f))
                }
            }
            sink.lineTo(lx(s, 0f), ly(s, 0f))
            arc(x[s], y[s], r[s], lx(s, 0f), ly(s, 0f), rx(s, 0f), ry(s, 0f))
            sink.close()
        }

        /** Anticlockwise arc of the nib at ([cx], [cy]) from one tangent point (already emitted) to the next. */
        fun arc(cx: Float, cy: Float, radius: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
            if (radius <= 0f) { sink.lineTo(toX, toY); return }
            val from = atan2(fromY - cy, fromX - cx)
            var sweep = atan2(toY - cy, toX - cx) - from
            while (sweep < 0f) sweep += (2.0 * PI).toFloat()
            while (sweep >= (2.0 * PI).toFloat()) sweep -= (2.0 * PI).toFloat()
            val steps = ceil(sweep / ((2.0 * PI).toFloat() / sides(radius))).toInt()
            for (k in 1 until steps) {
                val t = from + sweep * k / steps
                sink.lineTo(cx + radius * cos(t), cy + radius * sin(t))
            }
            sink.lineTo(toX, toY)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Sides used for a disc of radius [r]: enough that the flat sides stay well under a hundredth of a unit. */
    fun sides(r: Float): Int = ceil(PI / kotlin.math.acos((1f - TOLERANCE / r.coerceAtLeast(TOLERANCE)).coerceIn(-1f, .99995f))).toInt().coerceIn(8, 32)

    private fun disc(cx: Float, cy: Float, r: Float, sink: Sink) {
        if (r <= 0f) return
        val count = sides(r)
        // Counting the angle upwards in page coordinates gives positive shoelace area, like the contours.
        sink.moveTo(cx + r, cy)
        for (k in 1 until count) {
            val t = 2.0 * PI * k / count
            sink.lineTo(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat())
        }
        sink.close()
    }
}
