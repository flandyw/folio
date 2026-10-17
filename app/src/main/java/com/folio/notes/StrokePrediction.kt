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

    /**
     * Turns a platform prediction ([points], x/y pairs in page units, as many as [count]) into a
     * tail that starts exactly at the drawn tip. The pen filter may hold the drawn tip a pixel or so
     * behind the raw pen, so every predicted point is shifted by ([dx], [dy]) — the drawn tip minus
     * the raw one — and the tail is cut once it reaches [maxPx] screen pixels. Writes pairs to [out]
     * (the tip first) and returns how many points it holds; zero means draw no tail.
     */
    fun clip(points: FloatArray, count: Int, tipX: Float, tipY: Float, dx: Float, dy: Float,
        scale: Float, maxPx: Float, out: FloatArray): Int {
        if (count <= 0 || scale <= 0f || maxPx <= 0f) return 0
        val capacity = out.size / 2
        if (capacity < 2) return 0
        out[0] = tipX; out[1] = tipY
        var written = 1
        var previousX = tipX; var previousY = tipY
        var budget = maxPx / scale
        for (i in 0 until count) {
            if (written >= capacity) break
            val x = points[2 * i] + dx; val y = points[2 * i + 1] + dy
            if (!x.isFinite() || !y.isFinite()) return 0
            val step = hypot(x - previousX, y - previousY)
            if (step < MIN_STEP_PX / scale) continue
            if (step >= budget) {
                val k = budget / step
                out[2 * written] = previousX + (x - previousX) * k
                out[2 * written + 1] = previousY + (y - previousY) * k
                written++
                break
            }
            out[2 * written] = x; out[2 * written + 1] = y
            written++
            budget -= step
            previousX = x; previousY = y
        }
        return if (written >= 2) written else 0
    }

    private const val MIN_STEP_PX = .25f
}
