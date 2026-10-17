package com.folio.notes

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drops redundant samples from a pen stroke when it is finished. A stylus reports hundreds of samples a
 * second, and along a straight run or a gentle sweep most of them say nothing the neighbours don't.
 * Only near-lossless thinning is done: a sample goes when it sits within [TOLERANCE] page units of the
 * line between the samples kept either side and its pressure matches, and no kept gap may reach
 * [MAX_SPAN], because the renderer treats a gap of five units as a deliberate jump and stops smoothing
 * across it. Ends and every real turn survive, so the stroke draws the same and the file is smaller.
 */
internal object StrokeRefit {
    const val TOLERANCE = .04f
    const val MAX_SPAN = 3f
    /** Pressure change (0..1.8 scale) that counts as much as [TOLERANCE] of drift. */
    private const val PRESSURE_STEP = .03f
    private const val MIN_POINTS = 12

    fun simplify(points: List<InkPoint>): List<InkPoint> {
        val n = points.size
        if (n < MIN_POINTS) return points
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        // Iterative Ramer-Douglas-Peucker: a long handwriting stroke must not recurse its way into a stack overflow.
        val stack = ArrayDeque<Int>()
        stack.addLast(0); stack.addLast(n - 1)
        while (stack.isNotEmpty()) {
            val last = stack.removeLast(); val first = stack.removeLast()
            if (last - first < 2) continue
            var worst = -1; var worstError = 0f
            val a = points[first]; val b = points[last]
            val span = hypot(b.x - a.x, b.y - a.y)
            for (i in first + 1 until last) {
                val error = error(points[i], a, b, span, (i - first).toFloat() / (last - first))
                if (error > worstError) { worstError = error; worst = i }
            }
            // Too crooked, or too long a gap to leave to the smoother: keep a sample and split there.
            if (worstError <= TOLERANCE && span <= MAX_SPAN) continue
            val split = if (worstError > TOLERANCE) worst else (first + last) / 2
            keep[split] = true
            stack.addLast(first); stack.addLast(split)
            stack.addLast(split); stack.addLast(last)
        }
        var kept = 0
        for (flag in keep) if (flag) kept++
        if (kept >= n * 9 / 10) return points
        val result = ArrayList<InkPoint>(kept)
        for (i in 0 until n) if (keep[i]) result += points[i]
        return result
    }

    /** The larger of the sample's distance from chord a-b and its pressure drift, both in page-unit terms. */
    private fun error(p: InkPoint, a: InkPoint, b: InkPoint, span: Float, along: Float): Float {
        val distance = if (span < 1e-6f) hypot(p.x - a.x, p.y - a.y)
            else abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / span
        val pressure = abs(p.pressure - (a.pressure + (b.pressure - a.pressure) * along)) * (TOLERANCE / PRESSURE_STEP)
        return maxOf(distance, pressure)
    }
}
