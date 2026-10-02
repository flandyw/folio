package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class WritingFollowTests {
    private fun letter(x: Float, y: Float, height: Float = 20f) =
        listOf(InkPoint(x, y - height), InkPoint(x + 10f, y))

    @Test fun comfortPreservesPersonalChoicesAndReportsCustomChanges() {
        val original = FollowPreferences(direction = WritingDirection.RTL, mode = FollowMode.MATH,
            automaticReturn = true, position = .4f, horizontalFollow = false, autoSwitchAreas = false)
        val balanced = FollowComfort.apply(original, .5f)
        assertEquals(original.direction, balanced.direction)
        assertEquals(original.mode, balanced.mode)
        assertEquals(original.automaticReturn, balanced.automaticReturn)
        assertEquals(original.position, balanced.position)
        assertFalse(balanced.horizontalFollow)
        assertFalse(balanced.autoSwitchAreas)
        assertTrue(FollowComfort.matches(balanced, .5f))
        assertFalse(FollowComfort.matches(balanced.copy(glideDurationMs = 700), .5f))
        val relaxed = FollowComfort.apply(original, 0f)
        val responsive = FollowComfort.apply(original, 1f)
        assertTrue(relaxed.returnDelayMs > responsive.returnDelayMs)
        assertTrue(relaxed.glideDurationMs > responsive.glideDurationMs)
        assertTrue(relaxed.edgeThreshold > responsive.edgeThreshold)
        assertTrue(relaxed.verticalDeadBand > responsive.verticalDeadBand)
    }

    @Test fun faintDashedRulesKeepColumnsSeparate() {
        val width = 500
        val pixels = IntArray(width * 200) { -1 }
        for (y in listOf(50, 78, 106)) {
            for (range in listOf(30..220, 280..470)) {
                for (x in range) if ((x - range.first) % 10 < 6) pixels[y * width + x] = 0xffcccccc.toInt()
            }
        }
        val guides = WritingGuides.detect(pixels, width, 200, 500f, 200f)
        assertEquals(6, guides.size)
        val regions = WritingGuides.regions(guides)
        assertEquals(2, regions.size)
        assertNull(WritingGuides.regionAt(regions, 250f, 78f))
    }

    @Test fun tableBordersAndIsolatedUnderlinesAreRejected() {
        val width = 300
        val pixels = IntArray(width * 200) { -1 }
        for (y in listOf(40, 68, 96)) for (x in 20..250) pixels[y * width + x] = 0xff000000.toInt()
        for (y in 40..96) for (x in listOf(20, 250)) pixels[y * width + x] = 0xff000000.toInt()
        for (x in 20..250) pixels[170 * width + x] = 0xff000000.toInt()
        assertTrue(WritingGuides.detect(pixels, width, 200, 300f, 200f).isEmpty())
    }

    @Test fun dotsAndInvalidCoordinatesDoNotChangeLane() {
        val follow = WritingFollow()
        follow.completed(listOf(InkPoint(20f, 80f), InkPoint(30f, 100f)), 1)
        val before = follow.state
        follow.completed(listOf(InkPoint(70f, 90f), InkPoint(71f, 91f)), 2)
        assertEquals(before, follow.state)
        follow.completed(listOf(InkPoint(Float.NaN, 100f)), 3)
        assertEquals(before, follow.state)
        assertFalse(follow.progresses(listOf(InkPoint(Float.NaN, 100f)), WritingDirection.LTR))
    }

    @Test fun triggerMirrorsDirectionAndFixedTimingRespectsChoice() {
        val follow = WritingFollow()
        assertEquals(300, follow.sameLineDelayMs(300, adaptive = false))
        assertEquals(0f, follow.horizontalShift(.8f, .5f, WritingDirection.LTR, .9f))
        val left = follow.horizontalShift(.8f, .5f, WritingDirection.LTR, .7f)
        val right = follow.horizontalShift(.2f, .5f, WritingDirection.RTL, .7f)
        assertTrue(left < 0f)
        assertEquals(-left, right, .0001f)
    }

    @Test fun closeNaturalLinesAreConfirmedAndTeachTheirSpacing() {
        val follow = WritingFollow()
        follow.completed(letter(100f, 100f), 0)
        follow.completed(letter(160f, 100f), 100)
        assertEquals(WritingProgress.NONE, follow.completed(letter(100f, 120f), 200))
        assertEquals(100f, follow.state.baselineY!!, 0f)
        assertEquals(WritingProgress.NEW_LINE, follow.completed(letter(120f, 120f), 300))
        assertEquals(120f, follow.state.baselineY!!, 0f)
        // One observed gap is insufficient to override the selected starting spacing.
        assertEquals(32f, follow.lineSpacing(32f), 0f)
        follow.completed(letter(100f, 140f), 400)
        assertEquals(WritingProgress.NEW_LINE, follow.completed(letter(120f, 140f), 500))
        assertEquals(20f, follow.lineSpacing(32f), 0f)
        assertEquals(32f, follow.lineSpacing(32f, adaptive = false), 0f)
    }

    @Test fun rightToLeftLinesConfirmInWritingDirectionAndRememberTheirStart() {
        val follow = WritingFollow()
        val prefs = FollowPreferences(direction = WritingDirection.RTL)
        follow.completed(letter(500f, 100f), 0, prefs)
        follow.completed(letter(450f, 100f), 100, prefs)
        follow.completed(letter(500f, 128f), 200, prefs)
        // Revisiting the same symbol below a line does not confirm a new line.
        assertEquals(WritingProgress.NONE, follow.completed(letter(500f, 128f), 250, prefs))
        assertEquals(WritingProgress.NEW_LINE, follow.completed(letter(480f, 128f), 300, prefs))
        assertEquals(510f, follow.state.lineStartX!!, 0f)
        assertEquals(480f, follow.state.frontierLeft!!, 0f)
    }

    @Test fun correctionsDoNotTeachRhythmOrShiftTheWritingLane() {
        val follow = WritingFollow()
        follow.completed(letter(20f, 100f), 0)
        follow.completed(letter(100f, 100f), 100)
        val before = follow.state
        follow.penDown(900)
        assertEquals(WritingProgress.NONE, follow.completed(letter(25f, 105f), 1000))
        assertEquals(before.baselineY, follow.state.baselineY)
        assertEquals(before.recent, follow.state.recent)
        assertEquals(before.frontierRight, follow.state.frontierRight)
        assertEquals(before.writingGaps, follow.state.writingGaps)
    }

    @Test fun consecutiveDescendersDoNotPullTheBaselineOrLearnALineBreak() {
        val follow = WritingFollow()
        follow.completed(letter(20f, 100f), 0)
        follow.completed(letter(50f, 100f), 100)
        repeat(4) { index ->
            assertEquals(WritingProgress.SAME_LINE,
                follow.completed(letter(80f + index * 20, 122f, height = 28f), 200L + index * 100))
        }
        assertEquals(100f, follow.state.baselineY!!, 0f)
        assertTrue(follow.state.lineSpacings.isEmpty())
        assertNull(follow.state.candidateLane)
    }

    @Test fun aDescenderAfterLineReturnKeepsTheKnownBaselineWithoutAnEmptyMedian() {
        val follow = WritingFollow()
        follow.arrived(WritingAdvance(WritingGuide(36f, 804f, 100f), WritingGuide(36f, 804f, 128f)))
        assertEquals(WritingProgress.SAME_LINE, follow.completed(letter(40f, 148f, height = 28f), 100))
        assertEquals(128f, follow.state.baselineY!!, 0f)
        assertEquals(40f, follow.state.lineStartX!!, 0f)
        assertEquals(WritingProgress.SAME_LINE, follow.completed(letter(60f, 128f), 200))
    }

    @Test fun isolatedSubscriptsAndStaleCandidatesCannotConfirmALine() {
        val follow = WritingFollow()
        follow.completed(letter(20f, 100f), 0)
        follow.completed(letter(60f, 128f), 100)
        // Returning to the old line discards the lower mark.
        follow.completed(letter(80f, 100f), 200)
        assertNull(follow.state.candidateLane)
        follow.completed(letter(100f, 128f), 300)
        assertEquals(WritingProgress.NONE, follow.completed(letter(120f, 128f), 5000))
        assertEquals(100f, follow.state.baselineY!!, 0f)
    }

    @Test fun smallLoweredSymbolsDoNotTurnTextIntoANewLine() {
        val follow = WritingFollow()
        follow.completed(letter(20f, 100f), 0)
        follow.completed(letter(50f, 100f), 100)
        repeat(3) { index ->
            assertEquals(WritingProgress.NONE,
                follow.completed(letter(80f + index * 12, 120f, height = 8f), 200L + index * 100))
        }
        assertEquals(100f, follow.state.baselineY!!, 0f)
        assertNull(follow.state.candidateLane)
    }

    @Test fun aSkippedLineDoesNotDoubleTheLearnedSpacing() {
        val follow = WritingFollow()
        follow.completed(letter(20f, 100f), 0)
        for ((index, y) in listOf(128f, 156f, 212f).withIndex()) {
            follow.completed(letter(20f, y), 100L + index * 200)
            follow.completed(letter(40f, y), 200L + index * 200)
        }
        assertEquals(listOf(28f, 28f, 56f), follow.state.lineSpacings)
        assertEquals(28f, follow.lineSpacing(32f), 0f)
    }

    @Test fun mathsTracksTallAndWideWorkingButHoldsForEarlierCorrections() {
        val follow = WritingFollow()
        val prefs = FollowPreferences(mode = FollowMode.MATH)
        val fraction = listOf(InkPoint(80f, 100f), InkPoint(400f, 180f))
        assertEquals(WritingProgress.SAME_LINE, follow.completed(fraction, 0, prefs))
        assertEquals(180f, follow.state.baselineY!!, 0f)
        val before = follow.state
        assertEquals(WritingProgress.NONE, follow.completed(letter(100f, 150f), 100, prefs))
        assertEquals(before, follow.state)
        assertEquals(WritingProgress.SAME_LINE, follow.completed(letter(100f, 220f), 200, prefs))
        assertFalse(follow.readyForReturn())
        assertTrue(follow.state.lineSpacings.isEmpty())
    }

    @Test fun navigationKeepsLearnedRhythmAndSpacingButReleasesTheOldLane() {
        val follow = WritingFollow()
        follow.state = WritingFollowState(baselineY = 100f, writingGaps = listOf(850, 900, 950, 1000),
            lineSpacings = listOf(28f, 28f))
        assertEquals(1150, follow.sameLineDelayMs(400))
        follow.suspend(100)
        assertNull(follow.state.baselineY)
        assertEquals(1150, follow.sameLineDelayMs(400))
        assertEquals(28f, follow.lineSpacing(32f), 0f)
        assertEquals(WritingProgress.NONE, follow.completed(letter(20f, 100f), 200))
    }

    @Test fun blankPageReturnsToTheWritingStartAndPrintedRulesKeepTheirMargins() {
        val region = WritingLane(36f, 0f, 804f, 200f)
        val blank = FollowNavigation.next(100f, region, emptyList(), 28f, lineStartX = 120f)!!
        assertEquals(120f, blank.to.left, 0f)
        assertEquals(128f, blank.to.y, 0f)
        val rtl = FollowNavigation.next(100f, region, emptyList(), 28f, 700f, WritingDirection.RTL)!!
        assertEquals(700f, rtl.to.right, 0f)
        val rules = listOf(WritingGuide(36f, 804f, 100f), WritingGuide(36f, 804f, 128f))
        val ruled = FollowNavigation.next(100f, region, rules, 40f, 120f)!!
        assertEquals(rules[1], ruled.to)
        assertNull(FollowNavigation.next(128f, region, rules, 28f, 120f))
    }

    @Test fun dotsAndALoneEdgeMarkCannotArmAnAutomaticReturn() {
        val follow = WritingFollow()
        follow.completed(listOf(InkPoint(790f, 99f), InkPoint(791f, 100f)), 0)
        assertNull(follow.state.baselineY)
        follow.completed(letter(780f, 100f), 100)
        assertFalse(follow.readyForReturn())
        follow.completed(letter(800f, 100f), 200)
        assertFalse(follow.readyForReturn())
        follow.completed(letter(820f, 100f), 300)
        assertTrue(follow.readyForReturn())
    }

    @Test fun cancelledAndClampedGlidesKeepBackForTheLastActualMovement() {
        val history = FollowBackHistory()
        val before = WritingFollowState(baselineY = 100f)
        history.begin(before)
        history.moved(-40f, -20f)
        val last = history.entry
        history.begin(WritingFollowState(baselineY = 128f))
        history.moved(0f, 0f)
        history.cancelPending()
        assertEquals(last, history.entry)
        history.begin(WritingFollowState(baselineY = 156f))
        history.cancelPending()
        assertEquals(last, history.entry)
    }
}
