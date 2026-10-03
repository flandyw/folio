package com.folio.notes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The writer's choices. Line height, spacing and line length are measured from the ink instead. */
data class FollowPreferences(
    val direction: WritingDirection = WritingDirection.LTR,
    val mode: FollowMode = FollowMode.TEXT,
    val automaticReturn: Boolean = false,
    /** 0 waits longer and moves later; 1 follows sooner with a quicker glide. */
    val feel: Float = DEFAULT_FEEL,
    /** Where the writing line sits after a move, as a fraction down the view. */
    val height: Float = DEFAULT_HEIGHT,
    /**
     * Text only: keep the line at [height] while writing across it. Off, the page stays put
     * vertically until the line nears the bottom edge; Next line always moves regardless.
     */
    val keepHeight: Boolean = false,
) {
    private fun blend(relaxed: Float, responsive: Float) = relaxed + (responsive - relaxed) * feel.coerceIn(0f, 1f)
    /** How far across the view the end of the writing may go before the view glides sideways. */
    val sidewaysTrigger get() = blend(.86f, .68f)
    /** How far below [height] the line may sink, as a fraction of the view, before the view moves up. */
    val verticalBand get() = blend(.22f, .1f)
    val pauseMs get() = blend(600f, 200f).roundToInt()
    val returnPauseMs get() = blend(1100f, 450f).roundToInt()
    val glideMs get() = blend(420f, 200f).roundToInt()

    companion object {
        const val DEFAULT_FEEL = .5f
        const val DEFAULT_HEIGHT = .55f
        const val MIN_HEIGHT = .35f
        const val MAX_HEIGHT = .7f
        fun clampFeel(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT_FEEL
        fun clampHeight(value: Float) = if (value.isFinite()) value.coerceIn(MIN_HEIGHT, MAX_HEIGHT) else DEFAULT_HEIGHT
        fun feelLabel(value: Float) = when {
            value < .25f -> "Relaxed"
            value <= .75f -> "Balanced"
            else -> "Responsive"
        }
        fun seconds(ms: Int) = "${"%.1f".format(ms / 1000f)} s"
    }
}

data class FollowStatus(
    val message: String = "Write to start following",
    val paused: Boolean = false,
    val canGoBack: Boolean = false,
)

/** Page facts the engine needs; an infinite canvas has no edges and so no printed line end. */
data class FollowPage(val infinite: Boolean = false, val width: Float = 0f, val height: Float = 0f)

/** Where the next line begins, drawn briefly so a move to an already visible line still reads. */
data class FollowMarker(val x: Float, val y: Float, val length: Float, val until: Long)

/** Everything the engine needs from the view; kept free of Android so traces can drive it. */
interface FollowHost {
    /** The visible part of the page in page units, or null while it is off screen. */
    fun viewport(): InkBox?
    /** Screen pixels per page unit. */
    val scale: Float
    /** True while a stroke or another gesture is in progress. */
    val busy: Boolean
    /** Moves the content by screen pixels; returns what was actually applied after clamping. */
    fun panBy(dx: Float, dy: Float): Pair<Float, Float>
    /** Pen ink overlapping [area]. */
    fun marks(area: InkBox): List<InkMark>
    /** Calls [WritingFollow.tick] after [delayMs], or on the next frame when it is zero. */
    fun schedule(delayMs: Long)
    fun cancelSchedule()
    fun status(status: FollowStatus)
    fun redraw(delayMs: Long = 0)
}

/** Pen-up gaps between strokes that continued a line: the writer's own rhythm. */
internal class WritingRhythm {
    private val gaps = ArrayDeque<Long>()
    fun add(gap: Long) {
        if (gap !in 1..2000) return
        gaps.addLast(gap)
        while (gaps.size > 24) gaps.removeFirst()
    }
    private fun percentile(p: Float): Long? =
        if (gaps.size < 4) null else gaps.sorted()[((gaps.size - 1) * p).roundToInt()]

    /** A word gap must not trigger a move; at the visible edge a letter gap is enough. */
    fun pauseMs(base: Int, urgent: Boolean): Int =
        if (urgent) min(base, (percentile(.5f)?.let { (it * .6f).roundToInt() } ?: 120).coerceIn(80, 300))
        else max(base, ((percentile(.75f) ?: 0L) + 80).toInt().coerceAtMost(1000))

    fun returnPauseMs(base: Int): Int = max(base, ((percentile(.75f) ?: 0L) + 250).toInt().coerceAtMost(1800))
}

/**
 * Keeps the writing in a comfortable place on screen.
 *
 * Every pen-up re-reads the current line from the ink on the page ([LineReader]) and then plans one
 * move towards an absolute target. Nothing is accumulated except the writer's rhythm, so a move that
 * is interrupted, clamped or undone can never be applied twice or drift.
 *
 * Rules a writer can rely on:
 * - Touching down stops any pending or running move; the next pen-up replans from where the view is.
 * - The first stroke after you navigate (pinch, pan, page jump, Back) never moves the view.
 * - Writing behind the end of the line (a correction) or somewhere new holds the view still.
 * - Only progress moves it: the line growing towards its end, or the natural next line below.
 */
class WritingFollow(private val host: FollowHost) {
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            stop(); history.clear(); armed = false; cursor = null
            report(if (value) readyMessage() else "Writing follow is off")
        }
    var paused = false
        set(value) {
            if (field == value) return
            field = value
            stop(); armed = false
            report(readyMessage())
        }
    var preferences = FollowPreferences()
        set(value) { if (field != value) { field = value; navigated() } }
    var hand = WritingHand.RIGHT
        set(value) { if (field != value) { field = value; navigated() } }
    var guides: List<WritingGuide> = emptyList()
    /** Answer boundaries inferred from response lines in the unannotated PDF. */
    var areas: List<AnswerArea> = emptyList()
    var page = FollowPage()
        private set

    private enum class Relation { FIRST, PROGRESS, CORRECTION, NEXT, JUMP }
    private data class Plan(val vx: Float, val vy: Float, val dueAt: Long, val durationMs: Int, val toLine: WritingLine?)
    private data class BackEntry(val dx: Float, val dy: Float, val cursor: WritingLine?, val columnStart: Float?, val columnEnd: Float?)

    /** The line last written, as read from the ink. */
    private var line: WritingLine? = null
    /** A line placed by Next line that has not been written on yet. */
    private var cursor: WritingLine? = null
    /** Where lines of this paragraph start and (on a canvas) end, in page units. */
    private var columnStart: Float? = null
    private var columnEnd: Float? = null
    private var armed = false
    private var penDownAt: Long? = null
    private var lastUp: Long? = null
    private val rhythm = WritingRhythm()
    private val motion = FollowMotion()
    private var plan: Plan? = null
    private var moving: BackEntry? = null
    private var movingToLine = false
    private val history = ArrayDeque<BackEntry>()
    private var message = "Write to start following"
    var marker: FollowMarker? = null
        private set

    val status get() = FollowStatus(message, paused, history.isNotEmpty())
    val isMoving get() = plan != null || motion.active
    /** True only while the view is actually travelling, not while a move waits for its pause. */
    val isGliding get() = motion.active

    /** A different page or a rebound view: nothing written elsewhere applies here. */
    fun reset(page: FollowPage) {
        stop()
        this.page = page
        line = null; cursor = null; columnStart = null; columnEnd = null
        armed = false; marker = null; lastUp = null; penDownAt = null
        history.clear()
        report(readyMessage())
    }

    /** The writer moved the view: forget planned moves and let the next stroke settle in place. */
    fun navigated() {
        val had = isMoving || history.isNotEmpty() || cursor != null
        stop()
        history.clear(); armed = false; cursor = null
        if (had || message != readyMessage()) report(readyMessage())
    }

    /** Any deliberate touch stops movement at once. */
    fun touched() {
        if (!isMoving) return
        val returning = plan?.toLine != null || movingToLine
        stop()
        report(if (returning) "Line return cancelled · keep writing" else "Stopped · keep writing")
    }

    fun penDown(now: Long) {
        touched()
        penDownAt = now
    }

    fun strokeFinished(points: List<InkPoint>, now: Long) {
        val gap = penDownAt?.let { down -> lastUp?.let { down - it } }
        penDownAt = null
        lastUp = now
        if (!enabled) return
        if (paused) { report(readyMessage()); return }
        val seed = InkMark.of(points) ?: return
        val floor = line?.body ?: 0f
        val read = LineReader.read(seed, host.marks(LineReader.window(seed.box, max(floor, seed.box.height))),
            guides, preferences.mode, floor, line?.takeIf { it.measuredPitch }?.pitch)
        val current = (read as? LineRead.Line)?.line
        if (current == null) {
            // A dot or crossbar at the end of the line restarts whatever its touch-down interrupted.
            val last = line
            if (read == LineRead.Minor && last != null && armed && finishing(seed.box, last)) plan(last, Relation.PROGRESS, now)
            else report("Holding still · keep writing")
            return
        }
        val relation = relate(cursor ?: line, current, seed.box)
        line = current
        cursor = null
        when (relation) {
            Relation.PROGRESS -> gap?.let(rhythm::add)
            Relation.NEXT -> joinColumn(current)
            Relation.FIRST, Relation.JUMP -> startColumn(current)
            Relation.CORRECTION -> Unit
        }
        val move = armed && (relation == Relation.PROGRESS || relation == Relation.NEXT)
        armed = true
        if (!move) {
            report(if (relation == Relation.CORRECTION) "Holding for your correction" else "Following from here")
            return
        }
        plan(current, relation, now)
    }

    /** Moves to the start of the next line now. Works while paused: it is an explicit request. */
    fun nextLine(now: Long) {
        if (host.busy) return
        val view = host.viewport() ?: return
        // Right after turning follow on, or on a fresh page, the latest visible ink is the line.
        val from = cursor ?: line ?: host.marks(view).lastOrNull()?.let { latest ->
            (LineReader.read(latest, host.marks(LineReader.window(latest.box, latest.box.height)), guides,
                preferences.mode) as? LineRead.Line)?.line?.also { line = it; startColumn(it) }
        }
        val next = if (from != null) nextAfter(from) ?: return report(endMessage(from))
            else firstGuideIn(view) ?: return report("Write a line first, then Next line")
        stop()
        val (vx, vy) = lineTarget(next, view)
        plan = Plan(vx, vy, now, preferences.glideMs, next)
        tick(now)
    }

    /** Undoes the last follow move, one at a time. */
    fun back(now: Long) {
        if (host.busy) return
        stop()
        val entry = history.removeLastOrNull() ?: return report("No earlier view")
        host.panBy(-entry.dx, -entry.dy)
        cursor = entry.cursor; columnStart = entry.columnStart; columnEnd = entry.columnEnd
        armed = false; marker = null
        host.redraw()
        report(if (paused) "View restored · paused" else "View restored · write to continue")
    }

    fun tick(now: Long) {
        if (host.busy) { stop(); return }
        plan?.let { pending ->
            if (now < pending.dueAt) { host.schedule(pending.dueAt - now); return }
            plan = null
            if (!begin(pending, now)) return
        }
        if (!motion.active) return
        val step = motion.step(now)
        val applied = if (step.dx != 0f || step.dy != 0f) host.panBy(step.dx, step.dy) else 0f to 0f
        motion.applied(applied.first, applied.second)
        // A clamp at the document's edge ends the move; it never counts as travel.
        val blocked = (abs(step.dx) >= .5f || abs(step.dy) >= .5f) && applied.first == 0f && applied.second == 0f
        if (step.finished || blocked) finish() else host.schedule(0)
    }

    /** The marker to draw, if it has not expired. */
    fun marker(now: Long): FollowMarker? = marker?.takeIf { now < it.until }

    private fun begin(pending: Plan, now: Long): Boolean {
        val view = host.viewport() ?: return false
        val scale = host.scale
        moving = BackEntry(0f, 0f, cursor, columnStart, columnEnd)
        movingToLine = pending.toLine != null
        pending.toLine?.let { next ->
            cursor = next
            marker = FollowMarker(next.start(preferences.direction), next.baseline,
                max(next.body * 3f, view.width * .06f) * if (preferences.direction == WritingDirection.LTR) 1f else -1f,
                now + MARKER_MS)
            host.redraw(); host.redraw(MARKER_MS + 16)
        }
        motion.start(-pending.vx * scale, -pending.vy * scale, pending.durationMs)
        if (!motion.active) { finish(); return false }
        report(if (movingToLine) "Moving to the next line · touch down to stop" else "Following · touch down to stop")
        return true
    }

    private fun finish() {
        val wasLine = movingToLine
        val moved = motion.moved
        record()
        report(when {
            paused -> "Paused · tap Resume when ready"
            wasLine -> if (moved) "Next line · Back restores the view" else "Next line"
            moved -> "Following · Back restores the view"
            else -> "View is at the edge · pan to continue"
        })
    }

    /** Stops planned and running movement, keeping whatever already moved undoable. */
    private fun stop() {
        plan = null
        if (motion.active) record()
        host.cancelSchedule()
    }

    private fun record() {
        val entry = moving
        if (entry != null && motion.moved) {
            history.addLast(entry.copy(dx = motion.appliedX, dy = motion.appliedY))
            while (history.size > MAX_BACK) history.removeFirst()
        }
        moving = null; movingToLine = false
        motion.reset()
    }

    private fun plan(l: WritingLine, relation: Relation, now: Long) {
        val view = host.viewport() ?: return
        val scale = host.scale
        if (l.body * scale < MIN_BODY_PX) { report("Zoom in to follow · Next line still works"); return }
        val text = preferences.mode == FollowMode.TEXT
        val atEnd = text && atLineEnd(l)
        if (atEnd && relation == Relation.PROGRESS && preferences.automaticReturn) {
            val next = nextAfter(l) ?: return report(endMessage(l))
            val delay = rhythm.returnPauseMs(preferences.returnPauseMs)
            val (vx, vy) = lineTarget(next, view)
            plan = Plan(vx, vy, now + delay, preferences.glideMs, next)
            report("Next line in ${FollowPreferences.seconds(delay)} · touch down to cancel")
            host.schedule(delay.toLong())
            return
        }
        val (vx, reach) = sideways(l, view)
        val vy = vertical(l.baseline, view)
        val settled = if (atEnd) "Line end · tap Next line" else "Following"
        if (abs(vx) * scale < .5f && abs(vy) * scale < .5f) { report(settled); return }
        // Near the visible edge the pen is about to run out of room: a letter gap is enough.
        val urgent = reach > .92f
        val delay = rhythm.pauseMs(preferences.pauseMs, urgent)
        plan = Plan(vx, vy, now + delay, if (urgent) min(preferences.glideMs, 200) else preferences.glideMs, null)
        report(settled)
        host.schedule(delay.toLong())
    }

    /** Sideways viewport travel in page units, and how far across the view the writing reached. */
    private fun sideways(l: WritingLine, view: InkBox): Pair<Float, Float> {
        val ltr = preferences.direction == WritingDirection.LTR
        val w = view.width
        if (w <= 0f) return 0f to 0f
        val reach = if (ltr) (l.right - view.left) / w else (view.right - l.left) / w
        if (preferences.mode != FollowMode.TEXT || reach <= preferences.sidewaysTrigger) return 0f to reach
        // A hand on the written side of the pen hides what was just written: leave more of it showing.
        val handOnWriting = (hand == WritingHand.LEFT) == ltr
        var shift = (reach - if (handOnWriting) .47f else .42f) * w
        lineEnd(l)?.let { end ->
            // Never glide past the end of the line: the end stays in view, so it can be reached.
            val room = if (ltr) end + w * .12f - view.right else view.left - (end - w * .12f)
            shift = shift.coerceAtMost(max(0f, room))
        }
        return (if (ltr) shift else -shift) to reach
    }

    /** Upward travel once the line sinks into the band above the writer's hand. */
    private fun vertical(baseline: Float, view: InkBox): Float {
        val target = view.top + view.height * preferences.height
        val keep = preferences.keepHeight || preferences.mode == FollowMode.MATH
        // Without height keeping, only the bottom edge (where the hand would run out of room) triggers.
        val limit = if (keep) target + view.height * preferences.verticalBand else view.bottom - view.height * EDGE_BAND
        return if (baseline > limit) baseline - target else 0f
    }

    /** Puts a line's start at the writing height and, if it is out of comfortable view, near the leading edge. */
    private fun lineTarget(next: WritingLine, view: InkBox): Pair<Float, Float> {
        val vy = next.baseline - (view.top + view.height * preferences.height)
        if (preferences.mode != FollowMode.TEXT || view.width <= 0f) return 0f to vy
        val ltr = preferences.direction == WritingDirection.LTR
        val start = next.start(preferences.direction)
        val fraction = if (ltr) (start - view.left) / view.width else (view.right - start) / view.width
        if (fraction in .03f..0.6f) return 0f to vy
        val vx = if (ltr) start - (view.left + view.width * .08f) else start - (view.right - view.width * .08f)
        return vx to vy
    }

    private fun relate(previous: WritingLine?, current: WritingLine, seed: InkBox): Relation {
        previous ?: return Relation.FIRST
        val body = current.body
        val near = current.left <= previous.right + body * 4f && current.right >= previous.left - body * 4f
        if (preferences.mode == FollowMode.MATH) {
            if (!near || current.bottom < previous.top - body || current.top > previous.bottom + previous.pitch * 3f) return Relation.JUMP
            return if (seed.bottom >= previous.bottom - body || previous.strokes == 0) Relation.PROGRESS else Relation.CORRECTION
        }
        val pitch = max(previous.pitch, body * 1.2f)
        val dy = current.baseline - previous.baseline
        val same = near && if (previous.guide != null && current.guide != null) previous.guide == current.guide
            else abs(dy) < pitch * .5f
        val direction = preferences.direction
        if (same) {
            val frontier = previous.frontier(direction)
            val ahead = if (direction == WritingDirection.LTR) seed.right >= frontier - body * .5f
                else seed.left <= frontier + body * .5f
            return if (ahead || previous.strokes == 0) Relation.PROGRESS else Relation.CORRECTION
        }
        val start = columnStart ?: previous.start(direction)
        val aligned = abs(current.start(direction) - start) <= max(body * 6f, (previous.right - previous.left) * .25f) ||
            (current.left <= previous.right && current.right >= previous.left)
        return if (dy in pitch * .5f..pitch * 2.6f && aligned) Relation.NEXT else Relation.JUMP
    }

    private fun startColumn(l: WritingLine) {
        val ltr = preferences.direction == WritingDirection.LTR
        columnStart = l.start(preferences.direction)
        // A canvas has no printed edge: the line ends where the writer could see it end.
        columnEnd = host.viewport()?.takeIf { page.infinite && l.guide == null }?.let { view ->
            val margin = view.width * .06f
            if (ltr) max(view.right - margin, l.right + l.body) else min(view.left + margin, l.left - l.body)
        }
    }

    /** A hanging or first-line indent keeps the paragraph's leading edge. */
    private fun joinColumn(l: WritingLine) {
        val start = l.start(preferences.direction)
        val column = columnStart
        val joined = if (column == null || abs(start - column) > l.body * 6f) start
            else if (preferences.direction == WritingDirection.LTR) min(start, column) else max(start, column)
        // After navigation a canvas column may have no end yet; measure it from this line.
        if (columnEnd == null) startColumn(l)
        columnStart = joined
    }

    private fun lineEnd(l: WritingLine): Float? {
        val ltr = preferences.direction == WritingDirection.LTR
        l.guide?.let { return if (ltr) it.right else it.left }
        areaOf(l)?.let { return if (ltr) it.right else it.left }
        if (!page.infinite) return if (ltr) page.width - PAGE_MARGIN else PAGE_MARGIN
        return columnEnd
    }

    private fun lineStart(l: WritingLine): Float? {
        val ltr = preferences.direction == WritingDirection.LTR
        l.guide?.let { return if (ltr) it.left else it.right }
        areaOf(l)?.let { return if (ltr) it.left else it.right }
        if (!page.infinite) return if (ltr) PAGE_MARGIN else page.width - PAGE_MARGIN
        return columnStart
    }

    /** Prefer the rule's block; maths working may extend past its baseline. */
    private fun areaOf(l: WritingLine): AnswerArea? =
        l.guide?.block?.let { areas.getOrNull(it) }
            ?: WritingGuides.areaAt(areas, (l.left + l.right) / 2f, l.baseline)

    /** A full line that reached its end; a short note scribbled near the edge is not one. */
    private fun atLineEnd(l: WritingLine): Boolean {
        val end = lineEnd(l) ?: return false
        val start = lineStart(l) ?: return false
        val width = abs(end - start)
        if (width < l.body * 4f) return false
        val margin = max(l.body * 2.5f, width * .06f)
        val near = if (preferences.direction == WritingDirection.LTR) l.right >= end - margin else l.left <= end + margin
        return near && l.right - l.left >= width * .4f
    }

    private fun nextAfter(l: WritingLine): WritingLine? {
        val direction = preferences.direction
        if (preferences.mode == FollowMode.MATH) {
            val area = areaOf(l)
            val below = guides.filter {
                l.left <= it.right && l.right >= it.left && it.y > l.bottom + l.body * .3f &&
                    (area == null || area.contains((it.left + it.right) / 2f, it.y))
            }.minByOrNull { it.y }
            val y = below?.y ?: (l.bottom + l.pitch)
            if ((!page.infinite && y > page.height - PAGE_MARGIN) || (area != null && y > area.bottom)) return null
            return placed(y, l.start(direction), l, below)
        }
        val start = columnStart ?: l.start(direction)
        l.guide?.let { guide ->
            // Printed answer blocks end at their last rule: never spill into the next question.
            val next = WritingGuides.next(guide, guides) ?: return null
            return placed(next.y, start.coerceIn(next.left, next.right), l, next)
        }
        val y = l.baseline + l.pitch
        if (!page.infinite && y > page.height - PAGE_MARGIN) return null
        // The last response line ends the answer area, even if the writing did not snap to it.
        areaOf(l)?.let { if (y > it.bottom) return null }
        return placed(y, start, l, null)
    }

    private fun firstGuideIn(view: InkBox): WritingLine? {
        val guide = guides.filter { it.y in (view.top + view.height * .1f)..view.bottom && it.right >= view.left && it.left <= view.right }
            .minByOrNull { it.y } ?: return null
        val spacing = WritingGuides.spacing(guide, guides) ?: 28f
        val start = if (preferences.direction == WritingDirection.LTR) guide.left else guide.right
        return WritingLine(guide.y, spacing * .45f, start, start, guide.y - spacing * .45f, guide.y, spacing, guide, measuredPitch = true)
    }

    private fun placed(y: Float, start: Float, from: WritingLine, guide: WritingGuide?) =
        WritingLine(y, from.body, start, start, y - from.body, y, guide?.let { WritingGuides.spacing(it, guides) } ?: from.pitch,
            guide, strokes = 0, measuredPitch = from.measuredPitch || guide != null)

    private fun finishing(box: InkBox, l: WritingLine): Boolean {
        val ltr = preferences.direction == WritingDirection.LTR
        val x = if (ltr) box.right else box.left
        return abs(x - l.frontier(preferences.direction)) <= l.body * 3f && box.bottom in (l.top - l.body)..(l.bottom + l.body)
    }

    private fun endMessage(l: WritingLine) = when {
        l.guide != null || areaOf(l) != null -> "End of this answer area"
        !page.infinite -> "End of the page"
        else -> "No next line"
    }

    private fun readyMessage() = when {
        !enabled -> "Writing follow is off"
        paused -> "Paused · tap Resume when ready"
        else -> "Write to start following"
    }

    private fun report(text: String) {
        message = text
        host.status(status)
    }

    companion object {
        /** Handwriting smaller than this on screen is readable without moving the view. */
        const val MIN_BODY_PX = 8f
        /** The printed margin on ruled pages; blank pages use the same one. */
        const val PAGE_MARGIN = 36f
        const val MARKER_MS = 1400L
        /** Fraction of the view's bottom that counts as running out of room. */
        const val EDGE_BAND = .12f
        const val MAX_BACK = 12
    }
}
