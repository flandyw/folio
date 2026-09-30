package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class ShapeToolTests {
    private fun stroke(tool: Tool) = Stroke(tool, 0xFF303431.toInt(), 2f,
        listOf(InkPoint(10f, 20f), InkPoint(110f, 220f)), style = StrokeStyle.DASHED)

    @Test fun polygonsAreClosedAndFillTheDragBoundsInEitherDirection() {
        val counts = mapOf(Tool.TRIANGLE to 4, Tool.DIAMOND to 5, Tool.PENTAGON to 6,
            Tool.HEXAGON to 7, Tool.STAR to 11)
        counts.forEach { (tool, count) ->
            val shape = stroke(tool)
            val points = InkGeometry.pathPoints(shape)
            assertEquals(count, points.size)
            assertEquals(points.first(), points.last())
            assertEquals(points, InkGeometry.pathPoints(shape.copy(points = shape.points.reversed())))
            assertEquals(10f, points.minOf { it.x }, .001f)
            assertEquals(110f, points.maxOf { it.x }, .001f)
            assertEquals(20f, points.minOf { it.y }, .001f)
            assertEquals(220f, points.maxOf { it.y }, .001f)
        }
    }

    @Test fun degenerateDragProducesFiniteGeometry() {
        PolygonTools.forEach { tool ->
            val shape = stroke(tool)
            listOf(InkPoint(10f, 220f), InkPoint(110f, 20f), InkPoint(10f, 20f)).forEach { end ->
                assertTrue(InkGeometry.pathPoints(shape.copy(points = listOf(shape.points.first(), end)))
                    .all { it.x.isFinite() && it.y.isFinite() })
            }
        }
    }

    @Test fun eraserUsesOutlineAndCutsPolygons() {
        PolygonTools.forEach { tool ->
            val shape = stroke(tool)
            val tip = InkGeometry.pathPoints(shape).first()
            assertTrue(InkGeometry.hits(shape, tip, 2f))
            assertFalse(InkGeometry.hits(shape, InkPoint(60f, 120f), 2f))
            val fragments = InkGeometry.erase(shape, listOf(tip), 2f)
            assertTrue(fragments.isNotEmpty())
            assertTrue(fragments.none { InkGeometry.hits(it, tip, 1f) })
            assertEquals(fragments, InkGeometry.erase(shape, listOf(tip), listOf(2f)))
            assertEquals(listOf(shape), InkGeometry.erase(shape, listOf(InkPoint(500f, 500f)), 2f))
        }
    }

    @Test fun rotationPreservesPolygonVerticesAndStylesAcrossStorage() {
        PolygonTools.forEach { tool ->
            val shape = stroke(tool)
            val center = InkPoint(60f, 120f)
            val expected = InkGeometry.pathPoints(shape).map { InkGeometry.rotatePoint(it, center, 90f) }
            val rotated = InkGeometry.rotate(listOf(shape), center, 90f).single()
            assertEquals(expected, InkGeometry.pathPoints(rotated))
            assertEquals(tool, rotated.tool)
            assertEquals(StrokeStyle.DASHED, rotated.style)
            val notebook = Notebook(title = "Shapes", pages = listOf(NotePage(strokes = listOf(rotated))))
            val restored = NoteCodec.decode(NoteCodec.encode(notebook)).pages.single().strokes.single()
            assertEquals(rotated, restored)
            assertEquals(expected, InkGeometry.pathPoints(restored))
        }
    }

    @Test fun newShapesSupportPresetsRestylingAndToolbarSelection() {
        PolygonTools.forEach { tool ->
            assertTrue(tool in ToolbarSlot.SHAPES.tools)
            assertTrue(StylusShortcuts.isDrawingTool(tool))
            val preset = ToolPresets.fromOptions("Test", tool, ToolOptions.defaults(tool), StrokeStyle.DOTTED)!!
            assertEquals(listOf(preset), ToolPresets.decode(ToolPresets.encode(listOf(preset))))
            assertEquals(StrokeStyle.DOTTED,
                InkGeometry.restyle(listOf(stroke(tool)), style = StrokeStyle.DOTTED).single().style)
        }
    }
}
