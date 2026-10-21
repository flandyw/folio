package com.folio.notes.editor.selection

import kotlin.math.abs
import kotlin.math.round

/** Selection translation only: never changes stroke samples or enables canvas follow. */
internal object ObjectSnapping {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    fun offset(start: Float, end: Float, delta: Float, extent: Float?, neighbours: List<Pair<Float, Float>>, threshold: Float): Float {
        if (!start.isFinite() || !end.isFinite() || !delta.isFinite()) return delta
        val first = start + delta; val middle = (start + end) / 2f + delta; val last = end + delta
        var adjustment: Float? = null
        var distance = Float.POSITIVE_INFINITY
        fun consider(target: Float) {
            fun offer(value: Float) { val d = abs(value); if (d < distance) { distance = d; adjustment = value } }
            offer(target - first); offer(target - middle); offer(target - last)
        }
        extent?.takeIf { it.isFinite() && it > 0f }?.let { consider(24f); consider(it / 2f); consider(it - 24f) }
        neighbours.forEach { (min, max) -> if (min.isFinite() && max.isFinite()) { consider(min); consider((min + max) / 2f); consider(max) } }
        return if (adjustment != null && distance <= threshold.coerceAtLeast(0f)) delta + adjustment!!
            else round((start + delta) / 12f) * 12f - start
    }
}
