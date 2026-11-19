package com.folio.notes.planner

import com.folio.notes.progress.ExamReference
import com.folio.notes.progress.LoggedExam
import org.junit.Assert.*
import org.junit.Test

class GradesViewTest {
    private fun exam(id: String, subject: String, date: String, score: Double, max: Double = 100.0) =
        LoggedExam(id, subject, "VCAA", "Exam $id", 2024, "Written examination 1", date, score, max, "{}")

    private val exams = listOf(
        exam("a", "Mathematical Methods", "2026-03-01", 60.0),
        exam("b", "Chemistry", "2026-05-01", 45.0, 50.0),
        exam("c", "mathematical methods ", "2026-04-01", 80.0),
        exam("d", "Chemistry", "2026-05-01T09:00:00Z", 30.0, 50.0),
        exam("e", "Physics", "2026-02-01", 80.0),
    )

    @Test fun subjectOptionsStartWithAllAndMergeCase() {
        assertEquals(listOf(ALL_SUBJECTS, "Chemistry", "Mathematical Methods", "Physics"), gradesSubjects(exams))
        assertEquals(listOf(ALL_SUBJECTS), gradesSubjects(emptyList()))
    }

    @Test fun filtersBySubjectIgnoringCaseAndSpaces() {
        assertEquals(setOf("a", "c"), gradesResults(exams, "Mathematical Methods", GradesSort.NEWEST).map { it.id }.toSet())
        assertEquals(5, gradesResults(exams, ALL_SUBJECTS, GradesSort.NEWEST).size)
        assertEquals("an unknown subject falls back to everything", 5, gradesResults(exams, "Biology", GradesSort.NEWEST).size)
    }

    @Test fun sortsByDateThenTime() {
        // b and d share a day; d has a time so it is the later entry.
        assertEquals(listOf("d", "b", "c", "a", "e"), gradesResults(exams, ALL_SUBJECTS, GradesSort.NEWEST).map { it.id })
        assertEquals(listOf("e", "a", "c", "b", "d"), gradesResults(exams, ALL_SUBJECTS, GradesSort.OLDEST).map { it.id })
    }

    @Test fun sortsByPercentageNotRawMark() {
        // b is 45/50 = 90%, the highest; d is 30/50 = 60%, tied with a.
        assertEquals(listOf("b", "c", "e", "d", "a"), gradesResults(exams, ALL_SUBJECTS, GradesSort.HIGHEST).map { it.id })
        assertEquals(listOf("d", "a", "c", "e", "b"), gradesResults(exams, ALL_SUBJECTS, GradesSort.LOWEST).map { it.id })
    }

    @Test fun sortLabelsRoundTrip() {
        GradesSort.entries.forEach { assertEquals(it, GradesSort.of(it.label)) }
        assertEquals(GradesSort.NEWEST, GradesSort.of("bogus"))
    }

    @Test fun comparisonReferenceMatchesLikeReferenceFor() {
        fun ref(id: String, subject: String, paper: String) = ExamReference(id, subject, 2024, paper, 100.0, "", emptyList())
        val refs = listOf(
            ref("bio", "Biology", "Written examination"),
            ref("m1", "Mathematical Methods", "Written examination 1"),
            ref("m2", "Mathematical Methods", "Written examination 2")
        )
        fun logged(subject: String, paper: String) =
            LoggedExam("x", subject, "VCAA", "t", 2019, paper, "2026-01-01", 50.0, 100.0, "{}")
        assertEquals("bio", comparisonReference(logged("biology", "Exam"), refs)?.id)
        assertEquals("m2", comparisonReference(logged("mathematical methods", "Exam 2"), refs)?.id)
        assertNull(comparisonReference(logged("Mathematical Methods", "Exam 3"), refs))
        assertNull(comparisonReference(logged("Physics", "Exam"), refs))
        val french = listOf(ref("fr", "French", "Oral and written examination"))
        assertEquals("fr", comparisonReference(logged("French", "Exam"), french)?.id)
        assertNull(comparisonReference(logged("French", "Oral examination"), french))
    }
}
