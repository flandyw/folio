package com.folio.notes.editor.follow

import com.folio.notes.model.InkPoint
import kotlin.math.abs

/** Transient handwriting geometry; printed guide detection runs separately from input. */
data class WritingLane(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * .5f
    val unbounded get() = top == -Float.MAX_VALUE && bottom == Float.MAX_VALUE
}

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
    val lineStrokeCount: Int = 0,
    /** Forward edge of a possible list marker ("1.", a bullet, a dash) while the line is still being classified. */
    val markerEdge: Float? = null,
    /** Where the text after a leading marker began; automatic returns hang from it. */
    val textStartX: Float? = null,
    /** Survives pen-down interruptions until the new line has actually been placed. */
    val needsPlacement: Boolean = false,
    /**
     * Stroke heights of accepted body samples across lines. The writer's hand keeps its size
     * through returns, new lines and navigation, so a fresh line does not fall back to a guess.
     */
    val heightSamples: List<Float> = emptyList(),
    /** Where recent full lines ended (furthest frontier), to learn the writer's own margin. */
    val lineEnds: List<Float> = emptyList(),
)

enum class WritingHand(val direction: Float) { RIGHT(1f), LEFT(-1f) }

enum class WritingProgress { NONE, SAME_LINE, NEW_LINE }

data class WritingFollowStatus(
    val message: String = "Write to start following",
    val paused: Boolean = false,
    val canGoBack: Boolean = false,
    /** The view is not doing what the writer expects (held, blocked, at an end); worth showing briefly. */
    val attention: Boolean = false,
)

/**
 * Follow moves Back can undo, newest last. A requested or clamped glide that never moved adds
 * nothing, and the oldest move falls off past [limit], so stepping back is bounded.
 */
internal class FollowBackHistory(private val limit: Int = 8) {
    data class Entry(val x: Float, val y: Float, val state: WritingFollowState)
    private val entries = ArrayDeque<Entry>()
    val entry: Entry? get() = entries.lastOrNull()
    val depth get() = entries.size
    private var pending: WritingFollowState? = null
    fun begin(state: WritingFollowState) { pending = state }
    fun cancelPending() { pending = null }
    fun clear() { entries.clear(); pending = null }
    fun moved(x: Float, y: Float) {
        if (x == 0f && y == 0f) return
        pending?.let {
            entries.addLast(Entry(0f, 0f, it)); pending = null
            while (entries.size > limit.coerceAtLeast(1)) entries.removeFirst()
        }
        entries.lastOrNull()?.let { entries[entries.lastIndex] = it.copy(x = it.x + x, y = it.y + y) }
    }
    /** Takes the newest move off the history; the caller reverses it. */
    fun pop(): Entry? = entries.removeLastOrNull()
}

/** Keep an automatic return's screen destination through pen-down interruptions. */
internal class FollowReturnTarget {
    private var line: Pair<Float, Float>? = null
    private var screenY = 0f

    fun target(advance: WritingAdvance, screenFromY: Float, preferredY: Float): Float {
        val nextLine = advance.from.y to advance.to.y
        if (line != nextLine) {
            line = nextLine
            // Returning from above the preferred height must still scroll down one line.
            screenY = minOf(screenFromY, preferredY)
        }
        return screenY
    }

    fun clear() { line = null }
}

class WritingFollow {
    var state = WritingFollowState()

    /** Keep a real dead band even when the preferred writing column is near an edge. */
    fun horizontalShift(fraction: Float, target: Float, direction: WritingDirection, edgeThreshold: Float = .72f): Float {
        if (!fraction.isFinite() || !target.isFinite()) return 0f
        val destination = target.coerceIn(.1f, .9f)
        val trigger = horizontalTrigger(target, direction, edgeThreshold)
        return if (direction == WritingDirection.LTR) {
            if (fraction >= trigger)
                (destination - fraction).coerceAtMost(0f) else 0f
        } else {
            if (fraction <= trigger)
                (destination - fraction).coerceAtLeast(0f) else 0f
        }
    }

    /** A pen stroke reaching the visible strip can reveal room without being recognised as text. */
    fun stripShift(box: WritingLane, visible: WritingLane, area: WritingLane,
                   preferences: FollowPreferences, hand: WritingHand): Float {
        if (!preferences.horizontalFollow || preferences.mode != FollowMode.TEXT || visible.width <= 0f ||
            !listOf(box.left, box.top, box.right, box.bottom, visible.left, visible.right).all(Float::isFinite) ||
            box.bottom < visible.top || box.top > visible.bottom ||
            !FollowNavigation.contains(box, area, preferences.spacing) ||
            !FollowNavigation.hasRoomAhead(visible.left, visible.right, area, preferences.direction)) return 0f
        val edge = if (preferences.direction == WritingDirection.LTR) box.right else box.left
        val fraction = (edge - visible.left) / visible.width
        return horizontalShift(fraction, preferences.horizontalTarget(hand), preferences.direction,
            preferences.edgeThreshold) * visible.width
    }

    /** Following within a line and placing natural line breaks obey the vertical toggle. */
    fun verticalShift(screenY: Float, targetY: Float, viewportHeight: Float, preferences: FollowPreferences,
                      placing: Boolean = false): Float {
        if (!preferences.verticalFollow) return 0f
        return if (placing || screenY > targetY + viewportHeight * preferences.verticalDeadBand)
            targetY - screenY else 0f
    }

    fun suspend(now: Long) {
        state = WritingFollowState(suspendedUntil = now + 1500, lineSpacings = state.lineSpacings,
            heightSamples = state.heightSamples, lineEnds = state.lineEnds)
    }

    /** Learn spacing only from confirmed natural line breaks; a skipped line cannot set the pace. */
    fun lineSpacing(fallback: Float, adaptive: Boolean = true): Float {
        val learned = state.lineSpacings.sorted()
        if (adaptive && learned.size >= 2) return learned[(learned.size - 1) / 2]
        // Before any line break is learned, large handwriting (a zoomed-out canvas, a big hand)
        // must not land the next line on top of this one: room grows with the writing itself.
        val sized = if (adaptive && (state.recent.size + state.heightSamples.size) >= MIN_SIZED_SAMPLES)
            laneHeight() * SPACING_PER_HEIGHT else 0f
        return maxOf(fallback, sized).coerceIn(FollowPreferences.MIN_SPACING, FollowPreferences.MAX_SPACING)
    }

    /**
     * Ink was undone or erased. The line's frontier falls back to the furthest writing still on it,
     * so an undone word cannot trigger a return early; a line emptied entirely starts afresh at the
     * same height. [remaining] are the bounds of the page's pen strokes.
     */
    fun retract(remaining: List<WritingLane>, direction: WritingDirection) {
        val baseline = state.baselineY ?: return
        val left = state.frontierLeft ?: return
        val right = state.frontierRight ?: return
        val height = laneHeight()
        val slack = maxOf(8f, height)
        val lane = remaining.filter {
            it.bottom in (baseline - height * 1.5f)..(baseline + height * 1.2f) && it.top < baseline &&
                it.right >= left - slack && it.left <= right + slack
        }
        if (lane.isEmpty()) {
            state = state.copy(recent = emptyList(), frontierLeft = null, frontierRight = null, lineStrokeCount = 0,
                markerEdge = null, textStartX = null, candidateLane = null, candidateAt = null)
            return
        }
        val kept = state.recent.filter { it in lane }
        state = state.copy(
            recent = kept.ifEmpty { lane.takeLast(16) },
            frontierLeft = if (direction == WritingDirection.RTL) maxOf(left, lane.minOf { it.left }) else left,
            frontierRight = if (direction == WritingDirection.LTR) minOf(right, lane.maxOf { it.right }) else right,
            lineStrokeCount = minOf(state.lineStrokeCount, lane.size),
            candidateLane = state.candidateLane?.takeIf { it in remaining },
        )
    }

    /** A lone mark at the edge is not evidence that the writer has finished a line. */
    fun readyForReturn(): Boolean {
        val left = state.frontierLeft ?: return false
        val right = state.frontierRight ?: return false
        val span = right - left
        return (state.lineStrokeCount >= 2 && span >= maxOf(16f, laneHeight() * 1.5f)) ||
            span >= maxOf(48f, laneHeight() * 4f)
    }

    fun returnFor(region: WritingLane, guides: List<WritingGuide>, preferences: FollowPreferences,
                  columnStart: Float? = null, startOverride: Float? = null): WritingAdvance? {
        val baseline = state.baselineY ?: return null
        if (preferences.mode != FollowMode.TEXT || state.needsPlacement || state.candidateLane != null || !readyForReturn()) return null
        val frontier = if (preferences.direction == WritingDirection.LTR) state.frontierRight else state.frontierLeft
        if (frontier == null || !(FollowNavigation.nearEnd(frontier, region, preferences.direction, preferences.endMargin) ||
                reachedLearnedEnd(frontier, region, preferences.direction))) return null
        return FollowNavigation.next(baseline, region, guides, lineSpacing(preferences.spacing, preferences.adaptiveSpacing),
            startOverride ?: state.textStartX ?: columnStart ?: state.lineStartX, preferences.direction,
            overrideStart = startOverride != null)
    }

    /**
     * The frontier has reached where this writer's full lines usually end, inside the area. A
     * writer who keeps a margin short of the area's edge gets their return there, not never.
     */
    fun reachedLearnedEnd(frontier: Float, region: WritingLane, direction: WritingDirection): Boolean {
        val end = learnedLineEnd(direction) ?: return false
        if (end < region.left || end > region.right) return false
        val slack = laneHeight() * .75f
        return if (direction == WritingDirection.LTR) frontier >= end - slack else frontier <= end + slack
    }

    /**
     * A small cluster at the start of a line followed by a clear gap is a list marker. Only automatic
     * returns (the line ran to its end, so the item is wrapping) hang from the text; a manual Next
     * line still goes back to the marker column for the next item. The cost of a false positive,
     * such as a one-letter first word, is a return landing one word in.
     */
    private fun trackMarker(box: WritingLane, direction: WritingDirection) {
        val edge = state.markerEdge ?: return
        val start = state.lineStartX ?: return
        val ltr = direction == WritingDirection.LTR
        val near = if (ltr) box.left else box.right
        val far = if (ltr) box.right else box.left
        val gap = if (ltr) near - edge else edge - near
        val height = laneHeight()
        val limit = maxOf(height * 2.5f, 20f)
        state = when {
            gap >= maxOf(height * .6f, 8f) ->
                if (abs(edge - start) <= limit) state.copy(markerEdge = null, textStartX = near)
                else state.copy(markerEdge = null)
            abs(far - start) > limit -> state.copy(markerEdge = null)
            else -> state.copy(markerEdge = if (ltr) maxOf(edge, far) else minOf(edge, far))
        }
    }

    private fun overlapsBody(box: WritingLane): Boolean {
        val baseline = state.baselineY ?: return false
        val height = laneHeight()
        val tops = state.recent.map { it.top }.sorted()
        val top = tops.getOrNull(tops.size / 2) ?: (baseline - height)
        return box.top <= top + height * .35f && box.bottom >= baseline - height * .25f
    }

    /** Estimate the body, rather than a descender's lowest point, before a line is known. */
    fun anchorY(box: WritingLane): Float = if (overlapsBody(box)) state.baselineY!!
        else minOf(box.bottom, box.top + laneHeight() * 1.25f)

    private fun extendsFrontier(box: WritingLane, direction: WritingDirection): Boolean {
        val slack = (laneHeight() * .1f).coerceIn(.5f, 2f)
        return if (direction == WritingDirection.LTR)
            state.frontierRight?.let { box.right > it + slack } ?: true
        else state.frontierLeft?.let { box.left < it - slack } ?: true
    }

    private fun returned(box: WritingLane, direction: WritingDirection): Boolean {
        val distance = maxOf(16f, laneHeight() * 1.5f)
        return if (direction == WritingDirection.LTR)
            state.frontierRight?.let { box.left < it - distance } == true
        else state.frontierLeft?.let { box.right > it + distance } == true
    }

    /** Printed rules disambiguate tall capitals, first-letter descenders and mixed letter sizes. */
    fun guideFor(box: WritingLane, guides: List<WritingGuide>, preferences: FollowPreferences): WritingGuide? {
        val column = guides.filter { box.centerX in (it.left - 6f)..(it.right + 6f) }
        val baseline = state.baselineY
        val current = baseline?.let { y -> column.minByOrNull { abs(it.y - y) }
            ?.takeIf { abs(it.y - y) <= 12f } }
        val returning = returned(box, preferences.direction)
        if (current != null && overlapsBody(box) && !returning) return current
        // A tall capital can overlap the previous body's height, but returning to the
        // start and reaching the next rule is stronger evidence than its top alone.
        if (current != null && returning) {
            val next = WritingGuides.next(current, column)
            if (next != null && abs(box.bottom - next.y) <= maxOf(3f, (next.y - current.y) * .15f) &&
                box.top < next.y) return next
        }
        val y = if (baseline == null) anchorY(box)
            else minOf(box.bottom, box.top + laneHeight() * 1.25f)
        return column.minByOrNull { abs(it.y - y) }?.takeIf {
            val spacing = WritingGuides.spacing(it, column) ?: preferences.spacing
            abs(it.y - y) <= minOf(16f, spacing * .5f)
        }
    }

    /** Dots and crossbars on or beside the last word can resume an interrupted glide after pen-up. */
    fun finishingMark(box: WritingLane, preferences: FollowPreferences): Boolean {
        val baseline = state.baselineY ?: return false
        if (state.candidateLane != null || box.height > laneHeight() * .8f ||
            box.width > maxOf(24f, laneHeight() * 2f)) return false
        val frontier = if (preferences.direction == WritingDirection.LTR) state.frontierRight else state.frontierLeft
        frontier ?: return false
        val x = if (preferences.direction == WritingDirection.LTR) box.right else box.left
        // A cursive word is dotted and crossed after it is finished, so its marks sit anywhere along
        // the stroke just written, not only beside its end.
        val last = state.recent.lastOrNull()
        val slack = maxOf(6f, laneHeight() * .5f)
        val onLastStroke = last != null && box.left >= last.left - slack && box.right <= last.right + slack
        return (abs(x - frontier) <= maxOf(12f, laneHeight() * 2f) || onLastStroke) &&
            box.bottom in (baseline - laneHeight() * 1.5f)..(baseline + preferences.spacing * .35f)
    }

    /** A full-height letter may span both an ascender and a descender, including the first letter. */
    fun isTextStroke(points: List<InkPoint>, spacing: Float): Boolean =
        FollowNavigation.isTextStroke(points, spacing, spacing * .6f)

    fun completed(points: List<InkPoint>, now: Long, preferences: FollowPreferences = FollowPreferences(),
                  guides: List<WritingGuide> = emptyList()): WritingProgress {
        if (now < state.suspendedUntil) return WritingProgress.NONE
        val box = FollowNavigation.bounds(points) ?: return WritingProgress.NONE
        // Even a finishing dot can release an abandoned candidate. Otherwise it blocks
        // finishingMark/returnFor indefinitely until another advancing letter is written.
        if (state.candidateAt?.let { now - it !in 0..15000 } == true)
            state = state.copy(candidateLane = null, candidateAt = null)
        val noise = minOf(2f, preferences.spacing * .06f)
        if (box.height < noise && box.width < noise) return WritingProgress.NONE
        val baseline = state.baselineY
        if (preferences.mode == FollowMode.MATH) {
            if (baseline != null && box.bottom <= baseline + 2f) return WritingProgress.NONE
            accept(box, listOf(box), false, preferences.direction)
            return WritingProgress.SAME_LINE
        }
        val height = laneHeight()
        val guide = guideFor(box, guides, preferences)
        val spacing = guide?.let { WritingGuides.spacing(it, guides) }
            ?: lineSpacing(preferences.spacing, preferences.adaptiveSpacing)
        if (!isTextStroke(points, maxOf(spacing, height))) return WritingProgress.NONE
        val threshold = maxOf(spacing * .32f, height * .65f).coerceIn(3f, spacing * .6f)
        val guideChanged = baseline != null && guide != null && guide.y - baseline > threshold
        val changedLane = baseline != null && (guideChanged ||
            (guide == null && box.bottom - baseline > threshold && (!overlapsBody(box) ||
                (returned(box, preferences.direction) && box.bottom - baseline >= spacing * .65f))))
        if (changedLane) {
            val last = state.candidateLane
            val fresh = state.candidateAt?.let { now - it in 0..15000 } == true
            val sameBody = last != null &&
                abs(last.bottom - box.bottom) <= maxOf(height, spacing * .55f) &&
                maxOf(last.top, box.top) <= minOf(last.bottom, box.bottom) + 3f
            // Two pen lifts are not two independent pieces of evidence: retracing a
            // letter or adding a dot must not turn an uncertain mark into a line break.
            val slack = (height * .1f).coerceIn(.5f, 2f)
            val forward = last != null && if (preferences.direction == WritingDirection.LTR)
                box.right > last.right + slack else box.left < last.left - slack
            val gap = last?.let { if (preferences.direction == WritingDirection.LTR)
                box.left - it.right else it.left - box.right } ?: Float.MAX_VALUE
            val confirms = fresh && sameBody && forward && gap <= maxOf(spacing * 2f, height * 4f) &&
                box.height >= maxOf(2f, height * .3f)
            // Clear body separation at a printed rule needs no extra confirmation stroke.
            val definite = (guideChanged && box.top > baseline!! + height * .2f) ||
                (returned(box, preferences.direction) && box.top > baseline!! + height * .2f &&
                    box.width >= maxOf(32f, height * 2f))
            if (!definite && !confirms) {
                // Keep the original evidence while a letter is dotted or retraced;
                // those marks must neither replace its body nor renew its lifetime.
                if (!(fresh && sameBody && !forward))
                    state = state.copy(candidateLane = box, candidateAt = now)
                return WritingProgress.NONE
            }
            val recent = if (confirms) listOf(last!!, box) else listOf(box)
            recordLineEnd(preferences.direction)
            accept(box, recent, true, preferences.direction, guide?.y)
            return WritingProgress.NEW_LINE
        }
        val progressing = extendsFrontier(box, preferences.direction)
        if (baseline != null && (!progressing || baseline - box.bottom > height * .9f))
            return WritingProgress.NONE
        // Keep tiny finishing marks and deep descenders out of the body statistics, while
        // still accepting their forward progress. A one-stroke cursive word counts too.
        val bodySample = box.height >= maxOf(2f, height * .3f) &&
            (baseline == null || abs(box.bottom - baseline) <= threshold)
        val recent = if (bodySample || state.recent.isEmpty()) (state.recent + box).takeLast(16) else state.recent
        accept(box, recent, false, preferences.direction, guide?.y)
        return WritingProgress.SAME_LINE
    }

    private fun accept(box: WritingLane, recent: List<WritingLane>, changedLane: Boolean,
                       direction: WritingDirection, guideY: Float? = null) {
        val ys = recent.map { it.bottom }.sorted()
        // The lower median recovers immediately when the first letter was a descender,
        // and mixed ascenders/descenders cannot drag the lane steadily downward.
        val baseline = guideY ?: ys.getOrNull((ys.size - 1) / 2) ?: state.baselineY ?: box.bottom
        val gapY = state.baselineY?.let { baseline - it }?.takeIf {
            changedLane && it in FollowPreferences.MIN_SPACING..FollowPreferences.MAX_SPACING
        }
        val first = recent.firstOrNull() ?: box
        val start = if (direction == WritingDirection.LTR) first.left else first.right
        val newLine = changedLane || state.lineStartX == null
        val sampled = recent.lastOrNull() === box && box.height > 0f
        state = state.copy(
            heightSamples = if (sampled) (state.heightSamples + box.height).takeLast(MAX_HAND_SAMPLES) else state.heightSamples,
            baselineY = baseline, recent = recent, candidateLane = null, candidateAt = null,
            lineStartX = if (newLine) start else state.lineStartX,
            markerEdge = if (newLine) (if (direction == WritingDirection.LTR) first.right else first.left) else state.markerEdge,
            textStartX = if (newLine) null else state.textStartX,
            lineSpacings = if (gapY != null) (state.lineSpacings + gapY).takeLast(6) else state.lineSpacings,
            frontierLeft = if (changedLane) recent.minOf { it.left } else minOf(state.frontierLeft ?: box.left, box.left),
            frontierRight = if (changedLane) recent.maxOf { it.right } else maxOf(state.frontierRight ?: box.right, box.right),
            lineStrokeCount = if (changedLane) recent.size else state.lineStrokeCount + 1,
            needsPlacement = state.needsPlacement || changedLane,
        )
        if (newLine) recent.drop(1).forEach { trackMarker(it, direction) } else trackMarker(box, direction)
    }

    fun placed() { state = state.copy(needsPlacement = false) }
    /** Median stroke height of the current lane, in page units; drives line spacing. */
    fun laneHeight(): Float {
        val line = state.recent.map { it.bottom - it.top }
        // A line's own strokes decide once there are a few; until then the writer's hand,
        // learned from earlier lines, fills in rather than a fixed guess.
        val heights = (if (line.size >= 3) line else line + state.heightSamples.takeLast(HAND_SAMPLES)).sorted()
        return heights.getOrNull((heights.size - 1) / 2)?.coerceAtLeast(4f) ?: 24f
    }

    /** The writer's typical stroke height, or null before any has been seen. */
    fun handHeight(): Float? = state.heightSamples.takeIf { it.isNotEmpty() }?.sorted()?.let { it[(it.size - 1) / 2] }

    /**
     * Where this writer ends full lines, learned from the line ends they produced: the densest
     * cluster of recent ends, so a short final line of a paragraph or one long word does not count.
     */
    fun learnedLineEnd(direction: WritingDirection): Float? {
        val ends = state.lineEnds
        if (ends.size < 2) return null
        val tolerance = maxOf(24f, laneHeight() * 2.5f)
        val far = if (direction == WritingDirection.LTR) 1f else -1f
        val best = ends.maxWithOrNull(compareBy<Float>({ e -> ends.count { abs(it - e) <= tolerance } }, { it * far }))!!
        val cluster = ends.filter { abs(it - best) <= tolerance }.sorted()
        return if (cluster.size < 2) null else cluster[(cluster.size - 1) / 2]
    }

    /** Records the end of a line the writer actually finished (a natural break or a return). */
    private fun recordLineEnd(direction: WritingDirection) {
        val left = state.frontierLeft ?: return
        val right = state.frontierRight ?: return
        // A short line (a heading, a list item, a paragraph's last line) says nothing about the margin.
        if (right - left < maxOf(64f, laneHeight() * 6f)) return
        val end = if (direction == WritingDirection.LTR) right else left
        state = state.copy(lineEnds = (state.lineEnds + end).takeLast(6))
    }

    fun arrived(advance: WritingAdvance, direction: WritingDirection = WritingDirection.LTR) {
        // Going back up a line is a correction, not the end of a line.
        if (advance.to.y > advance.from.y) recordLineEnd(direction)
        state = state.copy(baselineY = advance.to.y, recent = emptyList(), completedGuide = advance.from,
            frontierLeft = null, frontierRight = null, candidateLane = null, candidateAt = null,
            lineStrokeCount = 0, markerEdge = null, needsPlacement = false)
    }

    companion object {
        /** Shared by movement and the visible strip, including the minimum gap from the landing column. */
        fun horizontalTrigger(target: Float, direction: WritingDirection, edgeThreshold: Float): Float {
            val destination = target.coerceIn(.1f, .9f)
            val edge = edgeThreshold.coerceIn(.55f, .95f)
            return if (direction == WritingDirection.LTR) maxOf(edge, destination + .08f).coerceAtMost(.95f)
            else minOf(1f - edge, destination - .08f).coerceAtLeast(.05f)
        }

        /** Default carriage-return glide, in ms; exposed so settings can offer speeds. */
        const val DEFAULT_GLIDE_MS = 280
        /** Default pause after pen lift before an automatic return fires, in ms. */
        const val DEFAULT_RETURN_MS = 650
        /** Line pitch as a multiple of median stroke height, used until spacing is learned. */
        const val SPACING_PER_HEIGHT = 1.6f
        /** Strokes needed before their height is trusted to size the line spacing. */
        const val MIN_SIZED_SAMPLES = 3
        /** Earlier-line heights that stand in while a line has fewer than three of its own. */
        const val HAND_SAMPLES = 8
        const val MAX_HAND_SAMPLES = 24
    }
}
