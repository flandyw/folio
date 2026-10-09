package com.folio.notes

import kotlin.math.*

/** Hold-to-shape geometry. Results are ordinary ink, with no new storage types. */
internal object ShapeRecognition {
    fun tidy(stroke: Stroke): List<Stroke>? {
        if (stroke.tool != Tool.PEN || stroke.points.size < 5 ||
            stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val raw = stroke.points
        val span = hypot(raw.maxOf { it.x } - raw.minOf { it.x }, raw.maxOf { it.y } - raw.minOf { it.y })
        if (!span.isFinite() || span < 26f) return null
        var length = 0f
        for (i in 1..raw.lastIndex) length += distance(raw[i - 1], raw[i])
        if (!length.isFinite() || length < 40f) return null
        // Equal-distance sampling makes corners and ellipse fitting independent of pen speed,
        // and bounds the work even after a long pause with thousands of repeated tip samples.
        val points = resample(raw, length / 192f)
        if (distance(points.first(), points.last()) > span * .18f) {
            if (points.all { gap(it, points.first(), points.last()) <= max(2.5f, span * .045f) } && length < span * 1.25f)
                return listOf(edge(stroke, points.first(), points.last()))
            return arrow(stroke, simplify(points, span * .035f), span)
        }
        if (length < span * 1.6f || length > span * 4.5f) return null
        val loop = simplify(points + points.first(), span * .035f).dropLast(1)
        val corners = loop.indices.filter { abs(turn(loop, it)) > .35f }.map { loop[it] }
        if (corners.size !in 3..6 && corners.size != 10) return ellipse(stroke, points)?.let { listOf(it) }
        if (corners.indices.any { distance(corners[it], corners[(it + 1) % corners.size]) < span * .08f }) return null
        // A narrow oval can simplify to six corners. Its arcs still bow away from those
        // chords; an actual hexagon has straight sides, even with an uneven pen speed.
        val deviations = FloatArray(corners.size); val samples = IntArray(corners.size)
        for (p in points) {
            val edge = corners.indices.minBy { gap(p, corners[it], corners[(it + 1) % corners.size]) }
            deviations[edge] += gap(p, corners[edge], corners[(edge + 1) % corners.size]); samples[edge]++
        }
        if (corners.indices.any { deviations[it] / samples[it].coerceAtLeast(1) >
                distance(corners[it], corners[(it + 1) % corners.size]) * .025f })
            return ellipse(stroke, points)?.let { listOf(it) }
        if (points.any { p -> corners.indices.minOf { gap(p, corners[it], corners[(it + 1) % corners.size]) } > span * .045f }) return null
        val turns = corners.indices.map { turn(corners, it) }
        if (corners.size == 10) {
            // A five-point outline has alternating inward/outward corners and winds once.
            if (turns.indices.any { turns[it] * turns[(it + 1) % turns.size] >= 0f } ||
                abs(abs(turns.sum()) - 2 * PI) > .4) return null
        } else if (!(turns.all { it > 0f } || turns.all { it < 0f }) ||
            abs(abs(turns.sum()) - 2 * PI) > .4) return null
        if (corners.size == 4 && turns.all { abs(abs(it) - PI.toFloat() / 2) < .22f })
            return rectangle(stroke, corners)
        return edges(stroke, corners)
    }

    private fun edge(s: Stroke, a: InkPoint, b: InkPoint) =
        s.copy(tool = Tool.LINE, points = listOf(a.copy(pressure = 1f), b.copy(pressure = 1f)))

    private fun edges(s: Stroke, corners: List<InkPoint>) =
        corners.indices.map { edge(s, corners[it], corners[(it + 1) % corners.size]) }

    /** Fit orthogonal edges in the drawing's own orientation, rather than its screen box. */
    private fun rectangle(s: Stroke, corners: List<InkPoint>): List<Stroke> {
        val i = corners.indices.maxBy { distance(corners[it], corners[(it + 1) % corners.size]) }
        val a = corners[i]; val b = corners[(i + 1) % corners.size]
        var angle = atan2(b.y - a.y, b.x - a.x)
        // Near-horizontal/vertical boxes get the existing editable rectangle representation.
        val aligned = (angle / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
        if (abs(angle - aligned) < .04f) angle = aligned
        val c = cos(angle); val t = sin(angle)
        val xs = corners.map { it.x * c + it.y * t }; val ys = corners.map { -it.x * t + it.y * c }
        fun p(x: Float, y: Float) = InkPoint(x * c - y * t, x * t + y * c)
        val fitted = listOf(p(xs.min(), ys.min()), p(xs.max(), ys.min()), p(xs.max(), ys.max()), p(xs.min(), ys.max()))
        if (angle == aligned) return listOf(s.copy(tool = Tool.RECTANGLE,
            points = listOf(InkPoint(fitted.minOf { it.x }, fitted.minOf { it.y }),
                InkPoint(fitted.maxOf { it.x }, fitted.maxOf { it.y }))))
        return edges(s, fitted)
    }

    private fun ellipse(s: Stroke, points: List<InkPoint>): Stroke? {
        val mx = points.map { it.x.toDouble() }.average(); val my = points.map { it.y.toDouble() }.average()
        var xx = 0.0; var yy = 0.0; var xy = 0.0
        for (p in points) { val x = p.x - mx; val y = p.y - my; xx += x * x; yy += y * y; xy += x * y }
        var angle = (.5 * atan2(2 * xy, xx - yy)).toFloat()
        // Circles have no meaningful principal axis; keep their native ellipse tool.
        if (hypot(xx - yy, 2 * xy) / (xx + yy).coerceAtLeast(1.0) < .12) angle = 0f
        val aligned = (angle / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
        if (abs(angle - aligned) < .04f) angle = aligned
        val c = cos(angle); val t = sin(angle)
        val xs = points.map { it.x * c + it.y * t }; val ys = points.map { -it.x * t + it.y * c }
        val rx = (xs.max() - xs.min()) / 2; val ry = (ys.max() - ys.min()) / 2
        if (min(rx, ry) < 6f) return null
        val cx = (xs.max() + xs.min()) / 2; val cy = (ys.max() + ys.min()) / 2
        val errors = xs.indices.map { abs(hypot((xs[it] - cx) / rx, (ys[it] - cy) / ry) - 1f) }
        if (errors.average() > .055 || errors.max() > .18f) return null
        val angles = xs.indices.map { atan2((ys[it] - cy) / ry, (xs[it] - cx) / rx) }
        var swept = 0f; var travel = 0f
        for (i in 1..angles.lastIndex) {
            var d = angles[i] - angles[i - 1]
            if (d > PI) d -= (2 * PI).toFloat()
            if (d < -PI) d += (2 * PI).toFloat()
            swept += d; travel += abs(d)
        }
        if (abs(swept) < 5.5f || travel > 7.2f) return null
        fun p(x: Float, y: Float) = InkPoint(x * c - y * t, x * t + y * c)
        if (angle == aligned) {
            val ends = listOf(p(cx - rx, cy - ry), p(cx + rx, cy + ry))
            return s.copy(tool = Tool.ELLIPSE, points = listOf(
                InkPoint(ends.minOf { it.x }, ends.minOf { it.y }), InkPoint(ends.maxOf { it.x }, ends.maxOf { it.y })))
        }
        // Rotated ovals are a uniform-pressure pen outline, understood by every existing codec.
        return s.copy(points = (0..128).map { i ->
            val theta = i * 2 * PI / 128
            p(cx + rx * cos(theta).toFloat(), cy + ry * sin(theta).toFloat())
        })
    }

    /** One-stroke arrow: shaft → tip → wing → tip → other wing (or a V returning to tip). */
    private fun arrow(s: Stroke, corners: List<InkPoint>, span: Float): List<Stroke>? {
        if (corners.size != 5) return null
        val start = corners[0]; val tip = corners[1]; val first = corners[2]
        val second = when {
            distance(corners[3], tip) < span * .1f -> corners[4]
            distance(corners[4], tip) < span * .1f -> corners[3]
            else -> return null
        }
        val length = distance(start, tip)
        if (length < span * .65f) return null
        val ux = (tip.x - start.x) / length; val uy = (tip.y - start.y) / length
        fun back(p: InkPoint) = (tip.x - p.x) * ux + (tip.y - p.y) * uy
        fun side(p: InkPoint) = (p.x - tip.x) * -uy + (p.y - tip.y) * ux
        val b1 = back(first); val b2 = back(second); val h1 = side(first); val h2 = side(second)
        if (b1 !in length * .08f..length * .5f || b2 !in length * .08f..length * .5f ||
            h1 * h2 >= 0f || min(abs(h1), abs(h2)) < length * .06f ||
            max(b1, b2) > min(b1, b2) * 2 || max(abs(h1), abs(h2)) > min(abs(h1), abs(h2)) * 2) return null
        val back = (b1 + b2) / 2; val half = (abs(h1) + abs(h2)) / 2
        fun wing(sign: Float) = InkPoint(tip.x - back * ux - sign * half * uy, tip.y - back * uy + sign * half * ux)
        return listOf(edge(s, start, tip), edge(s, tip, wing(1f)), edge(s, tip, wing(-1f)))
    }

    private fun resample(raw: List<InkPoint>, step: Float): List<InkPoint> {
        val result = ArrayList<InkPoint>(); result += raw.first()
        var remaining = step
        for (i in 1..raw.lastIndex) {
            var a = raw[i - 1]; val b = raw[i]; var d = distance(a, b)
            while (d >= remaining && d > 0f) {
                val share = remaining / d
                a = InkPoint(a.x + (b.x - a.x) * share, a.y + (b.y - a.y) * share)
                result += a; d = distance(a, b); remaining = step
            }
            remaining -= d
        }
        result += raw.last()
        return result
    }

    private fun distance(a: InkPoint, b: InkPoint) = hypot(a.x - b.x, a.y - b.y)
    private fun gap(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val squared = dx * dx + dy * dy
        if (squared == 0f) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / squared).coerceIn(0f, 1f)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }
    private fun turn(points: List<InkPoint>, i: Int): Float {
        val a = points[(i - 1 + points.size) % points.size]; val b = points[i]; val c = points[(i + 1) % points.size]
        return atan2((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x),
            (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y))
    }
    private fun simplify(points: List<InkPoint>, tolerance: Float): List<InkPoint> {
        if (points.size < 3) return points
        val index = (1 until points.lastIndex).maxBy { gap(points[it], points.first(), points.last()) }
        if (gap(points[index], points.first(), points.last()) <= tolerance) return listOf(points.first(), points.last())
        return simplify(points.subList(0, index + 1), tolerance).dropLast(1) + simplify(points.subList(index, points.size), tolerance)
    }
}
