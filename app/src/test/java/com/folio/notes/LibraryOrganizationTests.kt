package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrganizationTests {
    private val notes = listOf(
        Notebook(id = "a", title = "Algebra", folderId = "school", starred = true, updated = 30),
        Notebook(id = "b", title = "biology", updated = 10, pages = listOf(NotePage(pdfIndex = 0), NotePage(pdfIndex = 1))),
        Notebook(id = "c", title = "Art", starred = true, updated = 20)
    )

    @Test fun filtersCombineWithoutLeakingOtherFoldersOrTypes() {
        assertEquals(listOf("a"), organizeNotebooks(notes, folderId = "school", starred = true, query = " ALG ").map { it.id })
        assertEquals(listOf("c"), organizeNotebooks(notes, starred = true, unfiled = true).map { it.id })
        assertEquals(listOf("b"), organizeNotebooks(notes, kind = LibraryKind.PDFS).map { it.id })
        assertEquals(listOf("a", "c"), organizeNotebooks(notes, kind = LibraryKind.NOTEBOOKS).map { it.id })
        assertEquals(emptyList<Notebook>(), organizeNotebooks(notes, folderId = "school", kind = LibraryKind.PDFS))
    }

    @Test fun sortingHandlesDatesCaseAndPageCounts() {
        assertEquals(listOf("a", "c", "b"), organizeNotebooks(notes).map { it.id })
        assertEquals(listOf("b", "c", "a"), organizeNotebooks(notes, sort = LibrarySort.OLDEST).map { it.id })
        assertEquals(listOf("a", "c", "b"), organizeNotebooks(notes, sort = LibrarySort.NAME).map { it.id })
        assertEquals(listOf("b", "a", "c"), organizeNotebooks(notes, sort = LibrarySort.PAGES).map { it.id })
    }
}
