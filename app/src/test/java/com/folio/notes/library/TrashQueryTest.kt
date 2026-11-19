package com.folio.notes.library

import com.folio.notes.model.Notebook
import com.folio.notes.storage.TrashedNotebook
import org.junit.Assert.assertEquals
import org.junit.Test

class TrashQueryTest {
    private val entries = listOf(
        TrashedNotebook(Notebook(id = "z", title = "Zoology", pages = emptyList()), 20),
        TrashedNotebook(Notebook(id = "a", title = "algebra", pages = emptyList()), 30),
        TrashedNotebook(Notebook(id = "b", title = "Biology", pages = emptyList()), 10)
    )

    @Test fun sortsByDeletedDateByDefaultAndOldestFirst() {
        assertEquals(listOf("a", "z", "b"), queryTrash(entries).map { it.notebook.id })
        assertEquals(listOf("b", "z", "a"), queryTrash(entries, sort = TrashSort.OLDEST).map { it.notebook.id })
    }

    @Test fun sortsNamesIgnoringCaseInBothDirections() {
        assertEquals(listOf("a", "b", "z"), queryTrash(entries, sort = TrashSort.NAME_ASC).map { it.notebook.id })
        assertEquals(listOf("z", "b", "a"), queryTrash(entries, sort = TrashSort.NAME_DESC).map { it.notebook.id })
    }

    @Test fun filtersTitleSubstringsIgnoringCaseAndTrimsQuery() {
        assertEquals(listOf("b", "z"), queryTrash(entries, " OLoG ", TrashSort.NAME_ASC).map { it.notebook.id })
        assertEquals(emptyList<TrashedNotebook>(), queryTrash(entries, "missing"))
    }

    @Test fun handlesEmptyInputsAndBlankQueriesWithoutChangingSource() {
        TrashSort.entries.forEach { sort ->
            assertEquals(emptyList<TrashedNotebook>(), queryTrash(emptyList(), "anything", sort))
            assertEquals(3, queryTrash(entries, "  ", sort).size)
        }
        assertEquals(listOf("z", "a", "b"), entries.map { it.notebook.id })
    }

    @Test fun equalSortKeysKeepTheirOriginalOrder() {
        val tied = entries.map { it.copy(deletedAt = 1, notebook = it.notebook.copy(title = "Same")) }
        TrashSort.entries.forEach { assertEquals(tied, queryTrash(tied, sort = it)) }
    }
}
