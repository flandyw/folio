package com.folio.notes.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressInsightsTest {
    private fun exam(id: String, subject: String, completedAt: String, score: Double, max: Double = 100.0) =
        LoggedExam(id, subject, "VCAA", "Exam", 2024, "Paper $id", completedAt, score, max, "{}")

    @Test fun oneAttemptHasNoChange() {
        val only = subjectBests(listOf(exam("a", "Methods", "2026-03-01", 72.0))).single()
        assertEquals("a", only.best.id)
        assertEquals("a", only.latest.id)
        assertEquals(1, only.attempts)
        assertNull(only.change)
    }

    @Test fun changeComparesTheLatestTwoAttemptsByDateNotListOrder() {
        val best = subjectBests(listOf(
            exam("late", "Methods", "2026-05-01", 60.0),
            exam("early", "Methods", "2026-03-01", 80.0),
            exam("mid", "Methods", "2026-04-01", 50.0),
        )).single()
        assertEquals("early", best.best.id)
        assertEquals("late", best.latest.id)
        assertEquals(10.0, best.change!!, 1e-9)
    }

    @Test fun mixedDateAndTimestampFormatsSortByDay() {
        val best = subjectBests(listOf(
            exam("b", "Chemistry", "2026-04-02T01:00:00Z", 30.0, 40.0),
            exam("a", "Chemistry", "2026-03-20", 20.0, 40.0),
        )).single()
        assertEquals("b", best.latest.id)
        assertEquals(25.0, best.change!!, 1e-9)
    }

    @Test fun aTiedBestKeepsTheFirstAttemptToReachIt() {
        val best = subjectBests(listOf(
            exam("second", "Physics", "2026-04-01", 45.0, 50.0),
            exam("first", "Physics", "2026-03-01", 90.0),
            exam("third", "Physics", "2026-05-01", 90.0),
        )).single()
        assertEquals("first", best.best.id)
        assertEquals(0.0, best.change!!, 1e-9)
    }

    @Test fun sameDayAttemptsFallBackToTimestampThenId() {
        val best = subjectBests(listOf(
            exam("z", "English", "2026-03-01", 70.0),
            exam("y", "English", "2026-03-01", 60.0),
        )).single()
        assertEquals("z", best.latest.id)
        assertEquals(10.0, best.change!!, 1e-9)
    }

    @Test fun subjectsAreListedAlphabetically() {
        val subjects = subjectBests(listOf(
            exam("1", "physics", "2026-03-01", 50.0),
            exam("2", "Biology", "2026-03-01", 50.0),
            exam("3", "Methods", "2026-03-01", 50.0),
        )).map { it.subject }
        assertEquals(listOf("Biology", "Methods", "physics"), subjects)
    }
}
