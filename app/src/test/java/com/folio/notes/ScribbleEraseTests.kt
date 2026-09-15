package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ScribbleEraseTests {
    private val zigzag = (0..5).map { InkPoint(if (it % 2 == 0) 0f else 60f, it * 3f) }
    private fun stroke(points: List<InkPoint>, tool: Tool = Tool.PEN) = Stroke(tool, 0, 2f, points)

    @Test fun recognizesSparseDenseAndDuplicateSamples() {
        val dense = zigzag.zipWithNext().flatMap { (a, b) ->
            (0 until 100).map { i -> InkPoint(a.x + (b.x - a.x) * i / 100, a.y + (b.y - a.y) * i / 100) }
        } + zigzag.last()
        assertTrue(InkGeometry.isScribble(zigzag))
        assertTrue(InkGeometry.isScribble(dense))
        assertTrue(InkGeometry.isScribble(dense.flatMap { listOf(it, it) }))
        assertTrue(InkGeometry.isScribble(zigzag.map { InkPoint(-it.y, it.x) }))
    }

    @Test fun recognizesSmoothRoundedScrubbing() {
        val rounded = (0..600).map {
            val t = it / 600.0
            InkPoint((30 * cos(t * 6 * PI)).toFloat(), (t * 18).toFloat())
        }
        assertTrue(InkGeometry.isScribble(rounded))
    }

    @Test fun rejectsOrdinaryMarksAndShapes() {
        assertFalse(InkGeometry.isScribble(listOf(InkPoint(1f, 1f))))
        assertFalse(InkGeometry.isScribble((0..100).map { InkPoint(it.toFloat(), 0f) }))
        assertFalse(InkGeometry.isScribble(zigzag.map { InkPoint(it.x / 10, it.y / 10) }))
        assertFalse(InkGeometry.isScribble((0..200).map {
            val angle = it / 200.0 * 2 * PI
            InkPoint((30 * cos(angle)).toFloat(), (30 * sin(angle)).toFloat())
        }))
        assertFalse(InkGeometry.isScribble(listOf(InkPoint(0f, 0f), InkPoint(60f, 0f),
            InkPoint(60f, 60f), InkPoint(0f, 60f), InkPoint(0f, 0f))))
        assertFalse(InkGeometry.isScribble(zigzag.take(4)))
    }

    @Test fun erasesCrossingsBetweenSamplesAndPreservesDistantInk() {
        val target = stroke(listOf(InkPoint(30f, -20f), InkPoint(30f, 40f)))
        val distant = stroke(listOf(InkPoint(100f, 0f), InkPoint(100f, 40f)))
        assertEquals(listOf(distant), InkGeometry.scribbleErase(listOf(target, distant), stroke(zigzag), 2f))
        assertTrue(InkGeometry.scribbleHits(stroke(zigzag), target.copy(tool = Tool.LINE), 2f))
    }

    @Test fun handlesDotsParallelSegmentsAndEmptyPaths() {
        val sweep = stroke(listOf(InkPoint(0f, 0f), InkPoint(60f, 0f)))
        assertTrue(InkGeometry.scribbleHits(sweep, stroke(listOf(InkPoint(30f, 2f))), 2f))
        assertTrue(InkGeometry.scribbleHits(sweep, stroke(listOf(InkPoint(10f, 2f), InkPoint(50f, 2f))), 2f))
        assertFalse(InkGeometry.scribbleHits(sweep, stroke(listOf(InkPoint(70f, 0f), InkPoint(90f, 0f))), 2f))
        assertFalse(InkGeometry.scribbleHits(sweep, stroke(emptyList()), 2f))
        assertFalse(InkGeometry.scribbleHits(stroke(emptyList()), sweep, 2f))
    }
}
