package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class DocumentHistoryAvailabilityTest {
    private val step = DocumentHistoryStep("g", listOf("a", "b"), 1)
    private fun note() = Notebook(id = "n", title = "Notebook",
        pages = IndexedItems.of(listOf(NotePage(id = "a"), NotePage(id = "b", loaded = false))) { it.id },
        documentHistory = DocumentHistory(enabled = true, undo = listOf(step)))

    @Test fun contentMetadataAndLoadChangesReuseMembershipValidation() {
        val cache = DocumentHistoryAvailability()
        val note = note()
        val available = cache.forNotebook(note)
        assertEquals(listOf(step), available.undo)
        val edited = note.withPage(note.pages[0].copy(revision = 3, texts = listOf(TextBox(x = 0f, y = 0f, text = "Answer"))))
            .withPage(note.pages[1].copy(loaded = true)).copy(title = "Renamed")
        assertSame(available, cache.forNotebook(edited))
    }

    @Test fun deletionRestorationLedgerChangesAndNotebookSwitchesInvalidateTheCache() {
        val cache = DocumentHistoryAvailability()
        val note = note()
        assertEquals(listOf(step), cache.forNotebook(note).undo)
        val deleted = note.copy(pages = IndexedItems.of(note.pages.take(1)) { it.id })
        assertTrue(cache.forNotebook(deleted).undo.isEmpty())
        assertEquals(listOf(step), cache.forNotebook(note).undo)
        val moved = note.copy(documentHistory = note.documentHistory.moved(step, true, 2))
        assertTrue(cache.forNotebook(moved).undo.isEmpty())
        assertEquals(listOf(step.copy(sequence = 2)), cache.forNotebook(moved).redo)
        assertTrue(cache.forNotebook(note.copy(id = "other", documentHistory = DocumentHistory())).undo.isEmpty())
        assertEquals(listOf(step), cache.forNotebook(note).undo)
        assertTrue(cache.forNotebook(null).undo.isEmpty())
        assertTrue(cache.forNotebook(null).redo.isEmpty())
        assertEquals(listOf(step), cache.forNotebook(note).undo)
    }

    @Test fun plainListsAreValidatedWithoutUsingTheirIdentityAsACacheKey() {
        val cache = DocumentHistoryAvailability()
        val pages = mutableListOf(NotePage(id = "a"), NotePage(id = "b"))
        val note = note().copy(pages = pages)
        assertEquals(listOf(step), cache.forNotebook(note).undo)
        pages.removeAt(1)
        assertTrue(cache.forNotebook(note).undo.isEmpty())
    }
}
