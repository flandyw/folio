package com.folio.notes

import kotlin.math.abs

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
    private var startedAt: Long? = null
    private var duration = 280

    val moved get() = appliedX != 0f || appliedY != 0f
    val reachedLine get() = abs(y - appliedY) < .75f

    fun start(dx: Float, dy: Float, now: Long, delayMs: Int, durationMs: Int) {
        cancel()
        if (!dx.isFinite() || !dy.isFinite()) return
        x = dx; y = dy
        dueAt = now + delayMs.coerceAtLeast(0)
        duration = durationMs.coerceIn(120, 800)
        active = true
    }

    fun step(now: Long): Step {
        if (!active) return Step(finished = true)
        if (now < dueAt) return Step(waitMs = dueAt - now)
        // A delayed/busy first frame starts at zero, rather than jumping halfway through.
        val start = startedAt ?: now.also { startedAt = it }
        val t = ((now - start).toFloat() / duration).coerceIn(0f, 1f)
        val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
        val targetX = x * eased
        val targetY = y * eased
        val step = Step(targetX - doneX, targetY - doneY, finished = t >= 1f || (x == 0f && y == 0f))
        doneX = targetX; doneY = targetY
        return step
    }

    fun applied(dx: Float, dy: Float) { appliedX += dx; appliedY += dy }

    fun cancel() {
        active = false
        x = 0f; y = 0f; doneX = 0f; doneY = 0f; appliedX = 0f; appliedY = 0f
        startedAt = null
    }
}
