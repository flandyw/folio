package com.folio.notes.editor.follow

import kotlin.math.abs
import kotlin.math.roundToInt

/** User intent is independent of the hand holding the pen. */
enum class WritingDirection { LTR, RTL }

enum class FollowMode { TEXT, MATH }

/**
 * Everything the writer chooses about following. Movement feel is three independent knobs
 * ([returnDelayMs], [room], [pace]); the thresholds and timings the engine reads are derived
 * from them, so no two settings fight over the same behaviour.
 */
data class FollowPreferences(
    val direction: WritingDirection = WritingDirection.LTR,
    val mode: FollowMode = FollowMode.TEXT,
    val automaticReturn: Boolean = false,
    val position: Float = .55f,
    val horizontalPosition: Float = .5f,
    val spacing: Float = 32f,
    /** Pen-up pause before following; automatic return may learn a longer line-break pause. */
    val returnDelayMs: Int = WritingFollow.DEFAULT_RETURN_MS,
    /** How far into the view you can write before it moves: 0 moves early, 1 waits for the edge. */
    val room: Float = DEFAULT_ROOM,
    /** How quickly the view travels: 0 gentle, 1 snappy. Sets glide length and next-line pace together. */
    val pace: Float = DEFAULT_PACE,
    val adaptiveSpacing: Boolean = true,
    val horizontalFollow: Boolean = true,
    val verticalFollow: Boolean = true,
    val autoSwitchAreas: Boolean = true,
    val minimumZoom: Float = 1.4f,
    /** How close to the answer margin a line must end before a return is offered; a writing habit, not a feel. */
    val endMargin: Float = .08f,
    /** Mark where the next line begins while a return is pending and until writing resumes. */
    val showLandingGuide: Boolean = true,
    /** Tint opacity of the visual hint; independent of movement thresholds. */
    val edgeStripOpacity: Float = .1f,
) {
    /** Sideways trigger, as a fraction across the view (mirrored for RTL). */
    val edgeThreshold: Float get() = ROOM_EDGE_MIN + ROOM_EDGE_SPAN * room.coerceIn(0f, 1f)
    /** Room below the writing height before the view moves down, as a fraction of the view. */
    val verticalDeadBand: Float get() = ROOM_BAND_MIN + ROOM_BAND_SPAN * room.coerceIn(0f, 1f)
    /** Minimum glide length; longer travel takes extra time. */
    val glideDurationMs: Int get() = knob(pace, GLIDE_GENTLE, WritingFollow.DEFAULT_GLIDE_MS.toFloat(), GLIDE_SNAPPY).roundToInt()
    /** Next-line glide pace: ms to cross one screen width or height. Sideways follow keeps the default. */
    val lineSpeedMs: Int get() = knob(pace, LINE_GENTLE, FollowGlide.MS_PER_VIEWPORT, LINE_SNAPPY).roundToInt()
    val automaticReturnDelayMs: Int get() = returnDelayMs.coerceIn(300, 2000)
    /** Fixed quiet period, independent of stroke history and distance from the visible edge. */
    val glideDelayMs: Int get() = automaticReturnDelayMs

    /** Actual sideways trigger and strip width use the same landing column and palm offset. */
    fun horizontalTarget(hand: WritingHand): Float = horizontalPosition + if (hand == WritingHand.RIGHT) -.02f else .02f
    fun horizontalTrigger(hand: WritingHand): Float = WritingFollow.horizontalTrigger(horizontalTarget(hand), direction, edgeThreshold)
    fun edgeStripWidth(hand: WritingHand): Float = horizontalTrigger(hand).let { if (direction == WritingDirection.LTR) 1f - it else it }
    fun maximumEdgeStripWidth(hand: WritingHand): Float = copy(room = 0f).edgeStripWidth(hand)

    /** Page units are A4 at 4 units per mm (840 x 1188), so users see millimetres. */
    val spacingMm: Float get() = spacing / UNITS_PER_MM
    /** Vertical writing height as a whole percent down the screen. */
    val positionPercent: Int get() = (position * 100).roundToInt()
    /** Horizontal writing column as a whole percent across the screen. */
    val horizontalPercent: Int get() = (horizontalPosition * 100).roundToInt()

    companion object {
        const val UNITS_PER_MM = 4f
        /** 85% across the view: the default strip is exactly 15% wide. */
        const val DEFAULT_ROOM = 5f / 6f
        const val DEFAULT_PACE = .6f
        private const val ROOM_EDGE_MIN = .6f
        private const val ROOM_EDGE_SPAN = .3f
        private const val ROOM_BAND_MIN = .07f
        private const val ROOM_BAND_SPAN = .2f
        private const val GLIDE_GENTLE = 700f
        private const val GLIDE_SNAPPY = 140f
        private const val LINE_GENTLE = 1500f
        private const val LINE_SNAPPY = 300f

        /** Piecewise-linear through the default at [DEFAULT_PACE], so the default stays exact. */
        private fun knob(t: Float, gentle: Float, default: Float, snappy: Float): Float {
            val v = t.coerceIn(0f, 1f)
            return if (v <= DEFAULT_PACE) gentle + (default - gentle) * v / DEFAULT_PACE
            else default + (snappy - default) * (v - DEFAULT_PACE) / (1f - DEFAULT_PACE)
        }
        private fun unknob(value: Float, gentle: Float, default: Float, snappy: Float): Float =
            if (value >= default) DEFAULT_PACE * (gentle - value) / (gentle - default)
            else DEFAULT_PACE + (1f - DEFAULT_PACE) * (default - value) / (default - snappy)
        private fun step(v: Float) = ((v.coerceIn(0f, 1f) * 20f).roundToInt() / 20f)

        /** Carries older thresholds over without rounding away the one-percent strip precision. */
        fun roomFrom(edgeThreshold: Float, verticalDeadBand: Float): Float = (
            ((edgeThreshold - ROOM_EDGE_MIN) / ROOM_EDGE_SPAN + (verticalDeadBand - ROOM_BAND_MIN) / ROOM_BAND_SPAN) / 2f).coerceIn(0f, 1f)
        /** One width control owns both the visible trigger and the downward dead band. */
        fun roomForStripWidth(width: Float): Float = ((1f - width - ROOM_EDGE_MIN) / ROOM_EDGE_SPAN).coerceIn(0f, 1f)
        /** Carries the older separate glide and next-line timings over to [pace]. */
        fun paceFrom(glideMs: Int, lineSpeedMs: Int): Float = step((
            unknob(glideMs.toFloat(), GLIDE_GENTLE, WritingFollow.DEFAULT_GLIDE_MS.toFloat(), GLIDE_SNAPPY) +
            unknob(lineSpeedMs.toFloat(), LINE_GENTLE, FollowGlide.MS_PER_VIEWPORT, LINE_SNAPPY)) / 2f)

        const val MIN_SPACING = 16f
        const val MAX_SPACING = 96f

        fun fromMm(mm: Float): Float = (mm * UNITS_PER_MM).coerceIn(MIN_SPACING, MAX_SPACING)

        fun returnDelayLabel(ms: Int): String = "${(if (ms % 100 == 0) "%.1f" else "%.2f").format(ms / 1000f)} s"
        fun paceLabel(pace: Float): String = when {
            pace < .35f -> "Gentle"
            pace <= .75f -> "Smooth"
            else -> "Snappy"
        }
    }
}

/**
 * Starting points for the three feel knobs. They never touch where you write, your hand,
 * direction, line spacing, line-end zone or automatic-return choice.
 */
enum class FollowFeel(val label: String, val returnDelayMs: Int, val room: Float, val pace: Float) {
    RELAXED("Relaxed", 1100, .9f, .2f),
    BALANCED("Balanced", WritingFollow.DEFAULT_RETURN_MS, FollowPreferences.DEFAULT_ROOM, FollowPreferences.DEFAULT_PACE),
    RESPONSIVE("Responsive", 400, .1f, .9f);

    fun apply(preferences: FollowPreferences) = preferences.copy(returnDelayMs = returnDelayMs, room = room, pace = pace)
    fun matches(preferences: FollowPreferences) = preferences.returnDelayMs == returnDelayMs &&
        abs(preferences.room - room) < .01f && abs(preferences.pace - pace) < .01f

    companion object {
        fun of(preferences: FollowPreferences): FollowFeel? = entries.firstOrNull { it.matches(preferences) }
    }
}
