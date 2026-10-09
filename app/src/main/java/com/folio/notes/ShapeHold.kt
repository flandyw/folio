package com.folio.notes

import kotlin.math.hypot

/** A pause at the tip, in screen pixels. Motion restarts the pause; jitter does not. */
internal class ShapeHold {
    private var anchorX = 0f
    private var anchorY = 0f
    private var since: Long? = null
    private var attempted = false

    fun reset() { since = null; attempted = false }

    /** Returns true when the pen travelled far enough to resume freehand drawing. */
    fun sample(x: Float, y: Float, time: Long, slop: Float): Boolean {
        if (since == null || hypot(x - anchorX, y - anchorY) > slop) {
            anchorX = x; anchorY = y; since = time; attempted = false
            return true
        }
        return false
    }

    fun remaining(now: Long, delayMs: Long): Long? =
        since?.takeUnless { attempted }?.let { (delayMs - (now - it)).coerceAtLeast(0L) }

    fun attempt(now: Long, delayMs: Long): Boolean {
        if (remaining(now, delayMs) != 0L) return false
        attempted = true
        return true
    }
}
