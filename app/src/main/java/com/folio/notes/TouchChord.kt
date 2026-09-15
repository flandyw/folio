package com.folio.notes

import kotlin.math.hypot

/** Observes touch chords without delaying normal drawing or navigation. */
internal class TouchChord(private val slop: Float) {
    private val origins = mutableMapOf<Int, Pair<Float, Float>>()
    private var startedAt = 0L
    private var eligible = false
    private var fingers = 0

    fun reset() { origins.clear(); eligible = false; fingers = 0 }

    fun down(id: Int, x: Float, y: Float, time: Long) {
        reset()
        eligible = true
        startedAt = time
        origins[id] = x to y
        fingers = 1
    }

    fun join(id: Int, x: Float, y: Float, time: Long) {
        if (!eligible) return
        if (time - startedAt > 280 || origins.size >= 3) { eligible = false; return }
        origins[id] = x to y
        fingers = origins.size
    }

    fun move(id: Int, x: Float, y: Float) {
        val origin = origins[id] ?: return
        if (hypot(x - origin.first, y - origin.second) > slop) eligible = false
    }

    /** Returns the chord size only for a short, stationary two/three finger tap. */
    fun finish(time: Long): Int {
        val result = if (eligible && time - startedAt in 40L..420L && fingers in 2..3) fingers else 0
        reset()
        return result
    }
}
