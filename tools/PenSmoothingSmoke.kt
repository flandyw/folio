package com.folio.notes

import kotlin.math.abs
import kotlin.math.hypot

fun main() {
    fun close(a: Float, b: Float) = check(abs(a - b) < .0001f) { "$a != $b" }
    val start = InkPoint(12f, 18f, .4f)
    val end = InkPoint(28f, 7f, .9f)
    val filter = PenSmoothing(1f, 1f)
    check(filter.sample(start, 0) === start)
    check(filter.sample(end, 8).pressure == end.pressure)
    check(filter.sample(end, 16, final = true) === end)

    // Off is an exact identity, including dots and pressure changes.
    val off = PenSmoothing(0f, 1f)
    repeat(100) { i ->
        val point = InkPoint(i.toFloat(), (i % 3).toFloat(), i / 100f)
        check(off.sample(point, i * 8L) === point)
    }

    // A stationary pen's alternating jitter should lose most of its amplitude.
    fun jitter(amount: Float): Float {
        val smoother = PenSmoothing(amount, 1f)
        smoother.sample(InkPoint(0f, 0f), 0)
        var amplitude = 0f
        repeat(120) { i ->
            val point = InkPoint(0f, if (i % 2 == 0) 1f else -1f)
            val result = smoother.sample(point, (i + 1) * 8L)
            if (i > 20) amplitude += abs(result.y)
        }
        return amplitude / 99f
    }
    check(jitter(.35f) < .6f)
    check(jitter(1f) < jitter(.35f))

    // Screen-space behaviour must match at different page zooms; pressure is untouched.
    val normal = PenSmoothing(.7f, 1f)
    val zoomed = PenSmoothing(.7f, 4f)
    repeat(500) { i ->
        val point = InkPoint(i * .5f, if (i % 2 == 0) 1f else -1f, .65f)
        val a = normal.sample(point, i * 8L)
        val b = zoomed.sample(point.copy(x = point.x / 4f, y = point.y / 4f), i * 8L)
        close(a.x, b.x * 4f); close(a.y, b.y * 4f)
        check(a.pressure == point.pressure && b.pressure == point.pressure)
        check(hypot(point.x - a.x, point.y - a.y) <= 2.801f)
    }

    // Fast movement and sharp reversals stay within the selected lag bound over a long stroke.
    val fast = PenSmoothing(.35f, 2f)
    repeat(100_000) { i ->
        val point = InkPoint((i % 40) * 12f, (i % 17) * 4f)
        val result = fast.sample(point, i * 4L)
        check(result.x.isFinite() && result.y.isFinite())
        check(hypot(point.x - result.x, point.y - result.y) * 2f <= 1.401f)
    }

    // A sharp turn between long legs keeps its apex far closer to the pen than the plain filter would.
    fun apexError(amount: Float): Float {
        val smoother = PenSmoothing(amount, 1f)
        var time = 0L
        var worst = 0f
        // Down the stroke of a "V" at about 1 px/ms, then straight back up. Lag along the path doesn't change the
        // shape; what matters is how far the smoothed points stray from the second leg's line.
        for (i in 0..20) { smoother.sample(InkPoint(i * 3f, i * 6f), time); time += 8 }
        val apex = InkPoint(20 * 3f + 3f, 20 * 6f + 6f)
        val length = hypot(3f, -6f)
        repeat(12) { i ->
            val point = if (i == 0) apex else InkPoint(apex.x + i * 3f, apex.y - i * 6f)
            val result = smoother.sample(point, time); time += 8
            val off = abs((result.x - apex.x) * (-6f) - (result.y - apex.y) * 3f) / length
            if (i in 1..4) worst = maxOf(worst, off)
        }
        return worst
    }
    check(apexError(1f) <= .35f) { "corner off-line ${apexError(1f)}" }
    // Straight writing is unaffected by corner handling: the same jitter still loses most of its amplitude.
    check(jitter(.35f) < .6f)
    // Tremor on short legs is not a corner.
    val tremor = PenSmoothing(1f, 1f)
    tremor.sample(InkPoint(0f, 0f), 0)
    var tremorOut = 0f
    repeat(80) { i ->
        val r = tremor.sample(InkPoint(if (i % 2 == 0) 1.2f else -1.2f, 0f), (i + 1) * 8L)
        if (i > 20) tremorOut += abs(r.x)
    }
    check(tremorOut / 59f < .8f) { "tremor survived: ${tremorOut / 59f}" }

    // Pen-down is not a corner: a wobbling first few samples are smoothed as fully as any other jitter,
    // however large the wobble, so no stray hook or blob is drawn at the start of a stroke.
    val down = PenSmoothing(1f, 1f)
    down.sample(InkPoint(0f, 0f), 0)
    var wobble = 0f
    for (i in 1..9) {
        val r = down.sample(InkPoint(i * .2f, if (i % 2 == 0) 3f else -3f), i * 4L)
        if (i >= 2) wobble = maxOf(wobble, abs(r.y))
    }
    check(wobble < 1.6f) { "pen-down wobble passed through: $wobble" }

    // Timestamp resets/gaps re-arm the filter instead of smearing across the discontinuity.
    check(filter.sample(start, 1000) === start)
    check(filter.sample(end, 1000) === end)
    check(filter.sample(start, 900) === start)
    check(PenSmoothing.strength(Float.NaN) == PenSmoothing.DEFAULT)
    check(PenSmoothing.strength(Float.POSITIVE_INFINITY) == PenSmoothing.DEFAULT)
    check(PenSmoothing.strength(-1f) == 0f && PenSmoothing.strength(2f) == 1f)
    println("Pen smoothing smoke passed: jitter, corners, endpoints, pressure, zoom, lag and resets")
}
