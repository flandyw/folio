package com.folio.notes

import kotlin.math.roundToInt

/**
 * The writer's own rhythm of pen lifts. Inside a word the pen lifts briefly; between words it
 * rests longer. A fixed wait before gliding is longer than a fluent writer's word pause, so every
 * touch-down cancelled the glide and the view only moved once they stopped. Waiting for a share of
 * *their* word pause, sooner after each cancelled wait and sooner still at the visible edge, makes
 * the glide happen while they are still writing. Pure, so the smoke check can trace it.
 */
class FollowRhythm {
    private val gaps = ArrayDeque<Long>()
    private var liftedAt: Long? = null
    private var lastGap: Long? = null
    private var candidateBreakGap: Long? = null
    private val returnGaps = ArrayDeque<Long>()
    /** Glides cancelled by touch-down before they moved, since the last glide that did. */
    var interruptions = 0
        private set

    fun lifted(now: Long) { liftedAt = now }

    /** A pen touch-down: the time since the last lift is one pause in the writer's rhythm. */
    fun touched(now: Long) {
        lastGap = null
        val lift = liftedAt ?: return
        liftedAt = null
        val gap = now - lift
        if (gap in MIN_GAP_MS..MAX_GAP_MS) {
            lastGap = gap
            gaps.addLast(gap)
            while (gaps.size > MAX_GAPS) gaps.removeFirst()
        }
    }

    fun interrupted() { interruptions = (interruptions + 1).coerceAtMost(MAX_INTERRUPTIONS) }
    fun glided() { interruptions = 0 }
    /** Navigation or a page change; the rhythm itself is the writer's and is kept. */
    fun reset() { liftedAt = null; lastGap = null; candidateBreakGap = null; interruptions = 0 }

    /** A natural line is confirmed by later ink; retain the pause before its first stroke. */
    fun possibleLineBreak() { if (candidateBreakGap == null) candidateBreakGap = lastGap }
    fun discardLineBreak() { candidateBreakGap = null }

    /** Confirmed natural line breaks teach a separate, conservative return cadence. */
    fun lineBreak() {
        (candidateBreakGap ?: lastGap)?.let {
            returnGaps.addLast(it)
            while (returnGaps.size > 12) returnGaps.removeFirst()
        }
        lastGap = null; candidateBreakGap = null
    }

    /** Horizontal urgency and interrupted glides must never rush a line return. */
    fun returnDelay(configuredMs: Int): Int {
        val configured = configuredMs.coerceIn(300, 2000)
        if (returnGaps.size < 3) return configured
        val sorted = returnGaps.sorted()
        val observed = sorted[((sorted.size - 1) * WORD_QUANTILE).roundToInt()]
        return maxOf(configured, (observed * 1.2f).roundToInt()).coerceAtMost(2000)
    }

    /** The pause between words: the upper part of the pause distribution, once there is enough of it. */
    fun wordPause(): Long? {
        if (gaps.size < MIN_GAPS) return null
        val sorted = gaps.sorted()
        return sorted[((sorted.size - 1) * WORD_QUANTILE).roundToInt()]
    }

    /**
     * Wait before a sideways glide or placement: never longer than [configuredMs], a share of the
     * writer's word pause, shortened by each cancelled wait and by [urgency] (0 = plenty of room,
     * 1 = the writing is at the visible edge).
     */
    fun glideDelay(configuredMs: Int, urgency: Float = 0f): Int {
        val ceiling = configuredMs.coerceAtLeast(MIN_DELAY_MS)
        var delay = ceiling.toFloat()
        wordPause()?.let { delay = minOf(delay, it * PAUSE_SHARE) }
        repeat(interruptions) { delay *= INTERRUPTION_FACTOR }
        delay *= 1f - (if (urgency.isFinite()) urgency.coerceIn(0f, 1f) else 0f) * URGENCY_SHARE
        return delay.roundToInt().coerceIn(MIN_DELAY_MS, ceiling)
    }

    companion object {
        const val MIN_GAP_MS = 30L
        /** Longer lifts are thinking or reading, not part of the writing rhythm. */
        const val MAX_GAP_MS = 1500L
        const val MAX_GAPS = 32
        const val MIN_GAPS = 6
        const val WORD_QUANTILE = .75f
        const val PAUSE_SHARE = .7f
        const val INTERRUPTION_FACTOR = .65f
        const val MAX_INTERRUPTIONS = 4
        const val URGENCY_SHARE = .75f
        const val MIN_DELAY_MS = 90

        /**
         * How close writing is to running out of visible room: 0 at the follow trigger, 1 at the
         * edge, on whichever axis is closer ([horizontal]/[vertical] are fractions across/down the view).
         */
        fun urgency(horizontal: Float, vertical: Float, edgeThreshold: Float, direction: WritingDirection): Float {
            val edge = edgeThreshold.coerceIn(.55f, .95f)
            val across = if (direction == WritingDirection.LTR) horizontal else 1f - horizontal
            val x = if (across.isFinite()) ((across - edge) / (.97f - edge).coerceAtLeast(.01f)) else 0f
            val y = if (vertical.isFinite()) (vertical - .82f) / .14f else 0f
            return maxOf(x, y).coerceIn(0f, 1f)
        }
    }
}
