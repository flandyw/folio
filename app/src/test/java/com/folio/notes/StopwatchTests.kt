package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

/**
 * The stopwatch: a simple count-up clock that runs independently of the exam
 * countdown timer. Manual start/pause/resume/reset only — no pen hooks, no idle
 * stop — and every transition is unit-testable without a clock.
 */
class StopwatchTests {
    @Test fun aFreshStopwatchIsIdleAndTicksAreNoOps() {
        val idle = StopwatchState()
        assertTrue(idle.idle)
        assertFalse(idle.active)
        assertFalse(idle.running)
        assertFalse(idle.paused)
        assertEquals(0, idle.elapsedSeconds)
        assertEquals("0:00", idle.clockText())
        assertEquals(idle, idle.tick(now = 60_000L))
    }

    @Test fun startCountsUpFromZero() {
        val started = StopwatchState().start(now = 1_000L)
        assertTrue(started.active)
        assertTrue(started.running)
        assertEquals(0, started.elapsedSeconds)
        assertEquals("0:00", started.tick(now = 1_500L).clockText())
        assertEquals(59, started.tick(now = 60_000L).elapsedSeconds)
        assertEquals("0:59", started.tick(now = 60_000L).clockText())
        assertEquals(61, started.tick(now = 62_000L).elapsedSeconds)
        assertEquals("1:01", started.tick(now = 62_000L).clockText())
        assertEquals("1:01:01", started.tick(now = 3_661_000L + 1_000L).clockText())
    }

    @Test fun pauseFreezesTheClockAndResumeCarriesOn() {
        val started = StopwatchState().start(now = 0L)
        val paused = started.pause(now = 61_000L)
        assertTrue(paused.paused)
        assertFalse(paused.running)
        assertFalse(paused.autoParked)
        assertEquals(61, paused.elapsedSeconds)
        // Parked time never counts, however long the break.
        assertEquals(paused, paused.tick(3_000_000L))
        val resumed = paused.unpause(now = 2_000_000L)
        assertTrue(resumed.running)
        assertFalse(resumed.paused)
        assertEquals(61, resumed.elapsedSeconds)
        assertEquals(62, resumed.tick(now = 2_001_000L).elapsedSeconds)
    }

    @Test fun autoParksFreezeAndClearTheirMarkOnResume() {
        val started = StopwatchState().start(now = 0L)
        val parked = started.pause(now = 61_000L, auto = true)
        assertTrue(parked.autoParked)
        val resumed = parked.unpause(now = 70_000L)
        assertFalse(resumed.autoParked)
        assertFalse(resumed.paused)
    }

    @Test fun resetReturnsToIdle() {
        val running = StopwatchState().start(now = 1_000L).tick(now = 61_000L)
        assertEquals(60, running.elapsedSeconds)
        val reset = running.reset()
        assertEquals(StopwatchState(), reset)
        assertTrue(reset.idle)
    }

    @Test fun pausingAnIdleClockIsANoOpAndUnpausingARunningClockIsANoOp() {
        assertEquals(StopwatchState(), StopwatchState().pause(now = 1_000L))
        val running = StopwatchState().start(now = 1_000L)
        assertEquals(running, running.unpause(now = 2_000L))
    }

    @Test fun anUnseenGapParksOnItsOwn() {
        val running = StopwatchState().start(now = 1_000L)
        val parked = running.clampUnseenGap(lastSeen = 21_000L, now = 600_000L)
        assertTrue(parked.paused)
        assertTrue(parked.autoParked)
        // Within the grace period the clock is untouched (a notebook switch restoring moments later).
        assertEquals(running, running.clampUnseenGap(lastSeen = 590_000L, now = 600_000L))
        assertEquals(running, running.clampUnseenGap(lastSeen = null, now = 600_000L))
    }

    @Test fun resumeRebuildsARunningSittingAndDropsNonsense() {
        val running = StopwatchState().start(now = 1_000L)
        val restored = StopwatchState.resume(
            startedAt = running.startedAt, now = 61_000L,
            pausedAt = null, pausedMillis = 0L
        )!!
        assertEquals(60, restored.elapsedSeconds)
        assertTrue(restored.running)
        val paused = running.pause(now = 11_000L)
        val restoredPaused = StopwatchState.resume(
            startedAt = paused.startedAt, now = 500_000L,
            pausedAt = paused.pausedAt, pausedMillis = paused.pausedMillis
        )!!
        assertEquals(paused, restoredPaused)
        assertNull(StopwatchState.resume(null, now = 500_000L))
        assertNull(StopwatchState.resume(0L, now = 500_000L))
        assertNull(StopwatchState.resume(600_000L, now = 500_000L))
    }
}
