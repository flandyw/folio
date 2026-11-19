package com.folio.notes.exam

import org.junit.Assert.*
import org.junit.Test

class ExamTimerTest {
    private val start = 1_000L
    private fun timer(writing: Int = 600, reading: Int = 0) =
        ExamTimerState().start(ExamTimerPreset("Test", writing, reading), start)

    @Test fun warningThresholdDependsOnWritingDuration() {
        listOf(-1 to 0, 0 to 0, 60 to 0, 61 to 60, 300 to 60, 301 to 300, 7200 to 300).forEach { (duration, expected) ->
            assertEquals(expected, ExamTimerState.finalWarningSeconds(duration))
            assertEquals(expected, timer(writing = duration).finalWarningSeconds)
        }
        assertFalse(timer(writing = 60).inFinalMinutes)
    }

    @Test fun tickingCrossesWarningExactlyOnce() {
        val before = timer().tick(start + 299_000)
        val warning = before.tick(start + 300_000)
        val after = warning.tick(start + 301_000)
        assertEquals(301, before.remaining)
        assertFalse(before.inFinalMinutes)
        assertEquals(300, warning.remaining)
        assertTrue(warning.inFinalMinutes)
        assertTrue(warning.crossedFinalWarning(before))
        assertFalse(after.crossedFinalWarning(warning))
        assertFalse(warning.crossedFinalWarning(warning))
    }

    @Test fun readingNeverWarnsAndTransitionIntoWindowCrosses() {
        val reading = timer(writing = 300, reading = 15)
        assertFalse(reading.inFinalMinutes)
        assertFalse(reading.crossedFinalWarning(reading))
        val nearEnd = reading.tick(start + 14_000)
        val adjusted = nearEnd.adjust(-1, start + 14_000)
        assertEquals(ExamTimerPhase.WRITING, adjusted.phase)
        assertFalse(adjusted.inFinalMinutes)
        val writingWarning = timer().tick(start + 300_000)
        val previousReading = writingWarning.copy(phase = ExamTimerPhase.READING)
        assertFalse(previousReading.inFinalMinutes)
        assertTrue(writingWarning.crossedFinalWarning(previousReading))
    }

    @Test fun adjustingTimeRearmsWarningAndCanCrossIntoWindow() {
        val now = start + 400_000
        val warning = timer().tick(now)
        val extended = warning.adjust(300, now)
        assertTrue(warning.inFinalMinutes)
        assertEquals(500, extended.remaining)
        assertFalse(extended.inFinalMinutes)
        val shortened = extended.adjust(-300, now)
        assertTrue(shortened.crossedFinalWarning(extended))
        assertFalse(shortened.tick(now + 1000).crossedFinalWarning(shortened))
    }

    @Test fun pausedWarningDisplaysWithoutCrossing() {
        val before = timer().tick(start + 299_000)
        val paused = before.pause(start + 300_000)
        assertTrue(paused.inFinalMinutes)
        assertFalse(paused.crossedFinalWarning(before))
        assertFalse(paused.unpause(start + 310_000).crossedFinalWarning(paused))
    }

    @Test fun firstObservationAndDifferentSittingNeverCross() {
        val warning = timer().tick(start + 300_000)
        assertFalse(warning.crossedFinalWarning(null))
        assertFalse(warning.crossedFinalWarning(timer().copy(startedAt = start + 1)))
    }

    @Test fun finishedTimerNeverWarns() {
        val warning = timer().tick(start + 300_000)
        val done = warning.tick(start + 600_000)
        assertEquals(ExamTimerPhase.DONE, done.phase)
        assertFalse(done.inFinalMinutes)
        assertFalse(done.crossedFinalWarning(warning))
        assertFalse(warning.copy(remaining = 0).inFinalMinutes)
    }

    @Test fun managerTickSequenceWarnsOnceAndAdjustmentRearms() {
        var prior = timer()
        var crossings = 0
        for (seconds in 1..400) {
            val next = prior.tick(start + seconds * 1000L)
            if (next.crossedFinalWarning(prior)) crossings++
            prior = next
        }
        assertEquals(1, crossings)
        val now = start + 400_000
        val extended = prior.adjust(300, now)
        assertFalse(extended.crossedFinalWarning(prior))
        val adjusted = extended.adjust(-300, now)
        assertTrue(adjusted.crossedFinalWarning(extended))
        assertFalse(adjusted.tick(now + 1000).crossedFinalWarning(adjusted))
    }
}
