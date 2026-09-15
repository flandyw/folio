package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchChordTests {
    private val chord = TouchChord(10f)

    @Test fun ordinaryTapDoesNotUndo() {
        chord.down(0, 0f, 0f, 0)
        assertEquals(0, chord.finish(100))
    }

    @Test fun stationaryTwoAndThreeFingerTapsAreRecognized() {
        for (count in 2..3) {
            chord.down(0, 0f, 0f, 0)
            for (id in 1 until count) chord.join(id, id * 100f, 0f, 50)
            chord.move(0, 3f, 4f)
            assertEquals(count, chord.finish(160))
        }
    }

    @Test fun pinchWithStationaryCentroidIsNotUndo() {
        chord.down(0, 0f, 0f, 0)
        chord.join(1, 100f, 0f, 50)
        chord.move(0, -20f, 0f)
        chord.move(1, 120f, 0f)
        assertEquals(0, chord.finish(150))
    }

    @Test fun movementCannotBecomeTapByReturningToOrigin() {
        chord.down(0, 0f, 0f, 0)
        chord.move(0, 25f, 0f)
        chord.move(0, 0f, 0f)
        chord.join(1, 100f, 0f, 50)
        assertEquals(0, chord.finish(150))
    }

    @Test fun longHoldOrLateSecondFingerDoesNotUndo() {
        chord.down(0, 0f, 0f, 0)
        chord.join(1, 100f, 0f, 50)
        assertEquals(0, chord.finish(500))
        chord.down(0, 0f, 0f, 0)
        chord.join(1, 100f, 0f, 300)
        assertEquals(0, chord.finish(350))
    }

    @Test fun fourthFingerCancelsChord() {
        chord.down(0, 0f, 0f, 0)
        for (id in 1..3) chord.join(id, id * 100f, 0f, 50)
        assertEquals(0, chord.finish(150))
    }

    @Test fun cancellationAndNewGestureForgetPriorFingers() {
        chord.down(0, 0f, 0f, 0)
        chord.join(1, 100f, 0f, 50)
        chord.reset()
        assertEquals(0, chord.finish(100))
        chord.down(5, 0f, 0f, 200)
        assertEquals(0, chord.finish(300))
    }
}
