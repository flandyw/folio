package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalStudyTests {
    @Test fun customSubjectsApplyOrderedFeedUpdatesAndTombstones() {
        val subjects = mutableMapOf<String, FocalSubject>()
        focalApplyCustomSubject(subjects, "custom-1", "put", JSONObject("""{"id":"custom-1","name":"Data Science"}"""))
        assertEquals("Data Science", subjects["custom-1"]?.name)
        focalApplyCustomSubject(subjects, "custom-1", "put", JSONObject("""{"name":"Statistics"}"""))
        assertEquals("Statistics", subjects["custom-1"]?.name)
        focalApplyCustomSubject(subjects, "custom-1", "delete", null)
        assertTrue(subjects.isEmpty())
    }

    @Test fun timerProjectionUsesServerTimeAndMonotonicElapsedDespiteWallClockSkew() {
        val serverStart = java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
        val anchor = FocalServerClockAnchor(serverStart, 1_000L)
        val wallClockTenMinutesFast = serverStart + 600_000L
        assertEquals(serverStart + 10_000L, focalEstimatedServerNow(anchor, 11_000L, wallClockTenMinutesFast))
    }

    @Test fun lifecycleElapsedSurvivesProcessRestartButNotDeviceReboot() {
        assertEquals(4_900L, focalElapsedSince(100L, 12, 5_000L, 12, 9_000L))
        assertEquals(0L, focalElapsedSince(100L, 12, 5_000L, 13))
        assertEquals(0L, focalElapsedSince(5_000L, 12, 100L, 12, 9_000L))
        assertEquals(9_000L, focalElapsedSince(null, -1, 5_000L, 12, 9_000L))
        assertEquals(604_800_000L, focalElapsedSince(null, -1, 5_000L, 12, 900_000_000L))
    }

    @Test fun processRecoveryUsesOnlyDurableActiveTimeSinceCanonicalBoundary() {
        assertEquals(180_000L, focalRecoveryElapsed(300_000L, 120_000L))
        assertEquals(0L, focalRecoveryElapsed(90_000L, 120_000L))
        assertEquals(604_800_000L, focalRecoveryElapsed(900_000_000L, 0L))
    }

    @Test fun canonicalTimingBoundaryRecoversWithoutDeviceWallTime() {
        val boundary = "2026-09-28T00:00:00Z"
        val serverNow = java.time.Instant.parse(boundary).toEpochMilli() + 42_000L
        assertEquals(42_000L, focalElapsedFromServerBoundary(boundary, serverNow))
        assertEquals(null, focalElapsedFromServerBoundary(boundary,
            java.time.Instant.parse(boundary).toEpochMilli() - 1))
        assertEquals(null, focalElapsedFromServerBoundary(boundary, serverNow + 604_800_001L))
        assertEquals(null, focalElapsedFromServerBoundary(null, serverNow))
    }

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
        val command = focalStudyCommand(merged.copy(paused = true), "pause", 8,
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        val metadata = command.getJSONObject("metadata")
        assertEquals("notebook", metadata.getJSONObject("folio").getString("notebook_id"))
        assertEquals("page", metadata.getJSONObject("legacy_metadata").getJSONObject("integrations")
            .getJSONObject("notion").getString("id"))
        assertEquals("focus", focalStudyCommand(active(), "start", 0,
            "11111111-1111-4111-8111-111111111111").getString("kind"))
    }

    @Test fun anUnsyncedEntryPublishesExactlyTheDifferenceFromTheServer() {
        val device = "11111111-1111-4111-8111-111111111111"
        val running = """{"state":"running","phase":"focus","kind":"focus","title":"Study","subject_id":"mm","metadata":{}}"""
        val base = active().copy(synced = false, remotePayload = running)
        // The server already has this session running, so a timer checkpoint is one save.
        val checkpoint = focalCommandsFor(base, device)
        assertEquals(1, checkpoint.size)
        assertEquals("save_progress", checkpoint.first().getString("action"))
        assertEquals(7L, checkpoint.first().getLong("expected_revision"))
        // A reflection edit is the same one call, and a synced entry needs none at all.
        assertEquals("save_progress", focalCommandsFor(base.copy(notes = "Remember this"), device).first().getString("action"))
        assertTrue(focalCommandsFor(base.copy(synced = true), device).isEmpty())
        // An entry the server has never seen gets the whole lifecycle, in order.
        val fresh = focalCommandsFor(active().copy(synced = false, remotePayload = null), device)
        assertEquals(listOf("start"), fresh.map { it.getString("action") })
    }

    @Test fun canonicalMetadataKeepsExamDetailsAndDropsDuplicateTimerBoundaries() {
        val legacy = org.json.JSONObject("""{"provider":"VCAA","examYear":2026,"paper":"1","marks":100,"readingMinutes":15,"writingMinutes":165,"workspaceItems":[{"id":"q1"}],"execution":{"intervals":[{"startedAt":1}]},"status":"running"}""")
        val command = focalStudyCommand(active().copy(kind = "exam", remotePayload = legacy.toString()), "start", 0,
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        val preserved = command.getJSONObject("metadata").getJSONObject("legacy_metadata")
        assertEquals("VCAA", preserved.getString("provider"))
        assertEquals(2026, preserved.getInt("examYear"))
        assertEquals(100, preserved.getInt("marks"))
        assertEquals("q1", preserved.getJSONArray("workspaceItems").getJSONObject(0).getString("id"))
        assertFalse(preserved.has("execution"))
        assertFalse(preserved.has("status"))
    }

    @Test fun pendingBoundariesSurviveServerEcho() {
        val remote = active().copy(changeId = "old", notebookId = null, revision = 8)
        for (local in listOf(
            active().copy(changeId = "pause", synced = false, paused = true),
            active().copy(changeId = "finish", synced = false, completed = true),
            active().copy(changeId = "discard", synced = false, deleted = true)
        )) {
            val merged = focalMergeSession(local, remote)
            assertEquals(local.paused, merged.paused)
            assertEquals(local.completed, merged.completed)
            assertEquals(local.deleted, merged.deleted)
            assertEquals(8L, merged.revision)
        }
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

    @Test fun recoveryDoesNotCountAnUncheckpointedOpenInterval() {
        val focus = FocalFocus(notebookId = "notebook", title = "Study", subjectId = "mm",
            startedAt = 0, resumedAt = 60_000, accumulatedMillis = 30_000,
            intervals = listOf(FocalStudyInterval(0, 30_000), FocalStudyInterval(60_000, null)))
        val recovered = focalRecoverFocus(active(), focus)
        assertEquals(listOf(30_000L, 60_000L), recovered.intervals.map { it.endAt })
        assertEquals(30_000L, recovered.activeMillis)
    }

    @Test fun plannedCalendarRowsDoNotBlockFocus() {
        assertFalse(active().copy(planned = true).active)
        assertFalse(FocalStudyState(entries = listOf(active().copy(planned = true))).hasActiveSession)
        assertFalse(active().copy(deleted = true).active)
        assertFalse(active().copy(completed = true).active)
    }

    @Test fun staleSharedSessionsDoNotBlockStartingLocalStudy() {
        val stale = active().copy(paused = true, notebookId = null,
            intervals = listOf(FocalStudyInterval(0, 60_000)))
        val state = FocalStudyState(entries = List(8) { stale.copy(id = "stale-$it") })
        assertTrue(state.hasActiveSession)
        assertTrue(state.canStartFocus)
        assertFalse(state.copy(focus = FocalFocus(notebookId = "new", title = "Study",
            subjectId = "mm", startedAt = 60_000, resumedAt = null)).canStartFocus)
    }

    @Test fun commandRebaseIsLimitedToValidNonterminalTransitions() {
        assertTrue(focalMutationCanRebase("pause", "running"))
        assertTrue(focalMutationCanRebase("resume", "paused"))
        assertTrue(focalMutationCanRebase("complete", "planned"))
        assertFalse(focalMutationCanRebase("pause", "completed"))
        assertFalse(focalMutationCanRebase("start", "running"))
        assertFalse(focalMutationCanRebase("create", "planned"))
    }

    @Test fun completedLocalSessionWithDurationStartsBeforeCompletion() {
        val completed = active().copy(changeId = "finish", completed = true, synced = false,
            remotePayload = null, revision = 0)
        val commands = focalCommandsFor(completed, "11111111-1111-4111-8111-111111111111")
        assertEquals(listOf("start", "complete"), commands.map { it.getString("action") })
    }

    @Test fun pausedSharedEntriesCanBeFinishedOrDiscarded() {
        val paused = active().copy(notebookId = null, paused = true,
            intervals = listOf(FocalStudyInterval(0, 60_000)))
        val finished = focalControlledEntry(paused, "finish", 120_000)!!
        assertTrue(finished.completed)
        assertFalse(finished.active)
        assertEquals(60_000L, finished.activeMillis)
        assertEquals("completed", focalDesiredState(finished))

        val discarded = focalControlledEntry(paused, "discard", 120_000)!!
        assertTrue(discarded.deleted)
        assertFalse(discarded.active)
        assertFalse(discarded.synced)
    }

    @Test fun activeStatusReflectsActualSyncNotTimerState() {
        val state = FocalStudyState(entries = listOf(active().copy(userId = "user")),
            userId = "user", configured = true)
        assertTrue(state.hasActiveSession)
        assertEquals("Synced with Focal", state.syncStatus)
        assertEquals("Connecting to Focal…", state.copy(syncing = true).syncStatus)
        assertEquals("Focal sync needs attention", state.copy(error = "offline").syncStatus)
        assertEquals("Connecting to Focal…", state.copy(error = "offline", syncing = true).syncStatus)
        assertEquals("Sending saved session 1 of 2 to Focal…",
            state.copy(syncing = true, syncDetail = "Sending saved session 1 of 2 to Focal…").syncStatus)
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

    @Test fun pauseThenResumePublishesTheSharedProtocolVector() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("study-session-command-sequence.json")!!
            .bufferedReader().use { org.json.JSONObject(it.readText()) }
        val steps = fixture.getJSONArray("steps")
        val deviceId = fixture.getString("device_id")
        val sessionId = fixture.getString("session_id")
        val focus = FocalFocus(notebookId = "notebook", title = fixture.getString("title"), subjectId = fixture.getString("subject_id"),
            startedAt = 1_000, resumedAt = 1_000, sessionId = sessionId,
            intervals = listOf(FocalStudyInterval(1_000, null)))
        // One call per user action now, so the fixture is replayed step by step: each entry is
        // diffed against the state the server is known to be in, which is the previous step.
        val server = arrayOfNulls<JSONObject>(steps.length())
        val entry = active().copy(id = sessionId, title = fixture.getString("title"), subjectId = fixture.getString("subject_id"),
            startedAt = 1_000, endedAt = 121_000, paused = false, synced = false, revision = 0, remotePayload = null)
        val commands = (0 until steps.length()).map { index ->
            val step = steps.getJSONObject(index)
            val current = when (index) {
                0 -> entry
                1 -> entry.copy(paused = true, revision = 1)
                else -> entry.copy(paused = false, revision = 2)
            }.copy(changeId = step.getString("mutation_id"), synced = false,
                remotePayload = if (index == 0) null else server[index - 1]?.toString())
            val derived = focalCommandsFor(current, deviceId)
            assertEquals(step.getString("action"), derived.first().getString("action"))
            assertEquals(step.getLong("expected_revision"), derived.first().getLong("expected_revision"))
            assertEquals(sessionId, derived.first().getString("session_id"))
            assertEquals(deviceId, derived.first().getString("device_id"))
            assertFalse(derived.first().has("started_at"))
            // The server answers with the next revision; that is the next step's input.
            server[index] = JSONObject().put("state", when (step.getString("action")) {
                "pause" -> "paused"; "resume" -> "running"; else -> "running"
            }).put("revision", step.getLong("expected_revision") + 1)
            derived.first()
        }

        assertEquals(fixture.getString("protocol"), "study-session/1")
        assertEquals((0 until steps.length()).map { steps.getJSONObject(it).getString("action") }, commands.map { it.getString("action") })
        assertEquals((0 until steps.length()).map { steps.getJSONObject(it).getLong("expected_revision") }, commands.map { it.getLong("expected_revision") })
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
        val canonicalCalendar = allDay.copy(remotePayload = """{"metadata":{"legacy_metadata":{"createdVia":"notion","integrations":{"notion":{"kind":"event"}}}}}""")
        assertTrue(focalIsCalendarPlaceholder(canonicalCalendar))
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
