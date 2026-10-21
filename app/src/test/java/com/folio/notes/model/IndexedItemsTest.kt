package com.folio.notes.model

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

    @Test fun traversalAndBoundsFollowTheListContractForUnevenTrees() {
        for (size in listOf(0, 1, 2, 3, 17, 1025)) {
            val expected = List(size) { Item("$it", it) }
            val indexed = IndexedItems.of(expected) { it.id }
            val iterator = indexed.iterator()
            expected.forEach { assertTrue(iterator.hasNext()); assertSame(it, iterator.next()) }
            assertFalse(iterator.hasNext())
            assertThrows(NoSuchElementException::class.java) { iterator.next() }
            assertThrows(IndexOutOfBoundsException::class.java) { indexed[-1] }
            assertThrows(IndexOutOfBoundsException::class.java) { indexed[size] }
            assertEquals(expected, indexed.toList())
            assertEquals(expected.hashCode(), indexed.hashCode())
            assertEquals(-1, indexed.indexOfId("missing"))
            expected.forEachIndexed { index, item -> assertEquals(index, indexed.indexOfId(item.id)) }
        }
    }

    @Test fun sharedTreeChangesIncludeMultipleReplacementsInDocumentOrder() {
        val initial = IndexedItems.of(List(1025) { Item("$it", it) }) { it.id }
        val first = Item("1000", -1)
        val second = Item("2", -2)
        val changed = initial.replace(first.id, first).replace(second.id, second)
        assertEquals(listOf(second, first), changed.changedItemsFrom(initial))
        assertEquals(emptyList<Item>(), initial.changedItemsFrom(initial))
        assertEquals(listOf(first), initial.replace(first.id, first).changedItemsFrom(initial))
        assertNull(changed.changedItemsFrom(IndexedItems.of(initial.reversed()) { it.id }))
        assertNull(changed.changedItemsFrom(initial.toList()))
        assertSame(initial[500], changed[500])
        assertEquals(1000, initial.byId("1000")!!.value)
    }
}
