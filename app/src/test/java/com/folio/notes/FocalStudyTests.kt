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
}
