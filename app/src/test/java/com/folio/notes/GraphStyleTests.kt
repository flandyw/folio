package com.folio.notes

import org.junit.Assert.*
import kotlin.math.abs
import org.junit.Test

class GraphStyleTests {
    private fun draft(a: InkPoint = InkPoint(20f, 40f), b: InkPoint = InkPoint(420f, 340f)) =
        Stroke(Tool.GRAPH, 0xFF303431.toInt(), 2f, listOf(a, b), .7f, StrokeStyle.SOLID)

    private fun box(strokes: List<Stroke>) = strokes.flatMap { it.points }
    private fun inside(frame: GraphFrame, strokes: List<Stroke>) =
        box(strokes).all { it.x >= frame.left - 0.01f && it.x <= frame.right + 0.01f && it.y >= frame.top - 0.01f && it.y <= frame.bottom + 0.01f }

    @Test fun styleRoundTripsThroughItsPreferenceString() {
        val style = GraphStyle(GraphOrigin.CORNER, divisions = 6, step = 5, grid = true, ticks = true,
            numbers = true, letters = true, arrows = false, square = true)
        assertEquals(style, GraphStyle.decode(style.encode()))
        assertEquals(GraphStyle.DEFAULT, GraphStyle.decode(null))
        assertEquals(GraphStyle.DEFAULT, GraphStyle.decode(""))
    }

    @Test fun aDamagedPreferenceFallsBackToTheDefaults() {
        val partial = GraphStyle.decode("CORNER|3")
        assertEquals(GraphOrigin.CORNER, partial.origin)
        assertEquals(3, partial.divisions)
        assertEquals(GraphStyle.DEFAULT.step, partial.step)
        assertEquals(GraphStyle.DEFAULT.grid, partial.grid)
        assertEquals(GraphStyle.DEFAULT.square, partial.square)
        val nonsense = GraphStyle.decode("WOBBLE|-4|0|x|y|z|w|v|u")
        assertEquals(GraphStyle.DEFAULT.origin, nonsense.origin)
        assertEquals(GraphStyle.DEFAULT.divisions, nonsense.divisions)
        assertEquals(GraphStyle.DEFAULT.step, nonsense.step)
        assertTrue(nonsense.arrows)
        assertFalse(nonsense.square)
    }

    @Test fun cornerOriginCrossesAtTheBottomLeftWithTwoArrowheads() {
        val style = GraphStyle(origin = GraphOrigin.CORNER)
        val axes = GraphAxes.strokes(draft(), style)
        // Two axes and the two free ends, in the same order as the centred default.
        assertEquals(4, axes.size)
        assertEquals(listOf(InkPoint(20f, 340f), InkPoint(420f, 340f)), axes[0].points)
        assertEquals(listOf(InkPoint(20f, 340f), InkPoint(20f, 40f)), axes[1].points)
        assertEquals(InkPoint(420f, 340f), axes[2].points[1])
        assertEquals(InkPoint(20f, 40f), axes[3].points[1])
    }

    @Test fun divisionsPlaceTicksBothWaysFromACentredOriginAndOnlyForwardsFromACorner() {
        val centred = GraphStyle(divisions = 4, ticks = true)
        val corner = centred.copy(origin = GraphOrigin.CORNER)
        // Four divisions to each side of the centred origin: 8 ticks per axis, 6 frame strokes.
        assertEquals(6 + 16, GraphAxes.strokes(draft(), centred).size)
        // A corner origin only has the positive way to tick: half as many.
        // The corner frame is four strokes: two axes and two free-end arrowheads.
        assertEquals(4 + 8, GraphAxes.strokes(draft(), corner).size)
        // The first x tick sits one division right of the origin: 220 + 400/2/4. A tick is a
        // short stroke crossing its axis, so its midpoint is the mark it makes.
        fun midpoint(stroke: Stroke) = InkPoint(
            stroke.points.sumOf { it.x.toDouble() }.toFloat() / stroke.points.size,
            stroke.points.sumOf { it.y.toDouble() }.toFloat() / stroke.points.size)
        val tick = GraphAxes.strokes(draft(), centred)
            .first { it.points.size == 2 && abs(midpoint(it).y - 190f) < 0.5f && midpoint(it).x > 220f }
        assertEquals(270f, midpoint(tick).x, 0.001f)
        // And the first y tick one division above it: 190 - 150/4.
        val yTick = GraphAxes.strokes(draft(), centred)
            .first { it.points.size == 2 && abs(midpoint(it).x - 220f) < 0.5f && midpoint(it).y < 190f }
        assertEquals(152.5f, midpoint(yTick).y, 0.001f)
    }

    @Test fun gridLinesLandOnEveryDivisionAndSkipTheAxes() {
        val grid = GraphStyle(divisions = 4, grid = true)
        val strokes = GraphAxes.strokes(draft(), grid)
        // Three interior divisions on each axis, four lines between them, plus the two axes.
        assertEquals(12 + 2, strokes.count { it.points.size == 2 })
        val frame = GraphFrame.of(InkPoint(20f, 40f), InkPoint(420f, 340f), grid)!!
        assertTrue(inside(frame, strokes))
        // Every interior line avoids the origin itself, or the axes would be overdrawn.
        assertTrue(strokes.none { it.points.size == 2 && it.points.any { p -> p.x == 220f && p.y == 190f } })
    }

    @Test fun numbersAreDrawnAsInkBesideEachTickAndStayInsideTheFrame() {
        val style = GraphStyle(divisions = 4, step = 5, ticks = true, numbers = true)
        val strokes = GraphAxes.strokes(draft(), style)
        val frame = GraphFrame.of(InkPoint(20f, 40f), InkPoint(420f, 340f), style)!!
        assertTrue(inside(frame, strokes))
        strokes.forEach { assertEquals(Tool.LINE, it.tool) }
        // Far more strokes than bare axes: 4 strokes for the frame plus ticks and glyphs.
        assertTrue(strokes.size > 20)
        // Labels for 5, 10, 15, 20 in both directions: the glyph ink exists well away from the axes.
        val belowAxis = box(strokes).filter { it.y > 190f }
        val leftOfAxis = box(strokes).filter { it.x < 220f }
        assertTrue(belowAxis.size > 8)
        assertTrue(leftOfAxis.size > 8)
    }

    @Test fun tinyDragsKeepTheirAxesAndDropUnreadableLabels() {
        val style = GraphStyle(divisions = 8, ticks = true, numbers = true, letters = true)
        val small = GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(60f, 50f)), style)
        // Six frame strokes and the ticks survive; no glyph is small enough to read.
        // Four ticks survive per division each way; nothing unreadable is drawn.
        assertEquals(6 + 32, small.size)
        val plain = GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(60f, 50f)))
        assertEquals(6, plain.size)
    }

    @Test fun lettersLabelTheFreeEndsAndCanBeTurnedOff() {
        val style = GraphStyle(letters = true, divisions = 0)
        val strokes = GraphAxes.strokes(draft(), style)
        val frame = GraphFrame.of(InkPoint(20f, 40f), InkPoint(420f, 340f), style)!!
        assertTrue(inside(frame, strokes))
        // The x sits past its arrowhead, the y above the top of the y axis.
        assertTrue(strokes.any { it.points.all { p -> p.x > 400f } })
        assertTrue(strokes.any { it.points.all { p -> p.y < 60f } })
        assertEquals(6, GraphAxes.strokes(draft(), style.copy(letters = false)).size)
    }

    @Test fun arrowheadsCanBeRemovedWithoutTouchingAnythingElse() {
        val style = GraphStyle(divisions = 0)
        val bare = GraphAxes.strokes(draft(), style.copy(arrows = false))
        assertEquals(2, bare.size)
        assertEquals(GraphAxes.strokes(draft(), style.copy(arrows = true)), GraphAxes.strokes(draft()))
    }

    @Test fun aSquareGraphTakesTheShorterSideAndKeepsTheDraggedCorner() {
        val style = GraphStyle(square = true, divisions = 0)
        val frame = GraphFrame.of(InkPoint(100f, 100f), InkPoint(500f, 220f), style)!!
        assertEquals(120f, frame.width, 0.001f)
        assertEquals(120f, frame.height, 0.001f)
        assertEquals(100f, frame.left, 0.001f)
        assertEquals(220f, frame.bottom, 0.001f)
        // Dragged from the other corner the box grows that way instead: the corner the drag
        // started from is always the one that stays put.
        val upLeft = GraphFrame.of(InkPoint(500f, 220f), InkPoint(100f, 100f), style)!!
        assertEquals(120f, upLeft.width, 0.001f)
        assertEquals(120f, upLeft.height, 0.001f)
        assertEquals(500f, upLeft.right, 0.001f)
        assertEquals(220f, upLeft.bottom, 0.001f)
        assertNull(GraphFrame.of(InkPoint(0f, 0f), InkPoint(0f, 0f), style))
    }

    @Test fun everyDressedGraphIsStillOrdinaryInkThroughTheStore() {
        val style = GraphStyle(divisions = 2, step = 10, grid = true, ticks = true, numbers = true, letters = true)
        val strokes = GraphAxes.strokes(draft(), style)
        val notebook = Notebook(title = "Graphs", pages = listOf(NotePage(strokes = strokes)))
        assertEquals(strokes, NoteCodec.decode(NoteCodec.encode(notebook)).pages.single().strokes)
        // Every part erases locally: cutting the x axis leaves the y axis and the labels alone.
        val cut = strokes.flatMap { InkGeometry.erase(it, InkPoint(120f, 190f), 8f) }
        assertTrue(cut.none { InkGeometry.hits(it, InkPoint(120f, 190f), 7f) })
        assertTrue(cut.any { InkGeometry.hits(it, InkPoint(220f, 100f), 1f) })
    }

    @Test fun theStepLabelOnlyAppearsOnceThereAreNumbersToRead() {
        assertNull(GraphAxes.stepLabel(GraphStyle.DEFAULT))
        assertNull(GraphAxes.stepLabel(GraphStyle(divisions = 4)))
        assertEquals("1 div = 5", GraphAxes.stepLabel(GraphStyle(divisions = 4, step = 5, numbers = true)))
    }

    @Test fun theGlyphFontCoversDigitsSignsAndAxisLetters() {
        assertTrue(GraphGlyphs.supports("0123456789"))
        assertTrue(GraphGlyphs.supports("-15"))
        assertTrue(GraphGlyphs.supports("x"))
        assertTrue(GraphGlyphs.supports("y"))
        assertFalse(GraphGlyphs.supports("a"))
        assertTrue(GraphGlyphs.polylines("11").size >= GraphGlyphs.polylines("1").size)
        assertEquals(0f, GraphGlyphs.width(""), 0.0001f)
        assertTrue(GraphGlyphs.width("15") > GraphGlyphs.width("5"))
        // Every glyph sits inside its own cell, so two digits never overlap.
        val polylines = GraphGlyphs.polylines("12")
        assertTrue(polylines.all { points -> points.all { it.x >= -0.001f && it.x <= GraphGlyphs.width("12") + 0.001f && it.y >= -0.001f } })
    }
}
