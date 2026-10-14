package com.folio.notes

import kotlin.math.*

/** Hold-to-shape geometry. Results are ordinary ink, with no new storage types. */
internal object ShapeRecognition {
    fun tidy(stroke: Stroke, options: ShapeAssistOptions = ShapeAssistOptions()): List<Stroke>? {
        if (stroke.tool == Tool.HIGHLIGHTER) {
            if (!options.highlighter || !options.lines) return null
            val line = detect(stroke.copy(tool = Tool.PEN), options)?.singleOrNull()?.takeIf { it.tool == Tool.LINE } ?: return null
            return listOf(line.copy(tool = Tool.HIGHLIGHTER))
        }
        val result = detect(stroke, options) ?: return null
        val allowed = when {
            result.singleOrNull()?.tool == Tool.LINE -> options.lines
            result.size == 3 && result.last().points.last() != result.first().points.first() -> options.arrows
            else -> options.closed
        }
        return result.takeIf { allowed }
    }

    private fun detect(stroke: Stroke, options: ShapeAssistOptions): List<Stroke>? {
        if (stroke.tool != Tool.PEN || stroke.points.size < 5 ||
            stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val raw = stroke.points
        val span = span(raw)
        if (!span.isFinite() || span < 26f) return null
        val length = length(raw)
        if (!length.isFinite() || length < 40f) return null
        // Equal-distance sampling makes corners and ellipse fitting independent of pen speed,
        // and bounds the work even after a long pause with thousands of repeated tip samples.
        val points = resample(raw, length / 192f)
        if (distance(points.first(), points.last()) > span * .18f) {
            open(stroke, points, span)?.let { return it }
            // A start or end hook (the nib landing or flicking off) is not part of the shape.
            dehook(points, span)?.let { trimmed -> open(stroke, trimmed, span(trimmed))?.let { return it } }
        }
        // Overshooting the start, or stopping just short of it, still means one closed outline.
        return closed(stroke, closure(points, span) ?: return null, options)
    }

    private fun open(stroke: Stroke, points: List<InkPoint>, span: Float): List<Stroke>? {
        if (points.all { gap(it, points.first(), points.last()) <= max(2.5f, span * .045f) } && length(points) < span * 1.25f)
            return listOf(edge(stroke, points.first(), points.last()))
        return arrow(stroke, simplify(points, span * .035f), span)
    }

    private fun closed(stroke: Stroke, drawn: List<InkPoint>, options: ShapeAssistOptions): List<Stroke>? {
        val span = span(drawn); val length = length(drawn)
        if (length < span * 1.6f || length > span * 4.5f) return null
        // Start the ring in the middle of its longest side, so a corner the pen started on
        // (especially a rounded one) is not split into two.
        val first = simplify(drawn + drawn.first(), span * .035f)
        val side = (0 until first.lastIndex).maxBy { distance(first[it], first[it + 1]) }
        val middle = (drawn.indexOf(first[side]) + if (side + 1 == first.lastIndex) drawn.size else drawn.indexOf(first[side + 1])) / 2
        val points = if (middle in 1 until drawn.size) drawn.subList(middle, drawn.size) + drawn.subList(0, middle) else drawn
        val loop = simplify(points + points.first(), span * .035f).dropLast(1)
        val corners = loop.indices.filter { abs(turn(loop, it)) > .35f }.map { loop[it] }
        if (corners.size !in 3..6 && corners.size != 10) return ellipse(stroke, points, options.perfect)?.let { listOf(it) }
        if (corners.indices.any { distance(corners[it], corners[(it + 1) % corners.size]) < span * .08f }) return null
        // A narrow oval can simplify to six corners. Its arcs still bow away from those
        // chords; an actual hexagon has straight sides, even with an uneven pen speed. A box
        // may have rounded corners, which pull its sides in a little but never like an oval's arcs.
        val square = corners.size == 4 && corners.indices.all { abs(abs(turn(corners, it)) - PI.toFloat() / 2) < .22f }
        val bow = if (square) .06f else .025f
        val deviations = FloatArray(corners.size); val samples = IntArray(corners.size)
        for (p in points) {
            val edge = corners.indices.minBy { gap(p, corners[it], corners[(it + 1) % corners.size]) }
            deviations[edge] += gap(p, corners[edge], corners[(edge + 1) % corners.size]); samples[edge]++
        }
        if (corners.indices.any { deviations[it] / samples[it].coerceAtLeast(1) >
                distance(corners[it], corners[(it + 1) % corners.size]) * bow })
            return ellipse(stroke, points, options.perfect)?.let { listOf(it) }
        if (points.any { p -> corners.indices.minOf { gap(p, corners[it], corners[(it + 1) % corners.size]) } > span * .045f }) return null
        // Ten corners that are not a star are a wobbly round outline.
        return polygon(stroke, corners, options) ?: if (corners.size == 10) ellipse(stroke, points, options.perfect)?.let { listOf(it) } else null
    }

    /**
     * A closed outline through [corners] in drawing order, styled like [style]. Lasso cleanup
     * uses it for boxes and polygons drawn as separate lines, with the one-stroke rules.
     */
    fun polygon(style: Stroke, corners: List<InkPoint>, options: ShapeAssistOptions): List<Stroke>? {
        if (corners.size !in 3..6 && corners.size != 10) return null
        val turns = corners.indices.map { turn(corners, it) }
        if (corners.size == 10) {
            // A five-point outline has alternating inward/outward corners and winds once.
            if (turns.indices.any { turns[it] * turns[(it + 1) % turns.size] >= 0f } ||
                abs(abs(turns.sum()) - 2 * PI) > .4) return null
        } else if (!(turns.all { it > 0f } || turns.all { it < 0f }) ||
            abs(abs(turns.sum()) - 2 * PI) > .4) return null
        if (corners.size == 4 && turns.all { abs(abs(it) - PI.toFloat() / 2) < .22f })
            return rectangle(style, corners, options.perfect)
        return edges(style, if (options.regular && corners.size != 4) regular(corners) else corners)
    }

    /** An arrow from a shaft and two wing ends, as in a head drawn as a separate stroke. */
    fun arrow(style: Stroke, start: InkPoint, tip: InkPoint, wing: InkPoint, other: InkPoint): List<Stroke>? =
        arrow(style, listOf(start, tip, wing, tip, other), distance(start, tip))

    /** Wing, apex, wing of a stroke drawn as one sharp V (an arrowhead), or null. */
    fun vee(stroke: Stroke): List<InkPoint>? {
        if (stroke.tool != Tool.PEN || stroke.points.size < 3 || stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val span = span(stroke.points); val length = length(stroke.points)
        if (span < 6f || length <= 0f) return null
        val points = resample(stroke.points, length / 96f)
        val tolerance = max(1.5f, span * .08f)
        val v = simplify(points, tolerance)
        if (v.size != 3 || points.any { min(gap(it, v[0], v[1]), gap(it, v[1], v[2])) > tolerance }) return null
        val angle = abs(turn(v, 1))
        return v.takeIf { angle in .7f..2.8f && distance(v[0], v[1]) > span * .25f && distance(v[1], v[2]) > span * .25f }
    }

    /** The ends of a short straight stroke (an arrow's wing), below the size a held line needs. */
    fun segment(stroke: Stroke): Pair<InkPoint, InkPoint>? {
        if (stroke.tool != Tool.PEN || stroke.points.size < 2 || stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val a = stroke.points.first(); val b = stroke.points.last()
        val length = distance(a, b)
        if (length < 6f || length(stroke.points) > length * 1.25f) return null
        return (a to b).takeIf { stroke.points.all { gap(it, a, b) <= max(1.5f, length * .08f) } }
    }

    /** The single loop of a nearly closed path: an overshoot past the start is cut off at the crossing. */
    private fun closure(points: List<InkPoint>, span: Float): List<InkPoint>? {
        val n = points.size
        val reach = span * .18f
        val ends = n * 7 / 20
        var best = distance(points.first(), points.last()); var from = 0; var to = n - 1
        for (i in 0 until ends) for (j in n - ends until n) {
            val d = distance(points[i], points[j])
            if (d < best - .5f) { best = d; from = i; to = j }
        }
        if (best > reach) return null
        // Retracing a large part (a spiral, a "6", a second lap) is not an overshoot.
        if (from + (n - 1 - to) > (to - from) * .3f) return null
        return if (from == 0 && to == n - 1) points else points.subList(from, to + 1)
    }

    /** The path without a short, sharply turned first or last segment, or null if there is none. */
    private fun dehook(points: List<InkPoint>, span: Float): List<InkPoint>? {
        val corners = simplify(points, span * .035f)
        if (corners.size < 3) return null
        val total = length(points)
        fun hook(end: InkPoint, corner: InkPoint, next: InkPoint) =
            distance(end, corner) < total * .1f && abs(turn(listOf(end, corner, next), 1)) > .9f
        val start = if (hook(corners[0], corners[1], corners[2])) points.indexOf(corners[1]) else 0
        val last = corners.lastIndex
        val stop = if (hook(corners[last], corners[last - 1], corners[last - 2])) points.lastIndexOf(corners[last - 1]) else points.lastIndex
        if ((start == 0 && stop == points.lastIndex) || stop - start < 4) return null
        return points.subList(start, stop + 1)
    }

    private fun edge(s: Stroke, a: InkPoint, b: InkPoint) =
        s.copy(tool = Tool.LINE, points = listOf(a.copy(pressure = 1f), b.copy(pressure = 1f)))

    private fun edges(s: Stroke, corners: List<InkPoint>) =
        corners.indices.map { edge(s, corners[it], corners[(it + 1) % corners.size]) }

    /** Fit orthogonal edges in the drawing's own orientation, rather than its screen box. */
    private fun rectangle(s: Stroke, corners: List<InkPoint>, perfect: Boolean): List<Stroke> {
        val i = corners.indices.maxBy { distance(corners[it], corners[(it + 1) % corners.size]) }
        val a = corners[i]; val b = corners[(i + 1) % corners.size]
        var angle = atan2(b.y - a.y, b.x - a.x)
        // Near-horizontal/vertical boxes get the existing editable rectangle representation.
        val aligned = (angle / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
        if (abs(angle - aligned) < .04f) angle = aligned
        val c = cos(angle); val t = sin(angle)
        val xs = corners.map { it.x * c + it.y * t }; val ys = corners.map { -it.x * t + it.y * c }
        fun p(x: Float, y: Float) = InkPoint(x * c - y * t, x * t + y * c)
        val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
        var rx = (xs.max() - xs.min()) / 2; var ry = (ys.max() - ys.min()) / 2
        if (perfect && min(rx, ry) / max(rx, ry) >= .8f) { val radius = (rx + ry) / 2; rx = radius; ry = radius }
        val fitted = listOf(p(cx - rx, cy - ry), p(cx + rx, cy - ry), p(cx + rx, cy + ry), p(cx - rx, cy + ry))
        if (angle == aligned) return listOf(s.copy(tool = Tool.RECTANGLE,
            points = listOf(InkPoint(fitted.minOf { it.x }, fitted.minOf { it.y }),
                InkPoint(fitted.maxOf { it.x }, fitted.maxOf { it.y }))))
        return edges(s, fitted)
    }

    private fun ellipse(s: Stroke, points: List<InkPoint>, perfect: Boolean): Stroke? {
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
        var rx = (xs.max() - xs.min()) / 2; var ry = (ys.max() - ys.min()) / 2
        if (min(rx, ry) < 6f) return null
        val cx = (xs.max() + xs.min()) / 2; val cy = (ys.max() + ys.min()) / 2
        val errors = xs.indices.map { abs(hypot((xs[it] - cx) / rx, (ys[it] - cy) / ry) - 1f) }
        if (errors.average() > .07 || errors.max() > .22f) return null
        val angles = xs.indices.map { atan2((ys[it] - cy) / ry, (xs[it] - cx) / rx) }
        var swept = 0f; var travel = 0f
        for (i in 1..angles.lastIndex) {
            var d = angles[i] - angles[i - 1]
            if (d > PI) d -= (2 * PI).toFloat()
            if (d < -PI) d += (2 * PI).toFloat()
            swept += d; travel += abs(d)
        }
        if (abs(swept) < 5.5f || travel > 7.2f) return null
        if (perfect && min(rx, ry) / max(rx, ry) >= .8f) {
            val radius = (rx + ry) / 2
            val centre = InkPoint(cx * c - cy * t, cx * t + cy * c)
            return s.copy(tool = Tool.ELLIPSE, points = listOf(
                InkPoint(centre.x - radius, centre.y - radius), InkPoint(centre.x + radius, centre.y + radius)))
        }
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

    /** Fit equal sides without changing the drawing's direction or centre. Stars keep two radii. */
    private fun regular(corners: List<InkPoint>): List<InkPoint> {
        val cx = corners.map { it.x.toDouble() }.average().toFloat()
        val cy = corners.map { it.y.toDouble() }.average().toFloat()
        val direction = if (turn(corners, 0) > 0f) 1 else -1
        // A star can start at either an inward or outward corner. Use its winding, not that corner.
        val winding = if (corners.size == 10) if (corners.indices.sumOf { turn(corners, it).toDouble() } > 0) 1 else -1 else direction
        val step = winding * 2 * PI / corners.size
        val phases = corners.mapIndexed { i, point -> atan2((point.y - cy).toDouble(), (point.x - cx).toDouble()) - i * step }
        val angle = atan2(phases.sumOf { sin(it) }, phases.sumOf { cos(it) })
        val radii = corners.map { hypot(it.x - cx, it.y - cy) }
        val even = radii.filterIndexed { i, _ -> corners.size != 10 || i % 2 == 0 }.average().toFloat()
        val odd = if (corners.size == 10) radii.filterIndexed { i, _ -> i % 2 == 1 }.average().toFloat() else even
        return corners.indices.map { i ->
            val radius = if (i % 2 == 0) even else odd
            InkPoint(cx + radius * cos(angle + i * step).toFloat(), cy + radius * sin(angle + i * step).toFloat())
        }
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
    private fun span(points: List<InkPoint>) =
        hypot(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
    private fun length(points: List<InkPoint>): Float {
        var length = 0f
        for (i in 1..points.lastIndex) length += distance(points[i - 1], points[i])
        return length
    }
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
