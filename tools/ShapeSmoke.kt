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

    val hold = ShapeHold()
    check(hold.remaining(0, 650) == null)
    check(hold.sample(0f, 0f, 100, 8f))
    check(!hold.attempt(749, 650))
    check(!hold.sample(3f, 2f, 740, 8f)) // Stationary sample traffic must not postpone the hold.
    check(hold.attempt(750, 650)) // Fires even if no new MOVE is delivered.
    check(!hold.attempt(800, 650))
    check(hold.sample(12f, 0f, 900, 8f)) // Continue drawing: new pause, no early conversion.
    check(!hold.attempt(1549, 650))
    check(hold.attempt(1550, 650))
    hold.reset() // UP, CANCEL, navigation, detach, tool change and disabling recognition.
    check(!hold.attempt(5000, 650))
    check(hold.sample(0f, 0f, 6000, 8f))
    hold.reset() // Quick lift never tidies.
    check(!hold.attempt(7000, 650))
    println("Shape tidy checks passed: hold timing, cancellation, speed, rotated geometry, polygons, stars, arrows and rejected writing")
}
