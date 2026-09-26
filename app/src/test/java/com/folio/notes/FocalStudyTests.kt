package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalStudyTests {
    private fun active() = FocalStudyEntry(id = "session", notebookId = "notebook", title = "Study",
        subjectId = "mm", kind = "study", startedAt = 0, endedAt = 60_000,
        activeMillis = 60_000, completed = false, synced = true, revision = 7)

    @Test fun notionRoundTripCannotTurnLocalExamIntoNewSitting() {
        val local = active().copy(kind = "exam", notebookId = "notebook", startedAt = 1234)
        val remote = active().copy(kind = "study", notebookId = null, startedAt = 1000, revision = 8,
            remotePayload = """{"integrations":{"notion":{"type":"notion","id":"page"}}}""")
        val merged = focalMergeSession(local, remote)
        assertEquals("exam", merged.kind)
        assertEquals(1234L, merged.startedAt)
        assertEquals("notebook", merged.notebookId)
        val payload = focalPayload(merged.copy(paused = true))
        assertEquals("paused", payload.getJSONObject("integrations").getJSONObject("folio").getString("phase"))
        assertEquals("session", payload.getJSONObject("integrations").getJSONObject("folio").getString("id"))
        assertEquals("page", payload.getJSONObject("integrations").getJSONObject("notion").getString("id"))
        assertEquals("study", focalPayload(active()).getJSONObject("integrations").getJSONObject("folio").getString("kind"))
    }

    @Test fun pendingBoundariesSurviveServerEcho() {
        val remote = active().copy(changeId = "old", notebookId = null, revision = 8)
        for (local in listOf(
            active().copy(changeId = "pause", synced = false, paused = true),
            active().copy(changeId = "finish", synced = false, completed = true),
            active().copy(changeId = "discard", synced = false, deleted = true)
        )) assertEquals(local, focalMergeSession(local, remote))
    }

    @Test fun remoteTerminationWinsAndRetainsLocalIdentity() {
        val local = active().copy(synced = false)
        for (remote in listOf(
            active().copy(notebookId = null, deleted = true, revision = 8),
            active().copy(notebookId = null, completed = true, revision = 8)
        )) {
            val merged = focalMergeSession(local, remote)
            assertEquals("notebook", merged.notebookId)
            assertTrue(merged.deleted || merged.completed)
            assertTrue(merged.synced)
        }
    }

    @Test fun acknowledgementAndStalePullAreSafe() {
        val local = active().copy(changeId = "pending", synced = false)
        assertTrue(focalMergeSession(local, local.copy(synced = true, revision = 8)).synced)
        assertEquals(local, focalMergeSession(local, active().copy(revision = 6, deleted = true)))
    }

    @Test fun recoveryPublishesPauseInsteadOfResumingFromOldEcho() {
        val focus = FocalFocus(notebookId = "notebook", title = "Study", subjectId = "mm",
            startedAt = 0, resumedAt = null, accumulatedMillis = 60_000,
            intervals = listOf(FocalStudyInterval(0, 60_000)))
        val remote = active().copy(intervals = listOf(FocalStudyInterval(0, null)))
        val recovered = focalRecoverFocus(remote, focus)
        assertTrue(recovered.paused)
        assertFalse(recovered.synced)
        assertEquals(60_000L, recovered.intervals.single().endAt)
        assertEquals(recovered, focalMergeSession(recovered, remote))
    }

    @Test fun plannedCalendarRowsDoNotBlockFocus() {
        assertFalse(active().copy(planned = true).active)
        assertFalse(FocalStudyState(entries = listOf(active().copy(planned = true))).hasActiveSession)
        assertFalse(active().copy(deleted = true).active)
        assertFalse(active().copy(completed = true).active)
    }

    @Test fun activeStatusReflectsActualSyncNotTimerState() {
        val state = FocalStudyState(entries = listOf(active().copy(userId = "user")),
            userId = "user", configured = true)
        assertTrue(state.hasActiveSession)
        assertEquals("Synced with Focal", state.syncStatus)
        assertEquals("Syncing with Focal", state.copy(syncing = true).syncStatus)
        assertEquals("Focal sync needs attention", state.copy(error = "offline").syncStatus)
        assertEquals("1 waiting to sync", state.copy(entries = listOf(active().copy(synced = false))).syncStatus)
    }

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

    @Test fun importedCalendarBlockShorterThanDayDoesNotInflateToday() {
        val minute = 60_000L
        val imported = FocalStudyEntry(notebookId = null, title = "Calendar block", subjectId = "mm",
            kind = "study", startedAt = 0, endedAt = 1_401 * minute, activeMillis = 1_401 * minute,
            intervals = listOf(FocalStudyInterval(0, 1_401 * minute)),
            remotePayload = """{"createdVia":"notion","execution":{"intervals":[{"source":"imported"}]}}""")
        val studied = FocalStudyEntry(notebookId = "n", title = "Study", subjectId = "mm",
            kind = "study", startedAt = 0, endedAt = 149 * minute, activeMillis = 149 * minute,
            intervals = listOf(FocalStudyInterval(0, 149 * minute)))

        assertTrue(focalIsCalendarPlaceholder(imported))
        assertEquals(149 * minute, focalStudyMillisBetween(listOf(imported, studied), 0, 1_440 * minute))
        assertFalse(focalIsCalendarPlaceholder(imported.copy(remotePayload =
            """{"createdVia":"manual","execution":{"intervals":[{"source":"manual"}]}}""")))
    }

    @Test fun eventProvenanceDoesNotCountAsStudy() {
        val entry = FocalStudyEntry(notebookId = null, title = "Calendar event", subjectId = null,
            kind = "study", startedAt = 0, endedAt = 60_000, activeMillis = 60_000,
            intervals = listOf(FocalStudyInterval(0, 60_000)),
            remotePayload = """{"integrations":{"notion":{"kind":"event"}}}""")
        assertEquals(0L, focalStudyMillisBetween(listOf(entry), 0, 60_000))
    }
}
