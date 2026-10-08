package com.folio.notes

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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
                  columnStart: Float? = null): WritingAdvance? {
        val baseline = state.baselineY ?: return null
        if (preferences.mode != FollowMode.TEXT || state.needsPlacement || state.candidateLane != null || !readyForReturn()) return null
        val frontier = if (preferences.direction == WritingDirection.LTR) state.frontierRight else state.frontierLeft
        if (frontier == null || !(FollowNavigation.nearEnd(frontier, region, preferences.direction, preferences.endMargin) ||
                reachedLearnedEnd(frontier, region, preferences.direction))) return null
        return FollowNavigation.next(baseline, region, guides, lineSpacing(preferences.spacing, preferences.adaptiveSpacing),
            state.textStartX ?: columnStart ?: state.lineStartX, preferences.direction)
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
    fun verticalVelocity(baselineFraction: Float, zoom: Float, now: Long): Float {
        if (zoom < 1.4f || now < state.suspendedUntil) return 0f
        return when { baselineFraction > .75f -> -160f; baselineFraction < .35f -> 160f; else -> 0f }
    }
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

    fun arrived(advance: WritingAdvance, direction: WritingDirection = WritingDirection.LTR) {
        // Going back up a line is a correction, not the end of a line.
        if (advance.to.y > advance.from.y) recordLineEnd(direction)
        state = state.copy(baselineY = advance.to.y, recent = emptyList(), completedGuide = advance.from,
            frontierLeft = null, frontierRight = null, candidateLane = null, candidateAt = null,
            lineStrokeCount = 0, markerEdge = null, needsPlacement = false)
    }

    fun lineAdvanceProgress(liftedAt: Long, now: Long, durationMs: Int = DEFAULT_GLIDE_MS): Float =
        ((now - liftedAt).toFloat() / durationMs.coerceIn(120, 800).toFloat()).coerceIn(0f, 1f)

    companion object {
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

/** User intent is independent of the hand holding the pen. */
enum class WritingDirection { LTR, RTL }
enum class FollowMode { TEXT, MATH }
/** Explicit, transient permission to move an infinite canvas while composing prose. */
data class CanvasWritingSession(
    val column: WritingLane,
    val direction: WritingDirection,
    val automaticReturn: Boolean = false,
) {
    val startX get() = if (direction == WritingDirection.LTR) column.left else column.right
    fun preferences(base: FollowPreferences) = base.copy(
        mode = FollowMode.TEXT, direction = direction, automaticReturn = automaticReturn,
        autoSwitchAreas = false,
    )

    /**
     * The visible left edge, in canvas units, that keeps the response readable. A column that
     * fits on screen stays wholly in view with the least movement, so writing across it and
     * returning never slide the view sideways. A column wider than the view (after zooming in)
     * takes [wantedLeft], but never shows more than a margin beyond either column edge.
     */
    fun framedLeft(viewLeft: Float, viewWidth: Float, wantedLeft: Float = viewLeft): Float {
        if (!viewLeft.isFinite() || !viewWidth.isFinite() || viewWidth <= 0f) return viewLeft
        val pad = column.width * FRAME_PAD
        val showsTrailing = column.right + pad - viewWidth
        val showsLeading = column.left - pad
        if (showsTrailing <= showsLeading) return viewLeft.coerceIn(showsTrailing, showsLeading)
        return (if (wantedLeft.isFinite()) wantedLeft else viewLeft).coerceIn(showsLeading, showsTrailing)
    }

    /** Survives Activity recreation (rotation, resize, split screen) as plain text. */
    fun encode(): String = listOf(column.left, column.top, column.right, column.bottom,
        direction.name, automaticReturn).joinToString(",")

    companion object {
        /** Breathing room kept beside the column edges when framing it, as a fraction of its width. */
        const val FRAME_PAD = .04f

        fun decode(raw: String?): CanvasWritingSession? = runCatching {
            val parts = raw!!.split(",")
            val (l, t, r, b) = parts.take(4).map { it.toFloat() }
            val column = WritingLane(l, t, r, b)
            if (!l.isFinite() || !r.isFinite() || column.width <= 0f) null
            else CanvasWritingSession(column, WritingDirection.valueOf(parts[4]), parts[5].toBooleanStrict())
        }.getOrNull()

        fun start(viewport: WritingLane, preferences: FollowPreferences): CanvasWritingSession? {
            if (!viewport.left.isFinite() || !viewport.right.isFinite() ||
                !viewport.width.isFinite() || viewport.width <= 0f) return null
            return CanvasWritingSession(FollowNavigation.infiniteRegion(viewport, preferences.direction,
                endMargin = preferences.endMargin), preferences.direction)
        }
    }
}
data class FollowPreferences(
    val direction: WritingDirection = WritingDirection.LTR,
    val mode: FollowMode = FollowMode.TEXT,
    val automaticReturn: Boolean = false,
    val position: Float = .55f,
    val horizontalPosition: Float = .5f,
    val spacing: Float = 32f,
    /** Pause before automatic return; sideways follow uses half this pause. */
    val returnDelayMs: Int = WritingFollow.DEFAULT_RETURN_MS,
    /** Minimum glide length. 120..800 ms; longer travel takes extra time. */
    val glideDurationMs: Int = WritingFollow.DEFAULT_GLIDE_MS,
    /** Next-line glide pace: ms to cross one screen width or height. 250..1500; sideways follow keeps the default. */
    val lineSpeedMs: Int = FollowGlide.MS_PER_VIEWPORT.toInt(),
    val adaptiveSpacing: Boolean = true,
    val horizontalFollow: Boolean = true,
    val verticalFollow: Boolean = true,
    val autoSwitchAreas: Boolean = true,
    val minimumZoom: Float = 1.4f,
    val edgeThreshold: Float = .72f,
    val verticalDeadBand: Float = .15f,
    val endMargin: Float = .08f,
    /** Mark where the next line begins while a return is pending and until writing resumes. */
    val showLandingGuide: Boolean = true,
) {
    val automaticReturnDelayMs: Int get() = returnDelayMs.coerceIn(300, 2000)
    /** Fixed quiet period, independent of stroke history and distance from the visible edge. */
    val glideDelayMs: Int get() = (automaticReturnDelayMs * .5f).roundToInt()

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
        fun lineSpeedLabel(ms: Int): String = when {
            ms <= 450 -> "Fast"
            ms <= 900 -> "Smooth"
            else -> "Gentle"
        }
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

    fun bounds(points: List<InkPoint>): WritingLane? {
        if (points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return WritingLane(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    /** Margins contain the letter body; a tail or crossbar may protrude a little. */
    fun contains(box: WritingLane, region: WritingLane, spacing: Float): Boolean {
        val xSlack = minOf(8f, spacing * .2f)
        val ySlack = spacing * .6f
        return box.left >= region.left - xSlack && box.right <= region.right + xSlack &&
            box.top >= region.top - ySlack && box.bottom <= region.bottom + ySlack
    }

    fun isTextStroke(points: List<InkPoint>, spacing: Float, descenderAllowance: Float = 0f): Boolean {
        val box = bounds(points) ?: return false
        if (!spacing.isFinite() || spacing <= 0f ||
            box.height >= spacing * .9f + descenderAllowance.coerceIn(0f, spacing * .6f)) return false
        // Long cursive words may cover the whole lane. Their repeated up/down travel
        // distinguishes them from a long underline without an arbitrary word-width cap.
        val direct = hypot(box.width, box.height)
        var travel = 0f
        for (i in 1..points.lastIndex) travel += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        // A flat, smooth cursive word has less travel than a looped one, but unlike an underline it has height.
        return if (box.width > 220f) travel > direct * 1.35f || (box.height >= spacing * .25f && travel > direct * 1.15f)
        else box.width <= maxOf(64f, spacing * 2f) || travel > direct * 1.15f
    }

    /**
     * The writing lane on an unbounded canvas: the visible viewport inset by the end margin,
     * with the writer's own start column kept as the leading edge, even when off screen.
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
        // Canvas coordinates have no physical size. Fixed page-unit caps change the
        // visible margin with zoom and can make a narrow viewport's lane wider than itself.
        val margin = width * endMargin.coerceIn(.02f, .2f)
        // A full viewport lane inset on both sides; the trailing edge below derives from
        // this width so pans never move it.
        val laneWidth = width - 2f * margin
        // Following can move the start column off screen. Only deliberate navigation
        // resets it (WritingFollow.suspend); falling back here would chase the line end away.
        val remembered = startX?.takeIf { it.isFinite() }
        val (left, right) = if (direction == WritingDirection.LTR) {
            if (remembered != null) remembered to remembered + laneWidth
            else {
                val trailing = viewport.right - margin
                (viewport.left + margin) to trailing
            }
        } else {
            if (remembered != null) remembered - laneWidth to remembered
            else {
                val leading = viewport.left + margin
                leading to (viewport.right - margin)
            }
        }
        // The viewport top is not a paper boundary. A return from a baseline above
        // the screen still advances exactly one line, even after an interrupted pan.
        return WritingLane(left, -Float.MAX_VALUE, right, Float.MAX_VALUE)
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
    /**
     * The line above [baseline], for going back to correct or add to it: the printed rule before
     * the current one, or one spacing up on blank paper, never above the area's top.
     */
    fun previous(baseline: Float, region: WritingLane, guides: List<WritingGuide>, spacing: Float,
                 lineStartX: Float? = null, direction: WritingDirection = WritingDirection.LTR): WritingAdvance? {
        if (!baseline.isFinite() || baseline < region.top) return null
        val currentY = baseline.coerceAtMost(region.bottom)
        val eligible = guides.filter { it.left >= region.left - 6 && it.right <= region.right + 6 && it.y in region.top..region.bottom }
        val current = eligible.minByOrNull { abs(it.y - currentY) }?.takeIf { abs(it.y - currentY) <= 16f }
        val previous = current?.let { line -> eligible.filter { WritingGuides.next(it, eligible) == line }.maxByOrNull { it.y } }
        // The first rule of an answer block has nothing above it in that block.
        if (current != null && previous == null) return null
        val y = previous?.y ?: (currentY - spacing.coerceIn(16f, 96f))
        if (y < region.top) return null
        val start = lineStartX?.takeIf { it.isFinite() }?.coerceIn(region.left, region.right)
        val left = if (direction == WritingDirection.LTR) start ?: region.left else region.left
        val right = if (direction == WritingDirection.RTL) start ?: region.right else region.right
        return WritingAdvance(current ?: WritingGuide(left, right, baseline),
            previous ?: WritingGuide(left, right, y))
    }

    fun nearEnd(points: List<InkPoint>, region: WritingLane, direction: WritingDirection, endMargin: Float = .08f): Boolean {
        if (points.isEmpty()) return false
        val frontier = if (direction == WritingDirection.LTR) points.maxOf { it.x } else points.minOf { it.x }
        return nearEnd(frontier, region, direction, endMargin)
    }

    fun nearEnd(frontier: Float, region: WritingLane, direction: WritingDirection, endMargin: Float = .08f): Boolean {
        if (!frontier.isFinite()) return false
        val proportionalMargin = region.width * endMargin.coerceIn(.02f, .2f)
        val margin = if (region.unbounded) proportionalMargin else proportionalMargin.coerceIn(8f, 96f)
        return if (direction == WritingDirection.LTR) frontier >= region.right - margin
        else frontier <= region.left + margin
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
