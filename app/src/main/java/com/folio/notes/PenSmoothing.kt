package com.folio.notes

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max

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
    /** The raw sample before [raw], to read the turn at [raw]; null until two segments exist. */
    private var beforeRaw: InkPoint? = null
    /** 1 right after a sharp turn, fading to 0: the filter then follows the pen instead of the average. */
    private var corner = 0f

    fun sample(point: InkPoint, timeMs: Long, final: Boolean = false): InkPoint {
        val previous = raw
        val last = filtered
        // Long gaps and non-increasing timestamps must not invent a velocity or bridge gestures.
        if (previous == null || last == null || strength == 0f || final ||
            timeMs <= time || timeMs - time > 100L) {
            raw = point; filtered = point; time = timeMs
            velocityX = 0f; velocityY = 0f
            beforeRaw = null; corner = 0f
            return point
        }
        val dt = (timeMs - time) / 1000f
        // Writers slow down into a turn, which is the one warning of a corner before it has happened.
        val braking = braking(hypot(point.x - previous.x, point.y - previous.y) * screenScale / (dt * 1000f))
        corner = if (turnsSharply(beforeRaw, previous, point)) 1f else max(corner * CORNER_FADE, braking)
        val velocityAlpha = alpha(10f, dt)
        velocityX += velocityAlpha * ((point.x - previous.x) * screenScale / dt - velocityX)
        velocityY += velocityAlpha * ((point.y - previous.y) * screenScale / dt - velocityY)
        val cutoff = 24f - 18f * strength + .06f * hypot(velocityX, velocityY) + CORNER_CUTOFF * corner
        val positionAlpha = alpha(cutoff, dt)
        var x = last.x + positionAlpha * (point.x - last.x)
        var y = last.y + positionAlpha * (point.y - last.y)
        val lag = hypot(point.x - x, point.y - y)
        // A turn is where the letter is: the lag budget shrinks so t-bars, e-loops and a's keep their points.
        val limit = 4f * strength * (1f - CORNER_LAG_RELIEF * corner) / screenScale
        if (lag > limit) {
            val ratio = limit / lag
            x = point.x + (x - point.x) * ratio
            y = point.y + (y - point.y) * ratio
        }
        val result = InkPoint(x, y, point.pressure)
        beforeRaw = previous; raw = point; filtered = result; time = timeMs
        return result
    }

    /** 0..[BRAKE_RELIEF] when the pen has fallen well below the speed it was recently travelling at. */
    private fun braking(pxPerMs: Float): Float {
        val recent = hypot(velocityX, velocityY) / 1000f
        if (recent < BRAKE_MIN_SPEED || pxPerMs >= recent * BRAKE_RATIO) return 0f
        return BRAKE_RELIEF * (1f - pxPerMs / (recent * BRAKE_RATIO))
    }

    /**
     * True when the pen has just changed direction by more than about 55° over two real strokes of
     * movement. Both legs must be several screen pixels long, so a hand's tremor never reads as a corner.
     */
    private fun turnsSharply(before: InkPoint?, middle: InkPoint, last: InkPoint): Boolean {
        if (before == null) return false
        val inX = (middle.x - before.x) * screenScale; val inY = (middle.y - before.y) * screenScale
        val outX = (last.x - middle.x) * screenScale; val outY = (last.y - middle.y) * screenScale
        val inLength = hypot(inX, inY); val outLength = hypot(outX, outY)
        if (inLength < CORNER_LEG_PX || outLength < CORNER_LEG_PX) return false
        return (inX * outX + inY * outY) / (inLength * outLength) < CORNER_COS
    }

    private fun alpha(cutoff: Float, dt: Float): Float =
        1f / (1f + 1f / (2f * PI.toFloat() * cutoff * dt))

    companion object {
        const val DEFAULT = .35f
        /** Shortest leg, in screen pixels, that can form a corner; below it movement is treated as tremor. */
        private const val CORNER_LEG_PX = 3f
        private const val CORNER_COS = .55f
        private const val CORNER_FADE = .45f
        private const val CORNER_CUTOFF = 40f
        private const val CORNER_LAG_RELIEF = .85f
        /** Braking only matters at writing speed (px/ms) and when the pen has lost over 45% of it. */
        private const val BRAKE_MIN_SPEED = 1f
        private const val BRAKE_RATIO = .55f
        private const val BRAKE_RELIEF = .6f
        fun strength(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT
    }
}
