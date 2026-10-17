package com.folio.notes

import com.folio.notes.ShapeMath.distance
import com.folio.notes.ShapeMath.gap
import com.folio.notes.ShapeMath.length
import com.folio.notes.ShapeMath.resample
import com.folio.notes.ShapeMath.simplify
import com.folio.notes.ShapeMath.span
import com.folio.notes.ShapeMath.turn
import kotlin.math.*

/** What a stroke was recognised as. A polygon's or star's corner count is [Tidied.sides]. */
internal enum class ShapeKind {
    LINE, ARROW, RECTANGLE, ROUNDED, ELLIPSE, POLYGON, STAR, PENTAGRAM, HEART, ARC, BRACKET, BRACE;

    /** Arcs, brackets and braces: open strokes that are neither a straight line nor an arrow. */
    val curve get() = this == ARC || this == BRACKET || this == BRACE
    /** Everything drawn as an outline. */
    val closed get() = this != LINE && this != ARROW && !curve
}

/** A recognised shape: the ordinary ink that replaces the freehand stroke. */
internal class Tidied(val kind: ShapeKind, val strokes: List<Stroke>, val sides: Int = 0)

/** Hold-to-shape geometry. Results are ordinary ink, with no new storage types. */
internal object ShapeRecognition {
    /** Brackets and arcs below this size are indistinguishable from handwriting. */
    private const val SYMBOL_MIN = 50f
    /** Within about 4° of level or upright, a box or oval is made exactly so. */
    private const val ALIGN = .07f

    fun tidy(stroke: Stroke, options: ShapeAssistOptions = ShapeAssistOptions()): List<Stroke>? = recognise(stroke, options)?.strokes

    fun recognise(stroke: Stroke, options: ShapeAssistOptions = ShapeAssistOptions()): Tidied? {
        if (stroke.tool == Tool.HIGHLIGHTER) {
            if (!options.highlighter || !options.lines) return null
            val line = detect(stroke.copy(tool = Tool.PEN))?.takeIf { it.kind == ShapeKind.LINE } ?: return null
            return Tidied(ShapeKind.LINE, listOf(line.strokes.single().copy(tool = Tool.HIGHLIGHTER)))
        }
        return detect(stroke, options)?.takeIf { options.allows(it.kind) }
    }

    private fun detect(stroke: Stroke, options: ShapeAssistOptions = ShapeAssistOptions()): Tidied? {
        if (stroke.tool != Tool.PEN || stroke.points.size < 5 ||
            stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val raw = stroke.points
        val span = span(raw)
        if (!span.isFinite() || span < 26f) return null
        val length = length(raw)
        if (!length.isFinite() || length < 40f) return null
        // Equal-distance sampling makes fitting independent of pen speed, and bounds the work
        // even after a long pause with thousands of repeated tip samples.
        val points = resample(raw, length / 192f)
        if (distance(points.first(), points.last()) > span * .18f) {
            open(stroke, points, span)?.let { return it }
            // A start or end hook (the nib landing or flicking off) is not part of the shape.
            val trimmed = dehook(points, span)
            trimmed?.let { open(stroke, it, span(it))?.let { shape -> return shape } }
            // Only then a curve, so a hooked line is never taken for an arc.
            symbol(stroke, points, span)?.let { return it }
            trimmed?.let { symbol(stroke, it, span(it))?.let { shape -> return shape } }
        }
        // A pentagram crosses itself, which would read as an overshoot, so it is judged on the whole stroke.
        if (distance(points.first(), points.last()) <= span * .18f) pentagram(stroke, points, span, options)?.let { return it }
        // Overshooting the start, or stopping just short of it, still means one closed outline.
        return closed(stroke, closure(points, span) ?: return null, options)
    }

    /** A five-pointed star drawn as one continuous stroke of five crossing lines. */
    private fun pentagram(stroke: Stroke, points: List<InkPoint>, span: Float, options: ShapeAssistOptions): Tidied? {
        val length = length(points)
        if (length < span * 2.8f || length > span * 4.6f) return null
        val ring = resample(points, length / 120f)
        val peaks = ShapeFit.corners(ring)
        if (peaks.size < 5) return null
        val loop = ShapeFit.loops(ring, 5, peaks.take(3))[5] ?: return null
        val outline = ShapeFit.refine(ring, loop.vertices, span)
        if (classify(outline, span) != ShapeKind.PENTAGRAM || ShapeFit.outlineError(ring, outline) > span * .03f ||
            ShapeFit.supported(ring, outline, peaks) < 5) return null
        return shapeOf(stroke, outline, options)
    }

    // ---- Open strokes -----------------------------------------------------------------------

    private fun open(stroke: Stroke, points: List<InkPoint>, span: Float): Tidied? {
        if (straight(points, span)) return Tidied(ShapeKind.LINE, listOf(edge(stroke, points.first(), points.last())))
        // An arrow is a shaft and a head: four straight runs through five corners, drawn from either end.
        for (path in listOf(points, points.asReversed())) {
            val chain = ShapeFit.cornerChain(path, 4, max(1.5f, span * .012f)) ?: continue
            arrow(stroke, chain.map { path[it] }, span)?.let { return Tidied(ShapeKind.ARROW, it) }
        }
        if (span < SYMBOL_MIN) return null
        curvedArrow(stroke, points, span)?.let { return Tidied(ShapeKind.ARROW, it) }
        return null
    }

    /** An arc, square bracket or curly brace. */
    private fun symbol(stroke: Stroke, points: List<InkPoint>, span: Float): Tidied? {
        if (span < SYMBOL_MIN) return null
        val symbol = ShapeFit.symbol(points, span) ?: return null
        return Tidied(symbol.kind, listOf(stroke.copy(points = symbol.points)))
    }

    /** Within a pen's natural wobble of the chord between its ends, and not doubling back. */
    private fun straight(points: List<InkPoint>, span: Float): Boolean {
        if (length(points) >= span * 1.25f) return false
        val a = points.first(); val b = points.last()
        val limit = max(2.5f, span * .055f)
        var squares = 0f
        for (p in points) {
            val d = gap(p, a, b)
            if (d > limit) return false
            squares += d * d
        }
        return sqrt(squares / points.size) <= max(1.5f, span * .025f)
    }

    // ---- Closed outlines --------------------------------------------------------------------

    private class Candidate(val kind: ShapeKind, val corners: List<InkPoint>, val error: Float) {
        val score get() = error * (1f + .3f * (corners.size - 3))
    }

    private fun closed(stroke: Stroke, drawn: List<InkPoint>, options: ShapeAssistOptions): Tidied? {
        val span = span(drawn); val length = length(drawn)
        if (length < span * 1.6f || length > span * 5.2f) return null
        val ring = resample(drawn, length / 120f)
        val fit = ShapeFit.ellipse(ring)
        val round = fit?.takeIf { roundEnough(it, ring, span) }
        // A thin shape allows less wobble across its width than a fat one.
        val tolerance = min(span * .03f, (fit?.let { 2f * min(it.rx, it.ry) } ?: span) * .12f)
        val peaks = ShapeFit.corners(ring)
        // No sharp turns and a very good fit: a plain round outline, with nothing else worth testing.
        if (round != null && peaks.size <= 1 && round.error <= span * .008) return Tidied(ShapeKind.ELLIPSE, listOf(ellipse(stroke, round, options.perfect)))
        var pick: Candidate? = null
        if (peaks.size >= 3) {
            val loops = ShapeFit.loops(ring, min(12, max(5, peaks.size + 2)), peaks.take(3))
            for (loop in loops) {
                loop ?: continue
                val outline = ShapeFit.refine(ring, loop.vertices, span)
                val kind = classify(outline, span) ?: continue
                val error = ShapeFit.outlineError(ring, outline).toFloat()
                if (error > tolerance) continue
                // Corners of a drawn polygon are sharp turns. A bowl fitted with a few chords has corners only where it meets a stem.
                if (ShapeFit.supported(ring, outline, peaks) * 4 < outline.size * 3) continue
                // A star is nothing like an ellipse; a scalloped cloud, whose cusps look like star corners, nearly is.
                if (kind == ShapeKind.STAR && fit != null && error > .4f * fit.error) continue
                // A wobbly circle also fits an octagon. Many sides need that many sharp turns, sharper than the sides between.
                val sides = outline.size
                if (kind == ShapeKind.POLYGON && sides >= 5 &&
                    (peaks.size < (if (sides >= 7) sides - 1 else sides - 2) || ShapeFit.contrast(ring, outline) < (if (sides >= 7) 3.0 else 2.0))) continue
                val candidate = Candidate(kind, outline, error)
                if (pick == null || candidate.score < pick.score) pick = candidate
            }
        }
        // A heart is drawn with a notch and a point, which a pentagon or quadrilateral also roughly fits.
        val sides = pick?.corners?.size ?: 0
        if (peaks.isNotEmpty() && (pick == null || (pick.kind == ShapeKind.POLYGON && sides in 4..6))) {
            ShapeFit.heart(ring, span)?.let { heart ->
                val error = heart.error * span
                if (heart.error <= .045 && (pick == null || error <= .8 * pick.error) && (round == null || error <= .7 * round.error))
                    return Tidied(ShapeKind.HEART, listOf(stroke.copy(points = heart.outline)))
            }
        }
        // Soft corners or a pill: a box whose corners are arcs fits better than sharp corners or an ellipse.
        if (pick == null || (pick.kind == ShapeKind.POLYGON && sides == 4)) {
            ShapeFit.roundedBox(ring)?.let { box ->
                val rounded = box.radius / min(box.hw, box.hh)
                val circle = rounded > .85f && abs(box.hw - box.hh) < .12f * max(box.hw, box.hh)
                if (!circle && rounded >= .45f && box.error <= tolerance &&
                    (fit == null || box.error <= .6 * fit.error) && (pick == null || box.error <= .5 * pick.error)) {
                    var angle = box.angle
                    val aligned = (angle / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
                    if (abs(angle - aligned) < ALIGN) angle = aligned
                    return Tidied(ShapeKind.ROUNDED, listOf(stroke.copy(points = ShapeFit.outline(box, angle))))
                }
            }
        }
        if (pick != null) {
            val ratio = if (sides <= 4) .9f else if (sides <= 6) .7f else .85f
            if (pick.kind != ShapeKind.POLYGON || round == null || pick.error <= ratio * round.error)
                return shapeOf(stroke, pick.corners, options)
        }
        return round?.let { Tidied(ShapeKind.ELLIPSE, listOf(ellipse(stroke, it, options.perfect))) }
    }

    /** Whether [corners] (in drawing order) outline a convex polygon, a star, or a one-stroke pentagram. */
    private fun classify(corners: List<InkPoint>, span: Float): ShapeKind? {
        val n = corners.size
        if (n !in 3..12) return null
        val turns = corners.indices.map { turn(corners, it) }
        val sameSign = turns.all { it > 0f } || turns.all { it < 0f }
        val sum = abs(turns.sum())
        val tau = (2 * PI).toFloat()
        val shortest = corners.indices.minOf { distance(corners[it], corners[(it + 1) % n]) }
        if (sameSign && n <= 8 && abs(sum - tau) < .5f && turns.all { abs(it) >= .3f } && shortest >= span * .07f) return ShapeKind.POLYGON
        // Five straight strokes crossing: every turn is about 144°.
        if (sameSign && n == 5 && abs(sum - 2 * tau) < 1.3f && turns.all { abs(it) in 1.9f..3.0f } && shortest >= span * .2f) return ShapeKind.PENTAGRAM
        if (n >= 8 && n % 2 == 0 && shortest >= span * .04f && abs(sum - tau) < .6f &&
            turns.indices.all { turns[it] * turns[(it + 1) % n] < 0f } && turns.all { abs(it) >= .25f }) {
            val cx = corners.sumOf { it.x.toDouble() }.toFloat() / n; val cy = corners.sumOf { it.y.toDouble() }.toFloat() / n
            val radii = corners.map { hypot(it.x - cx, it.y - cy) }
            val even = radii.filterIndexed { i, _ -> i % 2 == 0 }; val odd = radii.filterIndexed { i, _ -> i % 2 == 1 }
            val (outer, inner) = if (even.average() > odd.average()) even to odd else odd to even
            if (inner.average() < outer.average() * .8 && inner.max() < outer.min() * 1.1f) return ShapeKind.STAR
        }
        return null
    }

    /** The ink for a classified outline. */
    private fun shapeOf(style: Stroke, corners: List<InkPoint>, options: ShapeAssistOptions): Tidied? {
        val kind = classify(corners, span(corners).coerceAtLeast(1f)) ?: return null
        val turns = corners.indices.map { turn(corners, it) }
        if (kind == ShapeKind.POLYGON && corners.size == 4 && turns.all { abs(abs(it) - PI.toFloat() / 2) < .22f })
            return Tidied(ShapeKind.RECTANGLE, rectangle(style, corners, options.perfect), 4)
        val fitted = when {
            !options.regular || corners.size == 4 -> corners
            else -> regular(corners, if (kind == ShapeKind.PENTAGRAM) 2 else 1, kind == ShapeKind.STAR)
        }
        return Tidied(kind, edges(style, fitted), corners.size)
    }

    /**
     * A closed outline through [corners] in drawing order, styled like [style]. Lasso cleanup
     * uses it for boxes and polygons drawn as separate lines, with the one-stroke rules.
     */
    fun polygon(style: Stroke, corners: List<InkPoint>, options: ShapeAssistOptions): List<Stroke>? =
        shapeOf(style, corners, options)?.strokes

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
        // The box's turn is the length-weighted mean direction of all four sides (folded to quarter turns),
        // so one bowed side cannot tilt it.
        var sx = 0.0; var sy = 0.0
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val theta = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()); val weight = distance(a, b)
            sx += weight * cos(4 * theta); sy += weight * sin(4 * theta)
        }
        var angle = (atan2(sy, sx) / 4).toFloat()
        // Near-horizontal/vertical boxes get the existing editable rectangle representation.
        val aligned = (angle / (PI.toFloat() / 2)).roundToInt() * PI.toFloat() / 2
        if (abs(angle - aligned) < ALIGN) angle = aligned
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

    /** Whether the fitted ellipse is one closed round outline, traced once. */
    private fun roundEnough(fit: ShapeFit.Ellipse, ring: List<InkPoint>, span: Float): Boolean {
        if (min(fit.rx, fit.ry) < 6f || fit.error > span * .028) return false
        val c = cos(fit.angle); val t = sin(fit.angle)
        val angles = ring.map {
            val x = (it.x - fit.cx) * c + (it.y - fit.cy) * t; val y = -(it.x - fit.cx) * t + (it.y - fit.cy) * c
            atan2(y / fit.ry, x / fit.rx)
        }
        var swept = 0f; var travel = 0f
        for (i in 1..angles.lastIndex) {
            var d = angles[i] - angles[i - 1]
            if (d > PI) d -= (2 * PI).toFloat()
            if (d < -PI) d += (2 * PI).toFloat()
            swept += d; travel += abs(d)
        }
        return abs(swept) >= 5.5f && travel <= 7.2f
    }

    private fun ellipse(s: Stroke, fit: ShapeFit.Ellipse, perfect: Boolean): Stroke {
        var rx = fit.rx; var ry = fit.ry
        // Circles have no meaningful principal axis; keep their native ellipse tool.
        var angle = if (abs(rx - ry) < .06f * max(rx, ry)) 0f else fit.angle
        val quarters = (angle / (PI.toFloat() / 2)).roundToInt()
        val aligned = quarters * PI.toFloat() / 2
        if (abs(angle - aligned) < ALIGN) angle = aligned
        val c = cos(angle); val t = sin(angle)
        fun p(x: Float, y: Float) = InkPoint(fit.cx + x * c - y * t, fit.cy + x * t + y * c)
        if (perfect && min(rx, ry) / max(rx, ry) >= .8f) {
            val radius = (rx + ry) / 2
            return s.copy(tool = Tool.ELLIPSE, points = listOf(
                InkPoint(fit.cx - radius, fit.cy - radius), InkPoint(fit.cx + radius, fit.cy + radius)))
        }
        if (angle == aligned) {
            if (quarters % 2 != 0) { val swap = rx; rx = ry; ry = swap }
            return s.copy(tool = Tool.ELLIPSE, points = listOf(
                InkPoint(fit.cx - rx, fit.cy - ry), InkPoint(fit.cx + rx, fit.cy + ry)))
        }
        // Rotated ovals are a uniform-pressure pen outline, understood by every existing codec.
        return s.copy(points = (0..128).map { i ->
            val theta = i * 2 * PI / 128
            p(rx * cos(theta).toFloat(), ry * sin(theta).toFloat())
        })
    }

    /** One-stroke arrow: shaft → tip → wing → tip → other wing (or a V returning to tip). */
    private fun arrow(s: Stroke, corners: List<InkPoint>, span: Float): List<Stroke>? {
        if (corners.size != 5) return null
        val start = corners[0]; val first = corners[2]
        // The pen comes back to the tip between the wings, in either order.
        val reach = span * .12f
        val (again, second) = when {
            distance(corners[3], corners[1]) < reach && distance(corners[3], corners[1]) <= distance(corners[4], corners[1]) -> corners[3] to corners[4]
            distance(corners[4], corners[1]) < reach -> corners[4] to corners[3]
            else -> return null
        }
        val tip = InkPoint((corners[1].x + again.x) / 2, (corners[1].y + again.y) / 2)
        val length = distance(start, tip)
        if (length < span * .6f) return null
        val wings = wings(tip, (tip.x - start.x) / length, (tip.y - start.y) / length, length, first, second) ?: return null
        return listOf(edge(s, start, tip), edge(s, tip, wings[0]), edge(s, tip, wings[1]))
    }

    /**
     * The two wing ends for a head at [tip] on a shaft arriving along ([ux], [uy]) over [length], made
     * even from the drawn wing ends [first] and [second]; null when they do not make an arrowhead.
     */
    private fun wings(tip: InkPoint, ux: Float, uy: Float, length: Float, first: InkPoint, second: InkPoint): List<InkPoint>? {
        fun back(p: InkPoint) = (tip.x - p.x) * ux + (tip.y - p.y) * uy
        fun side(p: InkPoint) = (p.x - tip.x) * -uy + (p.y - tip.y) * ux
        val b1 = back(first); val b2 = back(second); val h1 = side(first); val h2 = side(second)
        if (b1 !in length * .06f..length * .55f || b2 !in length * .06f..length * .55f ||
            h1 * h2 >= 0f || min(abs(h1), abs(h2)) < length * .05f ||
            max(b1, b2) > min(b1, b2) * 2.5f || max(abs(h1), abs(h2)) > min(abs(h1), abs(h2)) * 2.5f) return null
        val back = (b1 + b2) / 2; val half = (abs(h1) + abs(h2)) / 2
        fun wing(sign: Float) = InkPoint(tip.x - back * ux - sign * half * uy, tip.y - back * uy + sign * half * ux)
        return listOf(wing(1f), wing(-1f))
    }

    /** An arrow whose shaft is a curve: an arc, then a head. The shaft becomes a true arc and the head follows its end. */
    private fun curvedArrow(stroke: Stroke, points: List<InkPoint>, span: Float): List<Stroke>? {
        for (path in listOf(points, points.asReversed())) for (shaftSides in 2..3) {
            val chain = ShapeFit.cornerChain(path, shaftSides + 3, max(1.5f, span * .012f)) ?: continue
            val tipAt = chain[shaftSides]
            val first = path[chain[shaftSides + 1]]; val again = path[chain[shaftSides + 2]]; val second = path[chain.last()]
            if (distance(again, path[tipAt]) >= span * .12f) continue
            val shaft = path.subList(0, tipAt + 1)
            val shaftSpan = span(shaft)
            if (shaftSpan < span * .5f) continue
            val arc = ShapeFit.symbol(shaft, shaftSpan)?.takeIf { it.kind == ShapeKind.ARC }?.points ?: continue
            val tip = arc.last(); val before = arc[arc.size - 1 - arc.size / 12]
            val reach = distance(before, tip)
            if (reach <= 0f) continue
            val wings = wings(tip, (tip.x - before.x) / reach, (tip.y - before.y) / reach, length(arc), first, second) ?: continue
            return listOf(stroke.copy(points = arc), edge(stroke, tip, wings[0]), edge(stroke, tip, wings[1]))
        }
        return null
    }

    /**
     * Fit equal sides without changing the drawing's direction or centre. Stars keep two radii;
     * a pentagram visits every [skip]th point of its pentagon.
     */
    private fun regular(corners: List<InkPoint>, skip: Int, star: Boolean): List<InkPoint> {
        val cx = corners.map { it.x.toDouble() }.average().toFloat()
        val cy = corners.map { it.y.toDouble() }.average().toFloat()
        // A star can start at either an inward or outward corner. Use its winding, not that corner.
        val winding = if (corners.indices.sumOf { turn(corners, it).toDouble() } > 0) 1 else -1
        val step = winding * 2 * PI * skip / corners.size
        val phases = corners.mapIndexed { i, point -> atan2((point.y - cy).toDouble(), (point.x - cx).toDouble()) - i * step }
        val angle = atan2(phases.sumOf { sin(it) }, phases.sumOf { cos(it) })
        val radii = corners.map { hypot(it.x - cx, it.y - cy) }
        val even = radii.filterIndexed { i, _ -> !star || i % 2 == 0 }.average().toFloat()
        val odd = if (star) radii.filterIndexed { i, _ -> i % 2 == 1 }.average().toFloat() else even
        return corners.indices.map { i ->
            val radius = if (i % 2 == 0) even else odd
            InkPoint(cx + radius * cos(angle + i * step).toFloat(), cy + radius * sin(angle + i * step).toFloat())
        }
    }
}
