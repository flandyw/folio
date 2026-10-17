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
    for (mask in 0..127) {
        val config = ShapeAssistOptions((mask and 1) != 0, (mask and 2) != 0, (mask and 4) != 0,
            (mask and 8) != 0, (mask and 16) != 0, (mask and 32) != 0, (mask and 64) != 0)
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

    // Ink as people really draw it: overshooting the start, hooks, rounded corners.
    fun arc(cx: Float, cy: Float, r: Float, from: Double, to: Double, n: Int = 160) =
        (0..n).map { val t = from + (to - from) * it / n; p(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat()) }
    check(tidy(arc(200f, 200f, 90f, 0.0, 2 * PI + 1.1), "overshot circle").single().tool == Tool.ELLIPSE)
    check(tidy(arc(200f, 200f, 90f, 0.0, 2 * PI - .4), "unclosed circle").single().tool == Tool.ELLIPSE)
    check(tidy(path(box + box[0] + p(box[0].x + 40f, box[0].y), false), "overshot box").single().tool == Tool.RECTANGLE)
    check(tidy(path(listOf(p(10f, 10f), p(220f, 20f), p(212f, 32f)), false), "hooked line").single().tool == Tool.LINE)
    check(tidy(path(listOf(p(16f, 2f), p(10f, 10f), p(220f, 20f)), false), "line with a landing hook").single().tool == Tool.LINE)
    val rounded = listOf(0.0, .5 * PI, PI, 1.5 * PI).zip(listOf(p(290f, 210f), p(110f, 210f), p(110f, 110f), p(290f, 110f))).flatMap { (angle, c) ->
        arc(c.x, c.y, 10f, angle, angle + .5 * PI, 8) }
    check(tidy(rounded + rounded.first(), "rounded box").single().tool == Tool.RECTANGLE)

    // Lasso cleanup joins shapes drawn in several strokes, and leaves handwriting alone.
    fun clean(vararg parts: List<InkPoint>) = ShapeCleanup.strokes(parts.map { stroke(it) }, defaults)
    val corners = listOf(p(100f, 100f), p(300f, 100f), p(300f, 220f), p(100f, 220f))
    val fourLines = clean(*corners.indices.map { path(listOf(corners[it], corners[(it + 1) % 4]), false) }.toTypedArray())
    check(fourLines.changed == 4 && fourLines.shapes == 1 && fourLines.strokes.single().tool == Tool.RECTANGLE) { "Box of lines: ${fourLines.strokes.map { it.tool }}" }
    check(fourLines.strokes.single().layer == 2 && fourLines.strokes.single().opacity == .7f)
    check(ShapeCleanup.strokes(fourLines.strokes, defaults).changed == 0)
    val crossing = clean(path(listOf(p(88f, 100f), p(312f, 100f)), false), path(listOf(p(300f, 88f), p(300f, 232f)), false),
        path(listOf(p(314f, 220f), p(90f, 220f)), false), path(listOf(p(100f, 234f), p(100f, 92f)), false))
    check(crossing.strokes.single().tool == Tool.RECTANGLE) { "Overrunning box: ${crossing.strokes.map { it.tool }}" }
    val stoppedShort = clean(path(listOf(p(104f, 300f), p(196f, 124f)), false), path(listOf(p(204f, 126f), p(296f, 296f)), false),
        path(listOf(p(292f, 302f), p(108f, 301f)), false))
    check(stoppedShort.shapes == 1 && stoppedShort.strokes.size == 3 && stoppedShort.strokes.last().points.last() == stoppedShort.strokes.first().points.first())
    check(clean(arc(200f, 200f, 80f, 0.0, PI), arc(200f, 200f, 80f, 0.0, -PI)).strokes.single().tool == Tool.ELLIPSE)
    check(clean(path(listOf(corners[0], corners[1], corners[2]), false), path(listOf(corners[0], corners[3], corners[2]), false)).strokes.single().tool == Tool.RECTANGLE)
    val shaft = path(listOf(p(0f, 50f), p(200f, 50f)), false)
    val vHead = clean(shaft, path(listOf(p(176f, 32f), p(201f, 51f), p(175f, 69f)), false))
    check(vHead.changed == 2 && vHead.strokes.size == 3 && vHead.strokes.all { it.tool == Tool.LINE }) { "V head: ${vHead.strokes.map { it.tool }}" }
    val wingHead = clean(shaft, path(listOf(p(176f, 32f), p(201f, 51f)), false), path(listOf(p(201f, 49f), p(175f, 69f)), false))
    check(wingHead.changed == 3 && wingHead.strokes.size == 3) { "Wing head: ${wingHead.strokes.map { it.tool }}" }
    check(ShapeCleanup.strokes(wingHead.strokes, defaults).changed == 0)
    check(clean(shaft, path(listOf(p(176f, 32f), p(201f, 51f), p(175f, 69f)), false)).let { ShapeCleanup.strokes(it.strokes, defaults.copy(arrows = false)).changed } == 0)
    check(ShapeCleanup.strokes(listOf(stroke(shaft), stroke(path(listOf(p(176f, 32f), p(201f, 51f), p(175f, 69f)), false))), defaults.copy(arrows = false)).strokes.size == 2)
    val word = clean(*(0..2).map { k -> arc(k * 22f, 0f, 9f, 0.0, 2 * PI) }.toTypedArray())
    check(word.changed == 0) { "Tidied handwriting" }
    check(clean(arc(0f, 0f, 15f, 0.0, 2 * PI), path(listOf(p(-2f, -6f), p(-2f, 6f)), false)).strokes.first().tool == Tool.ELLIPSE) { "Labelled node stayed freehand" }
    val rotatedOval = tidy(rotate(oval(150f, 80f), .55), "rotated oval")
    check(ShapeCleanup.strokes(rotatedOval, defaults).changed == 0) { "Tidied ink counted again" }

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
    fun checkFixedHead(shapes: List<Stroke>) {
        val originalTip = shapes.first().points.last()
        val contact = originalTip
        val adjustment = ShapeAdjustment(shapes, contact)
        val start = shapes.first().points.first()
        val dx = contact.x - start.x; val dy = contact.y - start.y
        for ((factor, angle) in listOf(.3f to .0f, 3f to .0f, 1.8f to .9f, .5f to -1.3f)) {
            val target = p(start.x + factor * (dx * cos(angle) - dy * sin(angle)),
                start.y + factor * (dx * sin(angle) + dy * cos(angle)))
            val updated = adjustment.update(target, 0f)
            val tip = updated.first().points.last()
            check(ShapeMath.distance(updated.first().points.first(), start) < .001f)
            check(ShapeMath.distance(tip, target) < .001f)
            for (i in 1..2) {
                check(ShapeMath.distance(updated[i].points.first(), tip) < .001f)
                check(abs(ShapeMath.length(updated[i].points) - ShapeMath.length(shapes[i].points)) < .001f) { "Arrowhead scaled with shaft" }
                check(updated[i].width == shapes[i].width && updated[i].color == shapes[i].color && updated[i].layer == shapes[i].layer)
            }
            check(abs(ShapeMath.distance(updated[1].points.last(), updated[2].points.last()) -
                ShapeMath.distance(shapes[1].points.last(), shapes[2].points.last())) < .001f)
            check(adjustment.update(target, 0f) == ShapeAdjustment(shapes, contact).update(target, 0f))
        }
    }
    checkFixedHead(fittedArrow)
    // The same small drawn head is accepted on both short and long shafts.
    for (length in listOf(100f, 400f, 1000f)) {
        val tip = p(length, 0f)
        val fitted = checkNotNull(ShapeRecognition.arrow(stroke(emptyList()), p(0f, 0f), tip,
            p(length - 25f, -15f), p(length - 25f, 15f))) { "Small head rejected on $length shaft" }
        check(abs(ShapeMath.length(fitted[1].points) - hypot(25f, 15f)) < .001f)
        checkFixedHead(fitted)
        check(ShapeRecognition.recognise(stroke(path(listOf(p(0f, 0f), tip, p(length - 25f, -15f), tip,
            p(length - 25f, 15f)), false)))?.kind == ShapeKind.ARROW) { "Arrow with a small head was not recognised at length $length" }
    }
    // Thousands of updates must equal a fresh update at the same tip, with no accumulated drift.
    repeat(1000) { adjustment.update(p(210f + it % 30, 40f + it % 10), 5f) }
    check(adjustment.update(p(260f, 30f), 5f) == extended.let { listOf(it) })
    check(straight.points == tidy(line, "unchanged original").single().points)
    check(adjustment.update(p(Float.NaN, 0f), 5f).single() === straight)
    val highlightAdjustment = ShapeAdjustment(listOf(underline), underline.points.last())
    check(highlightAdjustment.update(InkPoint(300f, 80f), 0f).single().tool == Tool.HIGHLIGHTER)

    // More shapes. They are fitted, not corner-counted, so wobble and spare corners do not matter.
    fun kindOf(points: List<InkPoint>, name: String, options: ShapeAssistOptions = ShapeAssistOptions()): Tidied {
        val original = stroke(points)
        val result = checkNotNull(ShapeRecognition.recognise(original, options)) { "Failed to recognise $name" }
        check(result.strokes.all { it.color == original.color && it.width == original.width && it.opacity == original.opacity && it.layer == 2 }) { "Lost ink style: $name" }
        check(result.strokes.all { s -> s.points.all { it.x.isFinite() && it.y.isFinite() } }) { "Non-finite $name" }
        return result
    }
    for (n in listOf(7, 8)) for (angle in listOf(-.5 * PI, .27)) for (reverse in listOf(false, true)) {
        val ring = path(regular(n, angle), wobble = 1f).let { if (reverse) it.reversed() else it }
        val result = kindOf(ring, "$n-gon")
        check(result.kind == ShapeKind.POLYGON && result.sides == n && result.strokes.size == n) { "$n-gon became ${result.kind}/${result.sides}" }
    }
    for (corners in listOf(8, 12)) for (angle in listOf(-.5 * PI, .27)) {
        val result = kindOf(path(regular(corners, angle, star = true)), "${corners / 2}-point star")
        check(result.kind == ShapeKind.STAR && result.sides == corners) { "${corners / 2}-point star became ${result.kind}/${result.sides}" }
    }
    val points5 = regular(5, .3)
    val pentagram = kindOf(path(listOf(points5[0], points5[2], points5[4], points5[1], points5[3])), "pentagram")
    check(pentagram.kind == ShapeKind.PENTAGRAM && pentagram.strokes.size == 5)
    check(kindOf(path(listOf(points5[0], points5[2], points5[4], points5[1], points5[3])).reversed(), "reversed pentagram").kind == ShapeKind.PENTAGRAM)
    val regularPentagram = kindOf(path(listOf(points5[0], points5[2], points5[4], points5[1], points5[3]).mapIndexed { i, v -> if (i == 2) p(v.x + 7f, v.y - 4f) else v }),
        "uneven pentagram", defaults.copy(regular = true))
    val sides = regularPentagram.strokes.map { hypot(it.points[1].x - it.points[0].x, it.points[1].y - it.points[0].y) }
    check(sides.max() - sides.min() < .01f) { "Regular pentagram lost equal sides" }
    // A pentagon is not a pentagram, and a cloud-like scalloped loop is not a star.
    check(kindOf(path(regular(5, .3)), "pentagon").kind == ShapeKind.POLYGON)
    check(ShapeRecognition.recognise(stroke((0..240).map { val t = it * 2 * PI / 240; val k = 1 + .25 * sin(6 * t); p(200f + 120f * k.toFloat() * cos(t).toFloat(), 200f + 90f * k.toFloat() * sin(t).toFloat()) })) == null) { "Cloud became a shape" }

    fun heart(width: Float, tilt: Double = 0.0) = rotate((0..200).map {
        val t = it * 2 * PI / 200
        p(width * (16 * sin(t).pow(3)).toFloat() / 32f, -width * (13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)).toFloat() / 32f)
    }, tilt).map { p(it.x + 200f, it.y + 200f) }
    for (tilt in listOf(0.0, .3, -.3)) {
        check(kindOf(heart(220f, tilt), "heart").kind == ShapeKind.HEART)
        check(kindOf(heart(220f, tilt).reversed(), "reversed heart").kind == ShapeKind.HEART)
    }
    val hearts = kindOf(heart(220f), "heart").strokes.single()
    check(hearts.tool == Tool.PEN && hearts.points.first() == hearts.points.last() && hearts.points.all { it.pressure == 1f })

    fun arcOf(radius: Float, degrees: Double, turn: Double = 0.0) = rotate((0..120).map {
        val a = (-degrees / 2 + degrees * it / 120) * PI / 180
        p(radius * cos(a).toFloat(), radius * sin(a).toFloat())
    }, turn).map { p(it.x + 300f, it.y + 300f) }
    for (turn in listOf(0.0, 1.0, 3.5)) for (degrees in listOf(70.0, 120.0, 190.0)) {
        check(kindOf(arcOf(150f, degrees, turn), "$degrees° arc").kind == ShapeKind.ARC)
    }
    check(ShapeRecognition.recognise(stroke(arcOf(150f, 280.0))) == null) { "A nearly closed C became an arc" }
    check(ShapeRecognition.recognise(stroke(arcOf(14f, 120.0))) == null) { "A small bracket-like mark is handwriting" }
    val bracket = listOf(p(120f, 0f), p(0f, 0f), p(0f, 260f), p(120f, 260f))
    for (turn in listOf(0.0, PI / 2, 2.0)) check(kindOf(path(rotate(bracket, turn), false), "bracket").kind == ShapeKind.BRACKET)
    fun brace(height: Float, depth: Float): List<InkPoint> {
        val r = depth / 2; val out = ArrayList<InkPoint>()
        fun corner(cx: Float, cy: Float, from: Double, to: Double) { for (k in 0..10) { val a = (from + (to - from) * k / 10) * PI / 180; out += p(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()) } }
        corner(depth, r, -90.0, -180.0); corner(0f, height / 2 - r, 0.0, 90.0); corner(0f, height / 2 + r, -90.0, 0.0); corner(depth, height - r, 180.0, 90.0)
        return out
    }
    for (turn in listOf(0.0, PI / 2, 4.0)) check(kindOf(rotate(brace(300f, 70f), turn), "brace").kind == ShapeKind.BRACE)
    check(ShapeRecognition.recognise(stroke(path(listOf(p(0f, 0f), p(0f, 120f), p(90f, 120f)), false))) == null) { "An L became a bracket" }

    fun rounded(width: Float, height: Float, radius: Float, turn: Double = 0.0): List<InkPoint> {
        val out = ArrayList<InkPoint>()
        fun corner(cx: Float, cy: Float, from: Double) { for (k in 0..10) { val a = (from + 90.0 * k / 10) * PI / 180; out += p(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat()) } }
        corner(width - radius, radius, -90.0); corner(width - radius, height - radius, 0.0); corner(radius, height - radius, 90.0); corner(radius, radius, 180.0)
        return path(rotate(out, turn).map { p(it.x + 100f, it.y + 100f) })
    }
    check(kindOf(rounded(260f, 140f, 50f), "rounded box").kind == ShapeKind.ROUNDED)
    check(kindOf(rounded(300f, 110f, 55f, .4), "pill").kind == ShapeKind.ROUNDED)
    // A slightly rounded corner is still the editable rectangle, and a circle is still a circle.
    check(kindOf(rounded(260f, 140f, 12f), "soft box").kind == ShapeKind.RECTANGLE)
    check(kindOf(oval(100f, 100f), "circle").kind == ShapeKind.ELLIPSE)
    val pill = kindOf(rounded(300f, 110f, 55f), "pill").strokes.single()
    check(pill.points.first() == pill.points.last() && pill.points.size in 40..200)

    // A curved shaft with a head is one arrow: a true arc and two wings that follow its end.
    fun curvedArrow(radius: Float, turn: Double = 0.0): List<InkPoint> {
        val shaft = (0..90).map { val a = (-35 + 70.0 * it / 90) * PI / 180; p(radius * cos(a).toFloat(), radius * sin(a).toFloat()) }
        val tip = shaft.last(); val before = shaft[shaft.size - 6]
        val length = hypot(tip.x - before.x, tip.y - before.y); val ux = (tip.x - before.x) / length; val uy = (tip.y - before.y) / length
        fun wing(sign: Float) = p(tip.x - 38f * (ux * .85f - sign * uy * .5f), tip.y - 38f * (uy * .85f + sign * ux * .5f))
        return rotate(shaft + path(listOf(tip, wing(1f), tip, wing(-1f)), false), turn).map { p(it.x + 300f, it.y + 300f) }
    }
    for (turn in listOf(0.0, 1.7)) {
        val arrowOfArc = kindOf(curvedArrow(260f, turn), "curved arrow")
        check(arrowOfArc.kind == ShapeKind.ARROW && arrowOfArc.strokes.size == 3 && arrowOfArc.strokes[0].tool == Tool.PEN && arrowOfArc.strokes.drop(1).all { it.tool == Tool.LINE })
        check(kindOf(curvedArrow(260f, turn).reversed(), "curved arrow drawn from the head").kind == ShapeKind.ARROW)
        checkFixedHead(arrowOfArc.strokes)
    }
    // Straight arrows keep three straight strokes.
    check(kindOf(path(arrow, false), "straight arrow").strokes.all { it.tool == Tool.LINE })

    // Each family switches off independently; the new shapes belong to the family they look like.
    check(ShapeRecognition.recognise(stroke(arcOf(150f, 120.0)), defaults.copy(curves = false)) == null)
    check(ShapeRecognition.recognise(stroke(arcOf(150f, 120.0)), defaults.copy(closed = false, lines = false, arrows = false)) != null)
    check(ShapeRecognition.recognise(stroke(heart(220f)), defaults.copy(closed = false)) == null)
    check(ShapeRecognition.recognise(stroke(heart(220f)), defaults.copy(curves = false)) != null)
    check(ShapeRecognition.recognise(stroke(curvedArrow(260f)), defaults.copy(arrows = false)) == null)
    check(ShapeRecognition.recognise(stroke(rounded(300f, 110f, 55f)), defaults.copy(closed = false)) == null)
    check(ShapeAssistOptions.decode("1,1,1,0,0,1").curves) // preferences saved before curves existed keep it on

    // Lasso cleanup handles them too, and tidied ink is left alone the second time.
    for ((name, points) in listOf("heart" to heart(220f), "arc" to arcOf(150f, 120.0), "brace" to brace(300f, 70f),
        "bracket" to path(bracket, false), "pill" to rounded(300f, 110f, 55f), "curved arrow" to curvedArrow(260f), "pentagram" to path(listOf(points5[0], points5[2], points5[4], points5[1], points5[3])))) {
        val once = ShapeCleanup.strokes(listOf(stroke(points)), defaults)
        check(once.changed == 1 && once.shapes == 1) { "Cleanup missed $name" }
        check(ShapeCleanup.strokes(once.strokes, defaults).changed == 0) { "Cleanup of tidied $name changed it again" }
    }
    val wholeHeart = heart(220f)
    val halves = ShapeCleanup.strokes(listOf(stroke(wholeHeart.subList(0, 101)), stroke(wholeHeart.subList(100, wholeHeart.size))), defaults)
    check(halves.shapes == 1 && halves.strokes.single().points.size > 50) { "Heart in two halves: ${halves.strokes.map { it.tool }}" }
    check(ShapeCleanup.strokes(listOf(stroke(arcOf(150f, 120.0))), defaults.copy(curves = false)).changed == 0)

    // A tidied curve, heart or rounded box still resizes and turns about its centre, with finite points.
    for (shape in listOf(kindOf(heart(220f), "heart").strokes, kindOf(arcOf(150f, 120.0), "arc").strokes,
        kindOf(rounded(300f, 110f, 55f), "pill").strokes, kindOf(curvedArrow(260f), "curved arrow").strokes)) {
        val contact = shape.flatMap { it.points }.maxBy { it.x }
        val adjusted = ShapeAdjustment(shape, contact).update(p(contact.x + 80f, contact.y + 50f), 5f)
        check(adjusted.size == shape.size && adjusted.all { s -> s.points.all { it.x.isFinite() && it.y.isFinite() } })
        check(adjusted.flatMap { it.points } != shape.flatMap { it.points })
    }

    // Google's labels select a family; fitting still rejects incompatible paths and preserves style.
    for ((label, points) in listOf("RECTANGLE" to path(box), "TRIANGLE" to path(regular(3)),
        "ELLIPSE" to oval(150f, 80f), "ARROW" to path(arrow, false))) {
        val original = stroke(points)
        val fitted = checkNotNull(ShapeRecognition.fitClassified(original, label, defaults)) { "Cannot fit ML $label" }
        check(ShapeRecognition.modelLabel(fitted) == label)
        check(fitted.strokes.all { it.color == original.color && it.width == original.width && it.opacity == original.opacity && it.layer == original.layer })
        check(ShapeRecognition.fitClassified(original, "writing", defaults) == null)
    }
    check(ShapeRecognition.fitClassified(stroke(line), "RECTANGLE", defaults) == null)
    check(ShapeRecognition.fitClassified(stroke(path(box)), "ELLIPSE", defaults) == null)
    check(ShapeRecognition.fitClassified(stroke(oval(100f, 100f)), "TRIANGLE", defaults) == null)
    check(ShapeRecognition.fitClassified(stroke(path(box)), "RECTANGLE", defaults.copy(closed = false)) == null)
    check(ShapeRecognition.fitClassified(stroke(path(arrow, false)), "ARROW", defaults.copy(arrows = false)) == null)
    check(ShapeRecognition.fitClassified(stroke(listOf(p(Float.NaN, 0f))), "RECTANGLE", defaults) == null)

    // Irregular maths triangles keep their three drawn corners, including shallow obtuse turns
    // and a short side that the general polygon heuristics used to discard.
    val irregularTriangles = listOf(
        "scalene" to listOf(p(10f, 20f), p(240f, 40f), p(65f, 155f)),
        "right" to listOf(p(0f, 0f), p(250f, 0f), p(0f, 95f)),
        "obtuse" to listOf(p(0f, 0f), p(280f, 0f), p(90f, 15f)),
        "short-side" to listOf(p(0f, 0f), p(300f, 0f), p(5f, 18f)),
    )
    for ((name, vertices) in irregularTriangles) for (angle in listOf(0.0, .7)) for (reverse in listOf(false, true)) {
        val corners = rotate(vertices, angle)
        val points = path(corners).let { if (reverse) it.reversed() else it }
        val original = stroke(points)
        val fitted = checkNotNull(ShapeRecognition.fitClassified(original, "TRIANGLE", defaults)) { "ML missed irregular $name triangle" }
        check(fitted.sides == 3 && fitted.strokes.size == 3)
        val actual = fitted.strokes.map { it.points.first() }
        check(corners.all { corner -> actual.minOf { ShapeMath.distance(corner, it) } < max(4f, ShapeMath.span(corners) * .035f) }) { "Changed irregular $name proportions: $actual" }
        val tidied = checkNotNull(ShapeRecognition.recognise(original, defaults)) { "Cleanup missed irregular $name triangle" }
        check(ShapeRecognition.modelLabel(tidied) == "TRIANGLE") { "Irregular $name became ${tidied.kind}" }
        check(fitted.strokes.all { it.color == original.color && it.width == original.width && it.layer == original.layer })
    }
    for (vertices in listOf(listOf(p(0f, 0f), p(300f, 0f), p(120f, 2f)),
        listOf(p(0f, 0f), p(150f, 0f), p(300f, 0f)))) {
        check(ShapeRecognition.fitClassified(stroke(path(vertices, wobble = 0f)), "TRIANGLE", defaults) == null) { "Flat retracing became a triangle" }
    }

    println("Shape tidy checks passed: hold timing, cancellation, speed, rotated geometry, polygons, stars, hearts, arcs, brackets, braces, rounded boxes, straight and curved arrows, rejected writing, option round trips, exact geometry, highlighter, ordered cleanup, forgiving holds and live adjustment")
}
