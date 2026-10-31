package com.folio.notes.library.files

import com.folio.notes.storage.SharedStorageRules
import org.junit.Assert.*
import org.junit.Test

class FileBrowseRulesTest {
    @Test fun searchMatchesEveryWordInAnyOrder() {
        assertTrue(matchesFileName("VCAA Methods Exam 2 2025.pdf", "2025 methods"))
        assertTrue(matchesFileName("VCAA Methods Exam 2 2025.pdf", "  METHODS\t2025\n"))
        assertFalse(matchesFileName("VCAA Methods Exam 2 2025.pdf", "methods 2024"))
    }

    @Test fun pastedUnicodeSpacesAndEmptySearchAreSupported() {
        assertTrue(matchesFileName("Methods 2025.pdf", "methods\u00a02025"))
        assertTrue(matchesFileName("Methods 2025.pdf", " \u2003 "))
        assertTrue(matchesFileName("Methods 2025.pdf", ""))
    }

    @Test fun namesRejectedBeforeClosingTheDialogMatchTheStorageRules() {
        listOf(".", "..", "", " ", "a/b", "a\\b", "a\u0000b").forEach {
            assertTrue(it, runCatching { SharedStorageRules.validateName(it) }.isFailure)
        }
        listOf("Exam 2.pdf", "New folder", ".notes").forEach {
            SharedStorageRules.validateName(it)
        }
    }
}
