package com.folio.notes

import kotlin.math.abs
import kotlin.math.roundToInt

/** Transient handwriting geometry; printed guide detection runs separately from input. */
data class WritingLane(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class WritingFollowState(
    val baselineY: Float? = null,
    val recent: List<WritingLane> = emptyList(),
    val suspendedUntil: Long = 0L,
    val completedGuide: WritingGuide? = null,
    val frontierLeft: Float? = null,
    val frontierRight: Float? = null,
    val candidateLane: WritingLane? = null,
    val liftedAt: Long? = null,
    val pendingGap: Long? = null,
    val writingGaps: List<Long> = emptyList()
)
enum class WritingHand(val direction: Float) { RIGHT(1f), LEFT(-1f) }

/** A requested or clamped glide must not replace the last movement that Back can undo. */
internal class FollowBackHistory {
    data class Entry(val x: Float, val y: Float, val state: WritingFollowState)
    var entry: Entry? = null
        private set
    private var pending: WritingFollowState? = null
    fun begin(state: WritingFollowState) { pending = state }
    fun cancelPending() { pending = null }
    fun clear() { entry = null; pending = null }
    fun moved(x: Float, y: Float) {
        if (x == 0f && y == 0f) return
        pending?.let { entry = Entry(0f, 0f, it); pending = null }
        entry = entry?.let { it.copy(x = it.x + x, y = it.y + y) }
    }
}

class WritingFollow {
    var state = WritingFollowState()
    /** Corrections behind the writing frontier must not move the page. */
    fun progresses(points: List<InkPoint>, direction: WritingDirection): Boolean {
        if (points.isEmpty()) return false
        val baseline = state.baselineY ?: return true
        val bottom = points.maxOf { it.y }
        // A confirmed new lane is handled by completed(); an isolated descender is not progress.
        if (abs(bottom - baseline) > maxOf(28f, laneHeight() * 1.5f)) return false
        // Keep the whole lane's frontier even after old strokes leave the median window.
        return if (direction == WritingDirection.LTR)
            state.frontierRight?.let { points.maxOf { p -> p.x } > it + 2f } ?: true
        else state.frontierLeft?.let { points.minOf { p -> p.x } < it - 2f } ?: true
    }

    /** Every touchdown cancels the request; every completed stroke starts a fresh quiet period. */
    fun penDown(now: Long) {
        state = state.copy(pendingGap = state.liftedAt?.let { now - it }?.takeIf { it in 1..2000 })
    }

    fun sameLineDelayMs(returnDelayMs: Int): Int {
        val gaps = state.writingGaps.sorted()
        // Learn normal pen-up gaps, not stroke duration or long thinking breaks. A high
        // percentile protects word spaces; a small buffer leaves time to touch down again.
        val learned = if (gaps.size >= 4) (gaps[(gaps.size - 1) * 3 / 4] + 200).toInt() else 500
        return maxOf(returnDelayMs.coerceIn(500, 2000), learned.coerceIn(500, 1400))
    }

    /** Keep a real dead band even when the preferred writing column is near an edge. */
    fun horizontalShift(fraction: Float, target: Float, direction: WritingDirection): Float {
        if (!fraction.isFinite() || !target.isFinite()) return 0f
        val destination = target.coerceIn(.1f, .9f)
        return if (direction == WritingDirection.LTR) {
            if (fraction > maxOf(.72f, destination + .15f).coerceAtMost(.95f))
                (destination - fraction).coerceAtMost(0f) else 0f
        } else {
            if (fraction < minOf(.28f, destination - .15f).coerceAtLeast(.05f))
                (destination - fraction).coerceAtLeast(0f) else 0f
        }
    }

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
        val changedLane = baseline != null && box.bottom - baseline > threshold
        val recent = if (changedLane) {
            val last = state.candidateLane
            if (last != null && abs(last.bottom - box.bottom) < threshold / 2)
                listOf(last, box)
            else { state = state.copy(candidateLane = box); return }
        } else {
            // Dots, revisiting earlier lines and isolated marks cannot contaminate the lane.
            if (baseline != null && baseline - box.bottom > threshold) return
            (state.recent + box).takeLast(12)
        }
        val ys = recent.map { it.bottom }.sorted()
        val gap = state.pendingGap
        state = state.copy(
            baselineY = ys[ys.size / 2], recent = recent, candidateLane = null,
            frontierLeft = if (changedLane) recent.minOf { it.left } else minOf(state.frontierLeft ?: box.left, box.left),
            frontierRight = if (changedLane) recent.maxOf { it.right } else maxOf(state.frontierRight ?: box.right, box.right),
            liftedAt = now, pendingGap = null,
            writingGaps = if (gap != null) (state.writingGaps + gap).takeLast(12) else state.writingGaps
        )
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
        state = state.copy(baselineY = advance.to.y, recent = emptyList(), completedGuide = advance.from,
            frontierLeft = null, frontierRight = null, candidateLane = null, liftedAt = null, pendingGap = null)
    }

    fun lineAdvanceProgress(liftedAt: Long, now: Long, durationMs: Int = DEFAULT_GLIDE_MS): Float =
        ((now - liftedAt).toFloat() / durationMs.coerceIn(120, 800).toFloat()).coerceIn(0f, 1f)

    companion object {
        /** Default carriage-return glide, in ms; exposed so settings can offer speeds. */
        const val DEFAULT_GLIDE_MS = 280
        /** Default pause after pen lift before an automatic return fires, in ms. */
        const val DEFAULT_RETURN_MS = 650
    }
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
    val spacing: Float = 32f,
    /** Pause after pen lift before an automatic return fires. Same-line follow uses at least 500 ms. */
    val returnDelayMs: Int = WritingFollow.DEFAULT_RETURN_MS,
    /** Carriage-return glide length. 120..800 ms. */
    val glideDurationMs: Int = WritingFollow.DEFAULT_GLIDE_MS,
) {
    /** Page units are A4 at 4 units per mm (840 x 1188), so users see millimetres. */
    val spacingMm: Float get() = spacing / UNITS_PER_MM
    /** Vertical writing height as a whole percent down the screen. */
    val positionPercent: Int get() = (position * 100).roundToInt()
    /** Horizontal writing column as a whole percent across the screen. */
    val horizontalPercent: Int get() = (horizontalPosition * 100).roundToInt()

    companion object {
        const val UNITS_PER_MM = 4f
        const val MIN_SPACING = 16f
        const val MAX_SPACING = 96f

        fun fromMm(mm: Float): Float = (mm * UNITS_PER_MM).coerceIn(MIN_SPACING, MAX_SPACING)

        /** Named line-spacing presets: raw page units paired with their mm label. */
        val spacingPresets: List<Pair<String, Float>> = listOf(
            "Narrow · 5 mm" to 20f,
            "Ruled · 7 mm" to 28f,
            "Comfortable · 8 mm" to 32f,
            "Roomy · 10 mm" to 40f,
            "Large · 12 mm" to 48f,
        )

        fun spacingLabel(spacing: Float): String {
            val mm = spacing / UNITS_PER_MM
            val name = when {
                spacing <= 22f -> "Narrow"
                spacing <= 30f -> "Ruled-like"
                spacing <= 35f -> "Comfortable"
                spacing <= 44f -> "Roomy"
                else -> "Large"
            }
            return "$name · ${"%.1f".format(mm)} mm"
        }

        fun positionLabel(position: Float): String = when {
            position < .42f -> "Near the top"
            position < .52f -> "Slightly high"
            position < .60f -> "Comfortable"
            position < .66f -> "Lower"
            else -> "Near the bottom"
        }

        fun horizontalLabel(fraction: Float): String = when {
            fraction < .43f -> "Left of centre"
            fraction <= .57f -> "Centred"
            else -> "Right of centre"
        }

        fun returnDelayLabel(ms: Int): String = "${"%.1f".format(ms / 1000f)} s"
        fun glideLabel(ms: Int): String = when {
            ms <= 170 -> "Snappy"
            ms <= 350 -> "Smooth"
            else -> "Gentle"
        }
    }
}

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
