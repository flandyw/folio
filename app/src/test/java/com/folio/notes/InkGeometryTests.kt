package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class InkGeometryTests {
    @Test fun segmentHitsMatchThePairwiseOracleForEveryStoredTool() {
        val random = Random(188)
        for (tool in Tool.entries) {
            for (size in listOf(0, 1, 2, 7)) {
                val stroke = Stroke(tool, 0, 4f, List(size) {
                    InkPoint(random.nextFloat() * 200f, random.nextFloat() * 200f)
                })
                repeat(20) {
                    val point = InkPoint(random.nextFloat() * 200f, random.nextFloat() * 200f)
                    val radius = random.nextFloat() * 20f
                    val path = InkGeometry.pathPoints(stroke)
                    val distance = radius + stroke.width / 2
                    val expected = if (path.size == 1)
                        kotlin.math.hypot(path[0].x - point.x, path[0].y - point.y) <= distance
                    else path.zipWithNext().any { (a, b) -> InkGeometry.segmentDistance(point, a, b) <= distance }
                    assertEquals("$tool / $size", expected, InkGeometry.hits(stroke, point, radius))
                }
            }
        }
    }

    @Test fun aHitAtTheFirstSegmentDoesNotReadTheRestOfADenseStroke() {
        var reads = 0
        val points = object : AbstractList<InkPoint>() {
            override val size = 10_000
            override fun get(index: Int): InkPoint { reads++; return InkPoint(index.toFloat(), 0f) }
        }
        val stroke = Stroke(Tool.PEN, 0, 2f, points)
        assertTrue(InkGeometry.hits(stroke, InkPoint(.5f, 0f), 0f))
        assertTrue("Only corners and the first segment should be read: $reads", reads <= 4)
        println("InkGeometry.hits: P=10000, first-segment hit reads=$reads")
    }

    @Test fun scribbleSweepKeepsCrossingEndpointCollinearAndSinglePointContacts() {
        fun stroke(vararg points: InkPoint) = Stroke(Tool.LINE, 0, 2f, points.toList())
        val target = stroke(InkPoint(0f, 0f), InkPoint(100f, 0f))
        val cases = listOf(
            stroke(InkPoint(50f, -50f), InkPoint(50f, 50f)) to true,
            stroke(InkPoint(100f, -20f), InkPoint(100f, 0f)) to true,
            stroke(InkPoint(20f, 0f), InkPoint(80f, 0f)) to true,
            stroke(InkPoint(20f, 2f), InkPoint(80f, 2f)) to false,
            stroke(InkPoint(50f, 0f)) to true,
            stroke() to false
        )
        for ((sweep, expected) in cases) assertEquals(expected, InkGeometry.scribbleHits(sweep, target, 0f))
        val sweep = stroke(InkPoint(50f, -50f), InkPoint(50f, 50f))
        assertTrue(InkGeometry.scribbleHits(sweep, stroke(InkPoint(50f, 0f)), 0f))
        assertFalse(InkGeometry.scribbleHits(sweep, stroke(), 0f))
    }

    @Test fun sharedBoundsRetainMarginsEmptyInkAndRawShapeCorners() {
        assertNull(InkGeometry.bounds(emptyList()))
        assertNull(InkGeometry.bounds(listOf(Stroke(Tool.PEN, 0, 2f, emptyList()))))
        val strokes = listOf(
            Stroke(Tool.ELLIPSE, 0, 2f, listOf(InkPoint(90f, 50f), InkPoint(-10f, -20f))),
            Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(-30f, 12f), InkPoint(70f, 80f)))
        )
        assertArrayEquals(floatArrayOf(-35f, -25f, 95f, 85f), InkGeometry.bounds(strokes, 5f)!!, 0f)
        assertArrayEquals(InkGeometry.bounds(strokes)!!,
            InkGeometry.selectionBounds(strokes, emptyList(), emptyList(), { error("No text to measure") })!!, 0f)
        assertArrayEquals(floatArrayOf(-30f, -20f, 90f, 80f),
            InkGeometry.lassoBounds(strokes.flatMap { it.points }), 0f)
    }
}
