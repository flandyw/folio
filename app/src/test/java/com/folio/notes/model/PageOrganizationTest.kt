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

    @Test fun pageSearchSplitsOnUnicodeSpacesAndKeepsPageNumbersAndFilters() {
        val pages = listOf(NotePage(id = "a", title = "Linear algebra", bookmarked = true),
            NotePage(id = "b", title = "Calculus"), NotePage(id = "c", title = "Algebra linear review", redoFlag = true))
        listOf("linear algebra", "LINEAR algebra", " linear  \talgebra ").forEach {
            assertEquals(it, listOf(0, 2), organizePages(pages, it).map { hit -> hit.index })
        }
        assertEquals(listOf(0), organizePages(pages, "linear algebra", PageFilter.BOOKMARKED).map { it.index })
        assertEquals(listOf(2), organizePages(pages, "linear algebra", PageFilter.REDO).map { it.index })
        assertEquals(listOf(1), organizePages(pages, "page 2").map { it.index })
        assertEquals(listOf(0, 1, 2), organizePages(pages, "  ").map { it.index })
        assertTrue(organizePages(pages, "linear calculus").isEmpty())
    }
}
