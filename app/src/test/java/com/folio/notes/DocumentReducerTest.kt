package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class DocumentReducerTest {
    private fun note() = Notebook(id = "n", title = "Notebook", pages = listOf(NotePage(id = "a", revision = 3), NotePage(id = "b", revision = 9)))
    private val edit = PageEdit(texts = listOf(TextBox(id = "t", x = 1f, y = 2f, text = "Answer")))

    @Test fun groupRejectsStaleMissingOrUnloadedMembersBeforeChangingAnything() {
        val before = note()
        assertNull(DocumentReducer.group(before, mapOf("a" to edit, "b" to edit), mapOf("a" to 2), "g", 10, 0))
        assertNull(DocumentReducer.group(before, mapOf("missing" to edit), emptyMap(), "g", 10, 0))
        val unloaded = before.copy(pages = before.pages.replacePage(before.pages[1].copy(loaded = false)))
        assertNull(DocumentReducer.group(unloaded, mapOf("a" to edit, "b" to edit), emptyMap(), "g", 10, 0))
        assertTrue(before.pages.all { it.texts.isEmpty() })
        assertFalse(before.documentHistory.enabled)
    }

    @Test fun groupPlansRevisionedMembersAndExactUndoInDocumentOrder() {
        val before = note()
        val change = DocumentReducer.group(before, linkedMapOf("b" to edit, "a" to edit), mapOf("a" to 3, "b" to 9), "g", 10, 123)!!
        assertEquals(listOf("a", "b"), change.transactions.keys.toList())
        assertEquals(listOf(4, 10), change.notebook.pages.map { it.revision })
        assertEquals(listOf("a", "b"), change.notebook.documentHistory.undo.single().pages)
        assertEquals(123L, change.notebook.updated)
        change.transactions.forEach { (id, transaction) ->
            assertEquals("g", transaction.forward.groupId)
            assertEquals(10L, transaction.undoPush!!.actionSequence)
            assertEquals(before.pages.page(id)!!.content(), PageJournal.apply(change.pages.getValue(id).content(), transaction.undoPush))
        }
    }

    @Test fun pagePlanningPreservesMetadataOnlyEditsAndKnownStrokeFastPath() {
        val before = NotePage(id = "a")
        val metadata = DocumentReducer.page(before, before.copy(title = "Question"))
        assertNull(metadata.forward); assertNull(metadata.inverse); assertEquals(1, metadata.page.revision)
        val after = before.copy(texts = edit.texts!!)
        val inverse = PageJournal.invert(edit, before.content())
        val change = DocumentReducer.page(before, after, edit to inverse)
        assertSame(edit, change.forward); assertSame(inverse, change.inverse)
    }
}
