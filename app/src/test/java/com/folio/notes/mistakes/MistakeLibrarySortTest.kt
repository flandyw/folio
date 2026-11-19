package com.folio.notes.mistakes

import com.folio.notes.mistakes.sync.ExamTrackMistakeCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MistakeLibrarySortTest {
    private val now = 1_800_000_000_000L / 86_400_000L * 86_400_000L + 12 * 3_600_000L
    private val day = 86_400_000L

    private fun card(id: String, due: Long = now, created: Long = now, lapses: Int? = null,
        again: Int = 0): ExamTrackMistake =
        requireNotNull(ExamTrackMistakeCodec.decode(JSONObject().put("id", id).put("attemptId", "")
            .put("question", "Q $id").put("category", "Concept").put("explanation", "").put("correction", "")
            .put("createdAt", isoTime(created)).put("updatedAt", isoTime(created)).put("dueAt", isoTime(due))
            .apply { lapses?.let { put("lapses", it) } }
            .put("reviewHistory", JSONArray((0 until again).map { i ->
                JSONObject().put("id", "$id-$i").put("completedAt", isoTime(created + i)).put("result", "again")
            })).toString()))

    private fun ids(sort: MistakeLibrarySort, cards: List<ExamTrackMistake>,
        schedules: Map<String, MistakeSchedule> = cards.associate { it.id to MistakeScheduler.getMistakeSchedule(it) }) =
        sort.sorted(cards, schedules).map { it.id }

    @Test fun mostLapsesFirstThenDueThenId() {
        val cards = listOf(
            card("a", due = now + day, lapses = 1),
            card("b", due = now - day, lapses = 3),
            card("c", due = now + 2 * day, lapses = 1),
            card("d", due = now + day, lapses = 1),
            card("e", due = now, lapses = 0),
        )
        assertEquals(listOf("b", "a", "d", "c", "e"), ids(MistakeLibrarySort.LAPSES, cards))
        assertEquals(listOf("b", "a", "d", "c", "e"), ids(MistakeLibrarySort.LAPSES, cards.reversed()))
    }

    @Test fun lapsesFallBackToReviewHistoryWhenScheduleMissing() {
        val cards = listOf(card("x", again = 1), card("y", again = 2), card("z"))
        assertEquals(listOf("y", "x", "z"), ids(MistakeLibrarySort.LAPSES, cards, emptyMap()))
    }

    @Test fun oldestFirstWithStableIdTieBreak() {
        val cards = listOf(
            card("c", created = now),
            card("b", created = now - 2 * day),
            card("a", created = now),
            card("d", created = now - day),
        )
        assertEquals(listOf("b", "d", "a", "c"), ids(MistakeLibrarySort.OLDEST, cards))
        assertEquals(listOf("b", "d", "a", "c"), ids(MistakeLibrarySort.OLDEST, cards.reversed()))
        assertEquals(listOf("a", "c", "d", "b"), ids(MistakeLibrarySort.NEWEST, cards))
    }

    @Test fun newSortsParseAndHaveLabels() {
        assertEquals(MistakeLibrarySort.LAPSES, MistakeLibrarySort.parse("LAPSES"))
        assertEquals(MistakeLibrarySort.OLDEST, MistakeLibrarySort.parse("OLDEST"))
        assertEquals(listOf("Due date", "Recently added", "Most marks lost", "Most lapses", "Oldest first"),
            MistakeLibrarySort.entries.map { it.label })
    }
}
