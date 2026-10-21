package com.folio.notes.document

import com.folio.notes.model.IndexedItems
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.TextBox
import com.folio.notes.model.page
import com.folio.notes.model.replacePage
import com.folio.notes.model.withPage
import com.folio.notes.storage.PageEdit
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content
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

    @Test fun loadedPageReplacementPreservesOrderMetadataAndSharedPages() {
        val before = note().copy(pages = IndexedItems.of(note().pages) { it.id })
        val loaded = before.pages[1].copy(texts = edit.texts!!)
        val after = before.withPage(loaded)
        assertEquals(before.title, after.title)
        assertEquals(listOf("a", "b"), after.pages.map { it.id })
        assertSame(before.pages[0], after.pages[0])
        assertSame(loaded, after.pages[1])
        assertEquals(listOf(loaded), (after.pages as IndexedItems<NotePage>).changedItemsFrom(before.pages))
        assertSame(before.pages, before.withPage(NotePage(id = "missing")).pages)
    }

    @Test fun groupedHistoryRejectsIncompleteStaleOrUnloadedMembers() {
        val created = DocumentReducer.group(note(), mapOf("a" to edit, "b" to edit), emptyMap(), "g", 10, 0)!!
        val step = created.notebook.documentHistory.undo.single()
        val tops = created.transactions.mapValues { it.value.undoPush }
        fun plan(note: Notebook = created.notebook, top: Map<String, PageEdit?> = tops, candidate: DocumentHistoryStep = step) =
            DocumentReducer.history(note, candidate, top, true, 11, "undo", 1)
        assertNull(plan(top = tops - "b"))
        assertNull(plan(top = tops + ("b" to tops.getValue("b")!!.copy(groupId = null))))
        assertNull(plan(top = tops + ("b" to tops.getValue("b")!!.copy(actionSequence = 9))))
        assertNull(plan(note = created.notebook.copy(pages = created.notebook.pages.filterNot { it.id == "b" })))
        assertNull(plan(note = created.notebook.withPage(created.pages.getValue("b").copy(loaded = false))))
        assertNull(plan(candidate = step.copy(sequence = 9)))
        assertEquals(listOf(step), created.notebook.documentHistory.undo)
        assertTrue(created.notebook.pages.all { it.texts == edit.texts })
    }

    @Test fun groupedHistoryFollowsCurrentPageOrderAndPreservesUnchangedPages() {
        val initial = note().copy(pages = note().pages + NotePage(id = "c", loaded = false))
        val created = DocumentReducer.group(initial, linkedMapOf("b" to edit, "a" to edit), emptyMap(), "g", 10, 0)!!
        val reordered = created.notebook.copy(pages = IndexedItems.of(created.notebook.pages.reversed()) { it.id })
        val step = reordered.documentHistory.undo.single()
        val undone = DocumentReducer.history(reordered, step, created.transactions.mapValues { it.value.undoPush }, true, 11, "undo", 1)!!
        assertEquals(listOf("b", "a"), undone.transactions.keys.toList())
        assertEquals(listOf("b", "a"), undone.pages.keys.toList())
        assertSame(reordered.pages[0], undone.notebook.pages[0])
        assertTrue(undone.notebook.pages.drop(1).all { it.texts.isEmpty() })
        assertEquals(listOf("a", "b"), undone.notebook.documentHistory.redo.single().pages)
        assertEquals("undo", undone.notebook.documentHistory.lastCommit)
        assertEquals(11L, undone.notebook.documentHistory.redo.single().sequence)
        assertTrue(undone.transactions.values.all { it.undoPop && it.redoPush != null && it.undoPush == null && !it.redoPop })
    }
}
