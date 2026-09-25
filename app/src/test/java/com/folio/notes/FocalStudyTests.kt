package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class FocalStudyTests {
    @Test fun notebookMetadataAndTitleSuggestFocalSubjectIds() {
        assertEquals("mm", FocalSubjects.suggest(Notebook(title = "Practice", exam = ExamTags(subject = VceSubject.MATHS_METHODS))))
        assertEquals("eng-lang", FocalSubjects.suggest(Notebook(title = "English Language revision")))
        assertEquals("chem", FocalSubjects.suggest(Notebook(title = "Workbook", exam = ExamTags(subjectText = "Chemistry"))))
        assertNull(FocalSubjects.suggest(Notebook(title = "Untitled")))
    }

    @Test fun pausedFocusExcludesBreakTime() {
        val started = FocalFocus("note", "Study", "phys", 1_000L, 1_000L)
        val paused = started.pause(61_000L)
        assertEquals(60_000L, paused.elapsed(200_000L))
        assertEquals(70_000L, paused.resume(200_000L).elapsed(210_000L))
    }

    @Test fun completedPayloadMatchesFocalV2StudySessionShape() {
        val entry = FocalStudyEntry(id = "971b1116-d15f-4c79-8bf1-0ffbdbafdd12",
            changeId = "56ddc8e0-080d-4295-a541-3cb6fbe8c50a", notebookId = "notebook",
            title = "Methods exam", subjectId = "mm", kind = "exam",
            startedAt = 1_000L, endedAt = 91_000L, activeMillis = 90_000L,
            notes = "Calculus", confidence = 4)
        val payload = focalPayload(entry)
        assertEquals(2, payload.getInt("schemaVersion"))
        assertEquals("mm", payload.getJSONArray("subjectIds").getString(0))
        assertEquals("completed", payload.getJSONObject("execution").getString("state"))
        assertEquals("manual", payload.getJSONObject("execution").getJSONArray("intervals").getJSONObject(0).getString("source"))
        assertEquals(4, payload.getJSONObject("reflection").getInt("confidence"))
        assertEquals(1, payload.getJSONObject("execution").getInt("reportedMinutes"))
        assertEquals("manual", payload.getString("createdVia"))
    }

    @Test fun examProgressKeepsOneFocalRowUntilCompletion() {
        val note = Notebook(title = "Methods practice", exam = ExamTags(subject = VceSubject.MATHS_METHODS))
        val preset = ExamTimerPreset("Practice", writingSeconds = 120, readingSeconds = 30)
        val start = 1_000L
        val reading = ExamTimerState().start(preset, start)
        val first = examStudyEntry(note, reading, start, null)
        val readingPayload = focalPayload(first)
        assertFalse(first.completed)
        assertEquals("in-progress", readingPayload.getJSONObject("execution").getString("state"))
        assertEquals(0, readingPayload.getJSONObject("execution").getJSONArray("intervals").length())
        assertFalse(readingPayload.getJSONObject("execution").has("completedAt"))

        val writing = reading.tick(start + 60_000L)
        val progress = examStudyEntry(note, writing, start + 60_000L, first)
        assertEquals(first.id, progress.id)
        assertNotEquals(first.changeId, progress.changeId)
        assertEquals("mm", progress.subjectId)
        assertEquals(30_000L, progress.activeMillis)
        assertEquals(1, focalPayload(progress).getJSONObject("execution").getJSONArray("intervals").length())

        val finished = examStudyEntry(note, writing.pause(start + 70_000L), start + 80_000L,
            progress, completed = true)
        assertEquals(first.id, finished.id)
        assertEquals(40_000L, finished.activeMillis)
        assertEquals("completed", focalPayload(finished).getJSONObject("execution").getString("state"))
    }

    @Test fun examStoppedDuringReadingDeletesItsProvisionalRow() {
        val note = Notebook(title = "Exam")
        val timer = ExamTimerState().start(ExamTimerPreset("Practice", 120, 30), 1_000L)
        val provisional = examStudyEntry(note, timer, 10_000L, null)
        val stopped = examStudyEntry(note, timer, 20_000L, provisional, completed = true)
        assertEquals(provisional.id, stopped.id)
        assertTrue(stopped.deleted)
        assertEquals(0L, stopped.activeMillis)
    }

    @Test fun examWritingIntervalsDoNotCoverPausedTime() {
        val note = Notebook(title = "Exam")
        val timer = ExamTimerState().start(ExamTimerPreset("Practice", 120, 30), 1_000L)
        val first = examStudyEntry(note, timer.tick(61_000L), 61_000L, null)
        val paused = timer.pause(71_000L)
        val pauseRecord = examStudyEntry(note, paused, 71_000L, first)
        val resumed = paused.unpause(81_000L)
        val resumedRecord = examStudyEntry(note, resumed.tick(91_000L), 91_000L, pauseRecord)
        assertEquals(2, resumedRecord.intervals.size)
        assertEquals(71_000L, resumedRecord.intervals[0].endAt)
        assertEquals(81_000L, resumedRecord.intervals[1].startAt)
        assertEquals(2, focalPayload(resumedRecord).getJSONObject("execution").getJSONArray("intervals").length())
    }
}
