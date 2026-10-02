package com.folio.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SyncProtocolReductionTests {
    @Test fun indexedReducersMatchListScanningForRepeatedKeysAndMixedDecisions() {
        val random = Random(37)
        repeat(300) {
            fun row() = SyncProtocol.RowState(
                listOf("mistakes", "attempts")[random.nextInt(2)], "row-${random.nextInt(12)}",
                if (random.nextBoolean()) "put" else "delete", null,
                random.nextLong(8), "device-${random.nextInt(3)}", random.nextLong(30)
            )
            val state = List(random.nextInt(40)) { row() }
            val pending = List(4) { row().key }.toSet()
            val changes = List(random.nextInt(60)) { index ->
                val row = row()
                SyncProtocol.Change(row.seq, "change-$index", row.clientId, row.entity,
                    row.rowId, row.operation, row.payload, row.lamport, "")
            }
            val cursor = random.nextLong(10)
            assertEquals(scanChanges(state, pending, changes, cursor),
                SyncProtocol.reduceChanges("device-0", cursor, state, pending, changes))
            val snapshot = List(random.nextInt(50)) { row() }
            assertEquals(scanSnapshot(state, pending, snapshot),
                SyncProtocol.reduceSnapshot(pending, state, snapshot, 100))
        }
    }

    @Test fun largeBatchRetainsInsertionOrderWhileReplacingTheFirstDuplicateOnly() {
        val state = List(5_000) { row(it.toString(), 1) } + row("0", 99)
        val changes = (9_999 downTo 0).map { index ->
            val row = row(index.toString(), 2)
            SyncProtocol.Change(index + 1L, "change-$index", row.clientId, row.entity,
                row.rowId, row.operation, null, row.lamport, "")
        }
        val result = SyncProtocol.reduceChanges("own", 0, state, emptySet(), changes)
        assertEquals(10_000L, result.cursor)
        assertEquals(10_001, result.state.size)
        assertEquals(10_000, result.applied.size)
        assertEquals(2L, result.state.first().lamport)
        assertEquals(99L, result.state[5_000].lamport)
        assertEquals("5000", result.state[5_001].rowId)
        assertEquals("9999", result.state.last().rowId)
    }

    private fun row(id: String, version: Long) =
        SyncProtocol.RowState("mistakes", id, "delete", null, version, "remote", version)

    // The pre-index implementation is the parity oracle, including duplicate-list behavior.
    private fun scanChanges(
        state: List<SyncProtocol.RowState>, pending: Set<String>,
        changes: List<SyncProtocol.Change>, cursor: Long
    ): SyncProtocol.ReduceResult {
        val next = state.toMutableList()
        val applied = mutableListOf<SyncProtocol.Change>()
        val deferred = mutableListOf<SyncProtocol.Change>()
        var high = cursor
        for (change in changes.sortedBy { it.seq }) {
            if (change.seq <= cursor) continue
            when (SyncProtocol.decide(change, next.firstOrNull { it.key == change.key }, pending, "device-0")) {
                SyncProtocol.Decision.APPLY -> {
                    val index = next.indexOfFirst { it.key == change.key }
                    val row = SyncProtocol.toRowState(change)
                    if (index == -1) next.add(row) else next[index] = row
                    applied.add(change)
                }
                SyncProtocol.Decision.DEFERRED -> deferred.add(change)
                else -> Unit
            }
            high = maxOf(high, change.seq)
        }
        return SyncProtocol.ReduceResult(next, high, applied, deferred)
    }

    private fun scanSnapshot(
        state: List<SyncProtocol.RowState>, pending: Set<String>, rows: List<SyncProtocol.RowState>
    ): SyncProtocol.ReduceResult {
        val next = state.filter { it.key in pending }.toMutableList()
        for (row in rows) {
            if (row.key in pending) continue
            val index = next.indexOfFirst { it.key == row.key }
            if (index == -1) next.add(row) else next[index] = row
        }
        return SyncProtocol.ReduceResult(next, 100, emptyList(), emptyList())
    }
}
