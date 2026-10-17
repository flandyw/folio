package com.folio.notes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The filled shape of a variable-width pen stroke: the union of a disc at every centreline point and
 * the tangent hull joining each pair of neighbouring discs. That union is exactly what a round nib of
 * changing size sweeps, so width changes (pressure, soft ends) are smooth instead of stepping in runs.
 *
 * Every piece is convex and wound the same way (positive shoelace area in page coordinates), so a
 * non-zero fill paints the union once: no seams, no darker overlaps for translucent ink, and loops and
 * self-crossings fill correctly. Discs are drawn at the two ends and at joints where the stroke turns
 * or changes width; straight, even runs are hulls alone. Pure: it emits polygons to a [Sink].
 */
internal object InkOutline {
    interface Sink {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun close()
    }

    /** A disc's largest flat-side error, in page units. */
    private const val TOLERANCE = .015f
    /** How far apart (page units) two hulls' edges may meet at a joint before the joint's disc is needed. */
    private const val JOINT_SLACK = .004f

    /** [radius] gives each centre point's radius in page units. */
    fun build(centre: List<InkPoint>, radius: (Int) -> Float, sink: Sink) {
        val n = centre.size
        if (n == 0) return
        if (n == 1) { disc(centre[0].x, centre[0].y, radius(0), sink); return }
        disc(centre[0].x, centre[0].y, radius(0), sink)
        // Outward normals of the previous hull's two sides at the shared point, to see what a joint leaves open.
        var prevPlusX = 0f; var prevPlusY = 0f; var prevMinusX = 0f; var prevMinusY = 0f
        var havePrev = false
        for (i in 0 until n - 1) {
            val a = centre[i]; val b = centre[i + 1]
            val r1 = radius(i); val r2 = radius(i + 1)
            val dx = b.x - a.x; val dy = b.y - a.y
            val d = hypot(dx, dy)
            if (d <= abs(r1 - r2) || d < 1e-5f) {
                // One disc holds the other (or they coincide): the larger disc is the whole segment.
                if (r2 > r1) disc(b.x, b.y, r2, sink) else disc(a.x, a.y, r1, sink)
                havePrev = false
                continue
            }
            val ux = dx / d; val uy = dy / d
            val nx = -uy; val ny = ux
            val sa = (r1 - r2) / d
            val ca = sqrt((1f - sa * sa).coerceAtLeast(0f))
            // Unit vectors from each centre to its tangent points on the two sides.
            val px = ux * sa + nx * ca; val py = uy * sa + ny * ca
            val mx = ux * sa - nx * ca; val my = uy * sa - ny * ca
            // Where the path turns or the width changes, the two hulls meeting here leave part of this disc open
            // (a notch reaching in towards the centre); the disc closes it. Straight, even runs need none.
            if (havePrev && maxOf(hypot(prevPlusX - px, prevPlusY - py), hypot(prevMinusX - mx, prevMinusY - my)) * r1 > JOINT_SLACK) {
                disc(a.x, a.y, r1, sink)
            }
            quad(a.x + px * r1, a.y + py * r1, b.x + px * r2, b.y + py * r2,
                b.x + mx * r2, b.y + my * r2, a.x + mx * r1, a.y + my * r1, sink)
            prevPlusX = px; prevPlusY = py; prevMinusX = mx; prevMinusY = my
            havePrev = true
        }
        val last = centre[n - 1]
        disc(last.x, last.y, radius(n - 1), sink)
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, sink: Sink) {
        val area = (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3)
        if (area >= 0f) {
            sink.moveTo(x0, y0); sink.lineTo(x1, y1); sink.lineTo(x2, y2); sink.lineTo(x3, y3)
        } else {
            sink.moveTo(x0, y0); sink.lineTo(x3, y3); sink.lineTo(x2, y2); sink.lineTo(x1, y1)
        }
        sink.close()
    }

    /** Sides used for a disc of radius [r]: enough that the flat sides stay well under a hundredth of a unit. */
    fun sides(r: Float): Int = ceil(PI / kotlin.math.acos((1f - TOLERANCE / r.coerceAtLeast(TOLERANCE)).coerceIn(-1f, .99995f))).toInt().coerceIn(8, 32)

    private fun disc(cx: Float, cy: Float, r: Float, sink: Sink) {
        if (r <= 0f) return
        val count = sides(r)
        // Counting the angle upwards in page coordinates gives positive shoelace area, like the hulls.
        sink.moveTo(cx + r, cy)
        for (k in 1 until count) {
            val t = 2.0 * PI * k / count
            sink.lineTo(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat())
        }
        sink.close()
    }
}
