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

    @Test fun duplicateTitleNeverCollides() {
        assertEquals("Copy of Algebra", duplicateNotebookTitle("Algebra", emptySet()))
        assertEquals("Copy of Algebra", duplicateNotebookTitle("  Algebra  ", setOf("Algebra")))
        assertEquals("Copy of Algebra (2)", duplicateNotebookTitle("Algebra", setOf("Algebra", "Copy of Algebra")))
        assertEquals("Copy of Algebra (3)", duplicateNotebookTitle("Algebra", setOf("Algebra", "Copy of Algebra", "Copy of Algebra (2)")))
        assertEquals("Copy of Notebook", duplicateNotebookTitle("   ", emptySet()))
    }

    @Test fun duplicatedCopyHasFreshIdsAndNoAttempts() {
        val anchorPage = NotePage(id = "p1", title = "Q1")
        val note = Notebook(
            id = "n1", title = "Methods 2022",
            pages = listOf(anchorPage, NotePage(id = "p2", peekAnchor = PeekAnchor("p1", 0f, 0f, 10f, 10f))),
            exam = ExamTags(subject = VceSubject.MATHS_METHODS),
            attempts = listOf(ExamAttempt(score = 30, total = 40)),
            starred = true
        )
        val copy = note.duplicatedAsCopy("Copy of Methods 2022", now = 123L)
        assertEquals("Copy of Methods 2022", copy.title)
        assertEquals(123L, copy.updated)
        assertEquals(false, copy.starred)
        assertEquals(emptyList<ExamAttempt>(), copy.attempts)
        assertEquals(VceSubject.MATHS_METHODS, copy.exam.subject)
        assertEquals(2, copy.pages.size)
        assertEquals(false, copy.id == note.id)
        assertEquals(true, copy.pages.none { it.id == "p1" || it.id == "p2" })
        assertEquals(true, copy.pages.map { it.id }.toSet().size == 2)
        // The peek anchor follows its page into the copy.
        val anchor = copy.pages.mapNotNull { it.peekAnchor }.single()
        assertEquals(copy.pages[0].id, anchor.pageId)
    }

    @Test fun lastEditedLabelReadsRelative() {
        // Fixed midday so day boundaries are stable regardless of when the test runs.
        val now = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 22, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 86_400_000L
        assertEquals("Today", libraryLastEditedLabel(now, now))
        assertEquals("Today", libraryLastEditedLabel(now + day, now))
        assertEquals("Yesterday", libraryLastEditedLabel(now - day, now))
        assertEquals("3 days ago", libraryLastEditedLabel(now - 3 * day, now))
        assertEquals("6 days ago", libraryLastEditedLabel(now - 6 * day, now))
        // Older dates fall back to a short date, with a year when it is not this year.
        assertEquals(
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                .format(java.util.Date(now - 10 * day)),
            libraryLastEditedLabel(now - 10 * day, now)
        )
        assertEquals(
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(now - 400 * day)),
            libraryLastEditedLabel(now - 400 * day, now)
        )
    }
}
