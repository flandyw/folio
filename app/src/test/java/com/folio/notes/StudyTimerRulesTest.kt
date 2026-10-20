package com.folio.notes

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StudyTimerRulesTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun done(id: String, start: Long, end: Long, subject: String? = "maths", kind: String = "study") = FocalStudyEntry(
        id = id, notebookId = null, title = id, subjectId = subject, kind = kind, startedAt = start, endedAt = end,
        activeMillis = end - start)
    private fun plan(slots: List<FocalStudyInterval>) = FocalStudyEntry(id = "plan", notebookId = null, title = "Plan",
        subjectId = "maths", kind = "study", startedAt = 0L, endedAt = 0L, activeMillis = 0L, completed = false, planned = true,
        pendingBlocks = slots)

    @Test fun durationsAndClocksReadNaturally() {
        assertEquals("0 min", formatStudyMinutes(0))
        assertEquals("45 min", formatStudyMinutes(45))
        assertEquals("1 h", formatStudyMinutes(60))
        assertEquals("2 h 5 min", formatStudyMinutes(125))
        assertEquals("0 min", formatStudyMinutes(-3))
        assertEquals(1L, studyRoundedMinutes(30_000L))
        assertEquals(0L, studyRoundedMinutes(29_999L))
        assertEquals("1:02:03", formatStudyClock(3_723_000L))
        assertEquals("02:03", formatStudyChipClock(123_000L))
        assertEquals("1:00:00", formatStudyChipClock(3_600_000L))
    }

    @Test fun sharedSessionsCountOpenIntervalsUpToNow() {
        val entry = done("live", 0L, 0L).copy(completed = false, activeMillis = 1_000L,
            intervals = listOf(FocalStudyInterval(0L, 60_000L), FocalStudyInterval(120_000L, null)))
        assertEquals(120_000L, focalEntryElapsed(entry, 180_000L))
        // The server's figure wins when it is larger than the intervals Folio has seen.
        assertEquals(500_000L, focalEntryElapsed(entry.copy(activeMillis = 500_000L), 180_000L))
    }

    @Test fun historyGroupsByLocalDayNewestFirstAndExcludesExamsFromTotals() {
        val days = focalHistoryDays(listOf(
            done("a", at(19, 9), at(19, 10)),
            done("b", at(20, 8), at(20, 8, 30)),
            done("exam", at(20, 9), at(20, 12), kind = "exam"),
            done("c", at(20, 23), at(20, 23, 45)),
        ), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19)), days.map { it.date })
        assertEquals(listOf("c", "exam", "b"), days[0].entries.map { it.id })
        assertEquals(75L, days[0].studyMinutes)
        assertEquals(60L, days[1].studyMinutes)
    }

    @Test fun dayLabelsAreRelativeNearTodayAndDatedFurtherAway() {
        val today = LocalDate.of(2026, 10, 21)
        assertEquals("Today", studyDayLabel(today, today, Locale.UK))
        assertEquals("Yesterday", studyDayLabel(today.minusDays(1), today, Locale.UK))
        assertEquals("Tomorrow", studyDayLabel(today.plusDays(1), today, Locale.UK))
        assertEquals("Sunday", studyDayLabel(today.minusDays(3), today, Locale.UK))
        assertEquals("Mon 5 Oct", studyDayLabel(LocalDate.of(2026, 10, 5), today, Locale.UK))
        assertEquals("3 Dec 2025", studyDayLabel(LocalDate.of(2025, 12, 3), today, Locale.UK))
    }

    @Test fun weekStartsOnMondayAndStreakSurvivesAnUnstudiedMorning() {
        // 2026-10-21 is a Wednesday.
        val entries = listOf(
            done("sun", at(18, 9), at(18, 10)),
            done("mon", at(19, 9), at(19, 10)),
            done("tue", at(20, 9), at(20, 9, 30)),
        )
        assertEquals(90 * 60_000L, focalWeekMillis(entries, at(21, 8), zone))
        assertEquals(3, focalStudyStreak(entries, at(21, 8), zone))
        assertEquals(4, focalStudyStreak(entries + done("wed", at(21, 7), at(21, 7, 20)), at(21, 8), zone))
        assertEquals(0, focalStudyStreak(entries, at(23, 8), zone))
        // Planned time is not study.
        assertEquals(0, focalStudyStreak(listOf(plan(listOf(FocalStudyInterval(at(20, 9), at(20, 10))))), at(21, 8), zone))
    }

    @Test fun planScopesAndSearch() {
        val now = at(21, 12)
        val dayStart = at(21, 0)
        val dayEnd = at(22, 0)
        val past = listOf(FocalStudyInterval(at(21, 8), at(21, 9)))
        val later = listOf(FocalStudyInterval(at(21, 15), at(21, 16)))
        val tomorrow = listOf(FocalStudyInterval(at(22, 15), at(22, 16)))
        assertTrue(focalPlanInScope(past, PlanScope.TODAY, now, dayStart, dayEnd))
        assertTrue(focalPlanInScope(past, PlanScope.OVERDUE, now, dayStart, dayEnd))
        assertFalse(focalPlanInScope(past, PlanScope.UPCOMING, now, dayStart, dayEnd))
        assertTrue(focalPlanInScope(later, PlanScope.UPCOMING, now, dayStart, dayEnd))
        assertFalse(focalPlanInScope(tomorrow, PlanScope.TODAY, now, dayStart, dayEnd))
        assertTrue(focalPlanMatchesQuery("vectors maths", "Specialist", "Maths", "Lines and vectors"))
        assertFalse(focalPlanMatchesQuery("vectors chemistry", "Specialist", "Maths", "Lines and vectors"))
        assertTrue(focalPlanMatchesQuery("   ", "anything"))
    }

    @Test fun planWhenLabels() {
        val now = at(21, 12)
        assertEquals("Scheduled now", focalPlanWhen(listOf(FocalStudyInterval(at(21, 11), at(21, 13))), now, zone, Locale.UK))
        assertEquals("Starts in 20 min", focalPlanWhen(listOf(FocalStudyInterval(at(21, 12, 20), at(21, 13))), now, zone, Locale.UK))
        assertTrue(focalPlanWhen(listOf(FocalStudyInterval(at(21, 8), at(21, 9))), now, zone, Locale.UK)!!.startsWith("Overdue · ended"))
        assertEquals("Overdue · Yesterday", focalPlanWhen(listOf(FocalStudyInterval(at(20, 8), at(20, 9))), now, zone, Locale.UK))
        assertTrue(focalPlanWhen(listOf(FocalStudyInterval(at(22, 15), at(22, 16))), now, zone, Locale.UK)!!.startsWith("Tomorrow · "))
        assertNull(focalPlanWhen(emptyList(), now, zone))
    }

    @Test fun planBlockProblemsPointAtTheBlockToFix() {
        val ok = listOf(FocalStudyInterval(at(21, 9), at(21, 10)), FocalStudyInterval(at(21, 10), at(21, 11)))
        assertTrue(focalPlanSlotProblems(ok).isEmpty())
        val backwards = listOf(FocalStudyInterval(at(21, 10), at(21, 9)))
        assertEquals(mapOf(0 to "Ends before it starts"), focalPlanSlotProblems(backwards))
        // Listed out of order: the later-starting block is the one reported as overlapping.
        val overlap = listOf(FocalStudyInterval(at(21, 10), at(21, 12)), FocalStudyInterval(at(21, 9), at(21, 11)))
        assertEquals(mapOf(0 to "Overlaps block 2"), focalPlanSlotProblems(overlap))
        val long = listOf(FocalStudyInterval(at(20, 9), at(21, 10)))
        assertEquals(mapOf(0 to "Longer than 24 hours"), focalPlanSlotProblems(long))
    }

    @Test fun recentSubjectIgnoresPlansAndUnknownSubjects() {
        val subjects = listOf(FocalSubject("maths", "Maths"), FocalSubject("chem", "Chemistry"))
        val entries = listOf(done("old", at(19, 9), at(19, 10), subject = "chem"),
            done("gone", at(20, 9), at(20, 10), subject = "deleted"),
            plan(listOf(FocalStudyInterval(at(22, 9), at(22, 10)))).copy(subjectId = "maths", endedAt = at(22, 10)))
        assertEquals("chem", focalRecentSubject(entries, subjects))
        assertNull(focalRecentSubject(emptyList(), subjects))
    }

    @Test fun slotLabelsShowBothDatesOnlyAcrossMidnight() {
        val same = focalSlotLabel(FocalStudyInterval(at(21, 16), at(21, 17)), zone, Locale.UK)
        assertTrue(same, same.startsWith("Wed 21 Oct, ") && same.count { it == ',' } == 1)
        val overnight = focalSlotLabel(FocalStudyInterval(at(21, 23), at(22, 1)), zone, Locale.UK)
        assertTrue(overnight, overnight.contains("Thu 22 Oct"))
    }
}
