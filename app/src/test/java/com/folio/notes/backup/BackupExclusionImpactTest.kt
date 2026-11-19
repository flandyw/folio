package com.folio.notes.backup

import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import org.junit.Assert.*
import org.junit.Test

class BackupExclusionImpactTest {
    private val notes = listOf(
        Notebook(id = "a", title = "Algebra", pages = List(3) { NotePage() }),
        Notebook(id = "b", title = "Biology", pages = List(1) { NotePage() }),
        Notebook(id = "c", title = "Chemistry", pages = List(5) { NotePage() }),
    )

    @Test fun countsNotebooksAndPagesOnEachSide() {
        val impact = BackupExclusionImpact.of(notes, setOf("c"))
        assertEquals(BackupExclusionImpact(2, 4, 1, 5), impact)
        assertFalse(impact.nothingProtected)
        assertEquals("2 notebooks (4 pages) still protected by library backups.", impact.protectedSummary)
        assertEquals("1 notebook (5 pages) excluded of 3.", impact.excludedSummary)
    }

    @Test fun nothingExcludedProtectsEverything() {
        val impact = BackupExclusionImpact.of(notes, emptySet())
        assertEquals("3 notebooks (9 pages) still protected by library backups.", impact.protectedSummary)
        assertEquals("Nothing excluded.", impact.excludedSummary)
    }

    @Test fun excludingEveryNotebookWarns() {
        val impact = BackupExclusionImpact.of(notes, setOf("a", "b", "c"))
        assertTrue(impact.nothingProtected)
        assertEquals("No notebooks are protected by library backups.", impact.protectedSummary)
    }

    @Test fun unknownIdsAndAnEmptyLibraryNeverWarn() {
        assertEquals(BackupExclusionImpact(3, 9, 0, 0), BackupExclusionImpact.of(notes, setOf("gone")))
        val empty = BackupExclusionImpact.of(emptyList(), setOf("a"))
        assertFalse(empty.nothingProtected)
        assertEquals("No notebooks to back up yet.", empty.protectedSummary)
    }

    @Test fun singularWording() {
        val impact = BackupExclusionImpact.of(notes, setOf("a", "c"))
        assertEquals("1 notebook (1 page) still protected by library backups.", impact.protectedSummary)
    }
}
