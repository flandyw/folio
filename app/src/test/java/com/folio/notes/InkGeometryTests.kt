package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class InkGeometryTests {
    @Test fun smoothingKeepsTheEndsAndDensifiesTheLine() {
        val smooth = InkGeometry.smooth(listOf(InkPoint(0f, 0f), InkPoint(30f, 0f), InkPoint(60f, 0f)))
        assertTrue(smooth.size > 3)
        assertEquals(0f, smooth.first().x, .001f)
        assertEquals(0f, smooth.first().y, .001f)
        assertEquals(60f, smooth.last().x, .001f)
        assertEquals(0f, smooth.last().y, .001f)
        // A straight line stays straight through the spline.
        assertTrue(smooth.all { abs(it.y) < .001f })
    }

    @Test fun smoothingTakesTheCornerOffAndKeepsPressureInRange() {
        val smooth = InkGeometry.smooth(listOf(InkPoint(0f, 0f, .4f), InkPoint(40f, 0f, 1.2f), InkPoint(40f, 40f)))
        // The resampled line passes inside the right angle instead of turning on it.
        assertTrue(smooth.any { it.x in 1f..39f && it.y in 1f..39f })
        assertTrue(smooth.all { it.pressure in .25f..1.8f })
    }

    @Test fun smoothingCollapsesRepeatedSamplesToOnePoint() {
        val smooth = InkGeometry.smooth(listOf(InkPoint(5f, 5f), InkPoint(5.1f, 5f), InkPoint(5f, 5f)))
        assertEquals(1, smooth.size)
        assertEquals(5f, smooth.first().x, .001f)
    }

    @Test fun taperLeavesTheMiddleAtFullWidthAndThinsBothEnds() {
        val scales = InkGeometry.taperScales(listOf(InkPoint(0f, 0f), InkPoint(40f, 0f), InkPoint(80f, 0f), InkPoint(120f, 0f)))
        assertEquals(4, scales.size)
        assertEquals(.5f, scales[0], .0001f)
        assertEquals(1f, scales[1], .0001f)
        assertEquals(1f, scales[2], .0001f)
        assertEquals(.5f, scales[3], .0001f)
    }

    @Test fun taperLeavesADotAlone() {
        assertEquals(listOf(1f), InkGeometry.taperScales(listOf(InkPoint(3f, 3f))))
    }

    @Test fun erasingTheMiddleOfAStrokeLeavesTwoFragmentsWithTheSameLook() {
        val line = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(0f, 0f), InkPoint(50f, 0f), InkPoint(100f, 0f)))
        val fragments = InkGeometry.erase(line, InkPoint(50f, 0f), 5f)
        assertEquals(2, fragments.size)
        assertTrue(fragments.first().points.last().x < 50f)
        assertTrue(fragments.last().points.first().x > 50f)
        assertTrue(fragments.all { it.tool == Tool.PEN && it.width == 4f && it.color == 0 })
    }

    @Test fun erasingOverAShortStrokeRemovesItEntirely() {
        val tick = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(0f, 0f), InkPoint(6f, 0f)))
        assertTrue(InkGeometry.erase(tick, InkPoint(3f, 0f), 6f).isEmpty())
    }

    @Test fun shapesAreErasedWholeRatherThanSplit() {
        val box = Stroke(Tool.RECTANGLE, 0, 4f, listOf(InkPoint(0f, 0f), InkPoint(80f, 80f)))
        // The inside of the rectangle carries no ink, so a press there leaves it alone.
        assertEquals(1, InkGeometry.erase(box, InkPoint(50f, 50f), 2f).size)
        // A touch on the outline drops the whole shape.
        assertTrue(InkGeometry.erase(box, InkPoint(0f, 40f), 3f).isEmpty())
    }

    @Test fun eraserRemovesADotOnlyWhenItTouchesIt() {
        val dot = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(30f, 30f)))
        assertTrue(InkGeometry.erase(dot, InkPoint(31f, 31f), 2f).isEmpty())
        assertEquals(1, InkGeometry.erase(dot, InkPoint(60f, 60f), 2f).size)
    }

    @Test fun onePassTakesOutEverySampleTheGestureCovered() {
        val line = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(0f, 0f), InkPoint(50f, 0f), InkPoint(100f, 0f), InkPoint(150f, 0f)))
        // Two bites out of one line leave three pieces, found in a single walk of the samples.
        val fragments = InkGeometry.erase(line, listOf(InkPoint(50f, 0f), InkPoint(100f, 0f)), 5f)
        assertEquals(3, fragments.size)
        assertTrue(fragments[0].points.last().x < 50f)
        assertTrue(fragments[1].points.first().x > 50f && fragments[1].points.last().x < 100f)
        assertTrue(fragments[2].points.first().x > 100f)
    }

    @Test fun aStrokeTheEraserNeverReachesIsHandedBackUntouched() {
        val line = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(0f, 0f), InkPoint(20f, 0f)))
        // Same instance, so a full page of untouched ink costs nothing to erase across.
        assertSame(line, InkGeometry.erase(line, listOf(InkPoint(500f, 500f), InkPoint(600f, 600f)), 5f).single())
    }
}
