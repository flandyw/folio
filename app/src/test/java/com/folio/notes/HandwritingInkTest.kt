package com.folio.notes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class HandwritingInkTest {
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

    private fun deviationAfterRefit(points: List<InkPoint>): Pair<Float, Int> {
        val thin = StrokeRefit.simplify(points)
        val before = InkRenderer.handwritingCentreline(points)
        val after = InkRenderer.handwritingCentreline(thin)
        val worst = maxOf(before.maxOf { distance(it, after) }, after.maxOf { distance(it, before) })
        return worst to thin.size
    }

    @Test fun refitStrokesDrawTheSameAsTheOriginals() {
        // Small letters are where thinning could round a corner or flatten a hump, so test those hardest.
        val tinyLoop = (0..120).map { val a = it * 2f * PI.toFloat() / 100f; InkPoint(3f * cos(a) + 40f, 3f * sin(a) + 40f, .7f) }
        val humps = (0..160).map { InkPoint(it * .15f, -3f * kotlin.math.abs(sin(it * .12f)), .8f) }
        val cursive = (0..1200).map { InkPoint(it * .35f, 5f * sin(it * .05f) + 1.5f * sin(it * .21f), .8f + .1f * sin(it * .02f)) }
        val zigzag = (0..200).map { InkPoint(it * .4f, if ((it / 10) % 2 == 0) (it % 10) * .8f else 8f - (it % 10) * .8f, 1f) }
        for ((name, stroke) in listOf("tiny loop" to tinyLoop, "humps" to humps, "cursive" to cursive, "zigzag" to zigzag)) {
            val (worst, kept) = deviationAfterRefit(stroke)
            assertTrue("$name drifted ${worst} units", worst < .2f)
            assertTrue("$name kept $kept of ${stroke.size}", kept <= stroke.size)
        }
    }

    @Test fun tidyingHandwritingUndoesInOneStepAndKeepsEverythingElse() {
        val slope = .05f
        val letters = (0 until 14).map { i ->
            val x = 40f + i * 14f + (i % 3) * 2f
            Stroke(Tool.PEN, 0xFF000000.toInt(), 2.2f,
                listOf(InkPoint(x, 100f + slope * (x - 40f)), InkPoint(x + 3f, 110f + slope * (x - 40f), .8f), InkPoint(x + 6f, 100f + slope * (x - 40f))), 1f)
        }
        val highlight = Stroke(Tool.HIGHLIGHTER, 0x55FFEB3B, 14f, listOf(InkPoint(40f, 90f), InkPoint(240f, 96f)), .3f)
        val page = NotePage(strokes = letters + highlight)
        val result = HandwritingSelectionTidy.plan(page, CanvasSelection(strokes = letters + highlight), HandwritingTidy.Options())
        assertTrue(result.moved > 0)
        assertSame("the highlighter must not move", highlight, result.content.strokes.last())
        assertEquals(page.strokes.size, result.content.strokes.size)
        // Colours, widths and pressures are untouched.
        result.content.strokes.zip(page.strokes).forEach { (after, before) ->
            assertEquals(before.tool, after.tool); assertEquals(before.color, after.color); assertEquals(before.width, after.width, 0f)
            assertEquals(before.points.map { it.pressure }, after.points.map { it.pressure })
        }
        // The selection follows the moved strokes, so the lasso stays on the writing.
        assertEquals(result.content.strokes.filter { it.tool == Tool.PEN }, result.selection.strokes.filter { it.tool == Tool.PEN })
        val edit = requireNotNull(PageJournal.diff(page.content(), result.content))
        val inverse = PageJournal.invert(edit, page.content())
        assertEquals(page.content(), PageJournal.apply(result.content, inverse))
    }

    @Test fun lockedLayersAreNotTidied() {
        val locked = (0 until 8).map { i -> Stroke(Tool.PEN, 0xFF000000.toInt(), 2.2f,
            listOf(InkPoint(40f + i * 12f, 100f + i * 1.5f), InkPoint(44f + i * 12f, 110f + i * 1.5f)), 1f, layer = 1) }
        val page = NotePage(strokes = locked, layers = listOf(PageLayer(0, "Base"), PageLayer(1, "Locked", locked = true)))
        val result = HandwritingSelectionTidy.plan(page, CanvasSelection(strokes = locked), HandwritingTidy.Options())
        assertEquals(0, result.moved)
        assertEquals(page.content(), result.content)
    }

    @Test fun taperedEndsNarrowSmoothlyAndTheBodyIsUntouched() {
        val points = (0..200).map { InkPoint(10f + it * .8f, 40f + 3f * sin(it * .05f), .8f) }
        val stroke = Stroke(Tool.PEN, 0xFF2255FF.toInt(), 2.2f, points, 1f)
        val plain = InkRenderer.rendered(stroke).widths!!
        InkTaper.configure(.6f)
        try {
            val tapered = InkRenderer.rendered(stroke).widths!!
            val n = InkRenderer.rendered(stroke).centre.size
            val mid = tapered[n / 2]
            assertEquals("the body keeps its width", plain[n / 2], mid, 1e-4f)
            assertTrue("first point is thin: ${tapered[0]} vs $mid", tapered[0] < mid * .4f)
            assertTrue("last point is thin: ${tapered[n - 1]} vs $mid", tapered[n - 1] < mid * .4f)
            // No flat nub: from the tip inward the width never drops, and it takes several points to get there.
            val rise = (0 until 40).map { tapered[it] }
            assertTrue("start widens steadily: $rise", rise.zipWithNext().all { (a, b) -> b >= a - 1e-5f })
            assertTrue("start takes several points to widen", tapered[3] < mid * .95f)
        } finally { InkTaper.configure(0f) }
    }
}
