package com.folio.notes.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Runs the shared sync conformance vectors against this client's implementation. Focal
 * runs the same file from `src/lib/sync/vectors/conformance.json`; if the two ever
 * disagree about ordering, tombstones or backoff, one of the two builds fails instead of
 * somebody's reviews or notes going missing.
 *
 * The digest is checked with the digest field zeroed, so editing the vectors without
 * updating the recorded digest in both repositories is itself a failure.
 */
class SyncConformanceTests {
    private fun loadVectors(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("conformance.json")
            ?: error("conformance.json is missing from the test resources")
        val raw = stream.readBytes().toString(Charsets.UTF_8)
        val vectors = JSONObject(raw)
        assertEquals("focal-sync/3", vectors.getString("protocol"))
        val zeroed = raw.replace(Regex("\"sha256\": \"[0-9a-f]{64}\""), "\"sha256\": \"${"0".repeat(64)}\"")
        val digest = MessageDigest.getInstance("SHA-256").digest(zeroed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "conformance vectors were edited without updating their digest",
            vectors.getString("sha256"), digest
        )
        return vectors
    }

    /** Sorted-key JSON, so two structurally equal values always render identically. */
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            "$key:${canonical(value.opt(key))}"
        }
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun assertCase(name: String, actual: Any?, expected: Any?) {
        assertEquals("sync conformance: $name", canonical(expected), canonical(actual))
    }

    private fun changes(array: JSONArray) = array.objects().mapNotNull { SyncProtocol.parseChange(normalizeKeys(it)) }
    private fun rows(array: JSONArray) = array.objects().mapNotNull { SyncProtocol.parseRowState(normalizeKeys(it)) }

    /** Vectors are written in the protocol's own vocabulary; the wire uses snake_case. */
    private fun normalizeKeys(o: JSONObject): JSONObject = o

    private fun queued(o: JSONObject) = SyncProtocol.QueuedChange(
        changeId = o.getString("changeId"),
        entity = o.getString("entity"),
        rowId = o.getString("rowId"),
        operation = o.getString("operation"),
        payload = o.optJSONObject("payload"),
        lamport = o.optLong("lamport"),
        createdAt = o.getString("createdAt")
    )

    private fun queueable(change: SyncProtocol.QueuedChange) = JSONObject()
        .put("changeId", change.changeId)
        .put("entity", change.entity)
        .put("rowId", change.rowId)
        .put("operation", change.operation)
        .put("payload", change.payload ?: JSONObject.NULL)
        .put("lamport", change.lamport)
        .put("createdAt", change.createdAt)

    @Test
    fun matchesTheSharedVectors() {
        val vectors = loadVectors()
        val cases = vectors.getJSONArray("cases")
        var executed = 0
        for (index in 0 until cases.length()) {
            val testCase = cases.getJSONObject(index)
            val name = testCase.getString("name")
            when (testCase.getString("kind")) {
                "reduce" -> {
                    val result = SyncProtocol.reduceChanges(
                        ownClientId = testCase.getString("ownClientId"),
                        cursor = testCase.getLong("cursor"),
                        state = rows(testCase.getJSONArray("state")),
                        pending = testCase.getJSONArray("pending").let { pending ->
                            (0 until pending.length()).map { pending.getString(it) }.toSet()
                        },
                        changes = changes(testCase.getJSONArray("changes"))
                    )
                    val expected = testCase.getJSONObject("expected")
                    assertCase("$name (cursor)", result.cursor, expected.getLong("cursor"))
                    assertCase(
                        "$name (applied)",
                        JSONArray().apply { result.applied.forEach { put(it.changeId) } },
                        expected.getJSONArray("applied")
                    )
                    assertCase(
                        "$name (deferred)",
                        JSONArray().apply { result.deferred.forEach { put(it.changeId) } },
                        expected.getJSONArray("deferred")
                    )
                    assertCase(
                        "$name (state)",
                        JSONArray().apply { result.state.forEach { put(it.toJson()) } },
                        expected.getJSONArray("state")
                    )
                }
                "snapshot" -> {
                    val pending = testCase.getJSONArray("pending")
                    val result = SyncProtocol.reduceSnapshot(
                        pending = (0 until pending.length()).map { pending.getString(it) }.toSet(),
                        state = rows(testCase.getJSONArray("state")),
                        rows = rows(testCase.getJSONArray("rows")),
                        head = testCase.getLong("head")
                    )
                    val expected = testCase.getJSONObject("expected")
                    assertCase("$name (cursor)", result.cursor, expected.getLong("cursor"))
                    assertCase(
                        "$name (state)",
                        JSONArray().apply { result.state.forEach { put(it.toJson()) } },
                        expected.getJSONArray("state")
                    )
                }
                "coalesce" -> assertCase(
                    name,
                    JSONArray().apply {
                        SyncProtocol.coalesce(testCase.getJSONArray("input").objects().map { queued(it) })
                            .forEach { put(queueable(it)) }
                    },
                    testCase.getJSONArray("expected")
                )
                "backoff" -> assertCase(
                    name,
                    JSONArray().apply {
                        val input = testCase.getJSONArray("input")
                        for (i in 0 until input.length()) put(SyncProtocol.retryDelayMs(input.getInt(i)))
                    },
                    testCase.getJSONArray("expected")
                )
                else -> error("unknown conformance case kind: ${testCase.getString("kind")}")
            }
            executed++
        }
        assertTrue("no conformance vectors ran", executed >= 10)
    }

    @Test
    fun anUnpublishedTombstoneSurvivesASnapshotThatPredatesIt() {
        // The server's materialized state can still hold the older put while this device's
        // delete is only queued. The queued delete wins locally, or the row would come back.
        val localDelete = SyncProtocol.RowState("folio_pages", "page-1", "delete", null, 9, "device-a", 20)
        val result = SyncProtocol.reduceSnapshot(
            pending = setOf("folio_pages:page-1"),
            state = listOf(localDelete),
            rows = listOf(localDelete.copy(operation = "put", payload = JSONObject().put("title", "Back"), lamport = 4, seq = 9)),
            head = 30
        )
        assertEquals("delete", result.state.single().operation)
        assertEquals(30L, result.cursor)
    }

    @Test
    fun aSnapshotReplacesStaleLocalRowsThatWereNeverEditedHere() {
        val result = SyncProtocol.reduceSnapshot(
            pending = emptySet(),
            state = listOf(SyncProtocol.RowState("folio_pages", "page-1", "put", JSONObject().put("title", "Stale"), 3, "device-a", 5)),
            rows = listOf(SyncProtocol.RowState("folio_pages", "page-1", "put", JSONObject().put("title", "Fresh"), 8, "device-b", 25)),
            head = 30
        )
        assertEquals("Fresh", result.state.single().payload?.getString("title"))
    }

    @Test
    fun retryBlocksAfterTheCeilingAndStopsScheduling() {
        var change = SyncProtocol.QueuedChange("c", "mistakes", "m", "put", JSONObject(), 1, "2026-01-01T00:00:00.000Z")
        repeat(7) { change = SyncProtocol.retry(change, "nope", 0, 8) }
        assertEquals(300_000L, SyncProtocol.retryDelayMs(8))
        change = SyncProtocol.retry(change, "nope", 0, 8)
        assertTrue(change.blockedAt != null)
        assertTrue(!SyncProtocol.isDue(change, Long.MAX_VALUE))
    }
}
