package com.folio.notes

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An on-screen straightedge in page units: a band centred on ([cx], [cy]) and turned [degrees]
 * clockwise from horizontal. The band has a fixed size *on screen*, so its thickness and length
 * in page units come from the caller ([half] across, [halfLength] along) and follow the zoom.
 *
 * Pen strokes that start on or near one of its two edges run along that edge for the whole stroke
 * (see [edgeAt] and [onEdge]); one finger slides it, two fingers turn it, and the angle settles on
 * whole multiples of [SNAP_STEP] when it comes close. Session-only: never part of the page. Free of
 * Android types so the rules stay cheap to exercise.
 */
data class RulerLine(val cx: Float, val cy: Float, val degrees: Float = 0f) {
    private val radians get() = Math.toRadians(degrees.toDouble())
    /** Unit vector along the ruler. */
    val ux: Float get() = cos(radians).toFloat()
    val uy: Float get() = sin(radians).toFloat()
    /** Unit vector across it, pointing from the top edge (-1) to the bottom edge (+1). */
    val nx: Float get() = -uy
    val ny: Float get() = ux

    /** Distance of [p] along the ruler from its centre. */
    fun along(x: Float, y: Float): Float = (x - cx) * ux + (y - cy) * uy
    /** Signed distance of [p] across the ruler from its centre line. */
    fun across(x: Float, y: Float): Float = (x - cx) * nx + (y - cy) * ny

    /** True when ([x], [y]) lies on the band itself. */
    fun onBand(x: Float, y: Float, half: Float, halfLength: Float): Boolean =
        abs(across(x, y)) <= half && abs(along(x, y)) <= halfLength

    /**
     * The edge a pen landing at ([x], [y]) should follow: -1 for the top edge, +1 for the bottom,
     * 0 for neither. A press on the band goes to the nearer edge; outside it, only within [reach].
     */
    fun edgeAt(x: Float, y: Float, half: Float, halfLength: Float, reach: Float): Int {
        if (abs(along(x, y)) > halfLength + reach) return 0
        val d = across(x, y)
        if (abs(d) > half + reach) return 0
        return if (d < 0f) -1 else 1
    }

    /** [p] carried straight across onto edge [side] (-1 top, +1 bottom), keeping its place along the ruler. */
    fun onEdge(p: InkPoint, side: Int, half: Float): InkPoint {
        val a = along(p.x, p.y)
        val offset = side * half
        return InkPoint(cx + ux * a + nx * offset, cy + uy * a + ny * offset, p.pressure)
    }

    /** Slid by ([dx], [dy]) without turning. */
    fun moved(dx: Float, dy: Float): RulerLine = copy(cx = cx + dx, cy = cy + dy)

    /**
     * Turned by [delta] degrees about the pivot ([px], [py]) and then slid by ([dx], [dy]): one
     * step of a two-finger twist, where the pivot is the fingers' midpoint. The raw angle is kept
     * so slow turns accumulate; [settled] gives the angle to show and draw along.
     */
    fun twisted(px: Float, py: Float, delta: Float, dx: Float, dy: Float): RulerLine {
        val r = Math.toRadians(delta.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val ox = cx - px; val oy = cy - py
        return RulerLine(px + ox * c - oy * s + dx, py + ox * s + oy * c + dy, normalize(degrees + delta))
    }

    /** This ruler with its angle settled onto a nearby multiple of [SNAP_STEP]. */
    fun settled(): RulerLine = snapAngle(degrees).let { if (it == degrees) this else copy(degrees = it) }

    /** The angle for people: 0–180°, the way a protractor reads a line rather than a direction. */
    fun readout(): Int = ((normalize(degrees) % 180f + 180f) % 180f).roundToInt() % 180

    companion object {
        /** The angle settles on whole multiples of this step… */
        const val SNAP_STEP = 15f
        /** …once it comes within this many degrees of one. */
        const val SNAP_WITHIN = 2.5f

        /** [degrees] in -180..180. */
        fun normalize(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        fun snapAngle(degrees: Float): Float {
            val nearest = (degrees / SNAP_STEP).roundToInt() * SNAP_STEP
            return if (abs(degrees - nearest) <= SNAP_WITHIN) normalize(nearest) else degrees
        }

        /** Angle, in degrees, of the line from ([ax], [ay]) to ([bx], [by]). */
        fun angle(ax: Float, ay: Float, bx: Float, by: Float): Float =
            Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()

        /** The signed smallest turn from [from] to [to], so crossing ±180° never spins the ruler around. */
        fun turn(from: Float, to: Float): Float = normalize(to - from)
    }
}
