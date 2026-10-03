package com.folio.notes

import kotlin.math.hypot

/**
 * Where the pen tip is about to be, for hiding display latency. Pure and allocation-free: the
 * result is only ever drawn as a throwaway tail past the last real sample, never stored in the
 * stroke, so a wrong guess vanishes on the next frame and cannot reach the saved ink.
 */
internal object StrokePrediction {
    /** Samples of lead: roughly one display frame at stylus rates. */
    private const val LEAD_SAMPLES = 3f
    /** Longest tail, in screen pixels, so a jerk can never paint far from the pen. */
    const val MAX_TAIL_PX = 28f

    /**
     * Writes the predicted tip into [out] (x, y) and returns true, or returns false when the
     * recent motion is too slow, too short or turning too sharply to extrapolate safely.
     */
    fun predict(points: List<InkPoint>, scale: Float, out: FloatArray): Boolean {
        val n = points.size
        if (n < 3 || scale <= 0f) return false
        val a = points[n - 3]; val b = points[n - 2]; val c = points[n - 1]
        val ux = b.x - a.x; val uy = b.y - a.y
        val vx = c.x - b.x; val vy = c.y - b.y
        // A reversal means a corner or a hook: leave it to the real samples.
        if (ux * vx + uy * vy <= 0f) return false
        val step = hypot(vx, vy)
        if (step * scale < 0.5f) return false
        // Average the last two steps for steadiness, then cap the reach in screen space.
        var dx = (ux + vx) / 2f * LEAD_SAMPLES
        var dy = (uy + vy) / 2f * LEAD_SAMPLES
        val reach = hypot(dx, dy) * scale
        if (reach > MAX_TAIL_PX) { val k = MAX_TAIL_PX / reach; dx *= k; dy *= k }
        out[0] = c.x + dx; out[1] = c.y + dy
        return true
    }
}
