package com.folio.notes.ink.pen

import kotlin.math.hypot

/** A hold belongs only to pen-down: once drawing starts, later pauses cannot change tools. */
internal class PenStartHold {
    private var x = 0f
    private var y = 0f
    private var since: Long? = null
    private var armed = false

    fun reset() { since = null; armed = false }

    fun begin(x: Float, y: Float, time: Long) {
        reset()
        if (!x.isFinite() || !y.isFinite()) return
        this.x = x; this.y = y; since = time
    }

    /** True when an armed hold leaves the slop: only then does the tool switch, so a long dot stays a dot. */
    fun sample(x: Float, y: Float, slop: Float): Boolean {
        val finite = x.isFinite() && y.isFinite()
        if (finite && hypot(x - this.x, y - this.y) <= slop) return false
        val moved = finite && armed
        reset()
        return moved
    }

    fun remaining(now: Long): Long? = since?.let { (it + DELAY_MS - now).coerceAtLeast(0L) }

    fun attempt(now: Long): Boolean {
        if (armed || remaining(now) != 0L) return false
        armed = true
        return true
    }

    companion object {
        const val DELAY_MS = 500L
        const val SLOP_DP = 8f
    }
}
