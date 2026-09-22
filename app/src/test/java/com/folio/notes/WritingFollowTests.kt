package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class WritingFollowTests {
    private fun stroke(x: Float, y: Float) = listOf(InkPoint(x, y - 12), InkPoint(x + 8, y))
    @Test fun edgeColumnsKeepADeadBandAndMirrorExactly() {
        val follow = WritingFollow()
        assertEquals(0f, follow.horizontalShift(.78f, .75f, WritingDirection.LTR), 0f)
        val shift = follow.horizontalShift(.94f, .75f, WritingDirection.LTR)
        assertTrue(shift < 0f)
        assertEquals(-shift, follow.horizontalShift(.06f, .25f, WritingDirection.RTL), .001f)
        assertEquals(0f, follow.horizontalShift(.7f, .5f, WritingDirection.LTR), 0f)
        assertEquals(0f, follow.horizontalShift(Float.NaN, .5f, WritingDirection.LTR), 0f)
    }
    @Test fun learnsPenUpRhythmWithoutCountingTimeSpentDrawing() {
        val follow = WritingFollow()
        var now = 0L
        follow.completed(stroke(20f, 100f), now)
        repeat(6) {
            now += 800
            follow.penDown(now)
            now += 3000 // A long stroke is not a long pen-up pause.
            follow.completed(stroke(40f + it * 20, 100f), now)
        }
        assertEquals(1000, follow.sameLineDelayMs(650))
        assertEquals(1800, follow.sameLineDelayMs(1800))
        follow.penDown(now + 10000)
        follow.completed(stroke(200f, 100f), now + 11000)
        assertEquals(1000, follow.sameLineDelayMs(650))
        follow.suspend(now + 12000)
        assertEquals(650, follow.sameLineDelayMs(650))
    }
    @Test fun correctionsCannotEraseTheFrontierFromMemory() {
        val follow = WritingFollow()
        follow.completed(stroke(300f, 100f), 0)
        repeat(20) { follow.completed(stroke(20f + it, 100f), it.toLong()) }
        assertFalse(follow.progresses(stroke(200f, 100f), WritingDirection.LTR))
        assertTrue(follow.progresses(stroke(320f, 100f), WritingDirection.LTR))
    }
    @Test fun unrelatedLowerMarksDoNotEnterTheBaselineMedian() {
        val follow = WritingFollow()
        follow.completed(stroke(20f, 100f), 0)
        repeat(10) {
            follow.completed(stroke(30f, 160f), 0)
            follow.completed(stroke(40f, 100f), 0)
        }
        assertEquals(100f, follow.state.baselineY!!, 0f)
        assertTrue(follow.state.recent.all { it.bottom == 100f })
    }
    @Test fun revisitingEarlierLineDoesNotUndoAnAutomaticReturn() {
        val follow = WritingFollow()
        follow.arrived(WritingAdvance(WritingGuide(20f, 400f, 100f), WritingGuide(20f, 400f, 140f)))
        repeat(4) { follow.completed(stroke(380f, 100f), 0) }
        assertEquals(140f, follow.state.baselineY!!, 0f)
        assertFalse(follow.progresses(stroke(390f, 100f), WritingDirection.LTR))
        assertTrue(follow.progresses(stroke(20f, 140f), WritingDirection.LTR))
    }
    @Test fun sameLineFollowWaitsThroughWordGapsAndRespectsLongerPause() {
        val follow = WritingFollow()
        assertEquals(500, follow.sameLineDelayMs(300))
        assertEquals(650, follow.sameLineDelayMs(650))
        assertEquals(1500, follow.sameLineDelayMs(1500))
        assertEquals(2000, follow.sameLineDelayMs(Int.MAX_VALUE))
    }
    @Test fun correctionsDoNotCountAsForwardWritingInEitherDirection() {
        val follow = WritingFollow()
        follow.completed(stroke(100f, 100f), 0)
        assertTrue(follow.progresses(stroke(115f, 100f), WritingDirection.LTR))
        assertFalse(follow.progresses(stroke(90f, 100f), WritingDirection.LTR))
        assertFalse(follow.progresses(stroke(101f, 100f), WritingDirection.LTR))
        assertTrue(follow.progresses(stroke(80f, 100f), WritingDirection.RTL))
        assertFalse(follow.progresses(stroke(115f, 100f), WritingDirection.RTL))
        assertFalse(follow.progresses(emptyList(), WritingDirection.LTR))
    }
    @Test fun isolatedLowerStrokeDoesNotTriggerFollowButNewLaneCanProgress() {
        val follow = WritingFollow()
        follow.completed(stroke(100f, 100f), 0)
        assertFalse(follow.progresses(stroke(120f, 150f), WritingDirection.LTR))
        follow.completed(stroke(20f, 150f), 1)
        follow.completed(stroke(35f, 152f), 2)
        assertTrue(follow.progresses(stroke(50f, 152f), WritingDirection.LTR))
    }
    @Test fun medianBaselineRejectsTallOutliers() {
        val follow = WritingFollow()
        listOf(100f, 102f, 101f).forEach { follow.completed(stroke(20f, it), 0) }
        follow.completed(listOf(InkPoint(30f, 20f), InkPoint(40f, 250f)), 0)
        assertEquals(101f, follow.state.baselineY!!, 0f)
    }
    @Test fun nextLineNeedsTwoStrokesAndDescendersDoNotMoveLane() {
        val follow = WritingFollow()
        follow.completed(stroke(20f, 100f), 0)
        follow.completed(stroke(40f, 110f), 0)
        follow.completed(stroke(10f, 150f), 0)
        assertTrue(follow.state.baselineY!! < 130f)
        follow.completed(stroke(25f, 152f), 0)
        assertEquals(152f, follow.state.baselineY!!, 0f)
    }
    @Test fun mirroredTriggersAreSmoothAndRequireZoomAndProgress() {
        val follow = WritingFollow()
        follow.completed(stroke(10f, 100f), 0)
        fun v(x: Float, hand: WritingHand = WritingHand.RIGHT, zoom: Float = 2f, moving: Boolean = true) =
            follow.horizontalVelocity(x, zoom, hand, moving, 0)
        assertEquals(0f, v(.6f), 0f)
        assertTrue(v(.7f) < 0f)
        assertEquals(-v(.9f), v(.1f, WritingHand.LEFT), .001f)
        assertTrue(v(.99f) < v(.85f))
        assertEquals(0f, v(.99f, zoom = 1.39f), 0f)
        assertEquals(0f, v(.99f, moving = false), 0f)
    }
    @Test fun manualInterventionRequiresTimeoutAndFreshInk() {
        val follow = WritingFollow()
        follow.completed(stroke(0f, 100f), 0)
        follow.suspend(100)
        follow.completed(stroke(0f, 140f), 1599)
        assertNull(follow.state.baselineY)
        follow.completed(stroke(0f, 140f), 1600)
        assertEquals(140f, follow.state.baselineY!!, 0f)
        assertTrue(follow.horizontalVelocity(.95f, 2f, WritingHand.RIGHT, true, 1600) < 0)
    }
    @Test fun verticalBandHasNoMicroCorrections() {
        val follow = WritingFollow()
        assertEquals(0f, follow.verticalVelocity(.6f, 2f, 0), 0f)
        assertTrue(follow.verticalVelocity(.9f, 2f, 0) < 0)
        assertTrue(follow.verticalVelocity(.2f, 2f, 0) > 0)
        assertEquals(0f, follow.verticalVelocity(.9f, 1f, 0), 0f)
    }
    private val guides = listOf(WritingGuide(50f, 400f, 100f), WritingGuide(60f, 390f, 132f),
        WritingGuide(60f, 390f, 160f))
    @Test fun returnsAtPrintedEndpointToActualNextStartAndSpacing() {
        val follow = WritingFollow()
        val advance = follow.advanceFor(stroke(390f, 100f), guides, 2f, WritingHand.RIGHT, 0)!!
        assertEquals(guides[0], advance.from)
        assertEquals(guides[1], advance.to)
        assertEquals(60f, advance.startX(WritingHand.RIGHT), 0f)
        assertEquals(32f, advance.to.y - advance.from.y, 0f)
        assertNull(follow.advanceFor(stroke(360f, 100f), guides, 2f, WritingHand.RIGHT, 0))
        val left = follow.advanceFor(stroke(50f, 100f), guides, 2f, WritingHand.LEFT, 0)!!
        assertEquals(390f, left.startX(WritingHand.LEFT), 0f)
    }
    @Test fun neverInventsALineOnBlankPagesOrAfterLastRule() {
        val follow = WritingFollow()
        assertNull(follow.advanceFor(stroke(390f, 100f), emptyList(), 2f, WritingHand.RIGHT, 0))
        assertNull(follow.advanceFor(stroke(382f, 160f), guides, 2f, WritingHand.RIGHT, 0))
        assertNull(follow.advanceFor(stroke(390f, 70f), guides, 2f, WritingHand.RIGHT, 0))
        assertNull(follow.advanceFor(listOf(InkPoint(390f, 40f), InkPoint(400f, 100f)), guides, 2f, WritingHand.RIGHT, 0))
    }
    @Test fun manualPanAndLowZoomSuppressPrintedReturns() {
        val follow = WritingFollow()
        assertNull(follow.advanceFor(stroke(390f, 100f), guides, 1f, WritingHand.RIGHT, 0))
        follow.suspend(100)
        assertNull(follow.advanceFor(stroke(390f, 100f), guides, 2f, WritingHand.RIGHT, 1599))
        assertNotNull(follow.advanceFor(stroke(390f, 100f), guides, 2f, WritingHand.RIGHT, 1600))
    }
    @Test fun completedReturnTracksDestinationAndDoesNotRepeatForDotsOrCrosses() {
        val follow = WritingFollow()
        val advance = follow.advanceFor(stroke(390f, 100f), guides, 2f, WritingHand.RIGHT, 0)!!
        follow.arrived(advance)
        assertEquals(132f, follow.state.baselineY!!, 0f)
        assertNull(follow.advanceFor(stroke(390f, 100f), guides, 2f, WritingHand.RIGHT, 1))
        assertNotNull(follow.advanceFor(stroke(382f, 132f), guides, 2f, WritingHand.RIGHT, 1))
    }
    @Test fun printedReturnAnimatesWithoutTheOldArbitraryDelay() {
        val follow = WritingFollow()
        assertEquals(0f, follow.lineAdvanceProgress(1000, 1000), 0f)
        assertEquals(.5f, follow.lineAdvanceProgress(1000, 1140), .001f)
        assertEquals(1f, follow.lineAdvanceProgress(1000, 1280), 0f)
    }
}
