package com.folio.notes.mistakes

import com.folio.notes.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MistakeTests {
    private val at = "2026-09-16T02:00:00.000Z"
    private fun payload(id: String = "m", updated: String = "2026-09-01T00:00:00.000Z") = JSONObject()
        .put("id", id).put("attemptId", "a").put("question", "Q8c").put("category", "Reasoning")
        .put("explanation", "why").put("correction", "answer").put("resolved", false)
        .put("createdAt", "2026-09-01T00:00:00.000Z").put("updatedAt", updated)
        .put("future", JSONObject().put("nested", JSONArray(listOf(1, "keep"))))
    private fun mistake(o: JSONObject = payload()) = requireNotNull(ExamTrackMistakeCodec.decode(o.toString()))
    private fun row(o: JSONObject = payload()) = RemoteMistakeRow(o.getString("id"), o.toString(), o.getString("updatedAt"), null)
    private fun attempt(id: String = "r") = LocalMistakeReviewAttempt("u", "m", id, "n-$id", "p-$id")
    private class Store : MistakeCacheStore {
        val values = mutableMapOf<String, String>()
        override suspend fun load(userId: String) = values[userId]?.let(MistakeCacheCodec::decode) ?: MistakeCache()
        override suspend fun save(userId: String, cache: MistakeCache) { values[userId] = MistakeCacheCodec.encode(cache) }
    }
    private class Remote : ExamTrackRemote {
        var rows = emptyList<RemoteMistakeRow>()
        var offline = false
        var conflict = false
        var calls = 0
        val writes = mutableListOf<ExamTrackMistake>()
        override suspend fun fetch(userId: String): List<RemoteMistakeRow> { calls++; check(!offline); return rows }
        override suspend fun update(userId: String, expected: RemoteMistakeRow, payload: ExamTrackMistake): Boolean {
            check(!offline)
            if (conflict) return false
            writes += payload
            rows = rows.map { if (it.id == payload.id) RemoteMistakeRow(payload.id, payload.originalJson, payload.updatedAt, null) else it }
            return true
        }
        override suspend fun contexts(userId: String) = emptyMap<String, ExamContext>()
    }
    @Test fun parsesAndRoundTripsUnknownFields() {
        val o = payload().put("questionText", "Find x").put("areaOfStudy", "Calculus").put("criterion", "C2")
        val m = mistake(o)
        assertEquals("Find x", m.questionText)
        assertEquals("Calculus", m.areaOfStudy)
        assertTrue(o.similar(JSONObject(ExamTrackMistakeCodec.encode(m))))
    }
    @Test fun ratingsPreserveUnrelatedAndUnknownFields() {
        val m = mistake()
        val next = MistakeScheduler.recordMistakeReview(m, ReviewRating.GOOD, at, "r")
        assertTrue(JSONObject(m.originalJson).getJSONObject("future").similar(JSONObject(next.originalJson).getJSONObject("future")))
        assertEquals(m.correction, next.correction)
        assertFalse(JSONObject(next.originalJson).has("practicePageId"))
        assertEquals("r", next.reviewHistory.single().id)
    }
    @Test fun defensiveLegacyParsing() {
        val m = mistake(payload().put("dueAt", "bad").put("easeFactor", "bad").put("reviewState", "future-state"))
        assertEquals(ReviewState.NEW, MistakeScheduler.getMistakeSchedule(m).state)
        assertEquals(2.5, MistakeScheduler.getMistakeSchedule(m).easeFactor, 0.0)
        assertNull(ExamTrackMistakeCodec.decode("[]"))
        assertNull(ExamTrackMistakeCodec.decode(payload().toString(), "different-id"))
    }
    @Test fun attachmentMetadata() {
        val a = JSONObject().put("id", "img").put("name", "question.gif").put("type", "image/gif").put("size", 42).put("storagePath", "u/m/img.gif")
        val m = mistake(payload().put("attachments", JSONArray().put(a)))
        assertEquals(MistakeAttachment("img", "question.gif", "image/gif", 42, "u/m/img.gif"), m.attachments.single())
    }
    @Test fun exactTypeScriptSchedulerParity() {
        val fixtures = JSONArray(javaClass.getResource("/examtrack/scheduler.json")!!.readText())
        assertTrue(fixtures.length() > 300)
        repeat(fixtures.length()) { i ->
            val c = fixtures.getJSONObject(i)
            val m = mistake(c.getJSONObject("input"))
            val rating = requireNotNull(ReviewRating.parse(c.getString("rating")))
            fun checkSchedule(actual: MistakeSchedule, expected: JSONObject) {
                assertEquals("case $i", expected.getString("state"), actual.state.wire)
                assertEquals("case $i", expected.getString("dueAt"), actual.dueAt)
                assertEquals(expected.getDouble("intervalDays"), actual.intervalDays, 0.000001)
                assertEquals(expected.getDouble("easeFactor"), actual.easeFactor, 0.000001)
                assertEquals(expected.getInt("repetitions"), actual.repetitions)
                assertEquals(expected.getInt("lapses"), actual.lapses)
                assertEquals(expected.getBoolean("resolved"), actual.resolved)
            }
            checkSchedule(MistakeScheduler.getMistakeSchedule(m), c.getJSONObject("schedule"))
            checkSchedule(MistakeScheduler.previewMistakeReview(m, rating, at), c.getJSONObject("preview"))
            val record = MistakeScheduler.recordMistakeReview(m, rating, at, "review-fixture")
            assertTrue("record case $i", JSONObject(record.originalJson).similar(c.getJSONObject("record")))
        }
    }
    @Test fun dueSortIncludesMatureExcludesSuspended() {
        val older = mistake(payload("older").put("dueAt", "2026-09-01T00:00:00.000Z").put("resolved", true))
        val newer = mistake(payload("newer").put("dueAt", at))
        val suspended = mistake(payload("suspended").put("suspended", true))
        val future = mistake(payload("future").put("dueAt", "2027-01-01T00:00:00.000Z"))
        assertEquals(listOf("older", "newer"), MistakeScheduler.getDueMistakes(listOf(newer, suspended, older, future), timestamp(at)).map { it.id })
    }
    @Test fun downloadsNewAndReplacesOlder() = runBlocking {
        val store = Store(); val remote = Remote(); val repo = MistakeRepository(store, remote)
        remote.rows = listOf(row()); assertEquals(1, repo.sync("u").total)
        remote.rows = listOf(row(payload(updated = at).put("correction", "new answer")))
        repo.sync("u"); assertEquals("new answer", repo.cache("u").mistakes["m"]!!.correction)
    }
    @Test fun malformedRowDoesNotBlockOthers() = runBlocking {
        val remote = Remote().apply { rows = listOf(row(), RemoteMistakeRow("bad", "{}", at, null)) }
        val result = MistakeRepository(Store(), remote).sync("u")
        assertEquals(1, result.total); assertEquals(1, result.invalid)
    }
    @Test fun offlineReviewPersistsAndUploadsLater() = runBlocking {
        val store = Store(); val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(store, remote)
        repo.sync("u"); remote.offline = true
        repo.addAttempt("u", attempt()); repo.rate("u", attempt(), ReviewRating.HARD, at)
        assertEquals(setOf("m"), repo.cache("u").pending)
        assertTrue(runCatching { repo.sync("u") }.isFailure)
        // A new repository simulates process restart.
        val restored = MistakeRepository(store, remote)
        assertEquals("p-r", restored.cache("u").attempts.single().practicePageId)
        remote.offline = false; restored.sync("u")
        assertTrue(restored.cache("u").pending.isEmpty())
        assertEquals(ReviewRating.HARD, remote.writes.single().reviewHistory.single().result)
    }
    @Test fun tombstoneNeverResurrectsEvenAfterNewerOfflineReview() = runBlocking {
        val store = Store(); val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(store, remote)
        repo.sync("u"); repo.rate("u", attempt(), ReviewRating.GOOD, at)
        remote.rows = listOf(RemoteMistakeRow("m", null, "2026-09-02T00:00:00.000Z", "2026-09-02T00:00:00.000Z"))
        repo.sync("u"); repo.sync("u")
        assertTrue(repo.cache("u").mistakes.isEmpty()); assertTrue(repo.cache("u").pending.isEmpty())
        assertEquals(1, repo.cache("u").attempts.size); assertTrue(remote.writes.isEmpty())
    }
    @Test fun newerRemoteWinsOverQueuedLocalReviewButKeepsHandwriting() = runBlocking {
        val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(Store(), remote)
        repo.sync("u"); repo.rate("u", attempt(), ReviewRating.GOOD, at)
        remote.rows = listOf(row(payload(updated = "2026-09-17T00:00:00.000Z").put("correction", "latest")))
        repo.sync("u")
        assertEquals("latest", repo.cache("u").mistakes["m"]!!.correction)
        assertEquals(1, repo.cache("u").attempts.size); assertTrue(remote.writes.isEmpty())
    }
    @Test fun conditionalWriteConflictStaysQueued() = runBlocking {
        val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(Store(), remote)
        repo.sync("u"); repo.rate("u", attempt(), ReviewRating.GOOD, at); remote.conflict = true
        assertEquals(1, repo.sync("u").pending); assertTrue(remote.writes.isEmpty())
    }
    @Test fun syncKeepsLatestRemoteUnrelatedFields() = runBlocking {
        val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(Store(), remote)
        repo.sync("u"); repo.rate("u", attempt(), ReviewRating.GOOD, at)
        remote.rows = listOf(row(payload(updated = "2026-09-15T00:00:00.000Z").put("correction", "remote correction").put("newField", 99)))
        repo.sync("u")
        assertEquals("remote correction", remote.writes.single().correction)
        assertEquals(99, JSONObject(remote.writes.single().originalJson).getInt("newField"))
    }
    @Test fun userCachesArePartitioned() = runBlocking {
        val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(Store(), remote)
        repo.sync("u"); repo.rate("u", attempt(), ReviewRating.EASY, at)
        assertTrue(repo.cache("other").mistakes.isEmpty()); assertTrue(repo.cache("other").attempts.isEmpty())
        assertEquals(1, repo.cache("u").mistakes.size)
        assertTrue(runCatching { repo.rate("other", attempt(), ReviewRating.GOOD, at) }.isFailure)
    }
    @Test fun twoReviewsHaveSeparatePagesAndRetryIsIdempotent() = runBlocking {
        val remote = Remote().apply { rows = listOf(row()) }; val repo = MistakeRepository(Store(), remote)
        repo.sync("u")
        repo.rate("u", attempt("r1"), ReviewRating.HARD, at)
        repo.rate("u", attempt("r1"), ReviewRating.HARD, at)
        repo.rate("u", attempt("r2"), ReviewRating.EASY, "2026-09-20T00:00:00.000Z")
        assertEquals(listOf("p-r1", "p-r2"), repo.cache("u").attempts.map { it.practicePageId })
        assertEquals(2, repo.cache("u").mistakes["m"]!!.reviewHistory.size)
    }
    @Test fun practiceMetadataAndInkSurviveFolioBackup() {
        val note = Notebook(id = "n-r", title = "Practice", mistakePractice = true, mistakeReviews = listOf(attempt()),
            pages = listOf(NotePage(id = "p-r", texts = listOf(TextBox(x = 0f, y = 0f, text = "work")))))
        val output = ByteArrayOutputStream(); NotebookArchive.write(note, null, output)
        val restored = NotebookArchive.read(ByteArrayInputStream(output.toByteArray())).note
        assertEquals(note, restored)
        assertFalse(output.toString().contains("refresh_token"))
        assertEquals(note.mistakeReviews, NoteMetaCodec.decode(NoteMetaCodec.encode(note)).mistakeReviews)
    }
    @Test fun versionFiveAndOldNotebookRemainReadable() {
        val note = Notebook(title = "Old")
        val old = JSONObject(NoteMetaCodec.encode(note)).put("version", 5).apply { remove("mistakePractice"); remove("mistakeReviews") }
        val loaded = NoteMetaCodec.decodeVersion5(old.toString())
        assertFalse(loaded.mistakePractice); assertTrue(loaded.mistakeReviews.isEmpty())
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }
}
