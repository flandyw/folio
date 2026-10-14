package com.folio.notes

import kotlin.math.*

/** Recognition and pen-down pause regressions, using the editor's actual pure rules. */
fun main() {
    fun p(x: Float, y: Float) = InkPoint(x, y, .4f)
    fun stroke(points: List<InkPoint>) = Stroke(Tool.PEN, 0x123456, 3.5f, points, .7f, layer = 2)
    fun path(vertices: List<InkPoint>, closed: Boolean = true, wobble: Float = .6f): List<InkPoint> {
        val ends = if (closed) vertices + vertices.first() else vertices
        return ends.zipWithNext().flatMapIndexed { i, (a, b) ->
            // Deliberately uneven sampling, with small hand-drawn wobble along each edge.
            val count = if (i % 2 == 0) 60 else 20
            (0 until count).map { j ->
                val t = j.toFloat() / count
                p(a.x + (b.x - a.x) * t + sin(t * PI).toFloat() * wobble,
                    a.y + (b.y - a.y) * t + sin(t * 2 * PI).toFloat() * wobble)
            }
        } + ends.last()
    }
    fun regular(n: Int, rotation: Double = -.5 * PI, star: Boolean = false) = (0 until n).map { i ->
        val r = if (star && i % 2 == 1) 48f else 110f
        p(200f + r * cos(rotation + i * 2 * PI / n).toFloat(), 200f + r * sin(rotation + i * 2 * PI / n).toFloat())
    }
    fun rotate(points: List<InkPoint>, angle: Double) = points.map {
        p((it.x * cos(angle) - it.y * sin(angle)).toFloat(), (it.x * sin(angle) + it.y * cos(angle)).toFloat())
    }
    fun tidy(points: List<InkPoint>, name: String): List<Stroke> {
        val original = stroke(points)
        val result = checkNotNull(ShapeRecognition.tidy(original)) { "Failed to recognise $name" }
        check(result.all { it.color == original.color && it.width == original.width && it.opacity == original.opacity && it.layer == 2 }) { "Lost ink style: $name" }
        check(original.points === points) { "Mutated freehand source" }
        return result
    }
    val line = path(listOf(p(10f, 10f), p(200f, 25f)), false)
    check(tidy(line, "line").single().tool == Tool.LINE)
    val box = listOf(p(10f, 10f), p(200f, 10f), p(200f, 120f), p(10f, 120f))
    check(tidy(path(box), "rectangle").single().tool == Tool.RECTANGLE)
    check(tidy(path(box, wobble = 3f), "rough rectangle").single().tool == Tool.RECTANGLE)
    val rotated = tidy(path(rotate(box, .6)), "rotated rectangle")
    check(rotated.size == 4 && rotated.all { it.tool == Tool.LINE })
    for (i in rotated.indices) {
        val a = rotated[i].points; val b = rotated[(i + 1) % 4].points
        val dot = (a[1].x - a[0].x) * (b[1].x - b[0].x) + (a[1].y - a[0].y) * (b[1].y - b[0].y)
        check(abs(dot) < .1f) { "Rectangle lost right angles" }
    }
    for (n in listOf(3, 4, 5, 6, 10)) for (angle in listOf(-.5 * PI, .27)) {
        val name = if (n == 10) "star" else "$n-gon"
        val result = tidy(path(regular(n, angle, n == 10)), name)
        check(result.size == n || n == 4 && result.single().tool == Tool.RECTANGLE) { "$name became ${result.map { it.tool }}" }
        check(tidy(path(regular(n, angle, n == 10)).reversed(), "reverse $name").size == result.size)
        check(tidy(path(regular(n, angle, n == 10), wobble = 2f), "rough $name").size == result.size)
    }
    // Starting at the middle of an edge must not invent an extra corner.
    val pentagon = path(regular(5))
    check(tidy(pentagon.drop(25) + pentagon.take(26), "mid-edge pentagon").size == 5)
    val trapezoid = listOf(p(50f, 0f), p(150f, 0f), p(200f, 100f), p(0f, 100f))
    check(tidy(path(trapezoid), "trapezoid").size == 4)
    fun oval(rx: Float, ry: Float) = (0..240).map { i ->
        val angle = i * 2 * PI / 240
        p(200f + rx * cos(angle).toFloat(), 200f + ry * sin(angle).toFloat())
    }
    for ((rx, ry) in listOf(100f to 100f, 150f to 80f, 180f to 40f)) {
        check(tidy(oval(rx, ry), "oval $rx/$ry").single().tool == Tool.ELLIPSE)
        val result = tidy(rotate(oval(rx, ry), .55), "rotated oval $rx/$ry").single()
        check(result.tool == if (rx == ry) Tool.ELLIPSE else Tool.PEN)
        check(result.points.all { it.pressure == 1f })
    }
    val arrow = listOf(p(0f, 50f), p(200f, 50f), p(150f, 20f), p(200f, 50f), p(150f, 80f))
    for (angle in listOf(0.0, .7, PI)) for (v in listOf(arrow, arrow.take(3) + arrow[4] + arrow[1])) {
        check(tidy(path(rotate(v, angle), false), "arrow").size == 3)
    }
    check(tidy(line + List(3000) { line.last() }, "held line").single().tool == Tool.LINE)
    val circle = oval(100f, 100f)
    for ((name, points) in listOf(
        "dot" to List(50) { p(0f, 0f) },
        "tiny circle" to circle.map { p(it.x / 30, it.y / 30) },
        "open V" to path(listOf(p(0f, 0f), p(100f, 100f), p(200f, 0f)), false),
        "open oval" to circle.take(180),
        "double loop" to (circle + circle),
        "crossed bow tie" to path(listOf(p(0f, 0f), p(100f, 100f), p(0f, 100f), p(100f, 0f))),
        "scribble" to path((0..6).map { p(if (it % 2 == 0) 0f else 200f, it * 8f) }, false),
        "invalid sample" to (line + p(Float.NaN, 0f))
    )) check(ShapeRecognition.tidy(stroke(points)) == null) { "Accepted $name" }
    check(ShapeRecognition.tidy(stroke(circle).copy(tool = Tool.HIGHLIGHTER)) == null)

    val defaults = ShapeAssistOptions()
    check(ShapeAssistOptions.decode(null) == defaults)
    check(ShapeAssistOptions.decode("invalid") == defaults)
    for (mask in 0..63) {
        val config = ShapeAssistOptions((mask and 1) != 0, (mask and 2) != 0, (mask and 4) != 0,
            (mask and 8) != 0, (mask and 16) != 0, (mask and 32) != 0)
        check(ShapeAssistOptions.decode(config.encode()) == config)
    }
    check(ShapeRecognition.tidy(stroke(line), defaults.copy(lines = false)) == null)
    check(ShapeRecognition.tidy(stroke(path(arrow, false)), defaults.copy(arrows = false)) == null)
    check(ShapeRecognition.tidy(stroke(path(regular(3))), defaults.copy(arrows = false)) != null)
    check(ShapeRecognition.tidy(stroke(circle), defaults.copy(closed = false)) == null)
    val highlight = stroke(line).copy(tool = Tool.HIGHLIGHTER)
    val underline = checkNotNull(ShapeRecognition.tidy(highlight)).single()
    check(underline.tool == Tool.HIGHLIGHTER && underline.opacity == highlight.opacity && underline.width == highlight.width && underline.layer == 2)
    check(ShapeRecognition.tidy(highlight, defaults.copy(highlighter = false)) == null)
    val perfect = defaults.copy(perfect = true)
    val round = checkNotNull(ShapeRecognition.tidy(stroke(oval(100f, 90f)), perfect)).single().points
    check(abs((round[1].x - round[0].x) - (round[1].y - round[0].y)) < .01f)
    val longOval = checkNotNull(ShapeRecognition.tidy(stroke(oval(150f, 80f)), perfect)).single().points
    check((longOval[1].x - longOval[0].x) > (longOval[1].y - longOval[0].y) * 1.5f)
    val almostSquare = path(listOf(p(0f, 0f), p(100f, 0f), p(100f, 90f), p(0f, 90f)))
    val square = checkNotNull(ShapeRecognition.tidy(stroke(almostSquare), perfect)).single().points
    check(abs((square[1].x - square[0].x) - (square[1].y - square[0].y)) < .01f)
    for (n in listOf(3, 5, 6, 10)) for (reverse in listOf(false, true)) {
        val points = regular(n, .27, n == 10).mapIndexed { i, v -> if (i == 1) p(v.x + 6f, v.y + 3f) else v }
        val raw = path(points).let { if (reverse) it.reversed() else it }
        val fitted = checkNotNull(ShapeRecognition.tidy(stroke(raw), defaults.copy(regular = true)))
        val lengths = fitted.map { hypot(it.points[1].x - it.points[0].x, it.points[1].y - it.points[0].y) }
        check(lengths.max() - lengths.min() < .01f) { "Regular $n-gon lost equal sides" }
    }
    val dot = stroke(List(20) { p(20f, 20f) })
    val source = listOf(dot, stroke(line), dot.copy(color = 99))
    val cleaned = ShapeCleanup.strokes(source, defaults)
    check(cleaned.changed == 1 && cleaned.strokes.size == 3)
    check(cleaned.strokes[0] === source[0] && cleaned.strokes[2] === source[2])
    check(ShapeCleanup.strokes(cleaned.strokes, defaults).changed == 0)
    check(ShapeCleanup.strokes(listOf(dot), defaults).strokes.single() === dot)

    val hold = ShapeHold()
    check(hold.remaining(0, 650) == null)
    check(hold.sample(0f, 0f, 100, 8f))
    check(!hold.attempt(749, 650))
    check(!hold.sample(3f, 2f, 740, 8f)) // Stationary sample traffic must not postpone the hold.
    check(hold.attempt(750, 650)) // Fires even if no new MOVE is delivered.
    check(!hold.attempt(800, 650))
    check(hold.sample(20f, 0f, 900, 8f)) // Deliberate movement: new pause, no early conversion.
    check(!hold.attempt(1549, 650))
    check(hold.attempt(1550, 650))
    hold.reset() // UP, CANCEL, navigation, detach, tool change and disabling recognition.
    check(!hold.attempt(5000, 650))
    check(hold.sample(0f, 0f, 6000, 8f))
    hold.reset() // Quick lift never tidies.
    check(!hold.attempt(7000, 650))
    hold.reset()
    hold.sample(0f, 0f, 100, ShapeHold.SLOP_DP)
    for (time in 110L..740L step 10) {
        check(!hold.sample(if (time % 20L == 0L) 10f else -10f, 3f, time, ShapeHold.SLOP_DP))
    }
    check(hold.attempt(750, 650)) // Normal hand tremor never restarts the timer.
    hold.reset()
    hold.sample(0f, 0f, 100, 8f)
    check(!hold.sample(11f, 0f, 740, 8f))
    check(!hold.attempt(750, 650)) // Wait out a brief excursion near the hold deadline.
    check(!hold.sample(2f, 0f, 770, 8f))
    check(hold.attempt(770, 650)) // Returning inside the radius keeps the original timer.
    hold.reset()
    hold.sample(0f, 0f, 100, 8f)
    hold.sample(11f, 0f, 730, 8f)
    check(!hold.attempt(810, 650)) // No new MOVE: sustained displacement still restarts.
    check(hold.remaining(810, 650) == 570L)
    check(hold.attempt(1380, 650))
    hold.reset()
    hold.sample(0f, 0f, 100, 8f)
    check(!hold.sample(10f, 0f, 200, 8f))
    check(hold.sample(12f, 0f, 280, 8f))
    check(hold.remaining(280, 650) == 570L)

    val shakingCircle = stroke(circle + List(100) { i -> p(300f + if (i % 2 == 0) 8f else -8f, 200f + if (i % 2 == 0) 3f else -3f) })
    val settledCircle = ShapeHold.drawing(shakingCircle, circle.size - 4)
    check(checkNotNull(ShapeRecognition.tidy(settledCircle)).single().tool == Tool.ELLIPSE)
    check(shakingCircle.points.size == circle.size + 100)
    val smallCircle = oval(30f, 30f)
    val shakingSmallCircle = stroke(smallCircle + List(100) { i -> p(230f + if (i % 2 == 0) 6f else -6f, 200f + if (i % 2 == 0) 2f else -2f) })
    check(checkNotNull(ShapeRecognition.tidy(ShapeHold.drawing(shakingSmallCircle, smallCircle.size - 18))).single().tool == Tool.ELLIPSE)
    val shakingLine = stroke(line + List(100) { i -> p(200f + if (i % 2 == 0) 7f else -7f, 25f) })
    check(checkNotNull(ShapeRecognition.tidy(ShapeHold.drawing(shakingLine, line.size))).single().tool == Tool.LINE)
    check(ShapeHold.drawing(stroke(line), line.size).points === line)

    val straight = tidy(line, "adjustment line").single()
    val contact = p(205f, 30f) // Grab offset from the fitted endpoint must never jump.
    val adjustment = ShapeAdjustment(listOf(straight), contact)
    val initialFit = adjustment.update(contact, 5f)
    check(adjustment.update(p(207f, 31f), 5f) === initialFit)
    val extended = adjustment.update(p(260f, 30f), 5f).single()
    check(extended.points.first() == straight.points.first())
    check(abs(extended.points.last().x - straight.points.last().x - 50f) < .01f)
    check(extended.width == straight.width && extended.layer == straight.layer && extended.opacity == straight.opacity)
    check(adjustment.update(contact, 5f).single() === straight)
    val justOutside = adjustment.update(p(210.001f, 30f), 5f).single()
    check(abs(justOutside.points.last().x - straight.points.last().x) < .002f)
    val doubled = straight.copy(points = straight.points.map { it.copy(x = it.x * 2, y = it.y * 2) })
    val zoomed = ShapeAdjustment(listOf(doubled), p(contact.x * 2, contact.y * 2)).update(p(520f, 60f), 10f).single()
    check(abs(zoomed.points.last().x / 2 - extended.points.last().x) < .01f)
    check(abs(zoomed.points.last().y / 2 - extended.points.last().y) < .01f)
    val exactBox = Stroke(Tool.RECTANGLE, 12, 3f, listOf(InkPoint(0f, 0f), InkPoint(100f, 60f)))
    val boxAdjustment = ShapeAdjustment(listOf(exactBox), InkPoint(100f, 60f))
    val enlarged = boxAdjustment.update(InkPoint(150f, 90f), 0f).single()
    check(enlarged.tool == Tool.RECTANGLE)
    check(enlarged.points == listOf(InkPoint(-50f, -30f), InkPoint(150f, 90f)))
    val turned = boxAdjustment.update(InkPoint(20f, 80f), 0f)
    check(turned.size == 4 && turned.all { it.tool == Tool.LINE })
    for (i in turned.indices) {
        val a = turned[i].points; val b = turned[(i + 1) % 4].points
        val dot = (a[1].x - a[0].x) * (b[1].x - b[0].x) + (a[1].y - a[0].y) * (b[1].y - b[0].y)
        check(abs(dot) < .01f)
    }
    val circleStroke = Stroke(Tool.ELLIPSE, 12, 3f, listOf(InkPoint(0f, 0f), InkPoint(100f, 100f)))
    val circleAdjustment = ShapeAdjustment(listOf(circleStroke), InkPoint(100f, 50f))
    val biggerCircle = circleAdjustment.update(InkPoint(50f, 150f), 0f).single()
    check(biggerCircle.tool == Tool.ELLIPSE && biggerCircle.points == listOf(InkPoint(-50f, -50f), InkPoint(150f, 150f)))
    val ovalStroke = circleStroke.copy(points = listOf(InkPoint(0f, 0f), InkPoint(160f, 100f)))
    val ovalAdjustment = ShapeAdjustment(listOf(ovalStroke), InkPoint(160f, 50f))
    val turnedOval = ovalAdjustment.update(InkPoint(80f, 130f), 0f).single()
    check(turnedOval.tool == Tool.PEN && turnedOval.points.size == 129)
    check(turnedOval.points.all { it.pressure == 1f })
    val fittedArrow = tidy(path(arrow, false), "adjustable arrow")
    val adjustedArrow = ShapeAdjustment(fittedArrow, arrow.last()).update(p(250f, 80f), 0f)
    check(adjustedArrow.first().points.first() == fittedArrow.first().points.first())
    check(adjustedArrow[1].points.first() == adjustedArrow[0].points.last())
    check(adjustedArrow[2].points.first() == adjustedArrow[0].points.last())
    // Thousands of updates must equal a fresh update at the same tip, with no accumulated drift.
    repeat(1000) { adjustment.update(p(210f + it % 30, 40f + it % 10), 5f) }
    check(adjustment.update(p(260f, 30f), 5f) == extended.let { listOf(it) })
    check(straight.points == tidy(line, "unchanged original").single().points)
    check(adjustment.update(p(Float.NaN, 0f), 5f).single() === straight)
    val highlightAdjustment = ShapeAdjustment(listOf(underline), underline.points.last())
    check(highlightAdjustment.update(InkPoint(300f, 80f), 0f).single().tool == Tool.HIGHLIGHTER)

    println("Shape tidy checks passed: hold timing, cancellation, speed, rotated geometry, polygons, stars, arrows, rejected writing, option round trips, exact geometry, highlighter, ordered cleanup, forgiving holds and live adjustment")
}
