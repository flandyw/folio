package com.folio.notes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private fun close(a: Float, b: Float, eps: Float = .0001f) = check(abs(a - b) <= eps) { "$a != $b" }

/** Distance from [p] to the polyline [line]. */
private fun distance(p: InkPoint, line: List<InkPoint>): Float {
    var best = Float.MAX_VALUE
    for (i in 1 until line.size) {
        val a = line[i - 1]; val b = line[i]
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        best = minOf(best, hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy)))
    }
    return best
}

private fun pressure() {
    // Defaults reproduce the original response exactly, so existing notebooks and settings feel the same.
    for (raw in listOf(0f, .1f, .5f, 1f, 1.4f, 3f)) {
        val legacy = (raw * 1f).coerceIn(.25f, 1.8f).let { (1f + (it - 1f) * 1f).coerceIn(.25f, 1.8f) }
        close(PenPressure.sample(raw), legacy)
    }
    check(PenPressure.sample(.5f, enabled = false) == 1f)
    // A light curve reads a gentle touch as firmer; a firm one needs a harder press; full pressure is unchanged.
    check(PenPressure.sample(.5f, curve = .6f) > PenPressure.sample(.5f))
    check(PenPressure.sample(.5f, curve = 1.6f) < PenPressure.sample(.5f))
    close(PenPressure.sample(1f, curve = .6f), 1f); close(PenPressure.sample(1f, curve = 1.6f), 1f)
    check(PenPressure.curve(Float.NaN) == 1f && PenPressure.curve(9f) == 2f && PenPressure.curve(0f) == .5f)
    check(PenPressure.Feel.matching(1f, 1f) == PenPressure.Feel.NORMAL)
    check(PenPressure.Feel.matching(.7f, 1.3f) == PenPressure.Feel.LIGHT)
    check(PenPressure.Feel.matching(.8f, 1f) == null)
    // Speed thins ink even with pressure off, and never below the quarter-width floor.
    close(PenPressure.sample(.9f, enabled = false, speed = .5f), .5f)
    check(PenPressure.sample(1f, speed = .5f) < PenPressure.sample(1f))
    check(PenPressure.sample(1f, speed = 0f) >= .25f)
    // Tilt: upright and unreported tilt are the chosen width, lying flat is wider, and it only grows.
    close(PenPressure.tiltScale(0f), 1f); close(PenPressure.tiltScale(Float.NaN), 1f); close(PenPressure.tiltScale(-1f), 1f)
    check(PenPressure.tiltScale(.5f) > 1f && PenPressure.tiltScale(1f) > PenPressure.tiltScale(.5f))
    close(PenPressure.tiltScale(PI.toFloat() / 2f), 1.9f)
    close(PenPressure.tiltScale(9f), 1.9f)
    // Speed width: slow is full width, fast is thin, in between eases, and the first sample is never thin.
    val speed = PenPressure.SpeedWidth(1f)
    close(speed.factor(0f, 0f, 0), 1f)
    var t = 0L; var x = 0f
    repeat(40) { x += .3f * 8; t += 8; close(speed.factor(x, 0f, t), 1f) }
    var last = 1f
    repeat(60) { x += 12f * 8; t += 8; last = speed.factor(x, 0f, t) }
    check(last < .3f) { "fast width $last" }
    val eased = PenPressure.SpeedWidth(1f)
    eased.factor(0f, 0f, 0)
    val steps = (1..30).map { eased.factor(it * 16f, 0f, it * 8L) }
    check(steps.zipWithNext().all { (a, b) -> b <= a + 1e-5f }) { "width should thin monotonically as it speeds up" }
    // Zoom invariance: the same screen motion at another scale gives the same width.
    val zoomed = PenPressure.SpeedWidth(4f)
    zoomed.factor(0f, 0f, 0)
    val flat = PenPressure.SpeedWidth(1f); flat.factor(0f, 0f, 0)
    repeat(30) { i -> close(zoomed.factor((i + 1) * 4f, 0f, (i + 1) * 8L), flat.factor((i + 1) * 16f, 0f, (i + 1) * 8L)) }
    // A time gap or a clock reset re-arms rather than inventing a huge speed.
    check(eased.factor(5000f, 0f, 100_000L) == 1f)
    check(eased.factor(5001f, 0f, 100_001L) in .25f..1f)
}

private fun prediction() {
    val out = FloatArray(16)
    // Tip first, shifted by the filter's offset, cut at the reach.
    var n = StrokePrediction.clip(floatArrayOf(11f, 0f, 12f, 0f, 40f, 0f), 3, 10f, 0f, 0f, 0f, 1f, 5f, out)
    check(n == 4) { "n=$n" }
    close(out[0], 10f); close(out[2], 11f); close(out[4], 12f); close(out[6], 15f)
    n = StrokePrediction.clip(floatArrayOf(14f, 0f, 20f, 0f, 30f, 0f), 3, 10f, 0f, 0f, 0f, 1f, 8f, out)
    check(n == 3) { "n=$n" }
    close(out[2], 14f); close(out[4], 18f)   // 4 left of the 8-pixel budget after the first step
    // Zoom: the reach is in screen pixels, so a 2× page shows half the page units.
    n = StrokePrediction.clip(floatArrayOf(20f, 0f), 1, 10f, 0f, 0f, 0f, 2f, 8f, out)
    check(n == 2); close(out[2], 14f)
    // The drawn tip is the anchor even if the raw pen is ahead of it.
    n = StrokePrediction.clip(floatArrayOf(11f, 0f), 1, 9f, 0f, -1f, 0f, 1f, 28f, out)
    check(n == 2); close(out[2], 10f)
    // Nothing worth drawing, or nonsense, draws nothing.
    check(StrokePrediction.clip(floatArrayOf(10f, 0f), 1, 10f, 0f, 0f, 0f, 1f, 28f, out) == 0)
    check(StrokePrediction.clip(floatArrayOf(Float.NaN, 0f), 1, 10f, 0f, 0f, 0f, 1f, 28f, out) == 0)
    check(StrokePrediction.clip(floatArrayOf(), 0, 10f, 0f, 0f, 0f, 1f, 28f, out) == 0)
    check(StrokePrediction.clip(floatArrayOf(11f, 0f), 1, 10f, 0f, 0f, 0f, 0f, 28f, out) == 0)
    // The buffer is never overrun.
    val many = FloatArray(200) { if (it % 2 == 0) it * 2f else 0f }
    check(StrokePrediction.clip(many, 100, 0f, 0f, 0f, 0f, 1f, 1000f, FloatArray(8)) <= 4)
}

private fun taper() {
    val line = (0..100).map { InkPoint(it.toFloat(), 0f) }
    check(InkTaper.scales(line, 0f).all { it == 1f })
    val scales = InkTaper.scales(line, 6f)
    check(scales.first() < .5f && scales.last() < .5f) { "ends ${scales.first()} ${scales.last()}" }
    close(scales[50], 1f)
    check((0..5).map { scales[it] }.zipWithNext().all { (a, b) -> b >= a }) { "start should widen" }
    check((0 until 100).all { abs(scales[it] - scales[100 - it]) < 1e-4f }) { "symmetric" }
    check(scales.min() >= .25f - 1e-4f)
    // Dots, ticks and short dashes stay neutral; a short stroke's ends never overlap.
    check(InkTaper.scales(listOf(InkPoint(0f, 0f), InkPoint(.1f, 0f), InkPoint(.2f, 0f)), 6f).all { it == 1f })
    val dash = InkTaper.scales((0..10).map { InkPoint(it * .5f, 0f) }, 8f)
    check(dash.max() > .9f || dash.all { it > 0f })
    // Configuration clamps, rejects nonsense and bumps the version only on a real change.
    InkTaper.configure(0f); val v = InkTaper.version
    InkTaper.configure(0f); check(InkTaper.version == v)
    InkTaper.configure(.5f); check(InkTaper.version == v + 1 && InkTaper.length() > 2f)
    InkTaper.configure(Float.NaN); check(InkTaper.amount == 0f)
    InkTaper.configure(5f); check(InkTaper.amount == 1f)
    InkTaper.configure(0f)
    check(InkTaper.length() == 0f)
}

private fun refit() {
    // A dense straight stroke collapses to a handful of samples with every gap under the span limit.
    val straight = (0..600).map { InkPoint(it * .1f, it * .05f, 1f) }
    val thin = StrokeRefit.simplify(straight)
    check(thin.size < straight.size / 5) { "straight kept ${thin.size}" }
    check(thin.first() === straight.first() && thin.last() === straight.last())
    check(thin.zipWithNext().all { (a, b) -> hypot(b.x - a.x, b.y - a.y) <= StrokeRefit.MAX_SPAN + 1e-3f })
    check(straight.all { distance(it, thin) <= StrokeRefit.TOLERANCE + 1e-3f })
    // A looped, handwriting-like stroke keeps its shape within the tolerance and keeps the order.
    val loop = (0..720).map { val a = it * PI.toFloat() / 180f * .5f; InkPoint(30f * cos(a) + it * .01f, 20f * sin(a * 2f), 1f) }
    val round = StrokeRefit.simplify(loop)
    check(round.size < loop.size) { "loop was not thinned" }
    // Writing-like: 0.6-unit steps along a wavy line with pressure wobble, as a pen produces at a slow hand.
    val script = (0..2000).map { InkPoint(it * .6f * .95f, 6f * sin(it * .04f) + 2f * sin(it * .13f), .8f + .1f * sin(it * .02f)) }
    val scriptThin = StrokeRefit.simplify(script)
    println("refit: straight ${straight.size}->${thin.size}, loop ${loop.size}->${round.size}, script ${script.size}->${scriptThin.size}")
    check(loop.all { distance(it, round) <= StrokeRefit.TOLERANCE + 1e-3f }) { "loop drifted ${loop.maxOf { distance(it, round) }}" }
    check(round.all { p -> loop.any { it === p } } && round.map { loop.indexOf(it) }.zipWithNext().all { (a, b) -> a < b })
    // Pressure is as faithful as the geometry: a ramp along a straight line keeps its slope.
    val ramp = (0..200).map { InkPoint(it * .1f, 0f, .4f + it * .006f) }
    val rampThin = StrokeRefit.simplify(ramp)
    check(ramp.indices.all { i ->
        val p = ramp[i]
        val after = rampThin.indexOfFirst { it.x >= p.x - 1e-4f }
        val a = rampThin[(after - 1).coerceAtLeast(0)]; val b = rampThin[after]
        val t = if (b.x == a.x) 0f else (p.x - a.x) / (b.x - a.x)
        abs((a.pressure + (b.pressure - a.pressure) * t) - p.pressure) < .05f
    }) { "pressure drifted" }
    // Short strokes, dots, and a stroke that is already sparse are returned as they came.
    val dot = listOf(InkPoint(1f, 1f), InkPoint(1.1f, 1f))
    check(StrokeRefit.simplify(dot) === dot)
    val sparse = (0..30).map { InkPoint(it * 2.5f, if (it % 2 == 0) 0f else 3f) }
    check(StrokeRefit.simplify(sparse) === sparse)
    // Enormous strokes must not recurse the stack.
    val huge = (0..300_000).map { InkPoint(it * .01f, sin(it * .0005f) * 20f, 1f) }
    check(StrokeRefit.simplify(huge).size in 2 until huge.size)
}

private fun letter(x: Float, y: Float, w: Float = 7f, h: Float = 10f, strokes: Int = 1): List<List<InkPoint>> =
    (0 until strokes).map { s ->
        // A bent mark with a little height variation; the second stroke is a dot above, like an i.
        if (s == 0) listOf(InkPoint(x, y), InkPoint(x + w * .5f, y + h, .8f), InkPoint(x + w, y))
        else listOf(InkPoint(x + w * .5f, y - 4f), InkPoint(x + w * .5f + .2f, y - 3.8f))
    }

private fun handwritingTidy() {
    // Three lines of "writing", each drifting downhill by about 3.5° with uneven gaps.
    val slope = .06f
    val strokes = ArrayList<List<InkPoint>>()
    val gaps = floatArrayOf(2f, 6f, 1f, 9f, 3f, 14f, 2f, 7f, 1f, 11f, 4f, 2f, 15f, 3f, 5f, 1f, 8f, 2f)
    val lineStarts = (0 until 3).map { 100f + it * 70f }
    val lineOf = ArrayList<Int>()
    for ((li, top) in lineStarts.withIndex()) {
        var x = 40f
        for (i in gaps.indices) {
            val y = top + slope * (x - 40f)
            val glyph = letter(x, y, strokes = if (i % 4 == 1) 2 else 1)
            glyph.forEach { strokes += it; lineOf += li }
            x += 7f + gaps[(i + li) % gaps.size]
        }
    }
    // A long underline and a tall box are not writing; they have to stay put.
    val underline = listOf(InkPoint(40f, 165f), InkPoint(300f, 170f)); strokes += underline; lineOf += -1

    val result = HandwritingTidy.tidy(strokes)
    check(result.lines == 3) { "lines ${result.lines}" }
    check(result.changed && result.strokes.size == strokes.size)
    check(result.strokes.last() === strokes.last()) { "rule moved" }
    // Strokes only slide: every one keeps its exact shape and pressure.
    for (i in strokes.indices) {
        val a = strokes[i]; val b = result.strokes[i]
        check(a.size == b.size)
        for (k in 1 until a.size) {
            close(b[k].x - b[0].x, a[k].x - a[0].x, .001f); close(b[k].y - b[0].y, a[k].y - a[0].y, .001f)
            check(a[k].pressure == b[k].pressure)
        }
    }
    // Levelled: each line's centres no longer trend downhill.
    for (li in 0 until 3) {
        val centres = strokes.indices.filter { lineOf[it] == li && strokes[it].size == 3 }.map { i ->
            val p = result.strokes[i]; (p.minOf { it.x } + p.maxOf { it.x }) / 2f to (p.minOf { it.y } + p.maxOf { it.y }) / 2f
        }
        val first = centres.take(5).map { it.second }.average(); val last = centres.takeLast(5).map { it.second }.average()
        check(abs(last - first) < 3.0) { "line $li still drifts ${last - first}" }
        val before = strokes.indices.filter { lineOf[it] == li && strokes[it].size == 3 }.map { i ->
            val p = strokes[i]; (p.minOf { it.y } + p.maxOf { it.y }) / 2f
        }
        check(abs(before.takeLast(5).average() - before.take(5).average()) > 8.0)
    }
    // Spacing is evened, not forced: the spread of gaps shrinks but does not vanish.
    fun gapSpread(source: List<List<InkPoint>>, line: Int): Double {
        val boxes = strokes.indices.filter { lineOf[it] == line && strokes[it].size == 3 }
            .map { i -> source[i].minOf { it.x } to source[i].maxOf { it.x } }.sortedBy { it.first }
        val g = boxes.zipWithNext().map { (a, b) -> (b.first - a.second).toDouble() }
        val mean = g.average()
        return kotlin.math.sqrt(g.sumOf { (it - mean).pow(2) } / g.size)
    }
    for (li in 0 until 3) {
        val before = gapSpread(strokes, li); val after = gapSpread(result.strokes, li)
        check(after < before && after > before * .2) { "line $li spread $before -> $after" }
    }
    // Options switch each half off.
    val onlyLevel = HandwritingTidy.tidy(strokes, HandwritingTidy.Options(levelLines = true, evenSpacing = false))
    check(strokes.indices.all { i -> onlyLevel.strokes[i].first().x == strokes[i].first().x })
    val onlySpace = HandwritingTidy.tidy(strokes, HandwritingTidy.Options(levelLines = false, evenSpacing = true))
    check(strokes.indices.all { i -> onlySpace.strokes[i].first().y == strokes[i].first().y })
    // Already-neat writing is left alone, as is a handful of strokes.
    val neat = ArrayList<List<InkPoint>>()
    repeat(4) { l -> repeat(12) { i -> neat += letter(40f + i * 14f, 100f + l * 60f) } }
    val untouched = HandwritingTidy.tidy(neat)
    check(!untouched.changed) { "neat writing moved ${untouched.moved}" }
    check(!HandwritingTidy.tidy(strokes.take(3)).changed)
    check(!HandwritingTidy.tidy(emptyList()).changed)
    // A steep diagonal is a drawing, not a drifting line.
    val steep = (0 until 10).map { letter(40f + it * 12f, 100f + it * 14f) }.flatten()
    check(!HandwritingTidy.tidy(steep).changed)
    // Nonsense input doesn't throw or move anything.
    check(!HandwritingTidy.tidy(listOf(listOf(InkPoint(Float.NaN, 0f)), emptyList())).changed)
}

private class Polygons : InkOutline.Sink {
    val shapes = ArrayList<ArrayList<Pair<Float, Float>>>()
    override fun moveTo(x: Float, y: Float) { shapes += arrayListOf(x to y) }
    override fun lineTo(x: Float, y: Float) { shapes.last() += x to y }
    override fun close() {}
    fun area(shape: List<Pair<Float, Float>>): Float {
        var a = 0f
        for (i in shape.indices) { val p = shape[i]; val q = shape[(i + 1) % shape.size]; a += p.first * q.second - q.first * p.second }
        return a / 2f
    }
    /** Non-zero winding of every polygon at ([x], [y]). */
    fun winding(x: Float, y: Float): Int {
        var w = 0
        for (shape in shapes) for (i in shape.indices) {
            val (x0, y0) = shape[i]; val (x1, y1) = shape[(i + 1) % shape.size]
            if (y0 <= y) { if (y1 > y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) > 0) w++ }
            else if (y1 <= y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) < 0) w--
        }
        return w
    }
}

private fun outline() {
    // A wavy stroke whose width swells and tapers to a point at both ends, like a pressure ramp.
    val centre = (0..200).map { InkPoint(it * .5f, 8f * sin(it * .06f)) }
    val radius = { i: Int -> .2f + 2f * sin(PI.toFloat() * i / 200f) }
    val polygons = Polygons()
    InkOutline.build(centre, radius, polygons)
    // Every piece is wound the same way, so a non-zero fill paints the union exactly once.
    check(polygons.shapes.all { polygons.area(it) > 0f }) { "a piece is wound backwards" }
    // Coverage matches the swept nib: inside every disc is filled, and well outside all of them is not.
    val probe = java.util.Random(3)
    repeat(4000) {
        val x = probe.nextFloat() * 110f - 5f; val y = probe.nextFloat() * 24f - 12f
        var inside = false; var near = false
        for (i in centre.indices) {
            val d = hypot(x - centre[i].x, y - centre[i].y)
            if (d < radius(i) - .02f) inside = true
            if (d < radius(i) + .35f) near = true
        }
        // Between samples the hull also covers the gap, so only "inside a disc" and "far from all" are certain.
        if (inside) check(polygons.winding(x, y) != 0) { "hole at $x,$y" }
        if (!near) check(polygons.winding(x, y) == 0) { "spill at $x,$y" }
    }
    // At most one hull and one disc per sample; a straight, even run needs no joint discs at all.
    check(polygons.shapes.size <= 2 * centre.size + 2) { "too many pieces: ${polygons.shapes.size}" }
    val straight = Polygons()
    InkOutline.build((0..100).map { InkPoint(it * .5f, 0f) }, { 1f }, straight)
    check(straight.shapes.size == 100 + 2) { "straight run pieces: ${straight.shapes.size}" }
    // Degenerate input: a lone point is a disc, coincident points don't crash, zero radius emits nothing.
    val dot = Polygons(); InkOutline.build(listOf(InkPoint(1f, 1f)), { 1f }, dot); check(dot.shapes.size == 1)
    val same = Polygons(); InkOutline.build(listOf(InkPoint(1f, 1f), InkPoint(1f, 1f), InkPoint(2f, 1f)), { 1f }, same)
    check(same.shapes.all { same.area(it) > 0f })
    val sharp = Polygons(); InkOutline.build(listOf(InkPoint(0f, 0f), InkPoint(10f, 0f), InkPoint(0f, 1f)), { 1.5f }, sharp)
    check(sharp.winding(10.9f, 0f) != 0) { "a sharp corner must stay round and filled" }
}

private fun ends() {
    // Pen-down and lift pressures unlike their neighbours take the neighbours' values.
    val line = (0..40).map { InkPoint(it * 1f, 0f, .6f) }.toMutableList()
    line[0] = InkPoint(0f, 0f, 1.8f); line[40] = InkPoint(40f, 0f, 1.8f)
    val settled = StrokeEnds.settle(line, 2.2f)
    check(settled.first().pressure == .6f && settled.last().pressure == .6f)
    // Positions are never trimmed: the close of a small "o" and short final turns stay exactly as written.
    val o = (0..60).map { val t = it / 60f * 2.2f * PI.toFloat(); InkPoint(4f * cos(t), 4f * sin(t), .6f) }
    val keptO = StrokeEnds.settle(o, 2.2f)
    check(keptO.size == o.size && keptO.indices.all { keptO[it].x == o[it].x && keptO[it].y == o[it].y }) { "an o was cut short" }
    val skid = (0..40).map { InkPoint(it * 1f, 0f, .6f) } + listOf(InkPoint(40.6f, -.8f, .6f), InkPoint(40.8f, -1.6f, .6f))
    check(StrokeEnds.settle(skid, 2.2f) === skid)
    val plain = (0..40).map { InkPoint(it * 1f, 0f, .6f) }
    check(StrokeEnds.settle(plain, 2.2f) === plain)
    val dot = listOf(InkPoint(0f, 0f, 1f), InkPoint(.1f, 0f, .5f))
    check(StrokeEnds.settle(dot, 2.2f) === dot)
}

fun main() {
    pressure(); prediction(); taper(); refit(); handwritingTidy(); outline(); ends()
    println("Handwriting smoke passed: pressure, speed, tilt, prediction tail, taper, refit, tidy, outline and ends")
}
