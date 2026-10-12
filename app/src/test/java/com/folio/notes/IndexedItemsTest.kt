package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class IndexedItemsTest {
    private data class Item(val id: String, val value: Int)
    @Test fun randomizedReplacementsMatchListsAndPreserveOldSnapshots() {
        val random = Random(9241)
        var expected = List(2048) { Item("$it", it) }
        var indexed = IndexedItems.of(expected) { it.id }
        repeat(2000) {
            val index = random.nextInt(expected.size)
            val changed = Item("$index", random.nextInt())
            val old = indexed
            indexed = indexed.replace(changed.id, changed)
            assertEquals(expected[index], old[index])
            expected = expected.toMutableList().also { it[index] = changed }
            assertEquals(expected, indexed)
            assertEquals(indexed, expected)
            assertEquals(expected.hashCode(), indexed.hashCode())
            assertSame(changed, indexed.byId(changed.id))
            assertEquals(index, indexed.changedFrom(old))
        }
        assertSame(indexed, indexed.replace("missing", Item("missing", 0)))
    }
    @Test fun equalitySkipsSharedBranches() {
        var comparisons = 0
        class Counted(val id: String) {
            override fun equals(other: Any?): Boolean { comparisons++; return other is Counted && id == other.id }
            override fun hashCode(): Int = id.hashCode()
        }
        val initial = IndexedItems.of(List(16384) { Counted("$it") }) { it.id }
        val edited = initial.replace("8192", Counted("8192"))
        assertEquals(initial, edited)
        assertEquals(1, comparisons)
    }
}
