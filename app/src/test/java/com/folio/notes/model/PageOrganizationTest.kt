package com.folio.notes.model

import org.junit.Assert.*
import org.junit.Test

class PageOrganizationTest {
    @Test fun journalRevisionSurvivesLoadingAlongsideNewerPageOrganisation() {
        val summary = NotePage(id = "page", revision = 3, loaded = false,
            title = "Renamed", bookmarked = true, redoFlag = true, height = 1600f)
        val disk = summary.copy(revision = 8, loaded = true, title = "Old name", bookmarked = false,
            redoFlag = false, height = 1188f, texts = listOf(TextBox(x = 1f, y = 2f, text = "Recovered ink")))
        val loaded = summary.withLoadedContent(disk)
        assertEquals(8, loaded.revision)
        assertTrue(loaded.loaded)
        assertSame(disk.texts, loaded.texts)
        assertEquals("Renamed", loaded.title)
        assertTrue(loaded.bookmarked)
        assertTrue(loaded.redoFlag)
        assertEquals(1600f, loaded.height, 0f)
        assertEquals(9, summary.copy(revision = 9).withLoadedContent(disk).revision)
    }
}
