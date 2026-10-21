package com.folio.notes.mistakes

import com.folio.notes.mistakes.review.MistakeReviewPreferences
import com.folio.notes.mistakes.sync.ExamTrackMistakeCodec
import com.folio.notes.mistakes.sync.ExamTrackRemote
import com.folio.notes.mistakes.sync.MistakeCache
import com.folio.notes.mistakes.sync.MistakeCacheCodec
import com.folio.notes.mistakes.sync.MistakeCacheStore
import com.folio.notes.mistakes.sync.MistakeRepository
import com.folio.notes.sync.ApplyReceipt
import com.folio.notes.sync.ApplyResult
import com.folio.notes.sync.ApplyStale
import com.folio.notes.sync.ReadResult
import com.folio.notes.sync.SyncProtocol
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MistakeSchedulingTest {
    private val now = 1_800_000_000_000L / 86_400_000L * 86_400_000L
    private val day = 86_400_000L
    private fun card(id: String = "card", due: Long = now, suspended: Boolean = false): ExamTrackMistake =
        requireNotNull(ExamTrackMistakeCodec.decode(JSONObject().put("id", id).put("attemptId", "")
            .put("question", "Q").put("category", "Concept").put("explanation", "why").put("correction", "answer")
            .put("createdAt", isoTime(now)).put("updatedAt", isoTime(now)).put("dueAt", isoTime(due))
            .put("suspended", suspended).put("futureField", "keep").toString()))

    @Test fun relaxedDefaultsAndCustomSettingsDriveActualDatesAndHistory() {
        val c = card()
        val again = MistakeScheduler.previewMistakeReview(c, ReviewRating.AGAIN, isoTime(now))
        assertEquals(isoTime(now + day), again.dueAt)
        assertEquals(5.0, MistakeScheduler.previewMistakeReview(c, ReviewRating.GOOD, isoTime(now)).intervalDays, 0.0)
        assertEquals(11.0, MistakeScheduler.previewMistakeReview(c, ReviewRating.EASY, isoTime(now)).intervalDays, 0.0)
        val frequent = MistakeReviewPreferences(1.0, 1.0 / 6, 365)
        assertEquals(isoTime(now + 600_000), MistakeScheduler.previewMistakeReview(c, ReviewRating.AGAIN, isoTime(now), frequent).dueAt)
        val reviewed = MistakeScheduler.recordMistakeReview(c, ReviewRating.EASY, isoTime(now), "review", MistakeReviewPreferences(3.0, 24.0, 7))
        assertEquals(7.0, reviewed.intervalDays!!, 0.0)
        assertEquals(1, reviewed.reviewHistory.size)
        assertEquals("keep", JSONObject(reviewed.originalJson).getString("futureField"))
    }

    @Test fun reductionNeverAdvancesCardsAndRespectsFutureOccupancyAndSuspension() {
        val cards = (0..11).map { card("c%02d".format(it), now) } +
            listOf(card("future", now + 2 * day), card("paused", now, true))
        val dates = MistakeScheduler.reduceReviews(cards, 2, 2, now)
        assertFalse(dates.containsKey("future"))
        assertFalse(dates.containsKey("paused"))
        val effective = cards.filterNot { it.suspended }.map { it.id to (dates[it.id] ?: it.dueAt!!) }
        effective.groupBy { (java.time.Instant.parse(it.second).toEpochMilli() - now) / day }.forEach { (_, bucket) ->
            assertTrue(bucket.size <= 2)
        }
        dates.forEach { (id, due) -> assertTrue(due >= cards.first { it.id == id }.dueAt!!) }
        val breakDates = MistakeScheduler.reduceReviews(cards, 7, 0, now)
        cards.filter { !it.suspended }.forEach { assertTrue(java.time.Instant.parse(breakDates[it.id] ?: it.dueAt).toEpochMilli() >= now + 7 * day) }
    }

    @Test fun invalidPreferencesAreClampedAndCacheKeepsPendingSettings() = runBlocking {
        val bad = MistakeReviewPreferences(Double.NaN, Double.POSITIVE_INFINITY, -1).normalized()
        assertEquals(1.5, bad.spacingMultiplier, 0.0)
        assertEquals(24.0, bad.retryHours, 0.0)
        assertEquals(7, bad.maximumIntervalDays)
        val store = MemoryStore()
        val repo = MistakeRepository(store, object : ExamTrackRemote {
            override suspend fun read(userId: String, cursor: Long, limit: Int): ReadResult = error("unused")
            override suspend fun apply(userId: String, change: SyncProtocol.QueuedChange): ApplyResult = error("unused")
        })
        repo.saveReviewPreferences("user", MistakeReviewPreferences(2.0, 48.0, 90))
        val cached = MistakeCacheCodec.decode(MistakeCacheCodec.encode(store.value))
        assertEquals(2.0, cached.reviewPreferences.spacingMultiplier, 0.0)
        assertEquals("user_state", cached.preferencesChange!!.entity)
        assertEquals(0L, cached.preferencesChange!!.expectedSeq)
    }

    @Test fun lighterPeriodPacesNewRatingsAndExpiresWithoutBlockingEarlyPractice() {
        val cards = listOf(card("a", now + day), card("b", now + day))
        val settings = MistakeReviewPreferences(lighterUntil = isoTime(now + 7 * day), lighterDailyTarget = 2)
        val next = MistakeScheduler.previewMistakeReview(card("reviewed"), ReviewRating.AGAIN, isoTime(now), settings, cards)
        assertEquals(isoTime(now + 2 * day), next.dueAt)
        val ended = MistakeScheduler.previewMistakeReview(card("reviewed"), ReviewRating.AGAIN, isoTime(now + 7 * day), settings, cards)
        assertEquals(isoTime(now + 8 * day), ended.dueAt)
        val breakSchedule = MistakeScheduler.previewMistakeReview(card(), ReviewRating.AGAIN, isoTime(now),
            settings.copy(lighterDailyTarget = 0), cards)
        assertEquals(isoTime(now + 7 * day), breakSchedule.dueAt)
    }

    @Test fun syncedPreferencesUseDurableVersionsAndRetryStaleSettings() = runBlocking {
        val store = MemoryStore()
        var writes = 0
        val remotePreferences = MistakeReviewPreferences(2.0, 48.0, 90)
        val row = SyncProtocol.RowState("user_state", MistakeReviewPreferences.ROW_ID, "put",
            JSONObject().put("value", remotePreferences.encode()), 0, "web", 12)
        val remote = object : ExamTrackRemote {
            override suspend fun read(userId: String, cursor: Long, limit: Int) = ReadResult("snapshot", 0, 12, emptyList(), listOf(row))
            override suspend fun apply(userId: String, change: SyncProtocol.QueuedChange): ApplyResult {
                writes++
                if (writes == 1) return ApplyResult(emptyList(), listOf(ApplyStale(change.changeId, row.copy(seq = 13))), 13)
                assertEquals(13L, change.expectedSeq)
                return ApplyResult(listOf(ApplyReceipt(change.changeId, 14, false)), emptyList(), 14)
            }
        }
        val repo = MistakeRepository(store, remote)
        repo.sync("user")
        assertEquals(48.0, store.value.reviewPreferences.retryHours, 0.0)
        repo.saveReviewPreferences("user", remotePreferences.copy(retryHours = 72.0))
        assertEquals(12L, store.value.preferencesChange!!.expectedSeq)
        repo.sync("user")
        assertEquals(72.0, store.value.reviewPreferences.retryHours, 0.0)
        assertNull(store.value.preferencesChange)
        assertEquals(14L, store.value.preferencesSequence)
        assertEquals(2, writes)
    }

    @Test fun postponementRebasePreservesOtherDeviceReviewAndLaterDueDate() = runBlocking {
        val reviewed = MistakeScheduler.recordMistakeReview(card(), ReviewRating.GOOD, isoTime(now), "web-review")
        val local = requireNotNull(ExamTrackMistakeCodec.decode(JSONObject(card().originalJson)
            .put("dueAt", isoTime(now + 2 * day)).toString()))
        val change = SyncProtocol.QueuedChange("change", "mistakes", local.id, "put", JSONObject(local.originalJson),
            0, isoTime(now), expectedSeq = 1)
        val store = MemoryStore(MistakeCache(mistakes = mapOf(local.id to local), pending = setOf(local.id),
            pendingDateOnly = setOf(local.id), outbox = mapOf(local.id to change)))
        val remote = object : ExamTrackRemote {
            override suspend fun read(userId: String, cursor: Long, limit: Int) = ReadResult("snapshot", 0, 2, emptyList(),
                listOf(SyncProtocol.RowState("mistakes", reviewed.id, "put", JSONObject(reviewed.originalJson), 0, "web", 2)))
            override suspend fun apply(userId: String, change: SyncProtocol.QueuedChange): ApplyResult {
                assertEquals(2L, change.expectedSeq)
                assertEquals("web-review", change.payload!!.getJSONArray("reviewHistory").getJSONObject(0).getString("id"))
                assertEquals(reviewed.dueAt, change.payload!!.getString("dueAt"))
                return ApplyResult(listOf(ApplyReceipt(change.changeId, 3, false)), emptyList(), 3)
            }
        }
        MistakeRepository(store, remote).sync("user")
        assertEquals(1, store.value.mistakes[local.id]!!.reviewHistory.size)
        assertTrue(store.value.pendingDateOnly.isEmpty())
    }

    private class MemoryStore(var value: MistakeCache = MistakeCache()) : MistakeCacheStore {
        override suspend fun load(userId: String) = value
        override suspend fun save(userId: String, cache: MistakeCache) { value = cache }
    }
}
