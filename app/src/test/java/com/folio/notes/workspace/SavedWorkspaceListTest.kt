package com.folio.notes.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SavedWorkspaceListTest {
    private fun entries() = SavedWorkspaceList.parse("""[{"name":"First","editor":"a","companion":"b","editorPosition":"saved-editor-position","companionPosition":"saved-companion-position","fraction":0.6,"right":true,"linked":true,"offset":2,"future":[1,2]},{"name":" Second ","editor":"c","companion":"d"}]""")

    private fun assertSnapshotEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
        expected.keys().forEach { key -> assertEquals(expected.get(key).toString(), actual.get(key).toString()) }
    }

    @Test fun renameTrimsNameAndKeepsSnapshotAndOtherEntries() {
        val original = entries()
        val renamed = SavedWorkspaceList.rename(original, 0, "  Study  ")
        assertEquals("Study", renamed[0].getString("name"))
        val expected = JSONObject(original[0].toString()).put("name", "Study")
        assertSnapshotEquals(expected, renamed[0])
        assertEquals("First", original[0].getString("name"))
        assertSame(original[1], renamed[1])
        assertNull(SavedWorkspaceList.renameError(original, 0, " FIRST "))
    }

    @Test fun blankAndDuplicateNamesAreRejectedWithReasons() {
        val items = entries()
        for (name in listOf("", " \t\n", "second", " SECOND ")) {
            assertFalse(SavedWorkspaceList.renameError(items, 0, name).isNullOrBlank())
            assertThrows(IllegalArgumentException::class.java) { SavedWorkspaceList.rename(items, 0, name) }
        }
        assertNotNull(SavedWorkspaceList.renameError(emptyList(), 0, "Study"))
    }

    @Test fun movesSwapNeighboursWithoutChangingSnapshots() {
        val items = entries() + JSONObject().put("name", "Third")
        val down = SavedWorkspaceList.move(items, 0, 1)
        assertEquals(listOf(items[1], items[0], items[2]), down)
        assertEquals(items, SavedWorkspaceList.move(down, 1, -1))
        assertEquals(listOf(items[0], items[2], items[1]), SavedWorkspaceList.move(items, 2, -1))
        assertEquals("First", items[0].getString("name"))
    }

    @Test fun boundariesAndEmptyListsDoNotMove() {
        val items = entries()
        assertSame(items, SavedWorkspaceList.move(items, 0, -1))
        assertSame(items, SavedWorkspaceList.move(items, items.lastIndex, 1))
        assertSame(items, SavedWorkspaceList.move(items, -1, 1))
        assertSame(items, SavedWorkspaceList.move(items, items.size, -1))
        assertSame(items, SavedWorkspaceList.move(items, 0, 2))
        val empty = emptyList<JSONObject>()
        assertSame(empty, SavedWorkspaceList.move(empty, 0, 1))
        val single = items.take(1)
        assertSame(single, SavedWorkspaceList.move(single, 0, 1))
        assertSame(single, SavedWorkspaceList.move(single, 0, -1))
    }

    @Test fun existingJsonParsesAndRoundTripsInListOrder() {
        val items = entries()
        assertEquals(listOf("First", " Second "), items.map { it.getString("name") })
        val moved = SavedWorkspaceList.move(items, 0, 1)
        val parsed = SavedWorkspaceList.parse(JSONArray(moved).toString())
        assertEquals(moved.size, parsed.size)
        moved.indices.forEach { assertSnapshotEquals(moved[it], parsed[it]) }
        assertEquals(" Second ", parsed[0].getString("name"))
        assertTrue(SavedWorkspaceList.parse("[]").isEmpty())
        assertTrue(SavedWorkspaceList.parse("broken").isEmpty())
        assertTrue(SavedWorkspaceList.parse(null).isEmpty())
        assertEquals(1, SavedWorkspaceList.parse("[null,5,{},false]").size)
    }
}
