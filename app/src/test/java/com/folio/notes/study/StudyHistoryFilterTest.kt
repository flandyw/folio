package com.folio.notes.study

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StudyHistoryFilterTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val day = LocalDate.of(2026, 10, 4)
    private fun entry(title: String, date: LocalDate = day, minutes: Long = 10) = FocalStudyEntry(
        notebookId = null, title = title, subjectId = "maths", kind = "study",
        startedAt = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        endedAt = date.atStartOfDay(zone).toInstant().toEpochMilli(), activeMillis = minutes * 60_000)
    private fun filter(entries: List<FocalStudyEntry>, query: String = "", from: LocalDate? = null,
                       to: LocalDate? = null, limit: Int = 20) = focalFilterHistory(
        entries, mapOf("maths" to "Mathematics"), query, from, to, zone, limit)

    @Test fun searchesTitleAndSubjectIgnoringCaseAndWhitespace() {
        val entries = listOf(entry("Algebra"), entry("Reading").copy(subjectId = null))
        assertEquals(listOf("Algebra"), filter(entries, " ALG ").entries.map { it.title })
        assertEquals(listOf("Algebra"), filter(entries, "mAtHeMaTiCs").entries.map { it.title })
        assertEquals(2, filter(entries, "  ").totalCount)
        assertEquals(0, filter(entries, "unknown").totalCount)
    }

    @Test fun boundsAreInclusiveAndUseCompletionDateInLocalZone() {
        val entries = listOf(entry("before", day.minusDays(1)), entry("on"), entry("after", day.plusDays(1)))
        assertEquals(listOf("on"), filter(entries, from = day, to = day).entries.map { it.title })
        assertEquals(2, filter(entries, from = day).totalCount)
        assertEquals(2, filter(entries, to = day).totalCount)
        assertEquals(0, filter(entries, from = day.plusDays(1), to = day).totalCount)
        val late = entry("late").copy(endedAt = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
        assertEquals(1, filter(listOf(late), from = day, to = day).totalCount)
    }

    @Test fun filtersBeforePaginationAndTotalsCoverAllMatchesNotJustPage() {
        val entries = listOf(entry("other", day.plusDays(1))) + (1..25).map { entry("match $it", minutes = 2) }
        val first = filter(entries, "match", limit = 20)
        assertEquals(20, first.entries.size)
        assertEquals(25, first.totalCount)
        assertEquals(50L, first.days.single().studyMinutes)
        assertEquals(25, filter(entries, "match", limit = 40).entries.size)
        assertEquals(0, filter(entries, "missing").days.size)
        assertEquals(0, filter(emptyList()).totalCount)
        assertTrue(filter(entries, limit = -1).entries.isEmpty())
    }

    @Test fun excludesUnfinishedDeletedAndExamTimeFromStudyTotals() {
        val done = entry("done")
        val result = filter(listOf(done, done.copy(completed = false), done.copy(deleted = true),
            entry("exam").copy(kind = "exam")))
        assertEquals(2, result.totalCount)
        assertEquals(10L, result.days.single().studyMinutes)
    }
}
