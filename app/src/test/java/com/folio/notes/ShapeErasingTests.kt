package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ShapeErasingTests {
    private fun shape(tool: Tool, style: StrokeStyle = StrokeStyle.SOLID) = Stroke(
        tool, 0xFF237ABC.toInt(), 2f, listOf(InkPoint(0f, 0f), InkPoint(200f, 200f)),
        opacity = .65f, style = style)

    private fun length(strokes: List<Stroke>): Float = strokes.sumOf { stroke ->
        InkGeometry.pathPoints(stroke).zipWithNext().sumOf { (a, b) ->
            hypot(b.x - a.x, b.y - a.y).toDouble()
        }
    }.toFloat()

    @Test fun everyShapeIsCutLocallyForBothEraserModesAndAllLineStyles() {
        ShapeTools.forEach { tool ->
            StrokeStyle.entries.forEach { style ->
                val original = shape(tool, style)
                val path = InkGeometry.pathPoints(original)
                val at = InkPoint((path[0].x + path[1].x) / 2f, (path[0].y + path[1].y) / 2f)
                val fixed = InkGeometry.erase(original, at, 8f)
                val pressure = InkGeometry.erase(original, listOf(at), listOf(8f))
                assertEquals(fixed, pressure)
                assertTrue("$tool must survive a local cut", fixed.isNotEmpty())
                assertTrue(length(fixed) < length(listOf(original)))
                assertTrue(length(fixed) > length(listOf(original)) - 25f)
                fixed.forEach { fragment ->
                    assertEquals(Tool.LINE, fragment.tool)
                    assertEquals(original.color, fragment.color)
                    assertEquals(original.width, fragment.width)
                    assertEquals(original.opacity, fragment.opacity)
                    assertEquals(style, fragment.style)
                    assertFalse(InkGeometry.hits(fragment, at, 7f))
                }
            }
        }
    }

    @Test fun rectangleFragmentsKeepCornersAndNeverReconnectAcrossTheCut() {
        val original = shape(Tool.RECTANGLE, StrokeStyle.DOTTED)
        val at = InkPoint(100f, 0f)
        val fragments = InkGeometry.erase(original, at, 10f)
        assertEquals(2, fragments.size)
        assertEquals(778f, length(fragments), .001f)
        assertTrue(fragments.any { it.points.size > 2 })
        assertTrue(fragments.any { InkGeometry.hits(it, InkPoint(200f, 100f), 1f) })
        assertTrue(fragments.any { InkGeometry.hits(it, InkPoint(100f, 200f), 1f) })
        assertTrue(fragments.any { InkGeometry.hits(it, InkPoint(0f, 100f), 1f) })
        assertTrue(fragments.none { InkGeometry.hits(it, InkPoint(100f, 100f), 1f) })
        assertTrue(fragments.none { InkGeometry.hits(it, at, 9f) })
        val notebook = Notebook(title = "Cut shape", pages = listOf(NotePage(strokes = fragments)))
        val restored = NoteCodec.decode(NoteCodec.encode(notebook)).pages.single().strokes
        assertEquals(fragments, restored)
        assertEquals(fragments.map(InkGeometry::pathPoints), restored.map(InkGeometry::pathPoints))
        val again = restored.flatMap { InkGeometry.erase(it, InkPoint(200f, 100f), 10f) }
        assertEquals(756f, length(again), .001f)
        assertTrue(again.none { InkGeometry.hits(it, at, 9f) })
        assertTrue(again.none { InkGeometry.hits(it, InkPoint(200f, 100f), 9f) })
    }

    @Test fun untouchedShapesRetainIdentityAndCanStillBeFullyErased() {
        ShapeTools.forEach { tool ->
            val original = shape(tool)
            assertSame(original, InkGeometry.erase(original, InkPoint(1000f, 1000f), 10f).single())
            assertSame(original, InkGeometry.erase(original, listOf(InkPoint(1000f, 1000f)), listOf(10f)).single())
            assertTrue(InkGeometry.erase(original, InkPoint(100f, 100f), 300f).isEmpty())
            assertTrue(InkGeometry.erase(original, listOf(InkPoint(100f, 100f)), listOf(300f)).isEmpty())
        }
    }

    @Test fun reverseAndRotatedShapesUseTheirRenderedOutlines() {
        ShapeTools.forEach { tool ->
            val original = shape(tool).let { it.copy(points = it.points.reversed()) }
            val rotated = InkGeometry.rotate(listOf(original), InkPoint(100f, 100f), 90f).single()
            val path = InkGeometry.pathPoints(rotated)
            val at = InkPoint((path[0].x + path[1].x) / 2f, (path[0].y + path[1].y) / 2f)
            val fragments = InkGeometry.erase(rotated, at, 8f)
            assertTrue(fragments.isNotEmpty())
            assertTrue(fragments.none { InkGeometry.hits(it, at, 7f) })
            assertTrue(length(fragments) > length(listOf(rotated)) - 25f)
        }
    }
}
