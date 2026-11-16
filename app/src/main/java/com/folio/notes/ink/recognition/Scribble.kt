package com.folio.notes.ink.recognition

import com.folio.notes.ink.InkGeometry
import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import kotlin.math.*

/**
 * Scribble-to-erase geometry. No Android imports.
 *
 * A scrub is read in its own frame: the principal axis of its motion and the side direction across
 * it. It is a run of *legs* that go back and forth over the same stretch of that axis while barely
 * advancing sideways. Writing zigzags too (`www`, `mm`, cursive loops), but it advances by a good
 * share of a leg on every leg.
 *
 * Three answers come out of one reading:
 * - [isScribble]: a candidate. Something else must still approve the deletion (the gesture model
 *   or a deliberate hold).
 * - [isCertain]: unmistakable (extra reversals, tightly packed lines, not a hollow loop). Erases
 *   without the model.
 * - [erase]: which ink the scrub wiped. Ink goes when enough of its length lies between two of
 *   the scrub's lines or within a line spacing of two legs; one leg crossing it is only a graze.
 */
object Scribble {
    private class Scrub(
        /** Thinned path in the scrub's frame: along the axis and across it. */
        val a: FloatArray, val s: FloatArray, val ux: Float, val uy: Float,
        /** Leg boundaries as indices into the path; the last leg runs to the path's end. */
        val turns: IntArray,
        val leg: Float, val spacing: Float, val certain: Boolean
    ) {
        val aMin = a.min(); val aMax = a.max(); val sMin = s.min(); val sMax = s.max()
    }

    fun isScribble(points: List<InkPoint>, sensitivity: Float = ScribbleSensitivity.DEFAULT) = read(points, sensitivity) != null
    fun isCertain(points: List<InkPoint>, sensitivity: Float = ScribbleSensitivity.DEFAULT) = read(points, sensitivity)?.certain == true

    private fun read(raw: List<InkPoint>, sensitivity: Float): Scrub? {
        val ease = ScribbleSensitivity.normalize(sensitivity)
        val needed = ScribbleSensitivity.reversals(ease)
        if (raw.size < needed + 2 || raw.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        var minX = raw[0].x; var maxX = minX; var minY = raw[0].y; var maxY = minY; var length = 0f
        for (i in 1 until raw.size) {
            val p = raw[i]
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
            length += hypot(p.x - raw[i - 1].x, p.y - raw[i - 1].y)
        }
        val span = hypot(maxX - minX, maxY - minY)
        // Page-unit floors only stop dots and jitter; the view adds its own screen-size gate.
        if (span < 10f || length < 50f - 10f * ease) return null
        // Thin to a bounded, evenly spaced path: sample density must not change the answer or the cost.
        val gap = max(length / 400f, .5f)
        val xs = FloatArray(raw.size); val ys = FloatArray(raw.size); var n = 0
        for (i in raw.indices) {
            val p = raw[i]
            if (n == 0 || i == raw.lastIndex || hypot(p.x - xs[n - 1], p.y - ys[n - 1]) >= gap) { xs[n] = p.x; ys[n] = p.y; n++ }
        }
        // Principal direction of travel (an orientation, so back and forth agree), from moves long
        // enough that a slow pen's jitter weighs nothing.
        var xx = 0.0; var yy = 0.0; var xy = 0.0; var ax = xs[0]; var ay = ys[0]
        val step = span / 16f
        for (i in 1 until n) {
            val dx = (xs[i] - ax).toDouble(); val dy = (ys[i] - ay).toDouble()
            if (dx * dx + dy * dy < step * step) continue
            xx += dx * dx; yy += dy * dy; xy += dx * dy
            ax = xs[i]; ay = ys[i]
        }
        val angle = .5 * atan2(2 * xy, xx - yy)
        val ux = cos(angle).toFloat(); val uy = sin(angle).toFloat()
        val a = FloatArray(n) { xs[it] * ux + ys[it] * uy }
        val s = FloatArray(n) { ys[it] * ux - xs[it] * uy }
        val axisSpan = a.max() - a.min(); val side = s.max() - s.min()
        // Split at sustained reversals. The threshold follows the stroke, not one leg of it, but
        // stays small so short strokes scrubbed along a long word still count as legs.
        val sustained = max(3f, axisSpan * .1f)
        val turns = ArrayList<Int>().apply { add(0) }
        var extreme = 0; var direction = 0
        for (i in 1 until n) {
            val delta = a[i] - a[extreme]
            if (direction == 0) {
                if (abs(delta) >= sustained) { direction = if (delta > 0f) 1 else -1; extreme = i }
            } else if (delta * direction > 0f) extreme = i
            else if (delta * direction <= -sustained) { turns.add(extreme); direction = -direction; extreme = i }
        }
        if (extreme != turns.last()) turns.add(extreme)
        val legs = turns.size - 1
        if (legs < needed + 1) return null
        var travel = 0f
        for (i in 1..legs) travel += abs(a[turns[i]] - a[turns[i - 1]])
        // A hairpin re-traces ground: the shorter leg must cover most of the longer one.
        var reversals = 0
        for (i in 1 until legs) {
            val before = abs(a[turns[i]] - a[turns[i - 1]]); val after = abs(a[turns[i + 1]] - a[turns[i]])
            if (min(before, after) >= (.6f - .1f * ease) * max(before, after)) reversals++
        }
        if (reversals < needed) return null
        // It must stay over the same ground: little net progress, several times its own span travelled.
        val limit = .32f + .13f * ease
        if (abs(a[n - 1] - a[0]) > travel * limit || axisSpan > travel * limit) return null
        // Writing runs far along the line, advancing by a good share of a leg per leg; a scrub is
        // about as deep as it is long, or (scratching out a word) barely advances per leg.
        val leg = travel / legs; val spacing = side / legs
        if (side > axisSpan * (1f + .6f * ease) && spacing > leg * (.2f + .05f * ease)) return null
        // Hollow: consecutive legs run far apart mid-leg, as when circling something again and again.
        var apart = 0f; var previous = 0f
        for (i in 0 until legs) {
            val middle = (a[turns[i]] + a[turns[i + 1]]) / 2f
            var best = turns[i]
            for (j in turns[i]..turns[i + 1]) if (abs(a[j] - middle) < abs(a[best] - middle)) best = j
            if (i > 0) apart += abs(s[best] - previous)
            previous = s[best]
        }
        val filled = side <= leg * .15f || apart / (legs - 1) <= side * .4f
        val certain = reversals > needed && travel >= 80f && spacing <= leg * .15f && filled
        return Scrub(a, s, ux, uy, turns.toIntArray(), leg, spacing, certain)
    }

    /**
     * [strokes] without the ink [scribble] wiped, or the same list when it wiped nothing (or is not a
     * scrub at all). Whole strokes only: crossing a long line, or hatching beside one, never deletes it.
     */
    fun erase(strokes: List<Stroke>, scribble: Stroke, sensitivity: Float = ScribbleSensitivity.DEFAULT,
        canErase: (Stroke) -> Boolean = { true }): List<Stroke> {
        if (strokes.isEmpty()) return strokes
        val scrub = read(scribble.points, sensitivity) ?: return strokes
        val coverage = ScribbleSensitivity.coverage(sensitivity)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in scribble.points) { minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y) }
        var survivors: ArrayList<Stroke>? = null
        for (index in strokes.indices) {
            val target = strokes[index]
            // A broad highlighter scrub still has to reach the ink with its middle.
            val touch = min(scribble.width / 2f, 2f) + target.width / 2f + 1f
            // A line spacing past contact, so ink under the scrub's outermost line is within reach of the next one in.
            val near = touch + scrub.spacing.coerceIn(3f, 12f)
            // Reject distant ink on its raw points before expanding shapes or walking long paths.
            var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
            for (p in target.points) { left = min(left, p.x); right = max(right, p.x); top = min(top, p.y); bottom = max(bottom, p.y) }
            val wiped = canErase(target) && right >= minX - near && left <= maxX + near &&
                bottom >= minY - near && top <= maxY + near &&
                scrub.wiped(InkGeometry.pathPoints(target), touch, near) >= coverage
            if (!wiped) survivors?.add(target)
            else if (survivors == null) survivors = ArrayList(strokes.subList(0, index))
        }
        return survivors ?: strokes
    }

    /** Share of [path]'s length the scrub wiped; a dot is all or nothing. */
    private fun Scrub.wiped(path: List<InkPoint>, touch: Float, near: Float): Float {
        if (path.isEmpty()) return 0f
        var total = 0f; var hit = 0f
        for (i in 1 until path.size) {
            val p = path[i - 1]; val q = path[i]
            val length = hypot(q.x - p.x, q.y - p.y)
            if (length <= 0f) continue
            val steps = ceil(length / touch).toInt().coerceIn(1, 64)
            for (k in 0 until steps) {
                val t = (k + .5f) / steps
                if (wipes(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, near)) hit += length / steps
            }
            total += length
        }
        return if (total > 0f) hit / total else if (wipes(path[0].x, path[0].y, near)) 1f else 0f
    }

    private fun Scrub.wipes(x: Float, y: Float, near: Float): Boolean {
        val qa = x * ux + y * uy; val qs = y * ux - x * uy
        if (qa < aMin - near || qa > aMax + near || qs < sMin - near || qs > sMax + near) return false
        var above = Float.POSITIVE_INFINITY; var below = Float.NEGATIVE_INFINITY; var nearLegs = 0
        for (leg in 0 until turns.size - 1) {
            var close = false
            val end = if (leg == turns.size - 2) a.lastIndex else turns[leg + 1]
            for (i in turns[leg] until end) {
                val da = a[i + 1] - a[i]; val ds = s[i + 1] - s[i]
                val l2 = da * da + ds * ds
                val t = if (l2 <= 0f) 0f else (((qa - a[i]) * da + (qs - s[i]) * ds) / l2).coerceIn(0f, 1f)
                val d = hypot(qa - (a[i] + da * t), qs - (s[i] + ds * t))
                if (d <= near) close = true
                // Where this piece of the scrub passes the point, across the axis.
                if (da != 0f && (a[i] - qa) * (a[i + 1] - qa) <= 0f) {
                    val at = s[i] + ds * (qa - a[i]) / da
                    if (at > qs) above = min(above, at) else below = max(below, at)
                }
            }
            // One leg passing over or by it is a graze; two means the ink sits within the scrub's lines.
            if (close && ++nearLegs >= 2) return true
        }
        // Between two of the scrub's lines, unless they are far apart: the inside of a loop drawn
        // round something is not wiped.
        return above - below <= this.leg * .6f
    }
}
