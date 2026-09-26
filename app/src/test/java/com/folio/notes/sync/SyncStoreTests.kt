package com.folio.notes.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The store is the only place where "durable" is claimed, so these tests are about what
 * survives: a coalesced queue, a cursor that never moves backwards, and applied versions
 * that round-trip through disk.
 */
class SyncStoreTests {
    private fun store(): SyncStore {
        val dir = Files.createTempDirectory("folio-sync").toFile()
        dir.deleteOnExit()
        return SyncStore(dir)
    }

    @Test
    fun aBurstOfEditsToOneRowCollapsesToOneQueuedChange() {
        val store = store()
        val account = "user-1"
        store.enqueue(account, "mistakes", "m1", "put", JSONObject().put("title", "One"), 1, "2026-01-01T00:00:00.000Z")
        store.enqueue(account, "mistakes", "m1", "put", JSONObject().put("title", "Two"), 2, "2026-01-01T00:00:01.000Z")
        store.enqueue(account, "mistakes", "m2", "put", JSONObject().put("title", "Other"), 3, "2026-01-01T00:00:02.000Z")

        val queue = store.readOutbox(account)
        assertEquals(2, queue.size)
        assertEquals("Two", queue.first { it.rowId == "m1" }.payload?.getString("title"))
        // A later delete replaces the queued put rather than queueing a second change.
        store.enqueue(account, "mistakes", "m1", "delete", null, 4, "2026-01-01T00:00:03.000Z")
        assertEquals(1, store.readOutbox(account).count { it.rowId == "m1" })
        assertEquals("delete", store.readOutbox(account).first { it.rowId == "m1" }.operation)
    }

    @Test
    fun aQueuedChangeSurvivesAReopen() {
        val root = Files.createTempDirectory("folio-sync").toFile()
        val account = "user-1"
        SyncStore(root).enqueue(account, "folio_pages", "p1", "put", JSONObject().put("title", "Page"), 1, "2026-01-01T00:00:00.000Z")
        val reopened = SyncStore(root).readOutbox(account)
        assertEquals(1, reopened.size)
        assertEquals("Page", reopened.single().payload?.getString("title"))
    }

    @Test
    fun theLamportClockNeverRepeatsAndTheCursorNeverGoesBackwards() {
        val store = store()
        val account = "user-1"
        assertEquals(1L, store.nextLamport(account))
        assertEquals(2L, store.nextLamport(account))
        store.writeCursor(account, 40, 0)
        assertEquals(3L, store.nextLamport(account))
        store.writeCursor(account, 10, 0)
        assertEquals(40L to 3L, store.readCursor(account))
    }

    @Test
    fun appliedVersionsRoundTripAndCanBeDropped() {
        val store = store()
        val account = "user-1"
        val rows = listOf(
            SyncProtocol.RowState("mistakes", "m1", "put", JSONObject().put("id", "m1"), 5, "device-a", 12),
            SyncProtocol.RowState("folio_pages", "p1", "delete", null, 7, "device-b", 13)
        )
        store.writeApplied(account, rows)
        // JSONObject has no value equality, so compare the encoded rows.
        assertEquals(
            rows.map { it.toJson().toString() },
            store.readApplied(account).sortedBy { it.seq }.map { it.toJson().toString() }
        )
        store.dropApplied(account, setOf("mistakes:m1"))
        assertEquals(listOf("folio_pages:p1"), store.readApplied(account).map { it.key })
        // Dropping nothing must not rewrite the file.
        store.dropApplied(account, emptySet())
        assertEquals(1, store.readApplied(account).size)
    }

    @Test
    fun aDamagedFileIsReplacedRatherThanFatal() {
        val root = Files.createTempDirectory("folio-sync").toFile()
        val account = "user-1"
        val dir = File(SyncStore(root).accountDir(account).also { it.mkdirs() }, "outbox.json")
        dir.writeText("{not json")
        assertTrue(SyncStore(root).readOutbox(account).isEmpty())
        SyncStore(root).enqueue(account, "mistakes", "m1", "put", JSONObject(), 1, "2026-01-01T00:00:00.000Z")
        assertEquals(1, SyncStore(root).readOutbox(account).size)
    }

    @Test
    fun anAccountIdThatCouldEscapeTheDirectoryIsRejected() {
        val store = store()
        for (unsafe in listOf("../other", "a/b", "")) {
            val failure = runCatching { store.accountDir(unsafe) }.exceptionOrNull()
            assertTrue("expected $unsafe to be rejected", failure is IllegalArgumentException)
        }
    }
}
