package com.folio.notes.ink.pen

import kotlin.math.hypot

/** A hold belongs only to pen-down: once drawing starts, later pauses cannot change tools. */
internal class PenStartHold {
    private var x = 0f
    private var y = 0f
    private var since: Long? = null

    fun reset() { since = null }

    fun begin(x: Float, y: Float, time: Long) {
        reset()
        if (!x.isFinite() || !y.isFinite()) return
        this.x = x; this.y = y; since = time
    }

    fun sample(x: Float, y: Float, slop: Float) {
        if (!x.isFinite() || !y.isFinite() || hypot(x - this.x, y - this.y) > slop) reset()
    }

    fun remaining(now: Long): Long? = since?.let { (it + DELAY_MS - now).coerceAtLeast(0L) }

    fun attempt(now: Long): Boolean {
        if (remaining(now) != 0L) return false
        reset()
        return true
    }

    companion object {
        const val DELAY_MS = 500L
        const val SLOP_DP = 8f
    }
}
