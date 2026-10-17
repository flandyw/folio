package com.folio.notes

import kotlin.math.hypot
import kotlin.math.min

/**
 * Optional soft stroke ends for pen ink. This is a render-time look, never stored: the saved samples
 * are untouched, so turning it on or off restyles every pen stroke and needs no migration.
 * [amount] is 0 (off, the default) to 1; the thinning covers a few pen widths at each end and never
 * goes below [FLOOR] of the width, so a stroke can't vanish and dots stay dots.
 */
internal object InkTaper {
    private const val FLOOR = .4f
    private const val MIN_LENGTH = 2f
    private const val MAX_LENGTH = 10f
    /** Strokes shorter than this (page units) are left alone: a tick, dot or short dash is not a pen flick. */
    private const val MIN_STROKE = 4f

    @Volatile var amount = 0f
        private set
    /** Bumped whenever [amount] changes, so views holding geometry computed under the old look rebuild it. */
    @Volatile var version = 0
        private set

    fun normalize(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    fun configure(value: Float) {
        val next = normalize(value)
        if (next != amount) { amount = next; version++ }
    }

    /** Page units over which each end thins; 0 when [amount] is off. */
    fun length(amount: Float = this.amount): Float =
        if (amount <= 0f) 0f else MIN_LENGTH + (MAX_LENGTH - MIN_LENGTH) * amount

    /** Width multiplier at [distance] page units from a stroke end, for a taper [length]. */
    fun scaleAt(distance: Float, length: Float): Float {
        if (length <= 0f || distance >= length) return 1f
        val t = (distance / length).coerceIn(0f, 1f)
        return FLOOR + (1f - FLOOR) * t * t * (3f - 2f * t)
    }

    /** One multiplier per point of [points], thinning towards both ends; all ones when off or too short. */
    fun scales(points: List<InkPoint>, length: Float = length()): FloatArray {
        val n = points.size
        val result = FloatArray(n) { 1f }
        if (length <= 0f || n < 3) return result
        val along = FloatArray(n)
        for (i in 1 until n) along[i] = along[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        val total = along[n - 1]
        if (total < MIN_STROKE) return result
        // The two ends never overlap: a short stroke tapers over at most 40% of its length each way.
        val reach = min(length, total * .4f)
        for (i in 0 until n) result[i] = min(scaleAt(along[i], reach), scaleAt(total - along[i], reach))
        return result
    }
}
