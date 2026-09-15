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

    // ---- Incremental draft geometry ---------------------------------------------------------

    /**
     * The live stroke is drawn through [InkRenderer.IncrementalPenStroke] instead of the batch
     * pass. It has to agree with [InkRenderer.rendered] exactly at every prefix, or a stroke would
     * shift the moment the pen lifts and the committed copy takes over.
     */
    private fun assertIncrementalMatchesBatch(points: List<InkPoint>) {
        val live = InkRenderer.IncrementalPenStroke()
        for (length in 1..points.size) {
            val prefix = points.subList(0, length)
            val batch = InkRenderer.rendered(Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, prefix))
            val drawn = live.update(prefix)
            assertEquals("centre at length $length", batch.centre, drawn.centre)
            assertEquals("taper at length $length", batch.taper?.toList(), drawn.taper?.toList())
            assertEquals("minX at length $length", batch.minX, drawn.minX, 0f)
            assertEquals("minY at length $length", batch.minY, drawn.minY, 0f)
            assertEquals("maxX at length $length", batch.maxX, drawn.maxX, 0f)
            assertEquals("maxY at length $length", batch.maxY, drawn.maxY, 0f)
        }
    }

    @Test fun incrementalDraftMatchesBatchAtEveryPrefix() {
        val steps = mutableListOf<InkPoint>()
        for (i in 0 until 160) {
            val t = i.toFloat()
            steps += InkPoint(t * 2.4f, 12f * kotlin.math.sin(t / 5f) + 6f * kotlin.math.sin(t / 2.3f), .4f + (i % 5) * .3f)
        }
        assertIncrementalMatchesBatch(steps)
    }

    @Test fun incrementalDraftMatchesBatchThroughTheSmallStrokeSpan() {
        // Crosses InkRenderer's micro-stroke bound mid-stroke, which changes the turn threshold
        // and so has to re-take every earlier split.
        val steps = mutableListOf<InkPoint>()
        for (i in 0 until 90) {
            val t = i.toFloat()
            steps += InkPoint(t * .7f, 3f * kotlin.math.sin(t / 2f), 1f)
        }
        assertIncrementalMatchesBatch(steps)
    }

    @Test fun incrementalDraftMatchesBatchOnStraightSparseAndTinyStrokes() {
        val straight = (0 until 70).map { InkPoint(it * 5.5f, it * 1.25f, 1f) }
        assertIncrementalMatchesBatch(straight)
        val sparse = (0 until 40).map { InkPoint(it * 9f, (it % 3) * 7f, 1f) }
        assertIncrementalMatchesBatch(sparse)
        val tiny = listOf(InkPoint(0f, 0f), InkPoint(.2f, 0f), InkPoint(.4f, .1f), InkPoint(.6f, .3f))
        assertIncrementalMatchesBatch(tiny)
        assertIncrementalMatchesBatch(listOf(InkPoint(0f, 0f), InkPoint(.05f, 0f)))
        assertIncrementalMatchesBatch(listOf(InkPoint(3f, 3f)))
    }

    @Test fun incrementalDraftMatchesBatchOnARepeatingSampleWalk() {
        val random = kotlin.random.Random(4242)
        val steps = mutableListOf<InkPoint>()
        var x = 0f; var y = 0f
        repeat(140) {
            x += random.nextFloat() * 10f - 3f
            y += random.nextFloat() * 10f - 5f
            steps += InkPoint(x, y, .5f + random.nextFloat())
        }
        assertIncrementalMatchesBatch(steps)
    }

    @Test fun incrementalDraftGrowingInPlaceStaysInStep() {
        // The view appends to one backing list and re-passes it, rather than a fresh prefix copy.
        val backing = ArrayList<InkPoint>()
        val live = InkRenderer.IncrementalPenStroke()
        val all = (0 until 60).map { InkPoint(it * 1.7f, 5f * kotlin.math.cos(it / 4f), 1f) }
        all.forEach { point ->
            backing += point
            val batch = InkRenderer.rendered(Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, backing))
            assertEquals(batch.centre, live.update(backing).centre)
        }
    }

    @Test fun incrementalDraftReArmsWhenTheListShrinks() {
        // A reused backing list that was cut back cannot be trusted as the same prefix.
        val live = InkRenderer.IncrementalPenStroke()
        val long = (0 until 40).map { InkPoint(it * 2f, kotlin.math.sin(it.toFloat()) * 4f, 1f) }
        live.update(long)
        val short = long.subList(0, 12)
        val batch = InkRenderer.rendered(Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, short))
        val drawn = live.update(short)
        assertEquals(batch.centre, drawn.centre)
        assertEquals(batch.taper?.toList(), drawn.taper?.toList())
    }

    @Test fun incrementalDraftRepeatedUpdateWithoutNewSamplesReturnsTheSameGeometry() {
        val live = InkRenderer.IncrementalPenStroke()
        val points = (0 until 30).map { InkPoint(it * 2.2f, 3f * kotlin.math.sin(it / 3f), 1f) }
        val first = live.update(points)
        val again = live.update(points)
        assertEquals(first.centre, again.centre)
        assertEquals(first.taper?.toList(), again.taper?.toList())
    }
}
