package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InkRendererTests {
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
}
