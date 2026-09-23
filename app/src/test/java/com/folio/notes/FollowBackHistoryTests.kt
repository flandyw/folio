package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class FollowBackHistoryTests {
    @Test fun cancelledAndClampedRequestsPreservePreviousMovement() {
        val history = FollowBackHistory()
        val original = WritingFollowState(baselineY = 100f)
        history.begin(original)
        history.moved(-20f, -32f)
        val saved = history.entry
        history.begin(WritingFollowState(baselineY = 132f))
        history.moved(0f, 0f)
        history.cancelPending()
        assertEquals(saved, history.entry)
    }

    @Test fun recordsActualPartialMovementAndReplacesOnlyWhenNextGlideMoves() {
        val history = FollowBackHistory()
        history.begin(WritingFollowState(baselineY = 100f))
        history.moved(-5f, -8f)
        history.moved(-3f, -4f)
        history.cancelPending()
        assertEquals(-8f, history.entry!!.x, 0f)
        assertEquals(-12f, history.entry!!.y, 0f)
        val next = WritingFollowState(baselineY = 132f)
        history.begin(next)
        history.moved(0f, -2f)
        assertEquals(FollowBackHistory.Entry(0f, -2f, next), history.entry)
        history.clear()
        assertNull(history.entry)
    }

    @Test fun noMovementDoesNotCreateBackEntry() {
        val history = FollowBackHistory()
        history.begin(WritingFollowState())
        history.moved(0f, 0f)
        history.cancelPending()
        assertNull(history.entry)
    }
}
