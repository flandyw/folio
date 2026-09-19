package com.folio.notes

import kotlin.math.abs

/** Transient handwriting geometry; printed guide detection runs separately from input. */
data class WritingLane(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class WritingFollowState(
    val baselineY: Float? = null,
    val recent: List<WritingLane> = emptyList(),
    val suspendedUntil: Long = 0L,
    val completedGuide: WritingGuide? = null
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
    /** A return is possible only at a detected rule's endpoint with a real rule below it. */
    fun advanceFor(points: List<InkPoint>, guides: List<WritingGuide>, zoom: Float,
                   hand: WritingHand, now: Long): WritingAdvance? {
        if (zoom < 1.4f || now < state.suspendedUntil || points.isEmpty()) return null
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        val top = points.minOf { it.y }
        val line = guides.filter { left >= it.left - 6f && right <= it.right + 6f && abs(bottom - it.y) <= 10f }
            .minByOrNull { abs(bottom - it.y) } ?: return null
        if (line == state.completedGuide) return null
        val next = WritingGuides.next(line, guides) ?: return null
        // Tall marks and long strokes are diagrams/underlines, not the end of a word.
        if (bottom - top > (next.y - line.y) * .9f || right - left > 80f) return null
        val atEnd = if (hand == WritingHand.RIGHT) right >= line.right - 4f else left <= line.left + 4f
        return if (atEnd) WritingAdvance(line, next) else null
    }

    fun arrived(advance: WritingAdvance) {
        state = state.copy(baselineY = advance.to.y, recent = emptyList(), completedGuide = advance.from)
    }

    fun lineAdvanceProgress(liftedAt: Long, now: Long): Float =
        ((now - liftedAt).toFloat() / 280f).coerceIn(0f, 1f)
}

/** User intent is independent of the hand holding the pen. */
enum class WritingDirection { LTR, RTL }
enum class FollowMode { TEXT, MATH }
data class FollowPreferences(
    val direction: WritingDirection = WritingDirection.LTR,
    val mode: FollowMode = FollowMode.TEXT,
    val automaticReturn: Boolean = false,
    val position: Float = .55f,
    val horizontalPosition: Float = .5f,
    val spacing: Float = 32f
)

object FollowNavigation {
    fun isTextStroke(points: List<InkPoint>, spacing: Float): Boolean = points.isNotEmpty() &&
        points.maxOf { it.y } - points.minOf { it.y } < spacing * .9f &&
        points.maxOf { it.x } - points.minOf { it.x } < 80f

    fun next(baseline: Float, region: WritingLane, guides: List<WritingGuide>, spacing: Float): WritingAdvance? {
        if (!baseline.isFinite() || baseline > region.bottom) return null
        val currentY = baseline.coerceAtLeast(region.top)
        val eligible = guides.filter { it.left >= region.left - 6 && it.right <= region.right + 6 && it.y in region.top..region.bottom }
        val current = eligible.minByOrNull { abs(it.y - currentY) }?.takeIf { abs(it.y - currentY) <= 16f }
        val next = current?.let { WritingGuides.next(it, eligible) }
        // A detected answer block ends at its last rule. Do not spill into the next question.
        if (current != null && next == null) return null
        val y = next?.y ?: (currentY + spacing.coerceIn(16f, 96f))
        if (y > region.bottom) return null
        return WritingAdvance(current ?: WritingGuide(region.left, region.right, baseline),
            next ?: WritingGuide(region.left, region.right, y))
    }
    fun nearEnd(points: List<InkPoint>, region: WritingLane, direction: WritingDirection): Boolean {
        if (points.isEmpty()) return false
        val margin = ((region.right - region.left) * .08f).coerceIn(16f, 48f)
        return if (direction == WritingDirection.LTR) points.maxOf { it.x } >= region.right - margin
        else points.minOf { it.x } <= region.left + margin
    }
}
