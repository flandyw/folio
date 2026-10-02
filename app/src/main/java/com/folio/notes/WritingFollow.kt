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
    val candidateAt: Long? = null,
    val lineStartX: Float? = null,
    val lineSpacings: List<Float> = emptyList(),
    val liftedAt: Long? = null,
    val pendingGap: Long? = null,
    val writingGaps: List<Long> = emptyList()
)
enum class WritingHand(val direction: Float) { RIGHT(1f), LEFT(-1f) }
enum class WritingProgress { NONE, SAME_LINE, NEW_LINE }
data class WritingFollowStatus(
    val message: String = "Write to start following",
    val paused: Boolean = false,
    val canGoBack: Boolean = false,
)

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
        if (points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
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

    fun sameLineDelayMs(returnDelayMs: Int, adaptive: Boolean = true): Int {
        if (!adaptive) return returnDelayMs.coerceIn(300, 2000)
        val gaps = state.writingGaps.sorted()
        // Learn normal pen-up gaps, not stroke duration or long thinking breaks. A high
        // percentile protects word spaces; a small buffer leaves time to touch down again.
        // The floor stays near letter gaps (not word gaps) so fast writers still get a
        // glide between words instead of outrunning the view.
        val learned = if (gaps.size >= 4) (gaps[(gaps.size - 1) * 3 / 4] + 200).toInt() else 350
        return maxOf(returnDelayMs.coerceIn(300, 2000), learned.coerceIn(300, 1400))
    }

    /** Keep a real dead band even when the preferred writing column is near an edge. */
    fun horizontalShift(fraction: Float, target: Float, direction: WritingDirection, edgeThreshold: Float = .72f): Float {
        if (!fraction.isFinite() || !target.isFinite()) return 0f
        val destination = target.coerceIn(.1f, .9f)
        return if (direction == WritingDirection.LTR) {
            if (fraction > maxOf(edgeThreshold.coerceIn(.55f, .95f), destination + .08f).coerceAtMost(.95f))
                (destination - fraction).coerceAtMost(0f) else 0f
        } else {
            if (fraction < minOf(1f - edgeThreshold.coerceIn(.55f, .95f), destination - .08f).coerceAtLeast(.05f))
                (destination - fraction).coerceAtLeast(0f) else 0f
        }
    }

    fun suspend(now: Long) {
        // Navigation changes the writing location, but not the writer's rhythm.
        state = WritingFollowState(suspendedUntil = now + 1500, writingGaps = state.writingGaps,
            lineSpacings = state.lineSpacings)
    }

    /** Learn spacing only from confirmed natural line breaks; a skipped line cannot set the pace. */
    fun lineSpacing(fallback: Float, adaptive: Boolean = true): Float {
        val learned = state.lineSpacings.sorted()
        return if (adaptive && learned.size >= 2) learned[(learned.size - 1) / 2]
            else fallback.coerceIn(FollowPreferences.MIN_SPACING, FollowPreferences.MAX_SPACING)
    }

    /** A lone mark at the edge is not evidence that the writer has finished a line. */
    fun readyForReturn(): Boolean {
        val left = state.frontierLeft ?: return false
        val right = state.frontierRight ?: return false
        return state.recent.size >= 2 && right - left >= maxOf(32f, laneHeight() * 2f)
    }

    private fun overlapsBody(box: WritingLane): Boolean {
        val baseline = state.baselineY ?: return false
        val height = laneHeight()
        val tops = state.recent.map { it.top }.sorted()
        val top = tops.getOrNull(tops.size / 2) ?: (baseline - height)
        return box.top <= top + height * .35f && box.bottom >= baseline - height * .25f
    }

    /** A letter spanning the current body may also have an ascender and a descender. */
    fun isTextStroke(points: List<InkPoint>, spacing: Float): Boolean {
        if (points.isEmpty()) return false
        val box = WritingLane(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        val allowance = if (overlapsBody(box)) minOf(laneHeight(), spacing * .5f) else 0f
        return FollowNavigation.isTextStroke(points, spacing, allowance)
    }

    fun completed(points: List<InkPoint>, now: Long, preferences: FollowPreferences = FollowPreferences()): WritingProgress {
        if (now < state.suspendedUntil || points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return WritingProgress.NONE
        val box = WritingLane(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        val strokeHeight = box.bottom - box.top
        val strokeWidth = box.right - box.left
        // Detached dots must not establish a lane, trigger movement or teach word gaps.
        if (strokeHeight < 3f && strokeWidth < 3f) return WritingProgress.NONE
        val baseline = state.baselineY
        if (preferences.mode == FollowMode.MATH) {
            // Fractions, radicals and long equations reveal room below the working without
            // guessing text lines or changing the horizontal position.
            if (baseline != null && box.bottom <= baseline + 2f) return WritingProgress.NONE
            accept(box, listOf(box), now, changedLane = false, preferences.direction)
            return WritingProgress.SAME_LINE
        }
        // Diagrams and tall flourishes must not pull a handwriting lane down the page.
        val height = laneHeight()
        if (!isTextStroke(points, maxOf(preferences.spacing, height * 1.5f))) return WritingProgress.NONE
        val spacing = lineSpacing(preferences.spacing, preferences.adaptiveSpacing)
        val threshold = minOf(spacing * .55f, height).coerceAtLeast(10f)
        if (baseline != null && strokeHeight < height * .55f && box.top > baseline - height * .25f)
            return WritingProgress.NONE
        // A descender still spans the old body: keep following its horizontal progress,
        // without teaching its bottom as a new baseline. A tall capital on the next line
        // can span that body too, but returning behind the frontier permits confirmation.
        val returned = if (preferences.direction == WritingDirection.LTR)
            state.frontierRight?.let { box.right < it - maxOf(32f, height * 2f) } == true
        else state.frontierLeft?.let { box.left > it + maxOf(32f, height * 2f) } == true
        val changedLane = baseline != null && box.bottom - baseline > threshold && box.top >= baseline - height &&
            (!overlapsBody(box) || returned)
        val recent = if (changedLane) {
            val last = state.candidateLane
            // A thinking pause between the first and second stroke of a new line must not
            // force a third stroke to confirm it.
            val fresh = state.candidateAt?.let { now - it in 0..15000 } == true
            // Any second stroke on the same new level confirms the line. Requiring it to
            // extend further right than the first fails for short second words ("a" after
            // "Hello") and makes single-word lines need three strokes.
            if (last != null && fresh && abs(last.bottom - box.bottom) <= maxOf(6f, threshold * .6f))
                listOf(last, box)
            else { state = state.copy(candidateLane = box, candidateAt = now, pendingGap = null); return WritingProgress.NONE }
        } else {
            // Corrections must hold the view but must not kill a new line in progress.
            // Only continuing the old line (extending its frontier at the old level)
            // proves the staged drop was a descender and discards it.
            val progresses = if (preferences.direction == WritingDirection.LTR)
                state.frontierRight?.let { box.right > it + 2f } ?: true
            else state.frontierLeft?.let { box.left < it - 2f } ?: true
            val continuedOldLine = baseline != null && progresses &&
                abs(box.bottom - baseline) <= threshold
            if (continuedOldLine) {
                state = state.copy(candidateLane = null, candidateAt = null, pendingGap = null)
            } else if (baseline != null && (baseline - box.bottom > height * .8f || !progresses)) {
                // Dotting an "i" or touching up the previous line holds the view but keeps
                // the staged new-line candidate so the next stroke below still confirms.
                state = state.copy(pendingGap = null)
                return WritingProgress.NONE
            }
            if (baseline != null && box.bottom - baseline > threshold) state.recent
                else (state.recent + box).takeLast(12)
        }
        accept(box, recent, now, changedLane, preferences.direction)
        return if (changedLane) WritingProgress.NEW_LINE else WritingProgress.SAME_LINE
    }

    private fun accept(box: WritingLane, recent: List<WritingLane>, now: Long, changedLane: Boolean, direction: WritingDirection) {
        val ys = recent.map { it.bottom }.sorted()
        val baseline = ys.getOrNull(ys.size / 2) ?: state.baselineY ?: box.bottom
        val gapY = state.baselineY?.let { baseline - it }?.takeIf {
            changedLane && it in FollowPreferences.MIN_SPACING..FollowPreferences.MAX_SPACING
        }
        val gap = state.pendingGap
        state = state.copy(
            baselineY = baseline, recent = recent, candidateLane = null, candidateAt = null,
            lineStartX = if (changedLane || state.lineStartX == null)
                if (direction == WritingDirection.LTR) (recent.firstOrNull() ?: box).left else (recent.firstOrNull() ?: box).right
                else state.lineStartX,
            lineSpacings = if (gapY != null) (state.lineSpacings + gapY).takeLast(6) else state.lineSpacings,
            frontierLeft = if (changedLane) recent.minOf { it.left } else minOf(state.frontierLeft ?: box.left, box.left),
            frontierRight = if (changedLane) recent.maxOf { it.right } else maxOf(state.frontierRight ?: box.right, box.right),
            liftedAt = now, pendingGap = null,
            writingGaps = if (gap != null && !changedLane) (state.writingGaps + gap).takeLast(12) else state.writingGaps
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
            frontierLeft = null, frontierRight = null, candidateLane = null, candidateAt = null, liftedAt = null, pendingGap = null)
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
    /** Pause after pen lift before an automatic return fires. Same-line follow uses at least 300 ms. */
    val returnDelayMs: Int = WritingFollow.DEFAULT_RETURN_MS,
    /** Carriage-return glide length. 120..800 ms. */
    val glideDurationMs: Int = WritingFollow.DEFAULT_GLIDE_MS,
    val adaptiveTiming: Boolean = true,
    val adaptiveSpacing: Boolean = true,
    val horizontalFollow: Boolean = true,
    val verticalFollow: Boolean = true,
    val autoSwitchAreas: Boolean = true,
    val minimumZoom: Float = 1.4f,
    val edgeThreshold: Float = .72f,
    val verticalDeadBand: Float = .15f,
    val endMargin: Float = .08f,
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
    /** A lane narrower than this is a mistake, not a column; returns never aim that far in. */
    const val MIN_LANE_UNITS = 48f

    fun isTextStroke(points: List<InkPoint>, spacing: Float, descenderAllowance: Float = 0f): Boolean = points.isNotEmpty() &&
        points.all { it.x.isFinite() && it.y.isFinite() } && spacing.isFinite() && spacing > 0f &&
        points.maxOf { it.y } - points.minOf { it.y } < spacing * .9f + descenderAllowance.coerceIn(0f, spacing * .5f) &&
        // A cursive word in one stroke is easily 30-50 mm wide; only very long
        // underlines/diagrams (55+ mm) are rejected here.
        points.maxOf { it.x } - points.minOf { it.x } < 220f

    /**
     * The writing lane on an unbounded canvas: the visible viewport inset by the end margin,
     * with the writer's own start column kept as the leading edge while it is still in view.
     *
     * An infinite page carries a nominal page box it is not bound by, so a lane measured from
     * page coordinates puts "end of line" somewhere the writer cannot see. Anchoring the lane
     * to the viewport instead makes the line end the visible edge at every zoom, which is what
     * lets automatic line return work on a canvas at all.
     *
     * The trailing edge is measured from the start column plus the viewport width, not from
     * the current viewport edge, so following pans do not chase the end of the line away:
     * same-line pans move the viewport but leave the page-space line end where it was, and
     * writing reliably reaches it.
     */
    fun infiniteRegion(viewport: WritingLane, direction: WritingDirection, startX: Float? = null,
                       endMargin: Float = .08f): WritingLane {
        val width = viewport.right - viewport.left
        if (!width.isFinite() || width <= 0f) return viewport
        val margin = (width * endMargin.coerceIn(.02f, .2f)).coerceIn(8f, 96f)
        // A full viewport lane inset on both sides; the trailing edge below derives from
        // this width so pans never move it.
        val laneWidth = maxOf(width - 2f * margin, MIN_LANE_UNITS)
        // A column started long ago may be far off screen; fall back to the viewport edge then.
        val remembered = startX?.takeIf {
            it.isFinite() && it in (viewport.left - width * .25f)..(viewport.right + width * .25f)
        }
        val (left, right) = if (direction == WritingDirection.LTR) {
            if (remembered != null) remembered to remembered + laneWidth
            else {
                val trailing = viewport.right - margin
                (viewport.left + margin).coerceAtMost(trailing - MIN_LANE_UNITS) to trailing
            }
        } else {
            if (remembered != null) remembered - laneWidth to remembered
            else {
                val leading = viewport.left + margin
                leading to (viewport.right - margin).coerceAtLeast(leading + MIN_LANE_UNITS)
            }
        }
        return WritingLane(left, viewport.top, right, Float.MAX_VALUE)
    }

    fun next(baseline: Float, region: WritingLane, guides: List<WritingGuide>, spacing: Float,
             lineStartX: Float? = null, direction: WritingDirection = WritingDirection.LTR): WritingAdvance? {
        if (!baseline.isFinite() || baseline > region.bottom) return null
        val currentY = baseline.coerceAtLeast(region.top)
        val eligible = guides.filter { it.left >= region.left - 6 && it.right <= region.right + 6 && it.y in region.top..region.bottom }
        val current = eligible.minByOrNull { abs(it.y - currentY) }?.takeIf { abs(it.y - currentY) <= 16f }
        val next = current?.let { WritingGuides.next(it, eligible) }
        // A detected answer block ends at its last rule. Do not spill into the next question.
        if (current != null && next == null) return null
        val y = next?.y ?: (currentY + spacing.coerceIn(16f, 96f))
        if (y > region.bottom) return null
        // Printed rules keep their margins. On blank pages return to where writing began.
        val start = lineStartX?.takeIf { it.isFinite() }?.coerceIn(region.left, region.right)
        val left = if (direction == WritingDirection.LTR) start ?: region.left else region.left
        val right = if (direction == WritingDirection.RTL) start ?: region.right else region.right
        return WritingAdvance(current ?: WritingGuide(left, right, baseline),
            next ?: WritingGuide(left, right, y))
    }
    fun nearEnd(points: List<InkPoint>, region: WritingLane, direction: WritingDirection, endMargin: Float = .08f): Boolean {
        if (points.isEmpty()) return false
        val margin = ((region.right - region.left) * endMargin.coerceIn(.02f, .2f)).coerceIn(8f, 96f)
        return if (direction == WritingDirection.LTR) points.maxOf { it.x } >= region.right - margin
        else points.minOf { it.x } <= region.left + margin
    }
}

/**
 * How big writing has to look on screen before following the view is worth doing.
 *
 * A page has an absolute scale, so a zoom factor means something on a printed page. An infinite
 * canvas zooms relative to whatever the view happens to fit, where the same 1.0x can be tiny or
 * huge, so the test is the size of a line of handwriting in screen pixels instead.
 */
object FollowLegibility {
    /** About the smallest handwriting worth gliding the view for. */
    const val MIN_STROKE_PX = 8f

    /** The current lane's median stroke height, measured on screen. */
    fun strokePx(laneHeight: Float, scale: Float): Float =
        if (laneHeight.isFinite() && scale.isFinite() && scale > 0f) laneHeight * scale else 0f

    fun isReadable(laneHeight: Float, scale: Float): Boolean = strokePx(laneHeight, scale) >= MIN_STROKE_PX
}

/** One approachable control for related movement thresholds and timing. Personal choices stay intact. */
object FollowComfort {
    fun apply(preferences: FollowPreferences, responsiveness: Float): FollowPreferences {
        val t = responsiveness.coerceIn(0f, 1f)
        fun blend(calm: Float, quick: Float) = calm + (quick - calm) * t
        return preferences.copy(
            returnDelayMs = blend(1100f, 400f).roundToInt(),
            glideDurationMs = blend(440f, 180f).roundToInt(),
            edgeThreshold = blend(.88f, .66f),
            verticalDeadBand = blend(.24f, .08f),
            endMargin = blend(.04f, .12f),
        )
    }

    fun value(preferences: FollowPreferences): Float =
        ((1100f - preferences.returnDelayMs) / 700f).coerceIn(0f, 1f)

    fun matches(preferences: FollowPreferences, value: Float): Boolean {
        val target = apply(preferences, value)
        return preferences.returnDelayMs == target.returnDelayMs &&
            preferences.glideDurationMs == target.glideDurationMs &&
            abs(preferences.edgeThreshold - target.edgeThreshold) < .001f &&
            abs(preferences.verticalDeadBand - target.verticalDeadBand) < .001f &&
            abs(preferences.endMargin - target.endMargin) < .001f
    }
}
