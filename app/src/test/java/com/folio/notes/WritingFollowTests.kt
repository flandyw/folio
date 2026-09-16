package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class WritingFollowTests {
    private fun stroke(x: Float, y: Float) = listOf(InkPoint(x, y - 12), InkPoint(x + 8, y))
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
    @Test fun lineAdvanceRequiresFarEdgeAndRespectsSuspension() {
        val follow = WritingFollow()
        follow.completed(stroke(10f, 100f), 0)
        assertTrue(follow.shouldAdvance(.98f, 2f, WritingHand.RIGHT, 0))
        assertFalse(follow.shouldAdvance(.5f, 2f, WritingHand.RIGHT, 0))
        assertTrue(follow.shouldAdvance(.02f, 2f, WritingHand.LEFT, 0))
        assertFalse(follow.shouldAdvance(.5f, 2f, WritingHand.LEFT, 0))
        assertFalse(follow.shouldAdvance(.98f, 1.2f, WritingHand.RIGHT, 0))
        follow.suspend(0)
        assertFalse(follow.shouldAdvance(.98f, 2f, WritingHand.RIGHT, 0))
    }
    @Test fun visibleEdgeDoesNotEndALineWithPageSpaceRemaining() {
        val follow = WritingFollow()
        follow.completed(stroke(10f, 100f), 0)
        for (hand in WritingHand.entries) {
            fun fraction(value: Float) = if (hand == WritingHand.RIGHT) value else 1f - value
            assertFalse(follow.shouldAdvance(fraction(.99f), 2f, hand, 0, fraction(.6f)))
            assertFalse(follow.shouldAdvance(fraction(.8f), 2f, hand, 0, fraction(.98f)))
            assertTrue(follow.shouldAdvance(fraction(.98f), 2f, hand, 0, fraction(.98f)))
            assertFalse(follow.shouldAdvance(fraction(.8f), 2f, hand, 0))
            assertFalse(follow.shouldAdvance(fraction(.94f), 2f, hand, 0))
        }
    }
    @Test fun lineAdvanceWaitsForAPauseBeforeAnimating() {
        val follow = WritingFollow()
        assertEquals(0f, follow.lineAdvanceProgress(1000, 1000), 0f)
        assertEquals(0f, follow.lineAdvanceProgress(1000, 1699), 0f)
        assertEquals(0f, follow.lineAdvanceProgress(1000, 1700), 0f)
        assertEquals(.5f, follow.lineAdvanceProgress(1000, 1840), .001f)
        assertEquals(1f, follow.lineAdvanceProgress(1000, 1980), 0f)
        assertEquals(1f, follow.lineAdvanceProgress(1000, 2500), 0f)
    }
    @Test fun lineStartTracksCurrentLaneAndSpacingIsSane() {
        val follow = WritingFollow()
        assertNull(follow.lineStart(WritingHand.RIGHT))
        follow.completed(stroke(50f, 100f), 0)
        follow.completed(stroke(120f, 102f), 0)
        assertEquals(50f, follow.lineStart(WritingHand.RIGHT)!!, 0f)
        assertTrue(follow.estimateSpacing() >= 48f)
    }
}
