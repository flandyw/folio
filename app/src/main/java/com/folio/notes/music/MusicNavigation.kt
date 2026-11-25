package com.folio.notes.music

/** Null means the forward turn has reached the next piece; a backward turn stays in this score. */
internal fun musicTurnTarget(start: Int, pages: Int, step: Int, forward: Boolean, overlap: Boolean = false): Int? {
    val last = maxOf(0, pages - step)
    val advance = if (overlap) 1 else step
    return if (forward && start >= last) null else (start + if (forward) advance else -advance).coerceIn(0, last)
}

internal fun musicTurnSeconds(value: Int): Int = value.coerceIn(5, 300)

/** Round up so the countdown never shows zero before the next turn is due. */
internal fun musicTurnCountdown(deadline: Long, now: Long): Int =
    ((deadline - now).coerceAtLeast(0) + 999L).div(1000L).toInt()

/** The zero-based page a repeat or coda typed as 1-based [input] lands on, or null if it is not another page of the score. */
internal fun musicJumpTarget(input: String, pages: Int, from: Int): Int? =
    input.trim().toIntOrNull()?.minus(1)?.takeIf { it in 0 until pages && it != from }
