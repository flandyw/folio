package com.folio.notes

import kotlin.math.abs
import kotlin.math.hypot
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
    /** Canvas auto-return length, measured in viewport widths when a paragraph starts. */
    val canvasLineScreens: Int = DEFAULT_CANVAS_SCREENS,
    /**
     * Let the engine learn from how this writer writes (where they like the line, how fast they go, which
     * moves they cancel). Every effect is small and bounded, and the settings above stay the starting point.
     */
    val adaptive: Boolean = true,
) {
    private fun blend(relaxed: Float, responsive: Float) = relaxed + (responsive - relaxed) * feel.coerceIn(0f, 1f)
    /** How far across the view the end of the writing may go before the view glides sideways. */
    val sidewaysTrigger get() = blend(.86f, .68f)
    /** How far below [height] the line may sink, as a fraction of the view, before the view moves up. */
    val verticalBand get() = blend(.22f, .1f)
    val pauseMs get() = blend(600f, 200f).roundToInt()
    /** How long a full line waits for more writing before it returns; the writer's own word gap can raise it. */
    val returnPauseMs get() = blend(700f, 300f).roundToInt()
    /** The shortest sideways glide; longer travel takes longer so the page never snaps. */
    val glideMs get() = blend(480f, 300f).roundToInt()
    /** How far ahead of the pen, in seconds of the writer's own speed, the edge of the view is judged. */
    val lookaheadS get() = blend(1f, 2f)

    companion object {
        const val DEFAULT_FEEL = .5f
        const val DEFAULT_HEIGHT = .55f
        const val MIN_HEIGHT = .35f
        const val MAX_HEIGHT = .7f
        const val DEFAULT_CANVAS_SCREENS = 2
        fun clampCanvasScreens(value: Int) = value.coerceIn(1, 4)
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
    /** A change the writer's habits point to; shown as an offer, never applied on its own. */
    val suggestion: FollowSuggestion? = null,
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
    /** Moves on to the next page of the notebook, if there is one; true when it went. */
    fun nextPage(): Boolean = false
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
class WritingFollow(private val host: FollowHost, val learner: FollowLearner = FollowLearner()) {
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            navigated()
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
        set(value) {
            if (field == value) return
            // Asking for automatic return again is a fresh chance for a return the writer kept cancelling.
            if (!field.automaticReturn && value.automaticReturn) learner.releaseHold()
            field = value; navigated(); recordContext()
        }
    var hand = WritingHand.RIGHT
        set(value) { if (field != value) { field = value; navigated() } }
    var guides: List<WritingGuide> = emptyList()
        set(value) { if (field != value) { field = value; recordContext() } }
    /** Answer boundaries inferred from response lines in the unannotated PDF, then from blank space under questions. */
    var areas: List<AnswerArea> = emptyList()
        set(value) { if (field != value) { field = value; recordContext() } }
    var page = FollowPage()
        private set
    /** Records this session for tuning when set; see [FollowTrace]. */
    var trace: FollowTrace? = null
        set(value) { field = value; recordContext() }

    private enum class Relation { FIRST, PROGRESS, CORRECTION, NEXT, JUMP }
    private data class Plan(val left: Float, val top: Float, val dueAt: Long, val durationMs: Int,
                            val toLine: WritingLine?, val source: WritingLine?, val settling: Boolean = false,
                            /** Planned by the engine after a pause, not asked for; only these teach the learner. */
                            val automatic: Boolean = false,
                            /** How long a hovering pen has already held this move back. */
                            val held: Int = 0,
                            /** A jump to another answer area starts a new paragraph column here. */
                            val column: Float? = null)
    private data class BackEntry(val dx: Float, val dy: Float, val cursor: WritingLine?, val columnStart: Float?, val columnEnd: Float?,
                                 val kind: MoveKind? = null, val at: Long = 0)

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
    private val motion = FollowMotion()
    private var plan: Plan? = null
    /** A full line whose return waits for the view to finish revealing room for its last words. */
    private var resume: WritingLine? = null
    private var moving: BackEntry? = null
    private var movingToLine = false
    /** What kind of automatic move is running, so cancelling or finishing it can teach the learner. */
    private var movingAuto: MoveKind? = null
    /** An automatic return has placed the next line and the writer has not yet written on it. */
    private var placedByAuto = false
    /** The previous stroke, only to tell which way this writer's strokes run. */
    private var lastSeed: InkBox? = null
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverAt: Long? = null
    private var clock = 0L
    private val history = ArrayDeque<BackEntry>()
    private var message = "Write to start following"
    var marker: FollowMarker? = null
        private set

    private val rhythm get() = learner.rhythm
    val status get() = FollowStatus(message, paused, history.isNotEmpty(), learner.suggestion)
    val isMoving get() = plan != null || motion.active
    /** True only while the view is actually travelling, not while a move waits for its pause. */
    val isGliding get() = motion.active

    /** A different page or a rebound view: nothing written elsewhere applies here. */
    fun reset(page: FollowPage) {
        stop()
        this.page = page
        line = null; cursor = null; columnStart = null; columnEnd = null
        armed = false; marker = null; lastUp = null; penDownAt = null
        placedByAuto = false; lastSeed = null; hoverAt = null
        history.clear()
        recordContext()
        report(readyMessage())
    }

    /** The writer moved the view: forget planned moves and let the next stroke settle in place. */
    fun navigated() {
        val had = isMoving || history.isNotEmpty() || cursor != null
        trace?.navigated(clock)
        stop()
        history.clear(); armed = false; cursor = null; line = null
        columnStart = null; columnEnd = null; marker = null; lastUp = null; penDownAt = null
        placedByAuto = false; lastSeed = null; hoverAt = null
        host.redraw()
        if (had || message != readyMessage()) report(readyMessage())
    }

    /** Any deliberate touch stops movement at once. */
    fun touched() {
        trace?.touch(clock)
        hoverAt = null
        if (!isMoving) return
        val returning = plan?.toLine != null || movingToLine
        // Only a move the writer could see, already under way, counts when they interrupt it. A pending one is
        // invisible: finishing the last word of a line cancels a pending return at every stroke, and says nothing.
        if (motion.active) movingAuto?.let { learner.outcome(it, bad = true) }
        if (returning) placedByAuto = false
        stop()
        report(if (returning) "Line return cancelled · keep writing" else "Stopped · keep writing")
    }

    fun penDown(now: Long) {
        clock = now
        trace?.down(now)
        touched()
        penDownAt = now
    }

    /**
     * The pen is hovering over [x], [y] (page units) without touching. A move that would carry the view
     * away from where the pen points is dropped, and one the pen is still hovering at the edge of is
     * given a moment: a hovering pen is about to write.
     */
    fun hover(x: Float, y: Float, now: Long) {
        clock = now
        if (!enabled || paused) return
        trace?.hover(now, x, y)
        hoverX = x; hoverY = y; hoverAt = now
        val pending = plan ?: return
        if (motion.active || pending.toLine != null || host.busy) return
        val view = host.viewport() ?: return
        // After a sideways glide the view is somewhere else: a pen hovering over writing it would leave behind
        // is almost certainly about to correct it.
        val margin = view.width * .02f
        if (x < pending.left + margin || x > pending.left + view.width - margin || y < pending.top || y > pending.top + view.height) {
            stop()
            report("Holding still · the pen is over other writing")
        }
    }

    /** The pen left hover range: nobody is about to write, so a pending move need not wait out the pause. */
    fun hoverEnded(now: Long) {
        clock = now
        hoverAt = null
        if (!enabled || paused) return
        trace?.hoverEnd(now)
        val pending = plan ?: return
        if (motion.active) return
        val due = now + LIFT_MS
        if (due < pending.dueAt) {
            plan = pending.copy(dueAt = due)
            host.schedule(LIFT_MS)
        }
    }

    fun strokeFinished(points: List<InkPoint>, now: Long) = strokeFinished(InkMark.of(points), now)

    fun strokeFinished(stroke: InkMark?, now: Long) {
        clock = now
        val gap = penDownAt?.let { down -> lastUp?.let { down - it } }
        val previousUp = lastUp
        penDownAt = null
        lastUp = now
        if (!enabled) return
        stroke?.let { trace?.up(now, it, host.viewport(), host.scale) }
        if (paused) {
            line = null; cursor = null; columnStart = null; columnEnd = null; marker = null
            report(readyMessage()); return
        }
        val seed = stroke ?: return
        val floor = line?.body ?: 0f
        val window = host.marks(readWindow(seed.box, max(floor, seed.box.height)))
        val read = LineReader.read(seed, window, guides, preferences.mode, floor, line?.takeIf { it.measuredPitch }?.pitch)
        val current = (read as? LineRead.Line)?.line
        learnMaths(seed, window, current)
        if (current == null) {
            // A dot or crossbar at the end of the line restarts whatever its touch-down interrupted, and so
            // does the headline drawn over a word in scripts that hang their letters from one.
            val last = line?.let(::refreshLine).also { line = it }
            val finished = last != null && armed &&
                ((read == LineRead.Minor && finishing(seed.box, last)) || (read == LineRead.Rule && headline(seed.box, last)))
            if (finished) plan(last!!, Relation.PROGRESS, now)
            else report("Holding still · keep writing")
            return
        }
        val earlier = line
        val wasPlaced = cursor != null
        val previous = cursor ?: line
        val relation = relate(previous, current, seed.box)
        if (relation == Relation.NEXT && previous != null) learnReach(previous)
        learnFrom(relation, current, seed, earlier, wasPlaced, previousUp, now)
        lastSeed = seed.box
        line = current
        cursor = null
        when (relation) {
            Relation.PROGRESS -> { gap?.let(rhythm::add); extendCanvas(current) }
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

    /** Everything one finished stroke can teach the learner; none of it changes how the line itself is read. */
    private fun learnFrom(relation: Relation, current: WritingLine, seed: InkMark, earlier: WritingLine?,
                          wasPlaced: Boolean, previousUp: Long?, now: Long) {
        if (!preferences.adaptive) { placedByAuto = false; return }
        val direction = preferences.direction
        // Writing on the line an automatic return placed is the writer accepting it.
        if (placedByAuto) {
            if (wasPlaced && (relation == Relation.PROGRESS || relation == Relation.NEXT)) learner.outcome(MoveKind.RETURN, bad = false)
            placedByAuto = false
        }
        // Which way strokes run along a line, whatever the setting says.
        lastSeed?.let { before ->
            val dx = seed.box.centerX - before.centerX
            if (abs(seed.box.centerY - before.centerY) < current.body * .8f && abs(dx) in current.body * .3f..current.body * 10f) {
                learner.learnDirection(if (dx > 0f) 1 else -1)
            }
        }
        if (current.strokes >= 8) current.wordWidth?.let { learner.learnWordRoom(it / current.body) }
        // How fast the line grows per stroke cycle, to look ahead of the pen.
        if (relation == Relation.PROGRESS && !wasPlaced && earlier != null && previousUp != null) {
            val sign = if (direction == WritingDirection.LTR) 1f else -1f
            val progress = (current.frontier(direction) - earlier.frontier(direction)) * sign
            if (progress > 0f) learner.learnSpeed(progress / current.body, now - previousUp)
        }
        // Where on the screen the writer put a line they chose to start themselves.
        if (relation == Relation.FIRST || relation == Relation.JUMP) learnPlacement(current)
        // Rows of working that start under the `=` above, or at the start of the row above.
        if (preferences.mode == FollowMode.MATH && relation == Relation.PROGRESS && !wasPlaced && earlier != null) learnRow(current, earlier)
    }

    private fun learnPlacement(l: WritingLine) {
        val view = host.viewport() ?: return
        if (l.body * host.scale < MIN_BODY_PX || view.height <= 0f) return
        // A view held against the edge of the document says nothing about where the writer wanted the line.
        if (!page.infinite && (view.top <= 1f || view.bottom >= page.height - 1f)) return
        learner.learnHeight((l.baselineAt(l.start(preferences.direction)) - view.top) / view.height)
    }

    private fun learnRow(current: WritingLine, earlier: WritingLine) {
        val rowLeft = earlier.rowLeft ?: return
        val equals = earlier.equalsAt ?: return
        if (current.top <= earlier.bottom - current.body * .3f) return
        // An `=` at the very start of its row (a derivation's "= ...") says nothing about where the next row goes.
        if (abs(equals - rowLeft) <= current.body * 3f) return
        val start = current.left
        if (abs(start - equals) <= current.body * 1.5f) learner.learnRow(true)
        else if (abs(start - rowLeft) <= current.body * 1.5f) learner.learnRow(false)
    }

    /** In Text mode, writing that is full of `=` signs and fractions is worth a quiet offer to switch to Maths. */
    private fun learnMaths(seed: InkMark, window: List<InkMark>, current: WritingLine?) {
        if (!preferences.adaptive || preferences.mode != FollowMode.TEXT) return
        val body = current?.body ?: line?.body ?: max(seed.box.height, 2f)
        val y = seed.box.centerY
        val band = window.filter { abs(it.box.centerY - y) <= body * 3f }
        learner.learnMathLine((y / max(body * 2.2f, 8f)).roundToInt(), LineReader.mathEvidence(band, body))
    }

    /** Moves to the start of the next line now. Works while paused: it is an explicit request. */
    fun nextLine(now: Long): Boolean {
        clock = now
        if (!enabled || host.busy) return false
        trace?.next(now)
        // A return that was about to fire on its own and was beaten to it.
        val early = plan?.let { it.automatic && it.toLine != null } == true
        // Restore the source cursor before deciding where to go if a return was interrupted.
        stop()
        val view = host.viewport() ?: return false
        // Right after turning follow on, or on a fresh page, the latest visible ink is the line.
        val from = cursor ?: line?.let(::refreshLine)?.also { line = it } ?: visibleLine(view)?.also {
            line = it; startColumn(it)
        }
        from?.let(::learnReach)
        if (from != null) learnTap(from, early, now)
        var column: Float? = null
        val next = if (from == null) firstTargetIn(view) ?: run { report("Write a line first, then Next line"); return false }
            else nextAfter(from) ?: nextAnswerArea(from)?.let { area ->
                // The last line of an answer area: the next question that has no writing yet.
                column = if (preferences.direction == WritingDirection.LTR) area.left else area.right
                firstLineIn(area, from)
            } ?: run {
                if (!page.infinite && host.nextPage()) {
                    learner.carryUntil = now + CARRY_MS
                    report("Next page")
                } else report(endMessage(from))
                return false
            }
        goTo(next, from, view, now, column)
        return true
    }

    /** Plans and starts the carriage return to [next], which Back can undo. */
    private fun goTo(next: WritingLine, from: WritingLine?, view: InkBox, now: Long, column: Float? = null) {
        val (vx, vy) = lineTarget(next, view)
        plan = Plan(view.left + vx, view.top + vy, now, returnDuration(vx, vy), next, from?.takeIf { it.strokes > 0 }, column = column)
        tick(now)
    }

    /** Pressing Next line at the end of a full line is the writer asking for what automatic return would do. */
    private fun learnTap(from: WritingLine, early: Boolean, now: Long) {
        if (!preferences.adaptive || from.strokes == 0 || preferences.mode != FollowMode.TEXT || !atLineEnd(from)) return
        if (early) learner.earlyTap()
        else if (!preferences.automaticReturn && lastUp?.let { now - it <= TAP_WINDOW_MS } == true) learner.manualReturn()
        learner.evaluate(preferences)
    }

    /**
     * The view of a page the writer carried on to with Next line: once its printed rules or answer areas
     * are known, the first place to write is where Next line takes them.
     */
    fun continueFromPreviousPage(now: Long) {
        if (learner.carryUntil == 0L) return
        if (now > learner.carryUntil) { learner.carryUntil = 0L; return }
        if (!enabled || host.busy || line != null || cursor != null) return
        val view = host.viewport() ?: return
        // A fresh page starts at its top: even a rule right under the top edge is where to begin.
        val next = firstTargetIn(view, skipTop = 0f) ?: return
        learner.carryUntil = 0L
        clock = now
        goTo(next, null, view, now, next.start(preferences.direction))
    }

    /** The writer accepted or declined an offer from [FollowStatus.suggestion]. */
    fun resolveSuggestion(suggestion: FollowSuggestion) {
        learner.resolve(suggestion)
        report(message)
    }

    /** Undoes the last follow move, one at a time. */
    fun back(now: Long) {
        clock = now
        if (host.busy) return
        trace?.back(now)
        stop()
        val entry = history.removeLastOrNull() ?: return report("No earlier view")
        // Taking back a move the engine made by itself, soon after, says it came too early or was unwanted.
        if (preferences.adaptive) {
            entry.kind?.takeIf { now - entry.at <= BACK_WINDOW_MS }?.let { learner.outcome(it, bad = true) }
            if (entry.kind != null) learner.undoneMove()
        }
        placedByAuto = false
        host.panBy(-entry.dx, -entry.dy)
        cursor = entry.cursor; columnStart = entry.columnStart; columnEnd = entry.columnEnd
        line = null
        armed = false; marker = null
        host.redraw()
        report(if (paused) "View restored · paused" else "View restored · write to continue")
    }

    fun tick(now: Long) {
        clock = now
        if (host.busy) { stop(); return }
        plan?.let { pending ->
            if (now < pending.dueAt) { host.schedule(pending.dueAt - now); return }
            // Undo, erase or moved ink during the pause invalidates the move, including a return.
            if (pending.source != null && refreshLine(pending.source) != pending.source) {
                stop(); line = null; armed = false
                report("Writing changed · write to continue")
                return
            }
            // A pen still hovering where the writing ends is about to write: give it a moment more.
            if (hoverHolds(pending, now)) {
                plan = pending.copy(dueAt = now + HOVER_HOLD_MS, held = pending.held + HOVER_HOLD_MS)
                host.schedule(HOVER_HOLD_MS.toLong())
                return
            }
            plan = null
            if (!begin(pending, now)) return
        }
        if (!motion.active) return
        val step = motion.step(now)
        val applied = if (step.dx != 0f || step.dy != 0f) host.panBy(step.dx, step.dy) else 0f to 0f
        motion.applied(applied.first, applied.second)
        if (applied.first != 0f || applied.second != 0f) trace?.moved(now, applied.first, applied.second)
        // A clamp at the document's edge ends the move; it never counts as travel.
        val blocked = (abs(step.dx) >= .5f || abs(step.dy) >= .5f) && applied.first == 0f && applied.second == 0f
        if (step.finished || blocked) finish(now) else host.schedule(0)
    }

    /** True while a fresh hover sits at the writing's frontier and the move is not one made to give the pen room. */
    private fun hoverHolds(pending: Plan, now: Long): Boolean {
        val source = pending.source ?: return false
        val seen = hoverAt ?: return false
        if (pending.toLine != null || pending.settling || !pending.automatic || pending.held >= HOVER_MAX_HOLD_MS) return false
        if (now - seen > HOVER_FRESH_MS) return false
        val reach = max(source.body * 6f, wordRoom(source) * source.body)
        val frontier = source.frontier(preferences.direction)
        return abs(hoverX - frontier) <= reach && hoverY in (source.top - source.pitch * .6f)..(source.bottom + source.pitch * .6f)
    }

    /** The marker to draw, if it has not expired. */
    fun marker(now: Long): FollowMarker? = marker?.takeIf { now < it.until }

    private fun begin(pending: Plan, now: Long): Boolean {
        val view = host.viewport() ?: return false
        val scale = host.scale
        movingAuto = if (pending.automatic) (if (pending.toLine != null) MoveKind.RETURN else MoveKind.GLIDE) else null
        moving = BackEntry(0f, 0f, cursor, columnStart, columnEnd, movingAuto, now)
        movingToLine = pending.toLine != null
        pending.column?.let { columnStart = it; if (!page.infinite) columnEnd = null }
        pending.toLine?.let { next ->
            cursor = next
            placedByAuto = pending.automatic
            marker = FollowMarker(next.start(preferences.direction), next.baseline,
                max(next.body * 3f, view.width * .06f) * if (preferences.direction == WritingDirection.LTR) 1f else -1f,
                now + MARKER_MS)
            host.redraw(); host.redraw(MARKER_MS + 16)
        }
        motion.start((view.left - pending.left) * scale, (view.top - pending.top) * scale, pending.durationMs, pending.settling)
        if (!motion.active) { finish(now); return false }
        report(if (movingToLine) "Moving to the next line · touch down to stop" else "Following · touch down to stop")
        return true
    }

    private fun finish(now: Long) {
        val wasLine = movingToLine
        val moved = motion.moved
        val kind = movingAuto
        val waiting = resume
        resume = null
        record()
        if (kind == MoveKind.GLIDE && moved && preferences.adaptive) learner.outcome(MoveKind.GLIDE, bad = false)
        report(when {
            paused -> "Paused · tap Resume when ready"
            wasLine -> if (moved) "Next line · Back restores the view" else "Next line"
            moved -> "Following · Back restores the view"
            else -> "View is at the edge · pan to continue"
        })
        // The room is revealed: now the full line can start its return, if the ink is unchanged.
        if (!paused && !wasLine && waiting != null && refreshLine(waiting) == waiting) plan(waiting, Relation.PROGRESS, now, roomFirstAllowed = false)
    }

    /** Stops planned and running movement, keeping whatever already moved undoable. */
    private fun stop() {
        plan = null; resume = null
        if (motion.active && movingToLine) {
            cursor = moving?.cursor
            marker = null
            host.redraw()
        }
        if (motion.active) record()
        host.cancelSchedule()
    }

    private fun record() {
        val entry = moving
        if (entry != null && motion.moved) {
            history.addLast(entry.copy(dx = motion.appliedX, dy = motion.appliedY))
            while (history.size > MAX_BACK) history.removeFirst()
        }
        moving = null; movingToLine = false; movingAuto = null
        motion.reset()
    }

    private fun recordContext() { trace?.context(page, preferences, guides, areas, clock) }

    private fun plan(l: WritingLine, relation: Relation, now: Long, roomFirstAllowed: Boolean = true) {
        val view = host.viewport() ?: return
        val scale = host.scale
        if (l.body * scale < MIN_BODY_PX) { report("Zoom in to follow · Next line still works"); return }
        val text = preferences.mode == FollowMode.TEXT
        val atEnd = text && atLineEnd(l)
        val (vx, reach) = sideways(l, view)
        val vy = vertical(l.baselineAt(l.frontier(preferences.direction)), view)
        // Near the visible edge the pen is about to run out of room: a letter gap is enough.
        val urgent = reach > .92f || (1f - reach) * view.width < l.body * 1.5f
        var settled = if (atEnd) "Line end · tap Next line" else "Following"
        if (atEnd && relation == Relation.PROGRESS && preferences.automaticReturn) {
            val next = nextAfter(l)
            // Inside the end zone with the pen at the edge of the screen and line left to write on,
            // room comes first; the return follows once the writer settles.
            val roomFirst = roomFirstAllowed && urgent && !pastEnd(l)
            resume = if (roomFirst) l else null
            if (preferences.adaptive && learner.returnHeld) {
                // The writer keeps cancelling these: leave the return to them until they ask for it again.
                settled = "Line end · tap Next line · automatic return is on hold"
            } else if (next != null && !occupied(next, l) && !roomFirst) {
                // After a room-making glide the writer has already been still for a while.
                val base = if (roomFirstAllowed) rhythm.returnPauseMs(preferences.returnPauseMs, pastEnd(l))
                    else min(rhythm.returnPauseMs(preferences.returnPauseMs, true), AFTER_ROOM_MS)
                val delay = if (preferences.adaptive) (base * learner.returnFactor()).roundToInt().coerceAtLeast(MIN_RETURN_MS) else base
                val (rx, ry) = lineTarget(next, view)
                plan = Plan(view.left + rx, view.top + ry, now + delay, returnDuration(rx, ry), next, l, automatic = true)
                report("Next line in ${FollowPreferences.seconds(delay)} · touch down to cancel")
                host.schedule(delay.toLong())
                return
            } else {
                // Finishing the last answer line still needs sideways/vertical room at high zoom.
                if (next == null) settled = endMessage(l)
                else if (!roomFirst) settled = "Next line already has ink · tap Next line to move there"
            }
        }
        if (abs(vx) * scale < .5f && abs(vy) * scale < .5f) { report(settled); return }
        val base = rhythm.pauseMs(preferences.pauseMs, urgent)
        val delay = if (preferences.adaptive && !urgent) (base * learner.glideFactor()).roundToInt() else base
        // A move made to give the pen room is expected to be cut short by the next stroke, so it teaches nothing.
        plan = Plan(view.left + vx, view.top + vy, now + delay, glideDuration(vx, vy, urgent), null, l, settling = urgent, automatic = !urgent)
        report(settled)
        host.schedule(delay.toLong())
    }

    /** The height the line is kept at: the setting, nudged by where this writer actually puts their lines. */
    private fun writingHeight(): Float {
        val base = preferences.height
        if (!preferences.adaptive) return base
        return FollowPreferences.clampHeight(base + learner.heightShift(base))
    }

    /** Sideways viewport travel in page units, and how far across the view the writing reached. */
    private fun sideways(l: WritingLine, view: InkBox): Pair<Float, Float> {
        val ltr = preferences.direction == WritingDirection.LTR
        val w = view.width
        if (w <= 0f) return 0f to 0f
        val reach = if (ltr) (l.right - view.left) / w else (view.right - l.left) / w
        val trigger = min(preferences.sidewaysTrigger, 1f - (l.body * 2f / w).coerceIn(.12f, .4f))
        // A quick writer covers more line before the move finishes, so the edge is judged a little ahead of the pen.
        val speed = if (preferences.adaptive) learner.bodiesPerSecond else null
        val lead = speed?.let { min(it * l.body * preferences.lookaheadS, w * MAX_LEAD) } ?: 0f
        val predicted = lead > 0f && reach > MIN_PREDICT_REACH && reach <= trigger && reach + lead / w > trigger
        if (preferences.mode != FollowMode.TEXT || (reach <= trigger && !predicted)) return 0f to reach
        // A hand on the written side of the pen hides what was just written: leave more of it showing.
        val handOnWriting = (hand == WritingHand.LEFT) == ltr
        var shift = (reach - if (handOnWriting) .47f else .42f) * w
        if (predicted) shift = max(shift, w * MIN_PREDICT_SHIFT)
        lineEnd(l)?.let { end ->
            // Never glide past the end of the line: the end stays in view, so it can be reached.
            val room = if (ltr) end + w * .12f - view.right else view.left - (end - w * .12f)
            shift = shift.coerceAtMost(max(0f, room))
        }
        return (if (ltr) shift else -shift) to reach
    }

    /** Upward travel once the line sinks into the band above the writer's hand. */
    private fun vertical(baseline: Float, view: InkBox): Float {
        val target = view.top + view.height * writingHeight()
        val keep = preferences.keepHeight || preferences.mode == FollowMode.MATH
        // Without height keeping, only the bottom edge (where the hand would run out of room) triggers.
        val limit = if (keep) target + view.height * preferences.verticalBand else view.bottom - view.height * EDGE_BAND
        return if (baseline > limit) baseline - target else 0f
    }

    /**
     * A carriage return: the line's start goes to the writing height and, unless the whole line already
     * fits on screen, to the leading edge, so every return lands in the same place however far the
     * view had travelled. A line that fits is only nudged until all of it is visible.
     */
    private fun lineTarget(next: WritingLine, view: InkBox): Pair<Float, Float> {
        val vy = next.baseline - (view.top + view.height * writingHeight())
        if (preferences.mode != FollowMode.TEXT || view.width <= 0f) return 0f to vy
        val ltr = preferences.direction == WritingDirection.LTR
        val start = next.start(preferences.direction)
        val end = lineEnd(next)
        if (end != null && abs(end - start) <= view.width * .96f) {
            val margin = view.width * .02f
            val low = min(start, end)
            val high = max(start, end)
            return when {
                low < view.left + margin -> low - (view.left + margin)
                high > view.right - margin -> high - (view.right - margin)
                else -> 0f
            } to vy
        }
        val lead = view.width * LEAD
        return (if (ltr) start - (view.left + lead) else start - (view.right - lead)) to vy
    }

    private fun relate(previous: WritingLine?, current: WritingLine, seed: InkBox): Relation {
        previous ?: return Relation.FIRST
        if (previous.guide?.block != current.guide?.block) return Relation.JUMP
        val body = current.body
        val near = current.left <= previous.right + body * 4f && current.right >= previous.left - body * 4f
        if (preferences.mode == FollowMode.MATH) {
            if (!near || current.bottom < previous.top - body || current.top > previous.bottom + previous.pitch * 3f) return Relation.JUMP
            return if (seed.bottom >= previous.bottom - body || previous.strokes == 0) Relation.PROGRESS else Relation.CORRECTION
        }
        val pitch = max(previous.pitch, body * 1.2f)
        val dy = current.baseline - previous.baselineAt((current.left + current.right) * .5f)
        val same = near && if (previous.guide != null && current.guide != null) previous.guide == current.guide
            else abs(dy) < pitch * .5f
        val direction = preferences.direction
        if (same) {
            val frontier = previous.frontier(direction)
            val ahead = if (direction == WritingDirection.LTR) seed.right >= frontier - body * .5f
                else seed.left <= frontier + body * .5f
            return if (ahead || previous.strokes == 0) Relation.PROGRESS else Relation.CORRECTION
        }
        if (previous.guide != null && current.guide != null && WritingGuides.next(previous.guide, guides) != current.guide) {
            return Relation.JUMP
        }
        val start = columnStart ?: previous.start(direction)
        val aligned = abs(current.start(direction) - start) <= max(body * 6f, (previous.right - previous.left) * .25f) ||
            (current.left <= previous.right && current.right >= previous.left)
        return if (dy in pitch * .5f..pitch * 2.6f && aligned) Relation.NEXT else Relation.JUMP
    }

    private fun startColumn(l: WritingLine) {
        val ltr = preferences.direction == WritingDirection.LTR
        columnStart = l.start(preferences.direction)
        // Fix a useful paragraph width at its first stroke, independent of subsequent glides.
        columnEnd = host.viewport()?.takeIf { page.infinite && l.guide == null }?.let { view ->
            val length = max(view.width * FollowPreferences.clampCanvasScreens(preferences.canvasLineScreens), l.body * 8f)
            l.start(preferences.direction) + if (ltr) length else -length
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

    /** Continuing past a suggested canvas wrap is intentional; reveal another screen of room. */
    private fun extendCanvas(l: WritingLine) {
        if (!page.infinite || l.guide != null || !preferences.automaticReturn) return
        val end = columnEnd ?: return
        val sign = if (preferences.direction == WritingDirection.LTR) 1f else -1f
        if ((l.frontier(preferences.direction) - end) * sign <= l.body) return
        val length = max(host.viewport()?.width ?: 0f, l.body * 8f)
        columnEnd = end + sign * max(length, (l.frontier(preferences.direction) - end) * sign + l.body * 2f)
    }

    /** Read the whole response/paragraph width, even when tiny handwriting extends far off screen. */
    private fun readWindow(box: InkBox, body: Float): InkBox {
        val local = LineReader.window(box, body)
        val guide = LineReader.guideFor(box, guides)
        val left = guide?.left ?: if (!page.infinite) PAGE_MARGIN else columnStart ?: local.left
        val right = guide?.right ?: if (!page.infinite) page.width - PAGE_MARGIN else columnStart ?: local.right
        return InkBox(min(local.left, left), local.top, max(local.right, right), local.bottom)
    }

    /** Stored geometry only locates ink to re-read; it can never stand in for deleted strokes. */
    private fun refreshLine(previous: WritingLine): WritingLine? {
        val marks = host.marks(readWindow(InkBox(previous.left, previous.top, previous.right, previous.bottom), previous.body))
        val seed = marks.lastOrNull { belongsTo(it, previous) } ?: return null
        return (LineReader.read(seed, marks, guides, preferences.mode, previous.body,
            previous.pitch.takeIf { previous.measuredPitch }) as? LineRead.Line)?.line
    }

    private fun belongsTo(mark: InkMark, l: WritingLine): Boolean {
        val box = mark.box
        if (mark.straight || box.height < l.body * .4f || box.height > l.body * 3.2f) return false
        if (l.guide != null) return LineReader.guideFor(box, guides) == l.guide
        val probe = box.top + box.height * .3f
        return if (preferences.mode == FollowMode.MATH) box.intersects(InkBox(l.left, l.top, l.right, l.bottom))
            else abs(probe - (l.baselineAt(box.centerX) - l.body * .7f)) < l.pitch * .45f
    }

    /** A trailing dot or underline must not hide the last visible line from explicit Next line. */
    private fun visibleLine(view: InkBox): WritingLine? {
        for (seed in host.marks(view).asReversed()) {
            val read = LineReader.read(seed, host.marks(readWindow(seed.box, seed.box.height)), guides, preferences.mode)
            if (read is LineRead.Line) return read.line
        }
        return null
    }

    /** Whether the next line already has handwriting. The line just written never counts: a tilted one passes through it. */
    private fun occupied(next: WritingLine, from: WritingLine): Boolean {
        val start = next.start(preferences.direction)
        val end = lineEnd(next) ?: start
        val box = InkBox(min(start, next.guide?.left ?: end), next.baseline - next.pitch,
            max(start, next.guide?.right ?: end), next.baseline + next.body)
        return host.marks(box).any { belongsTo(it, next) && !belongsTo(it, from) }
    }

    /**
     * The view travels at a readable pace: never faster than [GLIDE_PX_PER_MS] on average however far
     * it has to go, and never quicker than the writer's feel setting. An urgent move, made with the
     * pen about to run out of room, may be briefer but is still a glide, not a snap.
     */
    private fun glideDuration(vx: Float, vy: Float, urgent: Boolean): Int {
        val paced = (hypot(vx, vy) * host.scale / GLIDE_PX_PER_MS).roundToInt()
        val floor = if (urgent) URGENT_GLIDE_MS else preferences.glideMs
        val ceiling = if (urgent) URGENT_GLIDE_MAX_MS else FollowMotion.MAX_MS
        return paced.coerceIn(floor, max(floor, ceiling))
    }

    /**
     * A carriage return crosses the whole line, so it is brisker than following: waiting for it is
     * what the writer notices, not the pace of the glide itself.
     */
    private fun returnDuration(vx: Float, vy: Float): Int {
        val paced = (hypot(vx, vy) * host.scale / RETURN_PX_PER_MS).roundToInt()
        return paced.coerceIn(preferences.glideMs, max(preferences.glideMs, RETURN_MAX_MS))
    }

    private fun lineEnd(l: WritingLine): Float? {
        val ltr = preferences.direction == WritingDirection.LTR
        l.guide?.let { return if (ltr) it.right else it.left }
        areaOf(l)?.let { return if (ltr) it.right else it.left }
        if (!page.infinite) return if (ltr) page.width - PAGE_MARGIN else PAGE_MARGIN
        return columnEnd.takeIf { preferences.automaticReturn }
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

    /** How far along its line the writing has got, 0 at the start to 1 at the end. */
    private fun reachOf(l: WritingLine): Float? {
        val end = lineEnd(l) ?: return null
        val start = lineStart(l) ?: return null
        val width = abs(end - start)
        if (width < l.body * 4f) return null
        val travelled = if (preferences.direction == WritingDirection.LTR) l.frontier(preferences.direction) - start
            else start - l.frontier(preferences.direction)
        return (travelled / width).coerceIn(0f, 1.2f)
    }

    /** The writing has reached or passed the line's end, with no further room to write on it. */
    private fun pastEnd(l: WritingLine): Boolean {
        val end = lineEnd(l) ?: return true
        return if (preferences.direction == WritingDirection.LTR) l.right >= end - l.body else l.left <= end + l.body
    }

    /** Remember where a line was left for the next one, but only from lines that were really filled. */
    private fun learnReach(l: WritingLine) {
        if (l.strokes == 0) return
        val reach = reachOf(l)?.takeIf { it >= LEARN_MIN_REACH } ?: return
        learner.learnWrap(reach)
    }

    /**
     * Room one more word needs before the end of a line, in letter heights: measured from this line's own
     * words, else from this writer's words so far, else a typical guess.
     */
    private fun wordRoom(l: WritingLine): Float {
        if (!preferences.adaptive) return WORD_ROOM
        val own = l.wordWidth?.let { it / l.body }
        return (own ?: learner.wordRoom ?: WORD_ROOM).coerceIn(FollowLearner.MIN_WORD_ROOM, FollowLearner.MAX_WORD_ROOM)
    }

    /**
     * The line is full when another word would not fit before its end, or when it reaches where this
     * writer has been wrapping. A student does not write to the very edge of a printed response line,
     * so the end is a zone about one word wide; a short note near the edge is still not a full line.
     */
    private fun atLineEnd(l: WritingLine): Boolean {
        val end = lineEnd(l) ?: return false
        val start = lineStart(l) ?: return false
        val width = abs(end - start)
        val reach = reachOf(l) ?: return false
        if (reach < MIN_FULL_REACH || l.right - l.left < width * MIN_FULL_REACH) return false
        val zone = min(max(l.body * wordRoom(l), width * .02f), min(width * .2f, (host.viewport()?.width ?: width) * .3f))
        if (abs(end - l.frontier(preferences.direction)) <= zone ||
            (preferences.direction == WritingDirection.LTR && l.right >= end) ||
            (preferences.direction == WritingDirection.RTL && l.left <= end)) return true
        return learner.wrapReach?.let { reach >= max(MIN_LEARNED_REACH, it * .94f) } ?: false
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
            return placed(y, mathStart(l), l, below)
        }
        val start = columnStart ?: l.start(direction)
        l.guide?.let { guide ->
            // Printed answer blocks end at their last rule: never spill into the next question.
            val next = WritingGuides.next(guide, guides) ?: return null
            return placed(next.y, start.coerceIn(next.left, next.right), l, next)
        }
        // Unruled writing tilts, so the next line begins one pitch below where this one *began*.
        val y = l.baselineAt(start) + l.pitch
        if (!page.infinite && y > page.height - PAGE_MARGIN) return null
        // The last response line ends the answer area, even if the writing did not snap to it.
        areaOf(l)?.let { if (y > it.bottom) return null }
        return placed(y, start, l, null)
    }

    /**
     * Where the next row of working begins: under the `=` of this one when the writer has been lining their
     * working up on its equals signs, otherwise at the start of the whole row (not just the block of it that
     * touched the last stroke).
     */
    private fun mathStart(l: WritingLine): Float {
        val ltr = preferences.direction == WritingDirection.LTR
        if (ltr && preferences.adaptive && learner.mathAligned) l.equalsAt?.let { return it }
        return if (ltr) l.rowLeft ?: l.left else l.right
    }

    /** The first line to write on in a printed or inferred answer area. */
    private fun firstLineIn(area: AnswerArea, from: WritingLine): WritingLine {
        val ltr = preferences.direction == WritingDirection.LTR
        val index = areas.indexOf(area)
        val guide = guides.filter { it.block == index }.minByOrNull { it.y }
        if (guide != null) return placed(guide.y, if (ltr) guide.left else guide.right, from, guide)
        return placed(area.top + from.pitch, if (ltr) area.left else area.right, from, null).copy(slope = 0f)
    }

    private fun unanswered(area: AnswerArea) = host.marks(InkBox(area.left, area.top, area.right, area.bottom)).isEmpty()

    /**
     * The next answer area with nothing written in it: below this one in its column, then in the column
     * after it. Questions already answered are skipped.
     */
    private fun nextAnswerArea(from: WritingLine): AnswerArea? {
        if (areas.isEmpty()) return null
        val ltr = preferences.direction == WritingDirection.LTR
        val here = areaOf(from)
        val x0 = here?.left ?: from.left
        val x1 = here?.right ?: from.right
        val bottom = here?.bottom ?: from.bottom
        fun sameColumn(a: AnswerArea) = min(a.right, x1) - max(a.left, x0) >= min(a.right - a.left, x1 - x0) * .5f
        val others = areas.filter { it != here }
        val below = others.filter { sameColumn(it) && it.top >= bottom - 2f }.sortedBy { it.top }
        val beside = others.filter { !sameColumn(it) && if (ltr) it.left >= x1 - (x1 - x0) * .25f else it.right <= x0 + (x1 - x0) * .25f }
            .sortedWith(compareBy({ it.top }, { if (ltr) it.left else -it.right }))
        return (below + beside).firstOrNull(::unanswered)
    }

    /** Where to start from nothing: the first printed rule in view, else the first unanswered area in view. */
    private fun firstTargetIn(view: InkBox, skipTop: Float = .1f): WritingLine? {
        firstGuideIn(view, skipTop)?.let { return it }
        val ltr = preferences.direction == WritingDirection.LTR
        val area = areas.filter { it.bottom > view.top && it.top < view.bottom && it.right >= view.left && it.left <= view.right }
            .sortedWith(compareBy({ it.top }, { it.left })).firstOrNull(::unanswered) ?: return null
        val pitch = 28f
        val y = area.top + pitch
        val start = if (ltr) area.left else area.right
        return WritingLine(y, pitch * .45f, start, start, y - pitch * .45f, y, pitch)
    }

    private fun firstGuideIn(view: InkBox, skipTop: Float = .1f): WritingLine? {
        val guide = guides.filter { it.y in (view.top + view.height * skipTop)..view.bottom && it.right >= view.left && it.left <= view.right }
            .minByOrNull { it.y } ?: return null
        val spacing = WritingGuides.spacing(guide, guides) ?: 28f
        val start = if (preferences.direction == WritingDirection.LTR) guide.left else guide.right
        return WritingLine(guide.y, spacing * .45f, start, start, guide.y - spacing * .45f, guide.y, spacing, guide, measuredPitch = true)
    }

    private fun placed(y: Float, start: Float, from: WritingLine, guide: WritingGuide?) =
        WritingLine(y, from.body, start, start, y - from.body, y, guide?.let { WritingGuides.spacing(it, guides) } ?: from.pitch,
            guide, strokes = 0, measuredPitch = from.measuredPitch || guide != null,
            slope = if (guide == null) from.slope else 0f)

    private fun finishing(box: InkBox, l: WritingLine): Boolean {
        val ltr = preferences.direction == WritingDirection.LTR
        val x = if (ltr) box.right else box.left
        return abs(x - l.frontier(preferences.direction)) <= l.body * 3f && box.bottom in (l.top - l.body)..(l.bottom + l.body)
    }

    /**
     * A long flat stroke over the top of the line's letters, ending at the writing: the headline that
     * Devanagari and Bengali words hang from, drawn after the word. It finishes the word as a dot does.
     * An underline, which sits below the line, is not one.
     */
    private fun headline(box: InkBox, l: WritingLine): Boolean {
        val reach = l.body * 3f
        val frontier = l.frontier(preferences.direction)
        val ltr = preferences.direction == WritingDirection.LTR
        val end = if (ltr) box.right else box.left
        return box.centerY in (l.top - l.body * .9f)..(l.top + l.body * .6f) && box.bottom <= l.top + l.body * .8f &&
            box.right >= l.left && box.left <= l.right && abs(end - frontier) <= reach
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
        learner.evaluate(preferences)
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
        /** Average speed cap for a glide, in screen pixels per millisecond. */
        const val GLIDE_PX_PER_MS = 1.0f
        const val RETURN_PX_PER_MS = 2.5f
        const val RETURN_MAX_MS = 650
        const val URGENT_GLIDE_MS = 260
        const val URGENT_GLIDE_MAX_MS = 450
        /** The pause before a return that follows a glide made to reveal the line's last words. */
        const val AFTER_ROOM_MS = 150
        /** However eager the writer, a return never fires faster than this after the pen stops. */
        const val MIN_RETURN_MS = 150
        /** Where a line's start sits across the view after a return. */
        const val LEAD = .08f
        /** Room, in letter heights, a further word needs before the end of a line, before it has been measured. */
        const val WORD_ROOM = 4.5f
        const val MIN_FULL_REACH = .5f
        const val LEARN_MIN_REACH = .7f
        const val MIN_LEARNED_REACH = .6f
        /** A pen that leaves hover range is not about to write: a pending move runs this soon after. */
        const val LIFT_MS = 150L
        /** A pen hovering at the frontier holds a move back this long at a time, and for at most [HOVER_MAX_HOLD_MS]. */
        const val HOVER_HOLD_MS = 250
        const val HOVER_MAX_HOLD_MS = 1000
        /** A hover older than this no longer says where the pen is. */
        const val HOVER_FRESH_MS = 300L
        /** Looking ahead of the pen never reaches further than this fraction of the view, and needs this much reach first. */
        const val MAX_LEAD = .25f
        const val MIN_PREDICT_REACH = .5f
        const val MIN_PREDICT_SHIFT = .1f
        /** Taking back an automatic move within this long counts as saying it was unwanted. */
        const val BACK_WINDOW_MS = 8000L
        /** Pressing Next line this soon after the pen stopped, on a full line, is asking for a return. */
        const val TAP_WINDOW_MS = 8000L
        /** How long after Next line flipped the page the new page still carries on to its first line. */
        const val CARRY_MS = 4000L
    }
}
