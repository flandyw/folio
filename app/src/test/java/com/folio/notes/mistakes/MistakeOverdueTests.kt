package com.folio.notes.mistakes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class MistakeOverdueTests {
    private val zone = ZoneId.of("Australia/Melbourne")
    private fun days(due: String, now: String) = MistakeScheduler.overdueDays(due, timestamp(now), zone)
    private fun mistake(id: String, due: String, suspended: Boolean = false) = requireNotNull(
        ExamTrackMistakeCodec.decode(JSONObject().put("id", id).put("attemptId", "exam")
            .put("question", id).put("category", "Reasoning").put("explanation", "").put("correction", "")
            .put("createdAt", due).put("updatedAt", due).put("dueAt", due)
            .put("suspended", suspended).toString())
    )

    @Test fun overdueStartsAtLocalMidnightNotAfter24Hours() {
        val now = "2026-09-16T14:01:00.000Z" // 00:01 in Melbourne
        assertEquals(1L, days("2026-09-16T13:59:00.000Z", now))
        assertEquals(0L, days(now, now))
        assertEquals(0L, days("2026-09-17T01:00:00.000Z", now))
    }

    @Test fun calendarDaysHandleDaylightSaving() {
        // The spring-forward day is 23 hours long.
        assertEquals(1L, days("2026-10-03T14:30:00.000Z", "2026-10-04T13:30:00.000Z"))
        // The fall-back day is 25 hours long.
        assertEquals(1L, days("2026-04-04T13:30:00.000Z", "2026-04-05T14:30:00.000Z"))
    }

    @Test fun overdueQueueExcludesSuspendedAndTodayAndKeepsOldestFirst() {
        val cards = listOf(
            mistake("today", "2026-09-16T14:00:00.000Z"),
            mistake("yesterday", "2026-09-16T13:59:00.000Z"),
            mistake("suspended", "2026-09-14T00:00:00.000Z", true),
            mistake("older", "2026-09-15T00:00:00.000Z"),
            mistake("future", "2026-09-18T00:00:00.000Z")
        )
        val now = timestamp("2026-09-16T14:01:00.000Z")
        val overdue = MistakeScheduler.getOverdueMistakes(cards, now, zone)
        assertEquals(listOf("older", "yesterday"), overdue.map { it.id })
        assertTrue(MistakeScheduler.getDueMistakes(cards, now).containsAll(overdue))
        assertTrue(MistakeScheduler.getOverdueMistakes(emptyList(), now, zone).isEmpty())
    }
}
