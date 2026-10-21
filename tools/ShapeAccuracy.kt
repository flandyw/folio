package com.folio.notes

import com.folio.notes.ink.shapes.ShapeAssistOptions
import com.folio.notes.ink.shapes.ShapeCleanup
import com.folio.notes.ink.shapes.ShapeFit
import com.folio.notes.ink.shapes.ShapeKind
import com.folio.notes.ink.shapes.ShapeMath
import com.folio.notes.ink.shapes.ShapeRecognition
import com.folio.notes.ink.shapes.Tidied
import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import java.util.Random
import kotlin.math.*

/**
 * Recognition accuracy over synthetic hand-drawn ink. Every trial draws an ideal shape the way a
 * hand would (low-frequency wobble, jitter, rounded corners, a start anywhere on the outline, either
 * direction, overshoot or a gap at the seam, uneven pen speed, rotation) and checks what the
 * recogniser makes of it. Writing-like strokes must stay freehand. Seeded, so runs are comparable.
 *
 *   node tools/shapes-smoke.cjs --accuracy [-v]
 */
private const val TRIALS = 60

private class Hand(seed: Long) {
    val random = Random(seed)
    fun between(a: Double, b: Double) = a + random.nextDouble() * (b - a)
    fun chance(p: Double) = random.nextDouble() < p
}

private fun pt(x: Double, y: Double) = InkPoint(x.toFloat(), y.toFloat(), .4f)

/** A dense, evenly spaced polyline through [vertices] (closed back to the first when [closed]). */
private fun polyline(vertices: List<InkPoint>, closed: Boolean): List<InkPoint> {
    val v = if (closed) vertices + vertices.first() else vertices
    val result = ArrayList<InkPoint>()
    for (i in 1 until v.size) {
        val a = v[i - 1]; val b = v[i]
        val n = max(1, hypot(b.x - a.x, b.y - a.y).roundToInt())
        for (k in 0 until n) result += InkPoint(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n, .4f)
    }
    result += v.last()
    return result
}

private fun rotated(points: List<InkPoint>, angle: Double, cx: Double = 0.0, cy: Double = 0.0) = points.map {
    val x = it.x - cx; val y = it.y - cy
    pt(cx + x * cos(angle) - y * sin(angle), cy + x * sin(angle) + y * cos(angle))
}

private fun pathLength(points: List<InkPoint>) = points.zipWithNext().sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }

/**
 * Turn an ideal dense path into what a pen reports. [level] scales wobble: .004 careful,
 * .012 ordinary, .025 hurried.
 */
private fun draw(ideal: List<InkPoint>, closed: Boolean, hand: Hand, level: Double, rounding: Double = 0.0, hooks: Boolean = false): List<InkPoint> {
    var path = ideal
    val size = hypot((path.maxOf { it.x } - path.minOf { it.x }).toDouble(), (path.maxOf { it.y } - path.minOf { it.y }).toDouble())
    if (closed) {
        // Start anywhere on the outline, in either direction, finishing short of or past the seam.
        val body = path.dropLast(1)
        val start = hand.random.nextInt(body.size)
        var ring = body.drop(start) + body.take(start)
        if (hand.chance(.5)) ring = ring.reversed()
        val extra = (ring.size * hand.between(-.04, .09)).roundToInt()
        path = when {
            extra > 0 -> ring + List(extra) { ring[it % ring.size] }
            else -> ring.dropLast(-extra)
        }
    } else if (hand.chance(.5)) path = path.reversed()
    // Rounded corners: a moving average narrower than a side, never wider than a few percent.
    if (rounding > 0) {
        val window = max(1, (path.size * rounding).roundToInt())
        path = path.indices.map { i ->
            val lo = max(0, i - window); val hi = min(path.lastIndex, i + window)
            val k = if (i - lo < hi - i) i - lo else hi - i
            var x = 0.0; var y = 0.0
            for (j in i - k..i + k) { x += path[j].x; y += path[j].y }
            pt(x / (2 * k + 1), y / (2 * k + 1))
        }
    }
    // Low-frequency wobble along the path, plus a little jitter.
    val waves = (1..3).map { Triple(hand.between(1.0, 5.0 + it * 2), hand.between(0.0, 2 * PI), hand.between(.3, 1.0) / it) }
    val dx = hand.between(-1.0, 1.0); val dy = hand.between(-1.0, 1.0)
    val amplitude = level * size
    val wobbly = path.mapIndexed { i, p ->
        val t = i.toDouble() / path.size
        var w = 0.0
        for ((f, phase, a) in waves) w += a * sin(2 * PI * f * t + phase)
        val ax = i.coerceAtMost(path.lastIndex - 1); val bx = ax + 1
        val tx = path[bx].x - path[ax].x; val ty = path[bx].y - path[ax].y
        val tl = hypot(tx.toDouble(), ty.toDouble()).coerceAtLeast(1e-6)
        val n = w * amplitude + hand.random.nextGaussian() * amplitude * .15
        pt(p.x + -ty / tl * n + dx * amplitude * .2, p.y + tx / tl * n + dy * amplitude * .2)
    }
    // Uneven pen speed: sample spacing swings by several times, with occasional dwell.
    val total = pathLength(wobbly)
    val base = max(2.0, total / hand.between(120.0, 420.0))
    val out = ArrayList<InkPoint>()
    var at = 0.0; var acc = 0.0; var i = 0
    out += wobbly.first()
    while (i < wobbly.lastIndex) {
        val seg = hypot((wobbly[i + 1].x - wobbly[i].x).toDouble(), (wobbly[i + 1].y - wobbly[i].y).toDouble())
        acc += seg; i++
        if (acc >= at) {
            out += wobbly[i]; acc = 0.0
            at = base * hand.between(.35, 2.2)
            if (hand.chance(.02)) repeat(hand.random.nextInt(6)) { out += wobbly[i] }
        }
    }
    if (out.last() != wobbly.last()) out += wobbly.last()
    // The nib landing and flicking off: a short stray tick at either end.
    if (hooks && hand.chance(.3)) {
        val reach = size * hand.between(.03, .07); val angle = hand.between(0.0, 2 * PI)
        val first = out.first()
        out.addAll(0, (4 downTo 1).map { pt(first.x + reach * it / 4 * cos(angle), first.y + reach * it / 4 * sin(angle)) })
    }
    if (hooks && hand.chance(.3)) {
        val reach = size * hand.between(.03, .07); val angle = hand.between(0.0, 2 * PI)
        val last = out.last()
        out += (1..4).map { pt(last.x + reach * it / 4 * cos(angle), last.y + reach * it / 4 * sin(angle)) }
    }
    return out
}

private val BASE = 200.0
private fun ngon(n: Int, rx: Double, ry: Double, rotation: Double, star: Double = 0.0): List<InkPoint> {
    val count = if (star > 0) n * 2 else n
    return (0 until count).map { i ->
        val r = if (star > 0 && i % 2 == 1) star else 1.0
        val a = -PI / 2 + i * 2 * PI / count
        pt(BASE + rx * r * cos(a), BASE + ry * r * sin(a))
    }.let { rotated(it, rotation, BASE, BASE) }
}

private fun ellipseIdeal(rx: Double, ry: Double, rotation: Double) =
    rotated((0..360).map { val a = it * PI / 180; pt(BASE + rx * cos(a), BASE + ry * sin(a)) }, rotation, BASE, BASE)

private fun heartIdeal(w: Double, h: Double, tilt: Double): List<InkPoint> {
    val raw = (0..400).map {
        val t = it * 2 * PI / 400
        pt(16 * sin(t).pow(3), -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)))
    }
    val minX = raw.minOf { it.x }; val maxX = raw.maxOf { it.x }; val minY = raw.minOf { it.y }; val maxY = raw.maxOf { it.y }
    return rotated(raw.map { pt(BASE + (it.x - (minX + maxX) / 2) / (maxX - minX) * w, BASE + (it.y - (minY + maxY) / 2) / (maxY - minY) * h) }, tilt, BASE, BASE)
}

private fun arcIdeal(radius: Double, sweepDegrees: Double, rotation: Double) =
    rotated((0..180).map { val a = (-sweepDegrees / 2 + sweepDegrees * it / 180) * PI / 180; pt(BASE + radius * cos(a), BASE + radius * sin(a)) }, rotation, BASE, BASE)

private fun bracketIdeal(height: Double, width: Double, rotation: Double) =
    rotated(polyline(listOf(pt(BASE + width, BASE - height / 2), pt(BASE, BASE - height / 2), pt(BASE, BASE + height / 2), pt(BASE + width, BASE + height / 2)), false), rotation, BASE, BASE)

private fun braceIdeal(height: Double, width: Double, rotation: Double): List<InkPoint> {
    val r = width / 2; val out = ArrayList<InkPoint>()
    fun arc(cx: Double, cy: Double, from: Double, to: Double) { for (k in 0..12) { val a = (from + (to - from) * k / 12) * PI / 180; out += pt(cx + r * cos(a), cy + r * sin(a)) } }
    val top = BASE - height / 2; val mid = BASE; val bottom = BASE + height / 2
    // Hooks at the open side (right), shaft in the middle, a point at the left of the middle.
    arc(BASE + width, top + r, -90.0, -180.0)
    out += pt(BASE + r, mid - r)
    arc(BASE, mid - r, 0.0, 90.0)
    arc(BASE, mid + r, -90.0, 0.0)
    out += pt(BASE + r, bottom - r)
    arc(BASE + width, bottom - r, 180.0, 90.0)
    return rotated(polyline(out, false), rotation, BASE, BASE)
}

private class Trial(val name: String, val expected: String, val closed: Boolean, val make: (Hand) -> List<InkPoint>, val rounding: Double = 0.0)

private fun label(result: Tidied?): String = when (result?.kind) {
    null -> "none"
    ShapeKind.POLYGON -> "polygon${result.sides}"
    ShapeKind.STAR -> "star${result.sides / 2}"
    else -> result.kind.name.lowercase()
}

fun main(args: Array<String>) {
    val verbose = "-v" in args
    // `-x name` explains the misses of one shape at the ordinary level: what the fitting saw.
    val explainLevel = args.indexOf("-l").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 1
    val explain = args.indexOf("-x").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun stroke(points: List<InkPoint>) = Stroke(Tool.PEN, 0x123456, 3.5f, points, .7f, layer = 2)
    val positives = ArrayList<Trial>()
    fun add(name: String, expected: String, closed: Boolean, rounding: Double = 0.0, make: (Hand) -> List<InkPoint>) { positives += Trial(name, expected, closed, make, rounding) }

    add("line", "line", false) { h ->
        val len = h.between(70.0, 420.0); val a = h.between(0.0, 2 * PI)
        polyline(listOf(pt(BASE, BASE), pt(BASE + len * cos(a), BASE + len * sin(a))), false)
    }
    fun arrowIdeal(h: Hand, vee: Boolean): List<InkPoint> {
        val len = h.between(110.0, 400.0); val head = len * h.between(.14, .26); val spread = h.between(.45, .8)
        val tip = pt(BASE + len, BASE)
        val w1 = pt(tip.x - head * cos(spread), BASE - head * sin(spread)); val w2 = pt(tip.x - head * cos(spread), BASE + head * sin(spread))
        val verts = if (vee) listOf(pt(BASE, BASE), tip, w1, tip, w2) else listOf(pt(BASE, BASE), tip, w1, w2.let { tip }, w2)
        return rotated(polyline(verts, false), h.between(0.0, 2 * PI), BASE, BASE)
    }
    add("arrow", "arrow", false) { arrowIdeal(it, true) }
    add("curved arrow", "arrow", false) { h ->
        val radius = h.between(90.0, 260.0); val sweep = h.between(60.0, 130.0) * PI / 180
        val arc = (0..120).map { val a = -sweep / 2 + sweep * it / 120; pt(BASE + radius * cos(a), BASE + radius * sin(a)) }
        val tip = arc.last(); val before = arc[arc.size - 8]
        val len = hypot((tip.x - before.x).toDouble(), (tip.y - before.y).toDouble()); val ux = (tip.x - before.x) / len; val uy = (tip.y - before.y) / len
        val head = sweep * radius * h.between(.2, .3); val spread = h.between(.5, .75)
        fun wing(sign: Double) = pt(tip.x - head * (ux * cos(spread) - sign * uy * sin(spread)), tip.y - head * (uy * cos(spread) + sign * ux * sin(spread)))
        rotated(arc + polyline(listOf(tip, wing(1.0), tip, wing(-1.0)), false), h.between(0.0, 2 * PI), BASE, BASE)
    }
    add("rectangle", "rectangle", true, .006) { h ->
        val w = h.between(80.0, 300.0); val k = h.between(.35, 1.0) * w
        val rot = if (h.chance(.6)) 0.0 else h.between(-.5, .5)
        rotated(polyline(listOf(pt(BASE - w / 2, BASE - k / 2), pt(BASE + w / 2, BASE - k / 2), pt(BASE + w / 2, BASE + k / 2), pt(BASE - w / 2, BASE + k / 2)), true), rot, BASE, BASE)
    }
    add("rounded box", "rounded", true) { h ->
        val w = h.between(110.0, 300.0); val k = w * h.between(.35, .8); val r = k * h.between(.28, .5)
        val rot = if (h.chance(.7)) 0.0 else h.between(-.4, .4)
        val v = ArrayList<InkPoint>()
        fun corner(cx: Double, cy: Double, from: Double) { for (i in 0..8) { val a = (from + 90.0 * i / 8) * PI / 180; v += pt(cx + r * cos(a), cy + r * sin(a)) } }
        corner(BASE + w / 2 - r, BASE - k / 2 + r, -90.0); corner(BASE + w / 2 - r, BASE + k / 2 - r, 0.0)
        corner(BASE - w / 2 + r, BASE + k / 2 - r, 90.0); corner(BASE - w / 2 + r, BASE - k / 2 + r, 180.0)
        rotated(polyline(v, true), rot, BASE, BASE)
    }
    add("pill", "rounded", true) { h ->
        val w = h.between(130.0, 320.0); val k = w * h.between(.25, .5); val r = k / 2
        val v = ArrayList<InkPoint>()
        for (i in 0..16) { val a = (-90.0 + 180.0 * i / 16) * PI / 180; v += pt(BASE + w / 2 - r + r * cos(a), BASE + r * sin(a)) }
        for (i in 0..16) { val a = (90.0 + 180.0 * i / 16) * PI / 180; v += pt(BASE - w / 2 + r + r * cos(a), BASE + r * sin(a)) }
        rotated(polyline(v, true), if (h.chance(.7)) 0.0 else h.between(-.4, .4), BASE, BASE)
    }
    add("circle", "ellipse", true) { h -> val r = h.between(40.0, 180.0); ellipseIdeal(r, r * h.between(.92, 1.0), h.between(0.0, PI)) }
    add("ellipse", "ellipse", true) { h -> val r = h.between(70.0, 200.0); ellipseIdeal(r, r * h.between(.35, .75), h.between(0.0, PI)) }
    add("triangle", "polygon3", true, .006) { h ->
        val rot = h.between(0.0, 2 * PI)
        val v = listOf(pt(BASE + h.between(-.4, .4) * 100, BASE - 110), pt(BASE + h.between(60.0, 140.0), BASE + 80), pt(BASE - h.between(60.0, 140.0), BASE + 80))
        rotated(polyline(v, true), rot, BASE, BASE)
    }
    add("right triangle", "polygon3", true, .006) { h ->
        val w = h.between(80.0, 220.0); val k = h.between(60.0, 200.0)
        rotated(polyline(listOf(pt(BASE, BASE), pt(BASE + w, BASE), pt(BASE, BASE - k)), true), h.between(0.0, 2 * PI), BASE, BASE)
    }
    add("diamond", "polygon4", true, .006) { h ->
        val a = h.between(70.0, 160.0); val b = a * h.between(.4, .8)
        rotated(polyline(listOf(pt(BASE, BASE - a), pt(BASE + b, BASE), pt(BASE, BASE + a), pt(BASE - b, BASE)), true), h.between(-.3, .3), BASE, BASE)
    }
    add("trapezoid", "polygon4", true, .006) { h ->
        val top = h.between(40.0, 90.0); val bottom = top + h.between(60.0, 120.0)
        rotated(polyline(listOf(pt(BASE - top, BASE - 60), pt(BASE + top, BASE - 60), pt(BASE + bottom, BASE + 60), pt(BASE - bottom, BASE + 60)), true), h.between(-.3, .3), BASE, BASE)
    }
    add("parallelogram", "polygon4", true, .006) { h ->
        val w = h.between(110.0, 180.0); val lean = h.between(30.0, 70.0)
        rotated(polyline(listOf(pt(BASE - w + lean, BASE - 55), pt(BASE + w + lean, BASE - 55), pt(BASE + w - lean, BASE + 55), pt(BASE - w - lean, BASE + 55)), true), h.between(-.3, .3), BASE, BASE)
    }
    for (n in 5..8) add(
        when (n) { 5 -> "pentagon"; 6 -> "hexagon"; 7 -> "heptagon"; else -> "octagon" }, "polygon$n", true, .004 + .003 * (8 - n) / 3.0
    ) { h -> val r = h.between(70.0, 150.0); polyline(ngon(n, r, r * h.between(.85, 1.0), h.between(0.0, 2 * PI)), true) }
    add("star", "star5", true, .004) { h -> val r = h.between(80.0, 150.0); polyline(ngon(5, r, r, h.between(0.0, 2 * PI), h.between(.38, .5)), true) }
    add("pentagram", "pentagram", true, .004) { h ->
        val r = h.between(80.0, 160.0); val c = ngon(5, r, r, h.between(0.0, 2 * PI))
        polyline(listOf(c[0], c[2], c[4], c[1], c[3]), true)
    }
    add("four-point star", "star4", true, .004) { h -> val r = h.between(80.0, 150.0); polyline(ngon(4, r, r, h.between(0.0, 2 * PI), h.between(.3, .45)), true) }
    add("six-point star", "star6", true, .004) { h -> val r = h.between(80.0, 150.0); polyline(ngon(6, r, r, h.between(0.0, 2 * PI), h.between(.5, .6)), true) }
    add("heart", "heart", true) { h -> val w = h.between(100.0, 280.0); heartIdeal(w, w * h.between(.85, 1.05), h.between(-.25, .25)) }
    add("arc", "arc", false) { h -> arcIdeal(h.between(60.0, 220.0), h.between(70.0, 190.0), h.between(0.0, 2 * PI)) }
    add("bracket", "bracket", false) { h -> val ht = h.between(100.0, 320.0); bracketIdeal(ht, ht * h.between(.12, .3), h.between(0.0, 2 * PI).let { if (h.chance(.6)) (it / (PI / 2)).roundToInt() * PI / 2 else it }) }
    add("brace", "brace", false) { h -> val ht = h.between(140.0, 360.0); braceIdeal(ht, ht * h.between(.12, .22), h.between(0.0, 2 * PI).let { if (h.chance(.6)) (it / (PI / 2)).roundToInt() * PI / 2 else it }) }

    // Writing, scribbles and loops that must stay as drawn.
    val negatives = ArrayList<Trial>()
    fun neg(name: String, closed: Boolean = false, make: (Hand) -> List<InkPoint>) { negatives += Trial(name, "none", closed, make) }
    neg("zigzag") { h -> val n = 5 + h.random.nextInt(5); polyline((0..n).map { pt(BASE + it * 22.0, BASE + if (it % 2 == 0) 0.0 else h.between(30.0, 70.0)) }, false) }
    neg("W") { polyline(listOf(pt(0.0, 0.0), pt(30.0, 90.0), pt(60.0, 20.0), pt(90.0, 90.0), pt(120.0, 0.0)), false) }
    neg("M") { polyline(listOf(pt(0.0, 90.0), pt(0.0, 0.0), pt(45.0, 60.0), pt(90.0, 0.0), pt(90.0, 90.0)), false) }
    neg("N") { polyline(listOf(pt(0.0, 90.0), pt(0.0, 0.0), pt(70.0, 90.0), pt(70.0, 0.0)), false) }
    neg("Z") { polyline(listOf(pt(0.0, 0.0), pt(80.0, 0.0), pt(0.0, 90.0), pt(80.0, 90.0)), false) }
    neg("S") { h -> val s = h.between(.8, 1.6); (0..100).map { val t = it / 100.0; pt(BASE + 40 * s * sin(t * 2 * PI), BASE + 120 * s * t) } }
    neg("eight") { h -> val s = h.between(.8, 1.6); (0..120).map { val t = it / 120.0 * 2 * PI; pt(BASE + 40 * s * sin(2 * t), BASE + 50 * s * sin(t)) } }
    neg("spiral") { h -> val s = h.between(.8, 1.6); (0..200).map { val t = it / 200.0 * 6 * PI; pt(BASE + (6 + t * 4) * s * cos(t), BASE + (6 + t * 4) * s * sin(t)) } }
    neg("double loop", true) { h -> val r = h.between(40.0, 120.0); val e = ellipseIdeal(r, r, 0.0); e + e }
    neg("cursive loops") { h -> val s = h.between(.8, 1.5); (0..300).map { val t = it / 300.0 * 8 * PI; pt(BASE + t * 6 * s + 24 * s * cos(t), BASE + 30 * s * sin(t)) } }
    neg("sine wave") { h -> val s = h.between(1.0, 2.0); (0..200).map { val t = it / 200.0; pt(BASE + t * 300 * s, BASE + 40 * s * sin(t * 6 * PI)) } }
    neg("squiggle") { h ->
        val waves = (1..5).map { Triple(h.between(1.0, 9.0), h.between(0.0, 2 * PI), h.between(10.0, 40.0)) }
        (0..220).map { val t = it / 220.0; pt(BASE + t * 220 + waves.sumOf { (f, p, a) -> a * sin(f * 2 * PI * t + p) }, BASE + waves.sumOf { (f, p, a) -> a * cos(f * 1.7 * PI * t + p) }) }
    }
    neg("crossed bow tie", true) { polyline(listOf(pt(0.0, 0.0), pt(100.0, 100.0), pt(0.0, 100.0), pt(100.0, 0.0)), true) }
    neg("L") { h -> polyline(listOf(pt(0.0, 0.0), pt(0.0, 120.0), pt(h.between(50.0, 90.0), 120.0)), false) }
    neg("check mark") { h -> polyline(listOf(pt(0.0, 50.0), pt(30.0, 90.0), pt(h.between(90.0, 130.0), 0.0)), false) }
    neg("V") { polyline(listOf(pt(0.0, 0.0), pt(60.0, 100.0), pt(120.0, 0.0)), false) }
    neg("A") { polyline(listOf(pt(0.0, 120.0), pt(50.0, 0.0), pt(100.0, 120.0), pt(80.0, 75.0), pt(20.0, 75.0)), false) }
    neg("four") { polyline(listOf(pt(60.0, 0.0), pt(0.0, 80.0), pt(100.0, 80.0), pt(80.0, 80.0), pt(80.0, 130.0)), false) }
    neg("seven") { polyline(listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(30.0, 130.0)), false) }
    neg("K") { polyline(listOf(pt(0.0, 0.0), pt(0.0, 120.0), pt(0.0, 60.0), pt(70.0, 0.0), pt(0.0, 60.0), pt(70.0, 120.0)), false) }
    neg("bolt") { polyline(listOf(pt(60.0, 0.0), pt(10.0, 70.0), pt(55.0, 70.0), pt(30.0, 140.0), pt(100.0, 50.0), pt(55.0, 50.0)), false) }
    neg("long tilde line") { h -> val w = h.between(150.0, 320.0); (0..80).map { val t = it / 80.0; pt(BASE + t * w, BASE + .04 * w * sin(t * 3 * PI)) } }
    neg("scribble fill") { h -> polyline((0..8).map { pt(if (it % 2 == 0) 0.0 else 150.0, it * 10.0 + h.between(-2.0, 2.0)) }, false) }
    neg("tiny circle", true) { h -> val r = h.between(3.0, 8.0); ellipseIdeal(r, r, 0.0) }
    fun semicircle(cx: Double, cy: Double, r: Double, from: Double, to: Double, n: Int = 40) = (0..n).map { val a = (from + (to - from) * it / n) * PI / 180; pt(cx + r * cos(a), cy + r * sin(a)) }
    neg("fish hook") { h -> val r = h.between(25.0, 50.0); polyline(listOf(pt(BASE, BASE - 140), pt(BASE, BASE)), false) + semicircle(BASE + r, BASE, r, 180.0, 40.0) }
    neg("U") { h -> val r = h.between(25.0, 70.0); val a = r * h.between(1.0, 2.2)
        polyline(listOf(pt(BASE - r, BASE - a), pt(BASE - r, BASE)), false) + semicircle(BASE, BASE, r, 180.0, 0.0) + polyline(listOf(pt(BASE + r, BASE), pt(BASE + r, BASE - a)), false) }
    neg("J") { h -> val r = h.between(20.0, 45.0); polyline(listOf(pt(BASE, BASE - 130), pt(BASE, BASE)), false) + semicircle(BASE - r, BASE, r, 0.0, 150.0) }
    neg("tilde") { h -> val w = h.between(80.0, 200.0); (0..60).map { val t = it / 60.0; pt(BASE + t * w, BASE + 0.12 * w * sin(t * 2 * PI)) } }
    neg("D", true) { h -> val r = h.between(40.0, 90.0); polyline(listOf(pt(BASE, BASE - r), pt(BASE, BASE + r)), false) + semicircle(BASE, BASE, r, 90.0, -90.0) }
    neg("P", true) { h -> val r = h.between(30.0, 60.0); polyline(listOf(pt(BASE, BASE + 2.2 * r), pt(BASE, BASE - r)), false) + semicircle(BASE, BASE - r + r * .5, r * .5 + r * .5, -90.0, 90.0).map { pt(it.x.toDouble() + 0.0, it.y.toDouble()) } }
    neg("kidney", true) { h -> val r = h.between(60.0, 120.0); (0..120).map { val t = it / 120.0 * 2 * PI; pt(BASE + r * cos(t) * 1.3, BASE + r * (sin(t) - .35 * exp(-8 * (t - PI / 2).pow(2)) * 2.2) * .8) } }
    neg("cloud", true) { h -> val r = h.between(70.0, 120.0); (0..160).map { val t = it / 160.0 * 2 * PI; val k = r * (1 + .25 * sin(6 * t)); pt(BASE + k * 1.3 * cos(t), BASE + k * .8 * sin(t)) } }
    neg("rounded triangle", true) { h -> val r = h.between(70.0, 130.0); (0..160).map { val t = it / 160.0 * 2 * PI; val k = r * (1 + .18 * cos(3 * t)); pt(BASE + k * cos(t), BASE + k * sin(t)) } }
    neg("open C", false) { h -> arcIdeal(h.between(60.0, 160.0), h.between(250.0, 300.0), 0.0) }

    // `-s` prints, for polygons and circles, how polygon evidence (error against the ellipse, corner
    // contrast, sharp turns) is distributed, to set the thresholds recognition uses.
    if ("-s" in args) {
        val subjects = (positives + negatives).mapNotNull { t ->
            val k = when {
                t.expected.startsWith("polygon") -> t.expected.removePrefix("polygon").toInt()
                t.expected.startsWith("star") -> t.expected.removePrefix("star").toInt() * 2
                t.expected == "pentagram" -> 5
                t.name in setOf("circle", "ellipse", "cloud") -> if (t.name == "cloud") 12 else 8
                else -> null
            }
            k?.let { t to it }
        }
        for ((trial, k) in subjects) {
            for ((li, level) in listOf(.004, .012, .025).withIndex()) {
                val ratios = ArrayList<Double>(); val contrasts = ArrayList<Double>(); val peaks = ArrayList<Int>()
                for (t in 0 until TRIALS) {
                    val hand = Hand(1000L * li + t + trial.name.hashCode() * 31L)
                    val raw = draw(trial.make(hand), trial.closed, hand, level, trial.rounding)
                    val span = ShapeMath.span(raw); val length = ShapeMath.length(raw)
                    val ring = ShapeMath.resample(ShapeMath.resample(raw, length / 192f), ShapeMath.length(raw) / 120f)
                    val e = ShapeFit.ellipse(ring)?.error ?: continue
                    val pk = ShapeFit.corners(ring); peaks += pk.size
                    if (pk.size < 3) continue
                    val loop = ShapeFit.loops(ring, 12, pk.take(3))[k] ?: continue
                    val o = ShapeFit.refine(ring, loop.vertices, span)
                    ratios += ShapeFit.outlineError(ring, o) / e; contrasts += ShapeFit.contrast(ring, o)
                }
                fun q(v: List<Double>) = if (v.isEmpty()) "-" else v.sorted().let { "%.2f/%.2f/%.2f".format(it[it.size / 10], it[it.size / 2], it[it.size * 9 / 10]) }
                println("%-14s L%d k=%-2d err/ell %s  contrast %s  peaks %s".format(trial.name, li, k, q(ratios), q(contrasts), peaks.sorted().let { "${it[it.size / 10]}/${it[it.size / 2]}/${it[it.size * 9 / 10]}" }))
            }
        }
        return
    }
    var failures = 0
    val timings = HashMap<String, MutableList<Double>>()
    println("%-16s %-9s %7s %7s %7s  %s".format("shape", "expected", "careful", "normal", "hurried", "misses (normal)"))
    for (trial in positives) {
        val rates = ArrayList<Double>(); var misses = ""
        for ((li, level) in listOf(.004, .012, .025).withIndex()) {
            var ok = 0
            val seen = HashMap<String, Int>()
            for (k in 0 until TRIALS) {
                val hand = Hand(1000L * li + k + trial.name.hashCode() * 31L)
                val raw = draw(trial.make(hand), trial.closed, hand, level, trial.rounding, "-h" in args)
                val began = System.nanoTime()
                val got = label(ShapeRecognition.recognise(stroke(raw)))
                if (k >= 10) timings.getOrPut(trial.name) { ArrayList() }.add((System.nanoTime() - began) / 1e6)
                if (got == trial.expected) ok++ else seen.merge(got, 1, Int::plus)
                if (verbose && got != trial.expected && li == explainLevel) println("   miss ${trial.name} #$k -> $got")
                if (explain == trial.name && (got != trial.expected || "-a" in args) && li == explainLevel) {
                    val s = stroke(raw); val span = ShapeMath.span(raw); val length = ShapeMath.length(raw)
                    val points = ShapeMath.resample(raw, length / 192f)
                    println("   #$k -> $got span=%.0f ends=%.2f".format(span, ShapeMath.distance(points.first(), points.last()) / span))
                    if (!trial.closed) {
                        val chain = ShapeFit.chains(points, 4, 2)[4]
                        println("      chain " + chain?.vertices?.joinToString(" ") { "%d(%.0f,%.0f)".format(it, points[it].x, points[it].y) })
                        if (verbose) println("      tail " + (110 until points.size step 4).joinToString(" ") { "%d(%.0f,%.0f)".format(it, points[it].x, points[it].y) })
                        fun brute(v: List<Int>): Double = (0 until v.size - 1).sumOf { e -> (v[e]..v[e + 1]).sumOf { t -> ShapeMath.gap(points[t], points[v[e]], points[v[e + 1]]).toDouble().pow(2) } }
                        if (chain != null) println("      dp cost %.0f brute(dp) %.0f".format(chain.cost, brute(chain.vertices.toList())))
                        if (chain != null) { val c = chain.vertices.map { points[it] }; println("      tip-gaps %.2f %.2f (reach .12)  shaft %.2f".format(ShapeMath.distance(c[3], c[1]) / span, ShapeMath.distance(c[4], c[1]) / span, ShapeMath.distance(c[0], c[1]) / span)) }
                    } else {
                        val ring = ShapeMath.resample(raw, length / 120f)
                        val e = ShapeFit.ellipse(ring)
                        val peaks = ShapeFit.corners(ring)
                        ShapeFit.roundedBox(ring)?.let { b -> println("      rounded box err %.4f rel %.2f hw %.0f hh %.0f".format(b.error / span, b.radius / min(b.hw, b.hh), b.hw, b.hh)) }
                        println("      ellipse err %.4f  peaks %d".format((e?.error ?: -1.0) / span, peaks.size))
                        if (peaks.size >= 3) {
                            val loops = ShapeFit.loops(ring, min(12, max(5, peaks.size + 2)), peaks.take(3))
                            println("      " + (3..12).mapNotNull { k -> loops.getOrNull(k)?.let { l ->
                                val o = ShapeFit.refine(ring, l.vertices, span)
                                "k$k=%.4f/c%.1f".format(ShapeFit.outlineError(ring, o) / span, ShapeFit.contrast(ring, o)) } }.joinToString(" "))
                        }
                    }
                    check(s.points.isNotEmpty())
                }
            }
            rates += ok * 100.0 / TRIALS
            if (li == 1) misses = seen.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}×${it.value}" }
        }
        println("%-16s %-9s %6.0f%% %6.0f%% %6.0f%%  %s".format(trial.name, trial.expected, rates[0], rates[1], rates[2], misses))
    }
    println()
    println("%-16s %12s".format("writing/other", "kept freehand"))
    for (trial in negatives) {
        var kept = 0; val seen = HashMap<String, Int>()
        for (k in 0 until TRIALS * 2) {
            val hand = Hand(777L + k + trial.name.hashCode() * 17L)
            val raw = draw(trial.make(hand), trial.closed, hand, .012, 0.004)
            val began = System.nanoTime()
            val got = label(ShapeRecognition.recognise(stroke(raw)))
            if (k >= 20) timings.getOrPut("~" + trial.name) { ArrayList() }.add((System.nanoTime() - began) / 1e6)
            if (got == "none") kept++ else seen.merge(got, 1, Int::plus)
        }
        println("%-16s %11.0f%%  %s".format(trial.name, kept * 100.0 / (TRIALS * 2), seen.entries.joinToString(" ") { "${it.key}×${it.value}" }))
    }
    // A page-sized lasso: lots of small handwriting-like marks, some long curves and a few real diagram shapes.
    run {
        val rng = Random(42)
        val page = ArrayList<Stroke>()
        repeat(1500) { n ->
            val big = n % 12 == 0
            val size = if (big) 40 + rng.nextDouble() * 90 else 12 + rng.nextDouble() * 24
            val x = rng.nextDouble() * 900; val y = rng.nextDouble() * 1300
            val closedLetter = rng.nextDouble() < .25
            val waves = (1..3).map { Triple(rng.nextDouble() * 2 + 1, rng.nextDouble() * 2 * PI, rng.nextDouble() * .5 + .2) }
            val count = if (big) 80 else 40
            val points = (0..count).map { i ->
                val t = i.toDouble() / count
                val a = if (closedLetter) t * 2 * PI else t * (1.5 + rng.nextDouble() * 0 + 1) * PI
                val r = size / 2 * (1 + waves.sumOf { (f, ph, amp) -> amp * .3 * sin(f * a + ph) })
                pt(x + r * cos(a) + t * size * (if (closedLetter) 0.0 else .9), y + r * sin(a))
            }
            page += Stroke(Tool.PEN, 0x123456, 3f, points, .7f)
        }
        val shapes = positives.filter { it.name in setOf("rectangle", "circle", "pentagon", "star", "heart", "arrow", "brace") }
        for (k in 0 until 30) {
            val trial = shapes[k % shapes.size]; val hand = Hand(5000L + k)
            val offset = pt(rng.nextDouble() * 500, rng.nextDouble() * 1000)
            page += Stroke(Tool.PEN, 0x123456, 3f, draw(trial.make(hand), trial.closed, hand, .008, trial.rounding).map { InkPoint(it.x + offset.x - 200f, it.y + offset.y - 200f, .4f) }, .7f)
        }
        val began = System.nanoTime()
        val result = ShapeCleanup.strokes(page, ShapeAssistOptions())
        println("page cleanup: ${page.size} strokes in %.0f ms on this machine; tidied ${result.shapes} shapes".format((System.nanoTime() - began) / 1e6))
    }
    println()
    println("recognition time on this machine (ms, mean / worst): " + timings.entries.sortedByDescending { it.value.max() }.take(8)
        .joinToString("  ") { "${it.key} %.1f/%.1f".format(it.value.average(), it.value.max()) })
    if (failures > 0) throw IllegalStateException("$failures accuracy checks failed")
}
