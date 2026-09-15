package com.folio.notes

import kotlin.math.hypot

/**
 * Decides when a pan may start moving the document.
 *
 * A hand resting on the page before the pen tip lands reports a contact that wanders as the hand
 * settles, and a pan following it drags the page out from under the writer before the first stroke
 * begins — the pen then lands somewhere else than where it was aimed. A finger-driven pan in
 * pen-only mode therefore waits for its own slop before it moves anything: a deliberate scroll
 * crosses it within the first few millimetres, a settling hand does not. Pans that are deliberate
 * from the first pixel — the hand tool, two fingers, the pen itself — are never held back.
 */
internal class PanGate(private val slop: Float) {
    private var originX = 0f
    private var originY = 0f
    private var waiting = false

    /** Whether the pan is still held back, so a release must not fling the document either. */
    val waitingForSlop: Boolean get() = waiting

    /** Arms a gesture at the contact's origin. */
    fun arm(waitForSlop: Boolean, x: Float, y: Float) {
        waiting = waitForSlop
        originX = x
        originY = y
    }

    /** Deliberate navigation started — a second finger, or the pen — so nothing is held back. */
    fun release() { waiting = false }

    /** True once the contact has travelled far enough to read as a deliberate drag. */
    fun moved(x: Float, y: Float): Boolean {
        if (waiting && hypot(x - originX, y - originY) > slop) waiting = false
        return !waiting
    }
}
