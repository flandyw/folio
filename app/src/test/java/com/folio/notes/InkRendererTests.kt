package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InkRendererTests {
    @Test fun nearbySplitEndpointSurvivesBothSectionJoins() {
        for (gap in listOf(.051f, .2f, .35f)) {
            val turn = InkPoint(2f + gap, 0f, .45f)
            val points = listOf(
                InkPoint(0f, 0f), InkPoint(2f, 0f), turn,
                InkPoint(turn.x, 2f), InkPoint(turn.x, 4f)
            )

            val centre = InkRenderer.handwritingCentreline(points)

            assertEquals(1, centre.count { it == turn })
            val join = centre.indexOf(turn)
            assertTrue(join > 0 && join < centre.lastIndex)
            assertTrue(centre[join - 1].x < turn.x)
            assertTrue(centre[join + 1].y > turn.y)
            assertEquals(points.first(), centre.first())
            assertEquals(points.last(), centre.last())
        }
    }

    @Test fun shortDenseSectionRetainsItsSplitEndpoint() {
        val turn = InkPoint(.2f, 0f, .45f)
        val points = listOf(InkPoint(0f, 0f), InkPoint(.1f, 0f), turn, InkPoint(.2f, 2f))

        assertEquals(listOf(points.first(), turn, points.last()), InkRenderer.handwritingCentreline(points))
    }

    @Test fun smallTightTurnSurvivesHandwritingSmoothing() {
        val apex = InkPoint(4f, 0f, .45f)
        val points = listOf(
            InkPoint(0f, 8f, .50f),
            InkPoint(2f, 4f, .48f),
            apex,
            InkPoint(6f, 4f, .46f),
            InkPoint(8f, 8f, .44f)
        )

        val centre = InkRenderer.handwritingCentreline(points)

        // The apex of a tiny hump is semantic handwriting geometry, not digitizer noise.
        assertTrue(centre.any { abs(it.x - apex.x) < .001f && abs(it.y - apex.y) < .001f })
    }

    @Test fun sparseFastTurnIsNotRoundedAcross() {
        val turn = InkPoint(7f, 7f)
        val points = listOf(InkPoint(0f, 0f), turn, InkPoint(14f, 0f))

        val centre = InkRenderer.handwritingCentreline(points)

        assertEquals(points, centre)
        assertTrue(centre.contains(turn))
    }

    @Test fun pressureCompressionKeepsLightPenMarksVisible() {
        val light = InkRenderer.penPressureScale(.25f)
        val normal = InkRenderer.penPressureScale(1f)
        val hard = InkRenderer.penPressureScale(1.8f)

        assertTrue(light >= .85f)
        assertEquals(1f, normal, .0001f)
        assertTrue(hard > 1f && hard <= 1.2f)
    }

    @Test fun renderedPenMatchesHandwritingCentrelineWithTaperAndRawBounds() {
        val stroke = Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, listOf(
            InkPoint(0f, 0f), InkPoint(4f, 1f), InkPoint(8f, 0f), InkPoint(12f, 3f), InkPoint(16f, 2f)))

        val rendered = InkRenderer.rendered(stroke)

        assertEquals(InkRenderer.handwritingCentreline(InkGeometry.pathPoints(stroke)), rendered.centre)
        assertEquals(rendered.centre.size, rendered.taper!!.size)
        assertEquals(0f, rendered.minX, .0001f)
        assertEquals(0f, rendered.minY, .0001f)
        assertEquals(16f, rendered.maxX, .0001f)
        assertEquals(3f, rendered.maxY, .0001f)
    }

    @Test fun renderedHighlighterMatchesSmoothWithoutTaper() {
        val stroke = Stroke(Tool.HIGHLIGHTER, 0xFF000000.toInt(), 8f, listOf(
            InkPoint(0f, 0f), InkPoint(5f, 2f), InkPoint(10f, 0f), InkPoint(15f, 4f)))

        val rendered = InkRenderer.rendered(stroke)

        assertEquals(InkGeometry.smooth(InkGeometry.pathPoints(stroke)), rendered.centre)
        assertEquals(null, rendered.taper)
    }

    @Test fun renderedShapeKeepsPathPointsWithoutTaper() {
        val stroke = Stroke(Tool.LINE, 0xFF000000.toInt(), 3f,
            listOf(InkPoint(2f, 3f), InkPoint(20f, 30f)))

        val rendered = InkRenderer.rendered(stroke)

        assertEquals(InkGeometry.pathPoints(stroke), rendered.centre)
        assertEquals(null, rendered.taper)
        assertEquals(2f, rendered.minX, .0001f)
        assertEquals(20f, rendered.maxX, .0001f)
    }

    @Test fun rawBoundsMatchStoredSamples() {
        val stroke = Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, listOf(
            InkPoint(4f, 9f), InkPoint(-2f, 5f), InkPoint(7f, -1f)))

        assertEquals(listOf(-2f, -1f, 7f, 9f), InkRenderer.rawBounds(stroke).toList())
        assertEquals(listOf(0f, 0f, 0f, 0f),
            InkRenderer.rawBounds(stroke.copy(points = emptyList())).toList())
    }

    @Test fun renderedEmptyStrokeStaysEmpty() {
        val rendered = InkRenderer.rendered(
            Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, emptyList()))

        assertTrue(rendered.centre.isEmpty())
        assertEquals(null, rendered.taper)
    }
}
