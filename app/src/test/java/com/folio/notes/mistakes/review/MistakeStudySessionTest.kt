package com.folio.notes.mistakes.review

import com.folio.notes.app.AppPrefs
import com.folio.notes.mistakes.LocalMistakeReviewAttempt
import com.folio.notes.mistakes.sync.ExamContext
import com.folio.notes.mistakes.sync.ExamTrackMistakeCodec
import com.folio.notes.study.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MistakeStudySessionTest {
    private val now = 1_800_000_000_000L
    private fun card(id: String) = requireNotNull(ExamTrackMistakeCodec.decode(JSONObject()
        .put("id", id).put("attemptId", "exam").put("question", "Question $id").put("category", "Concept")
        .put("explanation", "").put("correction", "").put("createdAt", "2026-01-01T00:00:00Z")
        .put("updatedAt", "2026-01-01T00:00:00Z").toString()))
    private fun attempt(id: String, user: String = "user") = LocalMistakeReviewAttempt(user, id, "review-$id", "note", "page")
    private val methods = ExamContext("Maths Methods", "2026 exam", "Exam 1")

    @Test fun recordingDefaultsOnAndChosenCardKeepsTheQueueAndLimit() {
        assertTrue(AppPrefs.DEFAULT_MISTAKE_RECORD_STUDY)
        val cards = listOf(card("a"), card("b"), card("c"))
        assertEquals(listOf("b", "a", "c"), practiceQueue(cards, 5, cards[1]).map { it.id })
        assertEquals(listOf("c", "a"), practiceQueue(cards, 2, cards[2]).map { it.id })
        assertEquals(listOf("a", "b", "c"), practiceQueue(cards, 5).map { it.id })
        assertEquals(listOf("a"), practiceQueue(emptyList(), 5, cards[0]).map { it.id })
        repeat(10) {
            val shuffled = practiceQueue(cards + cards[0], 5, cards[1], shuffle = true)
            assertEquals("b", shuffled.first().id)
            assertEquals(setOf("a", "b", "c"), shuffled.map { it.id }.toSet())
            assertEquals(3, shuffled.size)
        }
    }

    @Test fun questionsAreBlocksOfOneSessionWithSubjectDurationAndDescription() {
        val session = MistakeStudySession("user")
        session.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now)
        val first = session.entry(now + 30_000)!!
        session.pause(now + 60_000)
        session.resume(attempt("b"), card("b"), methods, FocalSubjects.builtIn, now + 65_000)
        session.pause(now + 185_000)
        val done = session.entry(now + 190_000, completed = true)!!
        assertEquals(first.id, done.id)
        assertEquals("mm", done.subjectId)
        assertEquals("Mistake practice · Mathematical Methods", done.title)
        assertEquals(180_000L, done.activeMillis)
        assertEquals(listOf(FocalStudyInterval(now, now + 60_000), FocalStudyInterval(now + 65_000, now + 185_000)), done.intervals)
        assertTrue(done.notes.contains("2026 exam · Exam 1 · Question a"))
        assertTrue(done.notes.contains("Question b"))
        assertTrue(done.completed)
        val commands = focalCommandsFor(done, "device")
        assertEquals(listOf("start", "pause", "resume", "complete"), commands.map { it.getString("action") })
        assertEquals(setOf(done.id), commands.map { it.getString("session_id") }.toSet())
        val metadata = commands.last().getJSONObject("metadata")
        assertEquals(done.notes, metadata.getString("description"))
        assertEquals(2, metadata.getJSONObject("examtrack").getJSONArray("questions").length())
    }

    @Test fun pausingOrDisablingExcludesTheGapAndRepeatedResumeDoesNotDuplicateTime() {
        val session = MistakeStudySession("user")
        session.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now)
        session.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now + 5_000)
        session.pause(now + 10_000)
        session.pause(now + 50_000)
        session.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now + 100_000)
        session.pause(now + 120_000)
        val done = session.entry(now + 500_000, true)!!
        assertEquals(30_000L, done.activeMillis)
        assertEquals(2, done.intervals.size)
        assertEquals(1, done.notes.lines().size)
        val empty = MistakeStudySession("user")
        empty.resume(attempt("a", "someone-else"), card("a"), methods, FocalSubjects.builtIn, now)
        assertNull(empty.entry(now))
        empty.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now)
        empty.pause(now)
        // Even an immediate pause must close the start that may already have reached Focal.
        assertEquals(now, empty.entry(now, true)!!.intervals.single().endAt)
    }

    @Test fun mixedAndCustomSubjectsKeepThePerQuestionAssignment() {
        val session = MistakeStudySession("user")
        val subjects = FocalSubjects.builtIn + FocalSubject("custom-law", "Legal Studies")
        session.resume(attempt("a"), card("a"), methods, subjects, now)
        session.pause(now + 30_000)
        session.resume(attempt("b"), card("b"), ExamContext("Legal Studies", "SAC", ""), subjects, now + 40_000)
        session.pause(now + 60_000)
        val done = session.entry(now + 60_000, true)!!
        assertEquals("custom-law", done.subjectId)
        assertEquals("Mistake practice · Mathematical Methods & Legal Studies", done.title)
        val spans = focalSubjectSpans(done, done.intervals, now + 60_000)
        assertEquals(listOf("mm", "custom-law"), spans.map { it.second })
        assertEquals(listOf(30_000L, 20_000L), spans.map { requireNotNull(it.first.endAt) - it.first.startAt })
        val questions = JSONObject(done.metadata!!).getJSONObject("examtrack").getJSONArray("questions")
        assertEquals("mm", questions.getJSONObject(0).getString("subjectId"))
        assertEquals("custom-law", questions.getJSONObject(1).getString("subjectId"))
        assertEquals("eng-lang", FocalSubjects.find("eng-lang"))
    }

    @Test fun checkpointFinishesOnlyTheRecoverableCopyAtTheLastHeartbeat() {
        val session = MistakeStudySession("user")
        session.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now)
        val live = session.entry(now + 10_000)!!.copy(synced = true)
        val saved = focalStudyCheckpoint(FocalStudyState(entries = listOf(live)), now + 20_000).entries.single()
        assertTrue(saved.completed)
        assertFalse(saved.synced)
        assertNotEquals(live.changeId, saved.changeId)
        assertEquals(20_000L, saved.activeMillis)
        assertEquals(now + 20_000, saved.intervals.single().endAt)
        assertFalse(live.completed)
        assertNull(live.intervals.single().endAt)
        assertEquals(listOf("start", "complete"), focalCommandsFor(saved, "device").map { it.getString("action") })
        val questions = JSONObject(saved.metadata!!).getJSONObject("examtrack").getJSONArray("questions")
        assertEquals(now + 20_000, questions.getJSONObject(0).getLong("endAt"))
    }

    @Test fun syncedQuestionContinuesWithOnlyTheMissingBoundariesAndKeepsDetails() {
        val session = MistakeStudySession("user")
        session.resume(attempt("a"), card("a"), methods, FocalSubjects.builtIn, now)
        session.pause(now + 60_000)
        val first = session.entry(now + 60_000)!!
        val canonical = JSONObject().put("state", "paused").put("phase", "focus").put("kind", "focus")
            .put("title", first.title).put("subject_id", "mm").put("revision", 2)
            .put("metadata", JSONObject(first.metadata!!))
            .put("segments", org.json.JSONArray().put(JSONObject().put("started_at", com.folio.notes.mistakes.isoTime(now))
                .put("ended_at", com.folio.notes.mistakes.isoTime(now + 60_000))))
        session.resume(attempt("b"), card("b"), methods, FocalSubjects.builtIn, now + 70_000)
        session.pause(now + 100_000)
        val done = session.entry(now + 100_000, true)!!.copy(remotePayload = canonical.toString(), revision = 2)
        val commands = focalCommandsFor(done, "device")
        assertEquals(listOf("resume", "complete"), commands.map { it.getString("action") })
        assertEquals(2L, commands.first().getLong("expected_revision"))
        assertEquals(2, commands.last().getJSONObject("metadata").getJSONObject("examtrack").getJSONArray("questions").length())
        val roundTrip = focalEntryFromCanonical(done.id, canonical, "receipt", "user")!!
        assertEquals(true, JSONObject(roundTrip.metadata!!).getBoolean("mistakePractice"))
    }
}
