package com.folio.notes

import kotlin.math.abs

/** Transient geometry only. Printed guides can be supplied later without coupling input to PDF IO. */
data class WritingLane(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class WritingFollowState(
    val baselineY: Float? = null,
    val recent: List<WritingLane> = emptyList(),
    val suspendedUntil: Long = 0L
)
enum class WritingHand(val direction: Float) { RIGHT(1f), LEFT(-1f) }

class WritingFollow {
    var state = WritingFollowState()
    fun suspend(now: Long) { state = WritingFollowState(suspendedUntil = now + 1500) }
    fun completed(points: List<InkPoint>, now: Long) {
        if (now < state.suspendedUntil || points.isEmpty()) return
        val box = WritingLane(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        // Diagrams and tall flourishes must not pull a handwriting lane down the page.
        val heights = state.recent.map { it.bottom - it.top }.sorted()
        val height = heights.getOrNull(heights.size / 2)?.coerceAtLeast(12f) ?: 24f
        if (box.bottom - box.top > maxOf(90f, height * 3f) || box.right - box.left > 240f) return
        val baseline = state.baselineY
        val threshold = maxOf(28f, height * 1.5f)
        val recent = if (baseline != null && abs(box.bottom - baseline) > threshold) {
            // Two completed strokes in the new cluster establish a new line, never one descender.
            val last = state.recent.lastOrNull()
            if (last != null && abs(last.bottom - box.bottom) < threshold / 2 && abs(last.bottom - baseline) > threshold)
                listOf(last, box)
            else { state = state.copy(recent = (state.recent + box).takeLast(12)); return }
        } else (state.recent + box).takeLast(12)
        val ys = recent.map { it.bottom }.sorted()
        state = state.copy(baselineY = ys[ys.size / 2], recent = recent)
    }
    fun horizontalVelocity(xFraction: Float, zoom: Float, hand: WritingHand, progressing: Boolean, now: Long): Float {
        if (zoom < 1.4f || !progressing || now < state.suspendedUntil || state.baselineY == null) return 0f
        val edge = if (hand == WritingHand.RIGHT) xFraction else 1f - xFraction
        val strength = ((edge - .65f) / .35f).coerceIn(0f, 1f)
        return -hand.direction * 180f * strength * strength
    }
    fun verticalVelocity(baselineFraction: Float, zoom: Float, now: Long): Float {
        if (zoom < 1.4f || now < state.suspendedUntil) return 0f
        return when { baselineFraction > .75f -> -160f; baselineFraction < .35f -> 160f; else -> 0f }
    }
    /** Median stroke height of the current lane, in page units; drives line spacing. */
    fun laneHeight(): Float {
        val heights = state.recent.map { it.bottom - it.top }.sorted()
        return heights.getOrNull(heights.size / 2)?.coerceAtLeast(12f) ?: 24f
    }
    /** Distance to drop the page for one handwritten line, in page units. */
    fun estimateSpacing(): Float = maxOf(48f, laneHeight() * 2.5f)
    /**
     * True when the pen lifted near the trailing edge of a zoomed-in line, so the view
     * should carriage-return to the next line's start instead of sitting at the margin.
     */
    fun shouldAdvance(tipFraction: Float, zoom: Float, hand: WritingHand, now: Long): Boolean {
        if (zoom < 1.4f || now < state.suspendedUntil || state.baselineY == null) return false
        val edge = if (hand == WritingHand.RIGHT) tipFraction else 1f - tipFraction
        return edge > .78f
    }
    /** Leftmost (or rightmost for left-hand) x of the current line, in page units. */
    fun lineStart(hand: WritingHand): Float? {
        val baseline = state.baselineY ?: return null
        val threshold = maxOf(28f, laneHeight() * 1.5f)
        val sameLine = state.recent.filter { abs(it.bottom - baseline) <= threshold }
        if (sameLine.isEmpty()) return null
        return if (hand == WritingHand.RIGHT) sameLine.minOf { it.left } else sameLine.maxOf { it.right }
    }
}
