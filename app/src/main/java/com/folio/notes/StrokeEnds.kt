package com.folio.notes

import kotlin.math.hypot
import kotlin.math.min

/**
 * Cleans the two places a stylus stroke is least reliable. Android's pen-down and lift events often carry
 * a pressure unlike the samples beside them (a full-pressure reading at the very tip, for instance), which
 * draws as a blob, then a thin neck, then the stroke; and the pen skids a hair as it lands or lifts, which
 * draws as a tiny hook. The end pressures are taken from their neighbours, and a hook shorter than
 * [hookLimit] that turns away from the stroke is dropped. Dots, ticks and short strokes are left alone.
 */
internal object StrokeEnds {
    private const val MIN_POINTS = 6
    /** A turn of more than this (cosine) off the stroke's direction reads as a skid, not as writing. */
    private const val HOOK_COS = .55f

    /** The longest hook (page units) that is trimmed for a pen of [width]. */
    fun hookLimit(width: Float) = min(3f, (width * 1.5f).coerceAtLeast(1f))

    fun settle(points: List<InkPoint>, width: Float): List<InkPoint> {
        val n = points.size
        if (n < 3) return points
        val out = ArrayList(points)
        out[0] = out[0].withPressure(out[1].pressure)
        out[n - 1] = out[n - 1].withPressure(out[n - 2].pressure)
        if (n >= MIN_POINTS && length(out) >= maxOf(8f, width * 4f)) {
            val limit = hookLimit(width)
            trimEnd(out, limit)
            out.reverse(); trimEnd(out, limit); out.reverse()
        }
        return if (out.indices.all { out[it] === points.getOrNull(it) } && out.size == n) points else out
    }

    private fun InkPoint.withPressure(p: Float) = if (pressure == p) this else InkPoint(x, y, p)

    /** Drops a short turned-away tail from the end of [points], keeping at least half of the stroke. */
    private fun trimEnd(points: ArrayList<InkPoint>, limit: Float) {
        val n = points.size
        var cut = -1
        var tail = 0f
        // Grow the candidate tail backwards while it stays short; remember the longest one that turns away.
        var k = n - 1
        while (k > n / 2) {
            tail += distance(points[k], points[k - 1])
            if (tail > limit) break
            val start = k - 1
            val body = bodyDirection(points, start, limit * 2f)
            if (body != null) {
                val hx = points[n - 1].x - points[start].x; val hy = points[n - 1].y - points[start].y
                val hl = hypot(hx, hy)
                if (hl > 1e-4f && (hx * body.first + hy * body.second) / hl < HOOK_COS) cut = start
            }
            k--
        }
        if (cut > 0) {
            // Keep the turning point itself, with the pressure the stroke had there.
            while (points.size > cut + 1) points.removeAt(points.size - 1)
        }
    }

    /** Unit direction of the stroke arriving at [end], measured over about [reach] page units. */
    private fun bodyDirection(points: List<InkPoint>, end: Int, reach: Float): Pair<Float, Float>? {
        var travelled = 0f
        var i = end
        while (i > 0 && travelled < reach) { travelled += distance(points[i], points[i - 1]); i-- }
        val dx = points[end].x - points[i].x; val dy = points[end].y - points[i].y
        val l = hypot(dx, dy)
        return if (l < reach * .5f) null else Pair(dx / l, dy / l)
    }

    private fun distance(a: InkPoint, b: InkPoint) = hypot(b.x - a.x, b.y - a.y)
    private fun length(points: List<InkPoint>): Float {
        var total = 0f
        for (i in 1 until points.size) total += distance(points[i - 1], points[i])
        return total
    }
}
