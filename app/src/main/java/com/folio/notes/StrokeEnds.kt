package com.folio.notes

/**
 * Cleans the pressure at the two places a stylus stroke reads least reliably. Android's pen-down and lift
 * events often carry a pressure unlike the samples beside them (a full reading at the very tip, for
 * instance), which draws as a blob, then a thin neck, then the stroke; those two samples take their
 * neighbours' pressure. Positions are never changed: a tight final curve (the close of an "o", a cursive
 * join) is indistinguishable from a pen skid, so no part of the stroke is ever trimmed.
 */
internal object StrokeEnds {
    fun settle(points: List<InkPoint>, @Suppress("UNUSED_PARAMETER") width: Float): List<InkPoint> {
        val n = points.size
        if (n < 3) return points
        val first = points[0]; val last = points[n - 1]
        val start = points[1].pressure; val end = points[n - 2].pressure
        if (first.pressure == start && last.pressure == end) return points
        val out = ArrayList(points)
        out[0] = InkPoint(first.x, first.y, start)
        out[n - 1] = InkPoint(last.x, last.y, end)
        return out
    }
}
