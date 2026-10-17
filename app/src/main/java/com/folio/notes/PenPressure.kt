package com.folio.notes

import kotlin.math.PI
import kotlin.math.pow

/** Calibrate samples before storing them so saved ink and exports retain the chosen response. */
object PenPressure {
    val sensitivityRange = .25f..3f
    val variationRange = 0f..2f
    /** Below 1 a light touch already counts as pressed; above 1 it takes a firmer press. */
    val curveRange = .5f..2f

    fun sensitivity(value: Float) = if (value.isFinite()) value.coerceIn(sensitivityRange) else 1f
    fun variation(value: Float) = if (value.isFinite()) value.coerceIn(variationRange) else 1f
    fun curve(value: Float) = if (value.isFinite()) value.coerceIn(curveRange) else 1f

    /**
     * Ready-made responses for the pressure section: how the raw pressure is bent ([curve]) and then
     * scaled ([sensitivity]). Choosing one only fills the sliders, so nothing is stored per stroke.
     */
    enum class Feel(val label: String, val curve: Float, val sensitivity: Float) {
        LIGHT("Light", .7f, 1.3f), NORMAL("Normal", 1f, 1f), FIRM("Firm", 1.5f, .9f);

        companion object {
            /** The preset a pair of settings matches, or null once either slider has been moved. */
            fun matching(curve: Float, sensitivity: Float): Feel? =
                entries.firstOrNull { kotlin.math.abs(it.curve - curve) < .005f && kotlin.math.abs(it.sensitivity - sensitivity) < .005f }
        }
    }

    /**
     * [curve] bends the raw reading first (so 0.5 reads a light touch as nearly full pressure and 2
     * needs a hard press), then [sensitivity] scales it. [speed] (see [SpeedWidth]) thins fast
     * strokes before the width variation applies. With the defaults the result is the plain reading.
     */
    fun sample(raw: Float, enabled: Boolean = true, sensitivity: Float = 1f, variation: Float = 1f,
        curve: Float = 1f, speed: Float = 1f): Float {
        if (!enabled && speed >= 1f) return 1f
        val reading = if (enabled && raw.isFinite()) raw.coerceAtLeast(0f) else 1f
        val bent = if (curve == 1f || reading == 0f) reading else reading.pow(curve(curve))
        val thinning = speed.coerceIn(.25f, 1f)
        val calibrated = (if (enabled) bent * sensitivity(sensitivity) * thinning else thinning).coerceIn(.25f, 1.8f)
        return (1f + (calibrated - 1f) * variation(variation)).coerceIn(.25f, 1.8f)
    }

    /**
     * The width a tilted stylus lays down: upright is the chosen width, and laid over towards the
     * page the tip broadens like the side of a chisel marker. [tiltRadians] is Android's AXIS_TILT
     * (0 upright, π/2 flat); a stylus that reports none (0) is unchanged.
     */
    fun tiltScale(tiltRadians: Float): Float {
        if (!tiltRadians.isFinite() || tiltRadians <= 0f) return 1f
        val lean = (tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        return 1f + .9f * lean * lean
    }

    /**
     * Fast strokes thinner, as with a real pen. One instance follows one stroke: speed is smoothed
     * over a few samples in screen pixels per millisecond, so the width eases rather than jumps, and
     * the first samples (no speed yet) stay full width.
     */
    class SpeedWidth(private val screenScale: Float) {
        private var x = 0f; private var y = 0f; private var time = 0L
        private var speed = 0f; private var started = false

        /** The factor (0.25..1, 1 = slow) for the sample at ([px], [py]) page units and [timeMs]. */
        fun factor(px: Float, py: Float, timeMs: Long): Float {
            if (started && timeMs > time && timeMs - time <= 100L) {
                val dt = (timeMs - time).toFloat()
                val instant = kotlin.math.hypot(px - x, py - y) * screenScale / dt
                speed += .25f * (instant - speed)
            } else speed = 0f   // first sample, a clock reset or a long pause: no speed to speak of
            started = true; x = px; y = py; time = timeMs
            val t = ((speed - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
            return 1f - .75f * t * t * (3f - 2f * t)
        }

        private companion object {
            /** Below this (px/ms, about 0.4 screen heights per second on a tablet) the pen keeps full width. */
            const val SLOW = .5f
            const val FAST = 4f
        }
    }
}
