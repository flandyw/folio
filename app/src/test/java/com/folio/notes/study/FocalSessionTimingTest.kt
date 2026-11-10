package com.folio.notes.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A row with stored timer stamps and the same row with them derived from `segments` read alike. */
class FocalSessionTimingTest {
    private fun segment(start: String, end: String?) = JSONObject().put("started_at", start).put("ended_at", end ?: JSONObject.NULL)

    private fun row(segments: List<JSONObject>, completed: Boolean = false, started: String? = "2026-11-10T09:00:00Z") =
        JSONObject().put("id", "s").put("kind", "focus").put("title", "Methods Focus").put("revision", 4)
            .put("completed", completed).put("started_at", started ?: JSONObject.NULL)
            .put("updated_at", "2026-11-10T11:00:00Z").put("segments", JSONArray(segments))

    private fun stored(value: JSONObject, running: String?, paused: String?, accumulated: Long) = JSONObject(value.toString())
        .put("segment_started_at", running ?: JSONObject.NULL).put("paused_at", paused ?: JSONObject.NULL)
        .put("accumulated_active_ms", accumulated)

    private fun assertSame(old: JSONObject, new: JSONObject, state: String) {
        val a = focalEntryFromCanonical("s", old, "c", "u")!!
        val b = focalEntryFromCanonical("s", new, "c", "u")!!
        assertEquals(state, focalStateOf(old)); assertEquals(state, focalStateOf(new))
        assertEquals(a.copy(remotePayload = null), b.copy(remotePayload = null))
    }

    @Test fun runningPausedCompletedAndPlannedMatch() {
        val first = segment("2026-11-10T09:00:00Z", "2026-11-10T09:30:00Z")
        val running = row(listOf(first, segment("2026-11-10T10:00:00Z", null)))
        assertSame(stored(running, "2026-11-10T10:00:00Z", null, 1_800_000), running, "running")

        val paused = row(listOf(first))
        assertSame(stored(paused, null, "2026-11-10T09:30:00Z", 1_800_000), paused, "paused")

        val done = row(listOf(first), completed = true).put("completed_at", "2026-11-10T09:30:00Z")
        assertSame(stored(done, null, null, 1_800_000), done, "completed")

        val plan = row(listOf(segment("2026-11-11T09:00:00Z", "2026-11-11T10:00:00Z")), started = null)
        assertSame(stored(plan, null, null, 0), plan, "planned")
    }
}
