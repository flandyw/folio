package com.folio.notes.storage

import com.folio.notes.model.Folder
import org.junit.Assert.*
import org.junit.Test

class LibraryFolderNameTest {
    private val folders = listOf(
        Folder("maths", "Maths"),
        Folder("term", "Term 1", "maths"),
        Folder("other", "Other"),
    )

    @Test fun duplicatesAreTrimmedCaseInsensitiveAndLimitedToSiblings() {
        assertNotNull(LibraryFolders.nameError(folders, "  MATHS  ", null))
        assertNotNull(LibraryFolders.nameError(folders, "term 1", "maths"))
        assertNull(LibraryFolders.nameError(folders, "Term 1", "other"))
        assertNull(LibraryFolders.nameError(folders, "Maths", "maths"))
    }

    @Test fun renameExcludesItselfButNotItsSiblings() {
        assertNull(LibraryFolders.nameError(folders, " Maths ", null, "maths"))
        assertNotNull(LibraryFolders.nameError(folders, "Other", null, "maths"))
    }

    @Test fun invalidNamesAndMissingParentsHaveAnExplanation() {
        for (name in listOf("", " \n ", "x".repeat(121))) {
            assertFalse(LibraryFolders.nameError(folders, name, null).isNullOrBlank())
        }
        assertNotNull(LibraryFolders.nameError(folders, "New", "deleted"))
        assertNull(LibraryFolders.nameError(folders, "  " + "x".repeat(120) + " ", null))
        assertNull(LibraryFolders.nameError(folders, "代数 📚", null))
    }
}
