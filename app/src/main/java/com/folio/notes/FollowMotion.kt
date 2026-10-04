package com.folio.notes

/**
 * One eased camera move, in screen pixels. Requested and actually applied travel stay separate,
 * because a document clamps at its edges and Back must only undo what really moved.
 */
internal class FollowMotion {
    data class Step(val dx: Float, val dy: Float, val finished: Boolean)

    var active = false
        private set
    var appliedX = 0f
        private set
    var appliedY = 0f
        private set
    val moved get() = appliedX != 0f || appliedY != 0f
    private var x = 0f
    private var y = 0f
    private var doneX = 0f
    private var doneY = 0f
    private var startedAt: Long? = null
    private var duration = 300
    private var settling = false

    /**
     * [settling] eases out instead of in and out: it covers most of the distance at once and lands
     * softly, so a move made with the pen about to run out of room still helps if the next stroke
     * cuts it short.
     */
    fun start(dx: Float, dy: Float, durationMs: Int, settling: Boolean = false) {
        reset()
        this.settling = settling
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return
        x = dx; y = dy
        duration = durationMs.coerceIn(MIN_MS, MAX_MS)
        active = true
    }

    fun step(now: Long): Step {
        if (!active) return Step(0f, 0f, true)
        // A late first frame starts the curve there, rather than jumping part of the way.
        val start = startedAt ?: now.also { startedAt = it }
        val t = ((now - start).toFloat() / duration).coerceIn(0f, 1f)
        val eased = if (settling) 1f - (1f - t) * (1f - t) * (1f - t) else t * t * t * (t * (t * 6f - 15f) + 10f)
        val step = Step(x * eased - doneX, y * eased - doneY, t >= 1f)
        doneX = x * eased; doneY = y * eased
        return step
    }

    fun applied(dx: Float, dy: Float) { appliedX += dx; appliedY += dy }

    fun reset() {
        active = false
        x = 0f; y = 0f; doneX = 0f; doneY = 0f; appliedX = 0f; appliedY = 0f
        startedAt = null
    }

    companion object {
        const val MIN_MS = 120
        const val MAX_MS = 800
    }
}
