package com.folio.notes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pen-up gaps between strokes that continued a line: the writer's own rhythm. */
internal class WritingRhythm {
    private val gaps = ArrayDeque<Long>()
    fun add(gap: Long) {
        if (gap !in 1..2000) return
        gaps.addLast(gap)
        while (gaps.size > 24) gaps.removeFirst()
    }
    fun clear() = gaps.clear()
    private fun percentile(p: Float): Long? =
        if (gaps.size < 4) null else gaps.sorted()[((gaps.size - 1) * p).roundToInt()]
    /** A typical gap between strokes, once enough have been seen. */
    fun typicalMs(): Long? = percentile(.5f)
    /** A word gap: long enough that three quarters of the writer's gaps were shorter. */
    fun wordGapMs(): Long? = percentile(.75f)

    /** A word gap must not trigger a move; at the visible edge a letter gap is enough. */
    fun pauseMs(base: Int, urgent: Boolean): Int =
        if (urgent) min(base, (percentile(.5f)?.let { (it * .4f).roundToInt() } ?: 80).coerceIn(40, 180))
        else max(base, ((percentile(.75f) ?: 0L) + 80).toInt().coerceAtMost(1000))

    /**
     * A full line returns once the pen has rested a little longer than one of this writer's word gaps.
     * With no room left for another word the pen is almost certainly done, so a little more than a
     * typical letter gap is enough.
     */
    fun returnPauseMs(base: Int, noRoom: Boolean): Int =
        if (noRoom) max(base / 2, ((percentile(.5f) ?: 0L) + 150).toInt()).coerceAtMost(700)
        else max(base, ((percentile(.75f) ?: 0L) + 150).toInt()).coerceAtMost(1200)
}

/** Something the learner noticed that the writer may want to switch on. It is only ever offered. */
enum class FollowSuggestion { AUTOMATIC_RETURN, MATHS_MODE, RIGHT_TO_LEFT, LEFT_TO_RIGHT }

/** Which kind of automatic move an outcome is about. */
internal enum class MoveKind { GLIDE, RETURN }

/** A short window of recent yes/no outcomes. */
private class Window(private val size: Int) {
    private val items = ArrayDeque<Boolean>()
    fun add(value: Boolean) { items.addLast(value); while (items.size > size) items.removeFirst() }
    fun clear() = items.clear()
    val count get() = items.size
    val yes get() = items.count { it }
    fun rate(minimum: Int): Float = if (items.size < minimum) 0f else items.count { it }.toFloat() / items.size
}

/**
 * What writing follow has noticed about *this writer*, shared by every page they write on.
 *
 * Nothing here describes ink: it is habit (rhythm, where lines wrap, how wide a word is, where on
 * screen they like to write, how fast they go) and feedback (a return they cancelled, a move they
 * undid). It lives for the session, every effect is bounded so it can only nudge what the settings
 * choose, and [forget] puts it all back. The engine still re-reads the ink on every pen-up.
 */
class FollowLearner {
    internal val rhythm = WritingRhythm()

    /** How far along its line (0..1) this writer usually gets before starting the next one. */
    var wrapReach: Float? = null
        private set
    /** Room a further word needs, in letter heights, measured from the writer's own words. */
    var wordRoom: Float? = null
        private set
    /** Letters of progress along a line per second while writing. */
    var bodiesPerSecond: Float? = null
        private set
    var suggestion: FollowSuggestion? = null
        private set
    /** Set by Next line when it turned the page: until this uptime the new page carries on to its first line. */
    var carryUntil = 0L

    private val heights = ArrayDeque<Float>()
    private val glides = Window(10)
    private val returns = Window(8)
    private var earlyTaps = 0
    private var manualReturns = 0
    private var held = false
    private val directionVotes = ArrayDeque<Int>()
    private val mathLines = ArrayDeque<Pair<Int, Boolean>>()
    private val rowVotes = Window(6)
    private var alignedRows = 0
    private val dismissed = mutableSetOf<FollowSuggestion>()
    var moves = 0
        private set
    var cancelled = 0
        private set
    var undone = 0
        private set

    // ---- Habit ----

    fun learnWrap(reach: Float) { wrapReach = wrapReach?.let { (it + reach) / 2f } ?: reach }

    fun learnWordRoom(bodies: Float) {
        if (!bodies.isFinite()) return
        val value = bodies.coerceIn(MIN_WORD_ROOM, MAX_WORD_ROOM)
        wordRoom = wordRoom?.let { it * .6f + value * .4f } ?: value
    }

    /** Where on the screen (0 top, 1 bottom) the writer chose to start writing, after panning there themselves. */
    fun learnHeight(fraction: Float) {
        if (fraction !in .25f..0.85f) return
        heights.addLast(fraction)
        while (heights.size > 8) heights.removeFirst()
    }

    /** How far the writer's own placements sit from the [base] height setting, bounded so the setting stays the prior. */
    fun heightShift(base: Float): Float {
        if (heights.size < MIN_HEIGHT_SAMPLES) return 0f
        val median = heights.sorted()[heights.size / 2]
        return (median - base).coerceIn(-MAX_HEIGHT_SHIFT, MAX_HEIGHT_SHIFT)
    }

    /** Progress along a line over one stroke cycle ([ms] from the last pen-up to this one). */
    fun learnSpeed(bodies: Float, ms: Long) {
        if (ms !in 40..1500 || bodies !in .15f..8f) return
        val rate = bodies / (ms / 1000f)
        bodiesPerSecond = bodiesPerSecond?.let { it * .7f + rate * .3f } ?: rate
    }

    // ---- Feedback ----

    internal fun outcome(kind: MoveKind, bad: Boolean) {
        moves++
        if (bad) cancelled++
        when (kind) {
            MoveKind.GLIDE -> glides.add(bad)
            MoveKind.RETURN -> {
                returns.add(bad)
                if (bad) earlyTaps = max(0, earlyTaps - 1)
                if (returns.count >= HOLD_MIN_OUTCOMES && returns.rate(1) >= HOLD_RATE) held = true
            }
        }
    }

    fun undoneMove() { undone++ }

    /** The writer pressed Next line before an automatic return fired. */
    fun earlyTap() { earlyTaps = min(earlyTaps + 1, 4) }

    /** The writer pressed Next line themselves at the end of a full line, with automatic return off. */
    fun manualReturn() { manualReturns++ }

    /** Waiting is longer for a writer who keeps interrupting moves that were already under way. */
    fun glideFactor(): Float = 1f + .6f * glides.rate(4)

    /** A writer who keeps cancelling returns waits longer; one who keeps tapping Next line first waits less. */
    fun returnFactor(): Float = (1f + returns.rate(3)) * (1f - .1f * earlyTaps)

    /** True once most recent automatic returns were cancelled or undone: they stop until the writer asks again. */
    val returnHeld get() = held

    /** The writer turned automatic return on again (or asked to forget): it gets another chance. */
    fun releaseHold() { held = false; returns.clear(); earlyTaps = 0 }

    // ---- Maths ----

    /** [key] names one line of writing so a line seen again is not counted twice. */
    fun learnMathLine(key: Int, mathy: Boolean) {
        val last = mathLines.lastOrNull()
        if (last != null && last.first == key) { mathLines.removeLast(); mathLines.addLast(key to (last.second || mathy)) }
        else { mathLines.addLast(key to mathy); while (mathLines.size > 6) mathLines.removeFirst() }
    }

    /** A new row of working began under the `=` of the one above (true) or at its start (false). */
    fun learnRow(underEquals: Boolean) {
        rowVotes.add(underEquals)
        alignedRows = rowVotes.yes
    }

    /** Working is lined up on its `=` signs when the writer's rows mostly start under the one above. */
    val mathAligned get() = rowVotes.count >= 1 && alignedRows * 2 > rowVotes.count

    // ---- Direction ----

    /** +1 for a stroke written to the right of the previous one on a line, -1 to the left. */
    fun learnDirection(sign: Int) {
        if (sign == 0) return
        directionVotes.addLast(sign)
        while (directionVotes.size > 30) directionVotes.removeFirst()
    }

    // ---- Suggestions ----

    /** Offers at most one change the writer's habits point to, once, and never twice if they decline. */
    fun evaluate(preferences: FollowPreferences) {
        val current = suggestion
        if (!preferences.adaptive) { suggestion = null; return }
        if (current != null && valid(current, preferences)) return
        suggestion = SUGGESTIONS.firstOrNull { it !in dismissed && valid(it, preferences) }
    }

    private fun valid(s: FollowSuggestion, p: FollowPreferences): Boolean = when (s) {
        FollowSuggestion.RIGHT_TO_LEFT -> p.direction == WritingDirection.LTR && directionShare(-1) >= DIRECTION_SHARE
        FollowSuggestion.LEFT_TO_RIGHT -> p.direction == WritingDirection.RTL && directionShare(1) >= DIRECTION_SHARE
        FollowSuggestion.MATHS_MODE -> p.mode == FollowMode.TEXT && mathLines.size >= 4 &&
            mathLines.toList().takeLast(5).count { it.second } >= 3
        FollowSuggestion.AUTOMATIC_RETURN -> p.mode == FollowMode.TEXT && !p.automaticReturn && manualReturns >= 3
    }

    private fun directionShare(sign: Int): Float =
        if (directionVotes.size < DIRECTION_VOTES) 0f else directionVotes.count { it == sign }.toFloat() / directionVotes.size

    /** The writer accepted or declined: either way it is not offered again this session. */
    fun resolve(s: FollowSuggestion) {
        dismissed += s
        if (suggestion == s) suggestion = null
        when (s) {
            FollowSuggestion.RIGHT_TO_LEFT, FollowSuggestion.LEFT_TO_RIGHT -> directionVotes.clear()
            FollowSuggestion.MATHS_MODE -> mathLines.clear()
            FollowSuggestion.AUTOMATIC_RETURN -> manualReturns = 0
        }
    }

    // ---- Everything it knows, for the settings panel ----

    /** Plain-language notes on what has been learned so far; empty before there is anything to say. */
    fun describe(preferences: FollowPreferences): List<String> = buildList {
        rhythm.wordGapMs()?.let { add("You pause about ${FollowPreferences.seconds(it.toInt())} between words, so moves wait a little longer than that.") }
        wrapReach?.let { add("Your lines usually wrap at about ${(it * 100).roundToInt()} % of the line.") }
        wordRoom?.let { add("A further word needs about ${"%.1f".format(it)} letter heights of room.") }
        if (heights.size >= MIN_HEIGHT_SAMPLES) {
            val shift = heightShift(preferences.height)
            if (abs(shift) >= .01f) add("You write at about ${((preferences.height + shift) * 100).roundToInt()} % of the screen height " +
                "(the setting is ${(preferences.height * 100).roundToInt()} %).")
        }
        bodiesPerSecond?.let { add("You write about ${"%.1f".format(it)} letters a second, so the view can move a little ahead of you.") }
        if (returns.count > 0) add("${returns.yes} of the last ${returns.count} automatic returns were cancelled or undone" +
            if (held) ", so they are on hold until you turn automatic return on again." else ".")
        if (mathAligned) add("Your working lines up on its = signs, so Next line returns there in Maths.")
        if (moves > 0) add("This session: $moves automatic moves, $cancelled interrupted or undone, $undone taken back.")
    }

    /** Forget everything, including offers the writer declined. */
    fun forget() {
        rhythm.clear(); wrapReach = null; wordRoom = null; bodiesPerSecond = null; suggestion = null
        heights.clear(); glides.clear(); returns.clear(); earlyTaps = 0; manualReturns = 0; held = false
        directionVotes.clear(); mathLines.clear(); rowVotes.clear(); alignedRows = 0; dismissed.clear()
        moves = 0; cancelled = 0; undone = 0; carryUntil = 0L
    }

    companion object {
        /** Used by every editor page, so what is learned on one page carries to the next. */
        val shared = FollowLearner()
        private val SUGGESTIONS = listOf(FollowSuggestion.RIGHT_TO_LEFT, FollowSuggestion.LEFT_TO_RIGHT,
            FollowSuggestion.MATHS_MODE, FollowSuggestion.AUTOMATIC_RETURN)
        const val MIN_WORD_ROOM = 2.5f
        const val MAX_WORD_ROOM = 10f
        const val MIN_HEIGHT_SAMPLES = 3
        const val MAX_HEIGHT_SHIFT = .1f
        const val HOLD_MIN_OUTCOMES = 5
        const val HOLD_RATE = .6f
        const val DIRECTION_VOTES = 14
        const val DIRECTION_SHARE = .85f
    }
}
