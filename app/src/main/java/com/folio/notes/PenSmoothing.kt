package com.folio.notes

import kotlin.math.PI
import kotlin.math.hypot

/**
 * One gesture's input filter. Slow motion gets steadier; faster motion raises the cutoff so
 * handwriting keeps up with the tip. The lag limit is in screen pixels, independent of zoom.
 * Only positions are filtered: pressure and the final lift position remain exact.
 */
internal class PenSmoothing(amount: Float, scale: Float) {
    private val strength = strength(amount)
    private val screenScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    private var raw: InkPoint? = null
    private var filtered: InkPoint? = null
    private var time = 0L
    private var velocityX = 0f
    private var velocityY = 0f

    fun sample(point: InkPoint, timeMs: Long, final: Boolean = false): InkPoint {
        val previous = raw
        val last = filtered
        // Long gaps and non-increasing timestamps must not invent a velocity or bridge gestures.
        if (previous == null || last == null || strength == 0f || final ||
            timeMs <= time || timeMs - time > 100L) {
            raw = point; filtered = point; time = timeMs
            velocityX = 0f; velocityY = 0f
            return point
        }
        val dt = (timeMs - time) / 1000f
        val velocityAlpha = alpha(10f, dt)
        velocityX += velocityAlpha * ((point.x - previous.x) * screenScale / dt - velocityX)
        velocityY += velocityAlpha * ((point.y - previous.y) * screenScale / dt - velocityY)
        val cutoff = 24f - 18f * strength + .06f * hypot(velocityX, velocityY)
        val positionAlpha = alpha(cutoff, dt)
        var x = last.x + positionAlpha * (point.x - last.x)
        var y = last.y + positionAlpha * (point.y - last.y)
        val lag = hypot(point.x - x, point.y - y)
        val limit = 4f * strength / screenScale
        if (lag > limit) {
            val ratio = limit / lag
            x = point.x + (x - point.x) * ratio
            y = point.y + (y - point.y) * ratio
        }
        val result = InkPoint(x, y, point.pressure)
        raw = point; filtered = result; time = timeMs
        return result
    }

    private fun alpha(cutoff: Float, dt: Float): Float =
        1f / (1f + 1f / (2f * PI.toFloat() * cutoff * dt))

    companion object {
        const val DEFAULT = .35f
        fun strength(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT
    }
}
