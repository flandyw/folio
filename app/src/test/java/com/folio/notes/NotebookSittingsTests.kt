package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class NotebookSittingsTests {
    private val preset = ExamTimerPreset("Practice", 600, 0)

    @Test fun switchingPreservesIndependentRunningAndPausedClocks() {
        val sittings = NotebookSittings()
        sittings.save("a", ExamTimerState().start(preset, 1000), null)
        val paused = ExamTimerState().start(preset, 2000).pause(12000)
        sittings.save("b", paused, null)

        assertEquals(580, sittings.restore("a", 21000)!!.timer.remaining)
        assertEquals(paused, sittings.restore("b", 21000)!!.timer)
        assertNull(sittings.restore("new", 21000))
        assertNull(sittings.restore(null, 21000))
    }

    @Test fun stoppingOneNotebookDoesNotStopAnotherOrShareItsResult() {
        val sittings = NotebookSittings()
        val running = ExamTimerState().start(preset, 1000)
        sittings.save("a", running.stop(), 20)
        sittings.save("b", running, null)

        assertEquals(ExamTimerPhase.IDLE, sittings.restore("a", 31000)!!.timer.phase)
        assertEquals(20, sittings.restore("a", 31000)!!.seconds)
        assertEquals(570, sittings.restore("b", 31000)!!.timer.remaining)
        assertNull(sittings.restore("b", 31000)!!.seconds)
        sittings.consumeResult("b")
        assertEquals(20, sittings.restore("a", 31000)!!.seconds)
        sittings.consumeResult("a")
        assertNull(sittings.restore("a", 31000)!!.seconds)
    }

    @Test fun aTimerCanFinishWhileAnotherNotebookIsOpen() {
        val sittings = NotebookSittings()
        sittings.save("a", ExamTimerState().start(preset, 1000), null)
        assertEquals(ExamTimerPhase.DONE, sittings.restore("a", 601000)!!.timer.phase)
    }

    @Test fun aPausedTimerDoesNotFinishWhileItsNotebookIsNotOpen() {
        val sittings = NotebookSittings()
        // selectNotebookTimer pauses on leave, so the background sitting is stored paused.
        val pausedOnLeave = ExamTimerState().start(preset, 1000).pause(21000)
        sittings.save("a", pausedOnLeave, null)
        val restored = sittings.restore("a", 601000)!!.timer
        assertTrue(restored.paused)
        assertEquals(pausedOnLeave.remaining, restored.remaining)
        assertNotEquals(ExamTimerPhase.DONE, restored.phase)
    }
}
