package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

/**
 * The smart timer: the clock starts itself on a pen-down, stops itself after a user-set stretch of
 * pen idleness, and keeps the other park conditions (hidden editor, unseen gaps) exactly as they
 * were. Any paused clock is started again by the pen while the auto-start setting is on.
 */
class SmartTimerTests {
    private val preset = ExamTimerPreset("Auto · 10 min", 10 * 60, 0)

    @Test fun aFirstStrokeStartsAFreshSittingAndOtherTouchesDoNot() {
        val idle = ExamTimerState()
        assertEquals(TimerAutoAction.START, idle.autoActionOnPenDown(autoStart = true, beginsStroke = true))
        // Erasing, a stroke still growing, or the switch being off never starts a sitting.
        assertEquals(TimerAutoAction.NONE, idle.autoActionOnPenDown(autoStart = true, beginsStroke = false))
        assertEquals(TimerAutoAction.NONE, idle.autoActionOnPenDown(autoStart = false, beginsStroke = true))
        // A running clock is left alone, and a finished one is never restarted by a stray stroke.
        val running = idle.start(preset, now = 1000L)
        assertEquals(TimerAutoAction.NONE, running.autoActionOnPenDown(autoStart = true, beginsStroke = true))
        val done = running.tick(now = 700_000L)
        assertEquals(ExamTimerPhase.DONE, done.phase)
        assertEquals(TimerAutoAction.NONE, done.autoActionOnPenDown(autoStart = true, beginsStroke = true))
    }

    @Test fun aStrokeStartsAPausedClockAgainHoweverItWasParked() {
        val running = ExamTimerState().start(preset, now = 1000L)
        val autoParked = running.pause(61_000L, auto = true)
        assertTrue(autoParked.autoParked)
        assertEquals(TimerAutoAction.RESUME, autoParked.autoActionOnPenDown(autoStart = true, beginsStroke = true))
        val byHand = running.pause(61_000L)
        assertFalse(byHand.autoParked)
        assertEquals(TimerAutoAction.RESUME, byHand.autoActionOnPenDown(autoStart = true, beginsStroke = true))
        // Resuming clears the mark, so the next park is judged afresh.
        val resumed = autoParked.unpause(70_000L)
        assertFalse(resumed.paused)
        assertFalse(resumed.autoParked)
    }

    @Test fun theClockStopsItselfAfterTheUserSetIdleness() {
        val running = ExamTimerState().start(preset, now = 0L)
        // Just under five minutes of quiet is still a working pause; past it the clock stops.
        assertFalse(running.idleExpired(lastActivityAt = 1_000L, now = 299_000L, idleMinutes = 5))
        assertTrue(running.idleExpired(lastActivityAt = 1_000L, now = 301_000L, idleMinutes = 5))
        // Zero minutes keeps the clock running until something else parks it.
        assertFalse(running.idleExpired(lastActivityAt = 1_000L, now = 301_000L, idleMinutes = 0))
        // Without a known last touch, or on a clock that is not running, nothing stops.
        assertFalse(running.idleExpired(lastActivityAt = 0L, now = 301_000L, idleMinutes = 5))
        val parked = running.pause(10_000L, auto = true)
        assertFalse(parked.idleExpired(lastActivityAt = 1_000L, now = 301_000L, idleMinutes = 5))
        val done = running.tick(700_000L)
        assertFalse(done.idleExpired(lastActivityAt = 1_000L, now = 800_000L, idleMinutes = 5))
    }

    @Test fun anIdleStopParksTheClockSoTheNextStrokeContinuesWhereItStopped() {
        val running = ExamTimerState().start(preset, now = 0L)
        val parked = running.pause(301_000L, auto = true)
        // Parked time never counts, however long the break.
        assertEquals(parked, parked.tick(3_000_000L))
        assertEquals(running.tick(301_000L).remaining, parked.remaining)
        // Resuming carries on from where the idle stop froze the clock.
        val resumed = parked.unpause(2_000_000L)
        assertEquals(parked.remaining, resumed.remaining)
        assertEquals(parked.remaining - 1, resumed.tick(2_001_000L).remaining)
    }

    @Test fun parkedReasonsSurviveARestart() {
        val autoParked = ExamTimerState().start(preset, now = 1000L).pause(61_000L, auto = true)
        val restored = ExamTimerState.resume(autoParked.preset, autoParked.startedAt, now = 500_000L,
            pausedAt = autoParked.pausedAt, pausedMillis = autoParked.pausedMillis, parkAuto = true)!!
        assertEquals(autoParked, restored)
        assertEquals(TimerAutoAction.RESUME, restored.autoActionOnPenDown(autoStart = true, beginsStroke = true))
        val byHand = ExamTimerState().start(preset, now = 1000L).pause(61_000L)
        val handRestored = ExamTimerState.resume(byHand.preset, byHand.startedAt, now = 500_000L,
            pausedAt = byHand.pausedAt, pausedMillis = byHand.pausedMillis)!!
        assertEquals(byHand, handRestored)
        assertEquals(TimerAutoAction.RESUME, handRestored.autoActionOnPenDown(autoStart = true, beginsStroke = true))
    }

    @Test fun anUnseenGapStillParksOnItsOwnAndThePenCanStartItAgain() {
        val running = ExamTimerState().start(preset, now = 1000L)
        val parked = running.clampUnseenGap(lastSeen = 21_000L, now = 600_000L)
        assertTrue(parked.paused)
        assertTrue(parked.autoParked)
        assertEquals(TimerAutoAction.RESUME, parked.autoActionOnPenDown(autoStart = true, beginsStroke = true))
    }

    @Test fun aManualPauseIsStartedAgainByAPenStroke() {
        val paused = ExamTimerState().start(preset, now = 1000L).pause(61_000L)
        assertEquals(TimerAutoAction.RESUME, paused.autoActionOnPenDown(autoStart = true, beginsStroke = true))
        // Stopping still clears the whole sitting, so the next stroke starts afresh.
        val stopped = paused.stop()
        assertEquals(ExamTimerPhase.IDLE, stopped.phase)
        assertFalse(stopped.autoParked)
        assertEquals(TimerAutoAction.START, stopped.autoActionOnPenDown(autoStart = true, beginsStroke = true))
    }
}
