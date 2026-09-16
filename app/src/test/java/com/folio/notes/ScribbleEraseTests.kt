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

    @Test fun rejectsCursiveWriting() {
        // Forward-marching sine: long and bendy like a cursive word, but always moving on.
        val wave = (0..400).map { i ->
            val t = i / 400.0
            InkPoint((t * 150).toFloat(), (10 * sin(t * 30 * PI) + 5 * sin(t * 61 * PI)).toFloat())
        }
        assertFalse(InkGeometry.isScribble(wave))
        // Sharp cursive teeth (a big W): hairpin turns with long legs, yet each tooth only
        // touches the last at a point instead of re-tracing it, and the stroke marches on.
        val teethBase = listOf(0f to 0f, 15f to 40f, 30f to 0f, 45f to 40f, 60f to 0f,
            75f to 40f, 90f to 0f, 105f to 40f, 120f to 0f)
        val teeth = teethBase.zipWithNext().flatMap { (a, b) ->
            (0 until 20).map { i ->
                InkPoint(a.first + (b.first - a.first) * i / 20, a.second + (b.second - a.second) * i / 20)
            }
        } + InkPoint(120f, 0f)
        assertFalse(InkGeometry.isScribble(teeth))
        // Loopy cursive: advancing loops that overlap locally but keep moving forward.
        val loops = (0 until 5).flatMap { k ->
            (0 until 30).map { j ->
                val a = j / 30.0 * 2 * PI
                InkPoint((k * 25 + 8 * cos(a) + j / 30.0 * 8).toFloat(), (8 * sin(a)).toFloat())
            }
        }
        assertFalse(InkGeometry.isScribble(loops))
    }

    @Test fun keepsDriftingAndVerticalScrubs() {
        // Crossing out a word drifts forward while scrubbing with a steady amplitude.
        val drift = (0..9).map { i -> InkPoint(i * 6f + (if (i % 2 == 0) 0f else 30f), if (i % 2 == 0) 0f else 12f) }
        assertTrue(InkGeometry.isScribble(drift))
        // A vertical scrub is the same motion turned sideways.
        val vertical = (0..5).map { InkPoint(it * 3f, if (it % 2 == 0) 0f else 60f) }
        assertTrue(InkGeometry.isScribble(vertical))
    }

    @Test fun erasesCrossingsBetweenSamplesAndPreservesDistantInk() {
        val target = stroke(listOf(InkPoint(30f, -20f), InkPoint(30f, 40f)))
        val distant = stroke(listOf(InkPoint(100f, 0f), InkPoint(100f, 40f)))
        assertEquals(listOf(distant), InkGeometry.scribbleErase(listOf(target, distant), stroke(zigzag), 2f))
        assertTrue(InkGeometry.scribbleHits(stroke(zigzag), target.copy(tool = Tool.LINE), 2f))
    }

    @Test fun rejectsShortCursiveBacktracksAndTentativeScrubs() {
        // A connected word with long forward strokes and shorter returning hooks.
        val hooks = listOf(0f, 40f, 22f, 62f, 44f, 84f, 66f, 106f, 88f)
            .mapIndexed { i, x -> InkPoint(x, if (i % 2 == 0) 0f else 8f) }
        assertFalse(InkGeometry.isScribble(hooks))
        assertFalse(InkGeometry.isScribble(zigzag.take(5), sensitivity = 0f))
        for (angle in listOf(0.0, PI / 4, PI / 2)) {
            val rotated = hooks.map { InkPoint(
                (it.x * cos(angle) - it.y * sin(angle)).toFloat(),
                (it.x * sin(angle) + it.y * cos(angle)).toFloat()) }
            assertFalse(InkGeometry.isScribble(rotated))
        }
    }

    @Test fun requiresRepeatedActualCoverageOfEachTarget() {
        val covered = stroke(listOf(InkPoint(30f, -5f), InkPoint(30f, 25f)))
        val nearby = stroke(listOf(InkPoint(65f, 0f), InkPoint(65f, 15f)))
        val singleTouch = stroke(listOf(InkPoint(30f, 0f)))
        val inEmptyPartOfBounds = stroke(listOf(InkPoint(5f, 15f)))
        val targets = listOf(covered, nearby, singleTouch, inEmptyPartOfBounds)
        assertEquals(targets.drop(1), InkGeometry.scribbleErase(targets, stroke(zigzag), 14f))
        assertEquals(listOf(nearby), InkGeometry.scribbleErase(listOf(nearby), stroke(zigzag), 14f))
        assertEquals(targets, InkGeometry.scribbleErase(targets, stroke(zigzag.take(4)), 14f))
        assertTrue(InkGeometry.scribbleErase(emptyList(), stroke(zigzag), 14f).isEmpty())
    }

    @Test fun sensitivityMakesShorterScrubsEasierWithoutRemovingCoverageChecks() {
        val medium = stroke(zigzag.take(5))
        val short = stroke(zigzag.take(4))
        val target = stroke(listOf(InkPoint(30f, -5f), InkPoint(30f, 25f)))
        val nearby = stroke(listOf(InkPoint(65f, 0f), InkPoint(65f, 15f)))
        assertFalse(InkGeometry.isScribble(medium.points, 0f))
        assertTrue(InkGeometry.isScribble(medium.points))
        assertFalse(InkGeometry.isScribble(short.points))
        assertTrue(InkGeometry.isScribble(short.points, 1f))
        assertEquals(listOf(target), InkGeometry.scribbleErase(listOf(target), short, 14f, 0f))
        assertTrue(InkGeometry.scribbleErase(listOf(target), short, 14f, 1f).isEmpty())
        for (sensitivity in listOf(0f, .5f, 1f)) {
            assertEquals(listOf(nearby), InkGeometry.scribbleErase(listOf(nearby), short, 14f, sensitivity))
            assertTrue(InkGeometry.scribbleErase(emptyList(), short, 14f, sensitivity).isEmpty())
            val word = (0..8).map { InkPoint(it * 15f, if (it % 2 == 0) 0f else 40f) }
            assertFalse(InkGeometry.isScribble(word, sensitivity))
            val hooks = listOf(0f, 40f, 22f, 62f, 44f, 84f, 66f, 106f, 88f)
                .mapIndexed { i, x -> InkPoint(x, if (i % 2 == 0) 0f else 8f) }
            assertFalse(InkGeometry.isScribble(hooks, sensitivity))
        }
    }

    @Test fun sensitivityClampsAndAcceptanceIsMonotonic() {
        assertEquals(0f, ScribbleSensitivity.normalize(-1f), 0f)
        assertEquals(1f, ScribbleSensitivity.normalize(2f), 0f)
        assertEquals(ScribbleSensitivity.DEFAULT, ScribbleSensitivity.normalize(Float.NaN), 0f)
        for (count in 4..6) {
            var accepted = false
            for (step in 0..100) {
                val result = InkGeometry.isScribble(zigzag.take(count), step / 100f)
                if (accepted) assertTrue(result)
                accepted = result
            }
        }
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
