package com.folio.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanGateTests {
    private val gate = PanGate(20f)

    @Test fun deliberatePanMovesFromTheFirstPixel() {
        gate.arm(waitForSlop = false, 100f, 100f)
        assertTrue(gate.moved(101f, 100f))
        assertFalse(gate.waitingForSlop)
    }

    @Test fun aRestingHandIsHeldBackUntilItTravelsLikeADrag() {
        gate.arm(waitForSlop = true, 100f, 100f)
        assertTrue(gate.waitingForSlop)
        // The wander of a hand settling on the page never moves the document.
        assertFalse(gate.moved(104f, 103f))
        assertFalse(gate.moved(95f, 100f))
        assertTrue(gate.waitingForSlop)
        // A finger scroll crosses the slop and pans from the contact's current place.
        assertTrue(gate.moved(130f, 100f))
        assertFalse(gate.waitingForSlop)
        assertTrue(gate.moved(131f, 100f))
    }

    @Test fun aWobblingHandThatReturnsToItsOriginNeverPans() {
        gate.arm(waitForSlop = true, 0f, 0f)
        // Each point stays inside the slop, so the wander never adds up into a drag.
        assertFalse(gate.moved(18f, 0f))
        assertFalse(gate.moved(0f, 18f))
        assertFalse(gate.moved(-10f, -14f))
        assertFalse(gate.moved(12f, -9f))
        assertTrue(gate.waitingForSlop)
    }

    @Test fun thePenOrASecondFingerReleasesTheHold() {
        gate.arm(waitForSlop = true, 100f, 100f)
        gate.release()
        assertFalse(gate.waitingForSlop)
        assertTrue(gate.moved(100f, 100f))
    }

    @Test fun aNewGestureIsArmedAfresh() {
        gate.arm(waitForSlop = true, 100f, 100f)
        assertTrue(gate.moved(200f, 100f))
        gate.arm(waitForSlop = true, 300f, 50f)
        assertTrue(gate.waitingForSlop)
        assertFalse(gate.moved(308f, 50f))
        assertTrue(gate.moved(340f, 50f))
    }
}
