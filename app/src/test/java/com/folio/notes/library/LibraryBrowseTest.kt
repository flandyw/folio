package com.folio.notes.library

import com.folio.notes.model.Folder
import com.folio.notes.model.Notebook
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowseTest {
    private val folders = listOf(Folder("maths", "Maths"), Folder("term", "Term 1", "maths"))
    private val notes = listOf(
        Notebook(id = "inside", title = "Algebra", folderId = "maths", starred = true),
        Notebook(id = "child", title = "Algebra practice", folderId = "term"),
        Notebook(id = "outside", title = "Algebra revision"),
        Notebook(id = "other", title = "Music"),
    )

    @Test fun broadeningSearchKeepsTheTermAndIncludesNotebooksOutsideTheFolder() {
        val scoped = LibraryBrowse.notebooks(notes, folders, LibraryPlace.ALL, "maths", null, "algebra")
        assertEquals(setOf("inside", "child"), scoped.map { it.id }.toSet())
        val everywhere = LibraryBrowse.notebooks(notes, folders, LibraryPlace.ALL, null, null, "algebra")
        assertEquals(setOf("inside", "child", "outside"), everywhere.map { it.id }.toSet())
    }

    @Test fun clearingSearchStaysInTheFolderAndRestoresItsDirectChildren() {
        val shown = LibraryBrowse.notebooks(notes, folders, LibraryPlace.ALL, "maths", null, "")
        assertEquals(listOf("inside"), shown.map { it.id })
        assertEquals(listOf("term"), LibraryBrowse.folders(folders, LibraryPlace.ALL, "maths", null).map { it.id })
    }

    @Test fun favouritesAndTagsStayScopedUntilSearchIsBroadened() {
        assertEquals(listOf("inside"), LibraryBrowse.notebooks(notes, folders, LibraryPlace.FAVORITES, null, null, "algebra").map { it.id })
        assertTrue(LibraryBrowse.notebooks(notes, folders, LibraryPlace.ALL, null, "biology", "algebra").isEmpty())
    }
}
