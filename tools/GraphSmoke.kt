package com.folio.notes

private var checks = 0
private fun check(ok: Boolean, what: String) { checks++; if (!ok) throw AssertionError("FAILED: $what") }

fun main() {
    val draft = Stroke(Tool.GRAPH, 0, 2f, listOf(InkPoint(0f, 0f), InkPoint(300f, 200f)))
    fun box(strokes: List<Stroke>): List<InkPoint> = strokes.flatMap { it.points }

    // Labels: decimals, negatives, π fractions.
    check(graphNumber(0.5) == "0.5" && graphNumber(-1.5) == "-1.5" && graphNumber(3.0) == "3" && graphNumber(0.1 * 3) == "0.3", "number labels")
    check(graphPiLabel(1, 2) == "π/2" && graphPiLabel(2, 2) == "π" && graphPiLabel(3, 2) == "3π/2" && graphPiLabel(4, 2) == "2π" && graphPiLabel(-2, 4) == "-π/2" && graphPiLabel(0, 2) == "0", "pi labels")
    check(GraphGlyphs.supports("3π/2") && GraphGlyphs.supports("-0.25") && GraphGlyphs.supports("O"), "glyphs")

    // Old nine-field preference strings still read, and every style round-trips.
    val old = GraphStyle.decode("CORNER|6|5|1|1|1|0|1|0")
    check(old.origin == GraphOrigin.CORNER && old.step == 5.0 && old.squareCells && old.stepY == 0.0, "legacy decode")
    GraphStyle.PRESETS.forEach { (name, p) -> check(GraphStyle.decode(p.encode()) == p, "round trip $name") }
    check(GraphStyle.decode("garbage|99|-3") == GraphStyle.DEFAULT, "garbage repair")

    // Every origin x every preset stays inside the dragged frame, and the default is unchanged.
    for (origin in GraphOrigin.entries) for ((name, p) in GraphStyle.PRESETS) {
        val pts = box(GraphAxes.strokes(draft, p.copy(origin = origin)))
        check(pts.isNotEmpty(), "$origin $name draws")
        check(pts.all { it.x >= -5f && it.x <= 305f && it.y >= -5f && it.y <= 205f }, "$origin $name inside frame (arrow wings and ticks may straddle an edge axis)")
    }
    check(GraphAxes.strokes(draft).size == 6, "default is two axes + four arrowheads")
    check(GraphAxes.strokes(draft, GraphStyle(origin = GraphOrigin.CORNER)).size == 4, "corner origin has two arrowheads")
    check(GraphAxes.strokes(draft, GraphStyle(origin = GraphOrigin.BOTTOM)).size == 5, "bottom origin has three arrowheads")

    // Square cells: a wide frame gets more vertical-line divisions than horizontal ones, same spacing.
    val grid = GraphStyle(divisions = 4, grid = true, arrows = false)
    val lines = GraphAxes.strokes(draft, grid).filter { it.opacity < 1f }
    val vertical = lines.filter { it.points[0].x == it.points[1].x }
    val horizontal = lines.filter { it.points[0].y == it.points[1].y }
    check(vertical.size > horizontal.size, "wide frame has more columns than rows")
    check(lines.all { it.width < draft.width }, "grid is thinner than axes")
    val xs = vertical.map { it.points[0].x }.sorted().distinct()
    check(xs.zipWithNext().all { (a, b) -> (Math.abs((b - a) - 25f) < .01f || Math.abs((b - a) - 50f) < .01f) }, "square cell spacing")
    val loose = GraphAxes.strokes(draft, grid.copy(squareCells = false)).filter { it.opacity < 1f }
    check(loose.filter { it.points[0].x == it.points[1].x }.size == loose.filter { it.points[0].y == it.points[1].y }.size, "non-square cells keep equal counts")

    // Thin frames drop labels rather than drawing outside them.
    val tiny = Stroke(Tool.GRAPH, 0, 2f, listOf(InkPoint(0f, 0f), InkPoint(40f, 30f)))
    check(box(GraphAxes.strokes(tiny, GraphStyle.PRESETS[0].second)).all { it.x in -0.01f..40.01f && it.y in -0.01f..30.01f }, "tiny frame")

    check(GraphAxes.stepLabel(GraphStyle(numbers = true, step = 0.5)) == "1 div = 0.5", "step label")
    check(GraphAxes.stepLabel(GraphStyle(numbers = true, piDen = 2)) == "1 div = π/2", "pi step label")
    println("graph smoke ok ($checks checks)")
}
