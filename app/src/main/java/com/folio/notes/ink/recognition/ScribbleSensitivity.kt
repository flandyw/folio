package com.folio.notes.ink.recognition

import kotlin.math.*

/** Higher values accept shorter, less exact scrubs; coverage of existing ink is always required. */
object ScribbleSensitivity {
    const val DEFAULT = 0.5f
    fun normalize(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT
    fun reversals(value: Float): Int = when {
        normalize(value) < .25f -> 4
        normalize(value) < .8f -> 3
        else -> 2
    }
    fun passes(value: Float) = if (normalize(value) < .25f) 3 else 2
    /** Share of a stroke's length the scrub must wipe before the whole stroke goes. */
    fun coverage(value: Float) = .5f - .15f * normalize(value)
}
