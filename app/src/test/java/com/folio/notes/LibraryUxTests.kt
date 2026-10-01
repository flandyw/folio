package com.folio.notes

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class LibraryUxTests {
    @Test fun searchAcceptsPastedWhitespaceAndTermsAcrossPages() {
        val note = Notebook(title = "Calculus practice", pages = listOf(NotePage(title = "Integration")))
        for (separator in listOf(" ", "  ", "\t", "\n", "\u00a0", "\u2003")) {
            assertTrue(matchesQuery(note, "CALCULUS${separator}integration"))
        }
        assertTrue(matchesQuery(note, "\t\n "))
        assertFalse(matchesQuery(note, "calculus chemistry"))
    }

    @Test fun filteringAndSortingDoNotMutateTheLibrary() {
        val first = Notebook(id = "a", title = "Alpha", folderId = "folder", starred = true, updated = 1)
        val second = Notebook(id = "b", title = "Beta", updated = 2)
        val library = listOf(first, second)
        assertEquals(listOf(first), organizeNotebooks(library, folderId = "folder"))
        assertEquals(listOf(first), organizeNotebooks(library, starred = true))
        assertEquals(listOf(second), organizeNotebooks(library, unfiled = true))
        assertEquals(listOf(second, first), organizeNotebooks(library))
        assertEquals(listOf(first, second), library)
    }

    @Test fun recencyUsesCalendarDaysAcrossDaylightSavingChanges() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val zone = ZoneId.systemDefault()
            fun timestamp(date: String) = LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            assertEquals("Yesterday", libraryLastEditedLabel(timestamp("2026-03-08"), timestamp("2026-03-09")))
            assertEquals("2 days ago", libraryLastEditedLabel(timestamp("2026-03-07"), timestamp("2026-03-09")))
            assertEquals("Yesterday", libraryLastEditedLabel(timestamp("2026-11-01"), timestamp("2026-11-02")))
            assertEquals("Today", libraryLastEditedLabel(timestamp("2026-03-10"), timestamp("2026-03-09")))
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }
}
