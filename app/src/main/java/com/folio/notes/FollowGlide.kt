package com.folio.notes

import kotlin.math.abs
import kotlin.math.ceil

/** Pure pen-up motion. Requested travel and actual (possibly clamped) travel stay separate. */
internal class FollowGlide {
    data class Step(val dx: Float = 0f, val dy: Float = 0f, val waitMs: Long = 0L, val finished: Boolean = false)
    var active = false
        private set
    private var x = 0f
    private var y = 0f
    private var doneX = 0f
    private var doneY = 0f
    private var appliedX = 0f
    private var appliedY = 0f
    private var dueAt = 0L
    private var lastFrameAt: Long? = null
    private var elapsedMs = 0L
    private var duration = 280

    val moved get() = appliedX != 0f || appliedY != 0f
    val reachedLine get() = abs(y - appliedY) < .75f

    fun start(dx: Float, dy: Float, now: Long, delayMs: Int, durationMs: Int,
              viewportWidth: Float, viewportHeight: Float, msPerViewport: Float = MS_PER_VIEWPORT,
              instant: Boolean = false) {
        cancel()
        if (!dx.isFinite() || !dy.isFinite() || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
            viewportWidth <= 0f || viewportHeight <= 0f) return
        x = dx; y = dy
        dueAt = now + delayMs.coerceAtLeast(0)
        // A half-screen pan needs at least 350 ms, regardless of resolution or zoom.
        // The configured duration is a minimum; long trips must not become faster trips.
        val travel = maxOf(abs(dx) / viewportWidth, abs(dy) / viewportHeight)
        // With system animations off (reduced motion), the move lands on the first drawn frame.
        duration = if (instant) 1 else
            maxOf(durationMs.coerceIn(120, 800), ceil(travel * msPerViewport.coerceIn(MIN_MS_PER_VIEWPORT, MAX_MS_PER_VIEWPORT)).toInt())
        active = true
    }

    fun step(now: Long): Step {
        if (!active) return Step(finished = true)
        if (now < dueAt) return Step(waitMs = dueAt - now)
        // Begin at zero and slow down through missed frames instead of catching up in a jump.
        val previous = lastFrameAt
        if (previous != null) elapsedMs += (now - previous).coerceIn(0L, 32L)
        lastFrameAt = now
        val t = (elapsedMs.toFloat() / duration).coerceIn(0f, 1f)
        val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
        val targetX = x * eased
        val targetY = y * eased
        val step = Step(targetX - doneX, targetY - doneY, finished = t >= 1f || (x == 0f && y == 0f))
        doneX = targetX; doneY = targetY
        return step
    }

    companion object {
        const val MS_PER_VIEWPORT = 700f
        const val MIN_MS_PER_VIEWPORT = 250f
        const val MAX_MS_PER_VIEWPORT = 1500f
    }

    fun applied(dx: Float, dy: Float) { appliedX += dx; appliedY += dy }

    fun cancel() {
        active = false
        x = 0f; y = 0f; doneX = 0f; doneY = 0f; appliedX = 0f; appliedY = 0f
        lastFrameAt = null; elapsedMs = 0L
    }
}
