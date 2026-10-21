package com.folio.notes.mistakes

import com.folio.notes.mistakes.review.retryLabel
import com.folio.notes.mistakes.sync.ExamTrackMistakeCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import com.folio.notes.mistakes.MistakeLibrarySort as MistakeSort

class MistakeWorkflowTest {
    private val now = 1_800_000_000_000L / 86_400_000L * 86_400_000L + 12 * 3_600_000L
    private val day = 86_400_000L

    private fun card(id: String, due: Long = now, created: Long = now, marksLost: Double? = null,
        reviews: List<Long> = emptyList()): ExamTrackMistake =
        requireNotNull(ExamTrackMistakeCodec.decode(JSONObject().put("id", id).put("attemptId", "")
            .put("question", "Q $id").put("category", "Concept").put("explanation", "").put("correction", "")
            .put("createdAt", isoTime(created)).put("updatedAt", isoTime(created)).put("dueAt", isoTime(due))
            .apply { marksLost?.let { put("marksLost", it) } }
            .put("reviewHistory", JSONArray(reviews.mapIndexed { i, at ->
                JSONObject().put("id", "$id-$i").put("completedAt", isoTime(at)).put("result", "good")
            })).toString()))

    @Test fun librarySortOrdersByDueRecencyAndMarks() {
        val a = card("a", due = now + 2 * day, created = now - 3 * day, marksLost = 1.0)
        val b = card("b", due = now - day, created = now - day, marksLost = 4.0)
        val c = card("c", due = now + day, created = now)
        val cards = listOf(a, b, c)
        val schedules = cards.associate { it.id to MistakeScheduler.getMistakeSchedule(it) }
        assertEquals(listOf("b", "c", "a"), MistakeSort.DUE.sorted(cards, schedules).map { it.id })
        assertEquals(listOf("c", "b", "a"), MistakeSort.NEWEST.sorted(cards, schedules).map { it.id })
        // Cards without marks go last.
        assertEquals(listOf("b", "a", "c"), MistakeSort.MARKS.sorted(cards, schedules).map { it.id })
        assertEquals(MistakeSort.DUE, MistakeSort.parse("nonsense"))
        assertEquals(MistakeSort.MARKS, MistakeSort.parse("MARKS"))
    }

    @Test fun labelsReadNaturally() {
        assertEquals("1 question", countLabel(1, "question"))
        assertEquals("0 questions", countLabel(0, "question"))
        assertEquals("2 Again · 1 Good", ratingTally(listOf("again", "good", "again")))
        assertEquals("", ratingTally(emptyList()))
        fun interval(days: Double) = intervalLabel(MistakeSchedule(ReviewState.REVIEW, isoTime(now), days, 2.5, 1, 0, false))
        assertEquals("10 min", interval(10.0 / 1440))
        assertEquals("4 h", interval(4.0 / 24))
        assertEquals("5 days", interval(5.0))
        assertEquals("10 minutes", retryLabel(1f / 6))
        assertEquals("1 hour", retryLabel(1f))
        assertEquals("24 hours", retryLabel(24f))
        assertEquals("3 days", retryLabel(72f))
        assertEquals("1 Jan 2026", localDateLabel("2026-01-01T10:00:00.000Z", ZoneOffset.UTC))
    }

    @Test fun reviewedTodayCountsDistinctCards() {
        val zone = ZoneOffset.UTC
        val twice = card("twice", reviews = listOf(now - 3_600_000, now - 1_800_000))
        val once = card("once", reviews = listOf(now - 600_000))
        val cards = listOf(twice, once)
        val stats = MistakeToday.stats(cards, cards.associate { it.id to MistakeScheduler.getMistakeSchedule(it) }, now, zone)
        assertEquals(2, stats.reviewedToday)
        assertEquals(1, stats.streak)
    }
}
