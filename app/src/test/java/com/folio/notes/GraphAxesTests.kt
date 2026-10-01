package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class GraphAxesTests {
    private fun draft(a: InkPoint = InkPoint(20f, 40f), b: InkPoint = InkPoint(420f, 340f)) =
        Stroke(Tool.GRAPH, 0xFF303431.toInt(), 2f, listOf(a, b), .7f, StrokeStyle.SOLID)

    @Test fun centredAxesHaveFourOpenArrowheadsAndNoOtherMarks() {
        val axes = GraphAxes.strokes(draft())
        assertEquals(6, axes.size)
        assertEquals(listOf(InkPoint(20f, 190f), InkPoint(420f, 190f)), axes[0].points)
        assertEquals(listOf(InkPoint(220f, 340f), InkPoint(220f, 40f)), axes[1].points)
        val tips = listOf(InkPoint(20f, 190f), InkPoint(420f, 190f), InkPoint(220f, 40f), InkPoint(220f, 340f))
        axes.drop(2).forEachIndexed { index, head ->
            assertEquals(3, head.points.size)
            assertEquals(tips[index], head.points[1])
        }
        axes.forEach {
            assertEquals(Tool.LINE, it.tool)
            assertEquals(draft().color, it.color)
            assertEquals(draft().width, it.width)
            assertEquals(draft().opacity, it.opacity)
            assertEquals(StrokeStyle.SOLID, it.style)
            assertTrue(it.points.all { p -> p.x in 20f..420f && p.y in 40f..340f })
        }
        assertTrue(axes.none { InkGeometry.hits(it, InkPoint(120f, 115f), 1f) })
    }

    @Test fun dragDirectionDoesNotChangeAxesAndTinyDragsRemainBounded() {
        assertEquals(GraphAxes.strokes(draft()), GraphAxes.strokes(draft(InkPoint(420f, 340f), InkPoint(20f, 40f))))
        val tiny = GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(1f, 2f)))
        assertEquals(6, tiny.size)
        assertTrue(tiny.flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..2f })
        assertTrue(GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(0f, 200f))).isEmpty())
        assertTrue(GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(200f, 0f))).isEmpty())
        assertTrue(GraphAxes.strokes(draft().copy(points = listOf(InkPoint(0f, 0f)))).isEmpty())
    }

    @Test fun arrowheadsStayCompactOnLargeAndSmallGraphs() {
        val large = GraphAxes.strokes(draft())[3].points
        assertEquals(10f, large[1].x - large[0].x, .001f)
        assertEquals(5f, large[1].y - large[0].y, .001f)
        val small = GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(100f, 100f)))[3].points
        assertEquals(4f, small[1].x - small[0].x, .001f)
        assertEquals(2f, small[1].y - small[0].y, .001f)
    }

    @Test fun denseTicksStayBehindArrowheadsOnEachAxis() {
        for (origin in GraphOrigin.entries) {
            val style = GraphStyle(origin = origin, divisions = 16, ticks = true)
            val strokes = GraphAxes.strokes(draft(InkPoint(0f, 0f), InkPoint(100f, 160f)), style)
            val frame = GraphFrame(0f, 0f, 100f, 160f)
            val (ox, oy) = frame.origin(style)
            val frameStrokes = if (origin == GraphOrigin.CENTRE) 6 else 4
            val ticks = strokes.drop(frameStrokes)
            assertTrue(ticks.isNotEmpty())
            ticks.forEach { tick ->
                val a = tick.points.first(); val b = tick.points.last()
                if (a.x == b.x) {
                    assertTrue(a.x < frame.right - 4f)
                    if (origin == GraphOrigin.CENTRE) assertTrue(a.x > frame.left + 4f)
                    assertEquals(oy, (a.y + b.y) / 2f, .001f)
                } else {
                    assertTrue(a.y > frame.top + 4f)
                    if (origin == GraphOrigin.CENTRE) assertTrue(a.y < frame.bottom - 4f)
                    assertEquals(ox, (a.x + b.x) / 2f, .001f)
                }
            }
        }
    }

    @Test fun graphIsInShapesAndSupportsPresets() {
        assertTrue(Tool.GRAPH in ToolbarSlot.SHAPES.tools)
        assertTrue(StylusShortcuts.isDrawingTool(Tool.GRAPH))
        val preset = ToolPresets.fromOptions("Graph", Tool.GRAPH, ToolOptions.defaults(Tool.GRAPH))!!
        assertEquals(listOf(preset), ToolPresets.decode(ToolPresets.encode(listOf(preset))))
    }

    @Test fun axesPersistAsEditableInkAndEraseLocally() {
        val axes = GraphAxes.strokes(draft())
        val notebook = Notebook(title = "Methods", pages = listOf(NotePage(strokes = axes)))
        val restored = NoteCodec.decode(NoteCodec.encode(notebook)).pages.single()
        assertTrue(restored.texts.isEmpty())
        assertEquals(axes, restored.strokes)
        val at = InkPoint(120f, 190f)
        val cut = restored.strokes.flatMap { InkGeometry.erase(it, at, 10f) }
        assertTrue(cut.none { InkGeometry.hits(it, at, 9f) })
        assertTrue(cut.any { InkGeometry.hits(it, InkPoint(220f, 100f), 1f) })
        assertTrue(cut.any { InkGeometry.hits(it, InkPoint(320f, 190f), 1f) })
        assertTrue(cut.any { InkGeometry.hits(it, InkPoint(20f, 190f), 1f) })
        val pressureCut = restored.strokes.flatMap { InkGeometry.erase(it, listOf(at), listOf(10f)) }
        assertEquals(cut, pressureCut)
    }
}
