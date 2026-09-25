package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalStudyTests {
    @Test fun activeTimerCheckpointsAreNotUploadedAsSeparateSessions() {
        val active = FocalStudyEntry(notebookId = "n", title = "Study", subjectId = "pe", kind = "study",
            startedAt = 0, endedAt = 10_000, activeMillis = 10_000, completed = false)
        val published = active.copy(synced = true)
        val completed = published.copy(synced = false, completed = true, endedAt = 20_000, activeMillis = 20_000)
        val discarded = published.copy(synced = false, deleted = true)

        assertTrue(focalShouldUpload(active))
        assertFalse(focalShouldUpload(published))
        assertTrue(focalShouldUpload(completed))
        assertTrue(focalShouldUpload(discarded))
    }

    @Test fun importedAllDayCalendarIntervalDoesNotCountAsStudy() {
        val day = 24L * 60 * 60 * 1_000
        val allDay = FocalStudyEntry(notebookId = null, title = "All-day event", subjectId = null,
            kind = "study", startedAt = 0, endedAt = day, activeMillis = day,
            intervals = listOf(FocalStudyInterval(0, day)),
            remotePayload = """{"createdVia":"notion","execution":{"intervals":[{"source":"imported"}]}}""")
        val studied = FocalStudyEntry(notebookId = null, title = "Study", subjectId = "mm",
            kind = "study", startedAt = 0, endedAt = 79L * 60_000, activeMillis = 79L * 60_000,
            intervals = listOf(FocalStudyInterval(0, 79L * 60_000)))

        assertTrue(focalIsCalendarPlaceholder(allDay))
        assertEquals(79L * 60_000, focalStudyMillisBetween(listOf(allDay, studied), 0, day))
        assertFalse(focalIsCalendarPlaceholder(allDay.copy(remotePayload =
            """{"createdVia":"manual","execution":{"intervals":[{"source":"manual"}]}}""")))
        assertEquals(day, focalStudyMillisBetween(listOf(allDay.copy(remotePayload = null)), 0, day))
    }

    @Test fun eventProvenanceDoesNotCountAsStudy() {
        val entry = FocalStudyEntry(notebookId = null, title = "Calendar event", subjectId = null,
            kind = "study", startedAt = 0, endedAt = 60_000, activeMillis = 60_000,
            intervals = listOf(FocalStudyInterval(0, 60_000)),
            remotePayload = """{"integrations":{"notion":{"kind":"event"}}}""")
        assertEquals(0L, focalStudyMillisBetween(listOf(entry), 0, 60_000))
    }
}
