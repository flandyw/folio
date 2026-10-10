package com.folio.notes

import kotlin.math.hypot

/**
 * A straight-edge ruler in page units, from end [a] to end [b]. Pen strokes on a freehand tool snap onto
 * its edge once they come near it; a finger drags an end to swing it or the body to move it. Session-only:
 * the ruler is never written to the page. Free of Android types so the rules stay cheap to exercise.
 */
data class RulerLine(val ax: Float, val ay: Float, val bx: Float, val by: Float) {
    /** What a finger landed on: one end, the body between them, or nothing. */
    enum class Grab { NONE, START, END, BODY }

    fun length(): Float = hypot(bx - ax, by - ay)

    /** The nearest point on the segment to [p]. */
    private fun closest(p: InkPoint): InkPoint {
        val dx = bx - ax; val dy = by - ay
        val squared = dx * dx + dy * dy
        if (squared <= 0f) return InkPoint(ax, ay, p.pressure)
        val t = (((p.x - ax) * dx + (p.y - ay) * dy) / squared).coerceIn(0f, 1f)
        return InkPoint(ax + t * dx, ay + t * dy, p.pressure)
    }

    /** Distance from [p] to the segment. */
    fun distance(p: InkPoint): Float {
        val q = closest(p)
        return hypot(p.x - q.x, p.y - q.y)
    }

    /** Which part of the ruler a press at [p] grabs. Ends take precedence over the body. */
    fun grab(p: InkPoint, reach: Float): Grab = when {
        hypot(p.x - ax, p.y - ay) <= reach -> Grab.START
        hypot(p.x - bx, p.y - by) <= reach -> Grab.END
        distance(p) <= reach -> Grab.BODY
        else -> Grab.NONE
    }

    /** [p] itself, or its projection onto the edge when it lies within [reach] of it. */
    fun snap(p: InkPoint, reach: Float): InkPoint {
        val q = closest(p)
        return if (hypot(p.x - q.x, p.y - q.y) <= reach) InkPoint(q.x, q.y, p.pressure) else p
    }

    /**
     * The ruler after a finger moved to [to]. Ends keep [MIN_LENGTH] so the edge never collapses to a point
     * that no stroke could follow. [dx]/[dy] move the body; ends simply follow the finger.
     */
    fun dragged(grab: Grab, to: InkPoint, dx: Float, dy: Float): RulerLine = when (grab) {
        Grab.START -> RulerLine(to.x, to.y, bx, by).takeIf { it.length() >= MIN_LENGTH } ?: this
        Grab.END -> RulerLine(ax, ay, to.x, to.y).takeIf { it.length() >= MIN_LENGTH } ?: this
        Grab.BODY -> RulerLine(ax + dx, ay + dy, bx + dx, by + dy)
        Grab.NONE -> this
    }

    companion object {
        /** Shortest ruler a finger can make; below this the edge gives no guidance. */
        const val MIN_LENGTH = 40f

        /**
         * Where a new ruler starts: across the middle of a finite page, or a horizontal run through the
         * origin on an infinite canvas, where new pictures and text also start.
         */
        fun initial(width: Float, height: Float, infinite: Boolean): RulerLine =
            if (infinite) RulerLine(-280f, 0f, 280f, 0f)
            else RulerLine(width * 0.12f, height * 0.5f, width * 0.88f, height * 0.5f)
    }
}
