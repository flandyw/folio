package com.folio.notes.ink.shapes

import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import kotlin.math.hypot

/** Screen-space settling: small drift and brief excursions do not restart a deliberate hold. */
internal class ShapeHold {
    private var anchorX = 0f
    private var anchorY = 0f
    private var since: Long? = null
    private var attempted = false
    private var outsideSince: Long? = null
    private var outsideX = 0f
    private var outsideY = 0f

    fun reset() { since = null; attempted = false; outsideSince = null }

    private fun restart(x: Float, y: Float, time: Long) {
        anchorX = x; anchorY = y; since = time; attempted = false; outsideSince = null
    }

    /** Large movement restarts immediately; a brief wobble outside the radius gets a grace period. */
    fun sample(x: Float, y: Float, time: Long, slop: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val distance = hypot(x - anchorX, y - anchorY)
        if (since == null || distance > slop * 2f) { restart(x, y, time); return true }
        if (distance <= slop) { outsideSince = null; return false }
        outsideX = x; outsideY = y
        val outside = outsideSince ?: time.also { outsideSince = it }
        if (time - outside >= GRACE_MS) { restart(x, y, outside); return true }
        return false
    }

    fun remaining(now: Long, delayMs: Long): Long? {
        val outside = outsideSince
        if (outside != null && now - outside >= GRACE_MS) restart(outsideX, outsideY, outside)
        return since?.takeUnless { attempted }?.let {
            maxOf(it + delayMs, outsideSince?.plus(GRACE_MS) ?: 0L).minus(now).coerceAtLeast(0L)
        }
    }

    fun attempt(now: Long, delayMs: Long): Boolean {
        if (remaining(now, delayMs) != 0L) return false
        attempted = true
        return true
    }

    companion object {
        /** Keep a settling tail from becoming a scribble in the recogniser; saved freehand stays exact. */
        fun drawing(stroke: Stroke, pointCount: Int): Stroke {
            val points = stroke.points
            if (points.size < 5 || pointCount >= points.size) return stroke
            val count = pointCount.coerceIn(5, points.size)
            val tail = points.subList(count - 1, points.size)
            val end = InkPoint(tail.sumOf { it.x.toDouble() }.div(tail.size).toFloat(),
                tail.sumOf { it.y.toDouble() }.div(tail.size).toFloat())
            return stroke.copy(points = points.take(count - 1) + end)
        }

        const val SLOP_DP = 14f
        const val GRACE_MS = 80L
        const val ADJUST_SLOP_DP = 5f
    }
}
