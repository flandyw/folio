package com.folio.notes.progress

import com.folio.notes.sync.ApplyReceipt
import com.folio.notes.sync.ApplyResult
import com.folio.notes.sync.ApplyStale
import com.folio.notes.sync.ReadResult
import com.folio.notes.sync.SyncProtocol.Change
import com.folio.notes.sync.SyncProtocol.QueuedChange
import com.folio.notes.sync.SyncProtocol.RowState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSyncTest {
    private fun row(id: String, title: String, seq: Long) = RowState("events", id, "put", JSONObject().put("title", title), 1, "me", seq)
    private fun queued(id: String, change: String, title: String, expected: Long) =
        QueuedChange(change, "events", id, "put", JSONObject().put("title", title), 2, "", expected)
    private fun cache(vararg queued: QueuedChange) = ProgressCache(
        rows = queued.associate { it.key to row(it.rowId, it.payload!!.getString("title"), it.expectedSeq ?: 0) },
        outbox = queued.associateBy { it.key })
    private fun ProgressCache.title(id: String) = rows.getValue("events:$id").payload!!.getString("title")

    @Test fun oneReplySettlesAWholeBatch() {
        val a = queued("a", "c1", "A", 0); val b = queued("b", "c2", "B", 3)
        val next = cache(a, b).settle(listOf(a, b), ApplyResult(listOf(ApplyReceipt("c1", 10, false)),
            listOf(ApplyStale("c2", row("b", "Theirs", 7))), 10))
        assertEquals(setOf("events:b"), next.outbox.keys)
        assertEquals(10, next.rows.getValue("events:a").seq)
        assertEquals("Theirs", next.conflicts.getValue("events:b").payload!!.getString("title"))
    }

    @Test fun anEditSavedWhileTheFirstWasOnTheWireFollowsIt() {
        val sent = queued("a", "c1", "First", 5)
        val newer = queued("a", "c2", "Second", 5)
        val next = cache(newer).settle(listOf(sent), ApplyResult(listOf(ApplyReceipt("c1", 9, false)), emptyList(), 9))
        assertEquals(9L, next.outbox.getValue("events:a").expectedSeq)
        assertEquals("Second", next.title("a"))
        // The first edit's echo in the log is neither a collision nor allowed to replace the newer local text.
        val echoed = next.absorb(ReadResult("changes", 0, 9, listOf(Change(9, "c1", "me", "events", "a", "put",
            JSONObject().put("title", "First"), 1, "")), emptyList()))
        assertEquals("Second", echoed.title("a"))
        assertTrue(echoed.conflicts.isEmpty())
        assertEquals(9, echoed.cursor)
    }

    @Test fun anotherDevicesChangeOverAPendingEditIsAConflictAndOwnEchoClearsTheOutbox() {
        val mine = queued("a", "c1", "Mine", 5)
        fun page(change: String, seq: Long) = ReadResult("changes", 0, seq, listOf(Change(seq, change, "other", "events", "a", "put",
            JSONObject().put("title", "Theirs"), 1, "")), emptyList())
        val clash = cache(mine).absorb(page("x", 6))
        assertEquals("Mine", clash.title("a"))
        assertEquals(6, clash.conflicts.getValue("events:a").seq)
        val echo = cache(mine).absorb(page("c1", 6))
        assertTrue(echo.outbox.isEmpty() && echo.conflicts.isEmpty())
    }

    @Test fun aSnapshotKeepsPendingEditsAndFlagsRowsCompactedAway() {
        val kept = queued("a", "c1", "Mine", 5); val gone = queued("b", "c2", "Lost", 4)
        val synced = cache(kept, gone).copy(rows = cache(kept, gone).rows + ("events:old" to row("old", "Old", 1)))
        val next = synced.absorb(ReadResult("snapshot", 0, 20, emptyList(), listOf(row("a", "Server", 5), row("c", "New", 12))))
        assertEquals("Mine", next.title("a"))
        assertEquals("New", next.title("c"))
        assertNull(next.rows["events:old"])
        assertEquals("delete", next.conflicts.getValue("events:b").operation)
        assertEquals(20, next.cursor)
    }
}
