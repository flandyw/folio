package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class ExportPagesTests {
    @Test fun normalizeKeepsValidIndicesInReadingOrder() {
        assertEquals(listOf(0, 2), normalizeExportIndices(listOf(2, 2, 0, 9, -1), 3))
        assertTrue(normalizeExportIndices(listOf(0), 0).isEmpty())
        assertTrue(normalizeExportIndices(emptyList(), 5).isEmpty())
    }

    @Test fun parseRangeReadsHumanPageNumbers() {
        assertEquals(listOf(0, 1, 2, 4), parsePageRange("1-3, 5", 6))
        assertEquals(listOf(0, 1, 2), parsePageRange("1-99", 3))
        assertEquals(listOf(1, 2), parsePageRange("3-2", 6))
        assertEquals(listOf(0, 2), parsePageRange("1, three, 3,,", 4))
        assertTrue(parsePageRange("abc", 4).isEmpty())
        assertTrue(parsePageRange("1-3", 0).isEmpty())
    }

    @Test fun selectionLabelCompactsConsecutivePages() {
        assertEquals("1–3, 5", formatExportSelection(listOf(4, 0, 1, 2)))
        assertEquals("2", formatExportSelection(listOf(1)))
        assertEquals("No pages", formatExportSelection(emptyList()))
    }

    @Test fun filenamesDistinguishSingleAndMultiPage() {
        val note = Notebook(title = "Calculus!", pages = List(4) { NotePage() })
        assertEquals("Calculus_-p2.pdf", selectiveExportFilename(note, listOf(1), PageExportFormat.PDF))
        assertEquals("Calculus_-pages.pdf", selectiveExportFilename(note, listOf(0, 2), PageExportFormat.PDF))
        assertEquals("Calculus_-p1.png", selectiveExportFilename(note, listOf(0), PageExportFormat.PNG))
        assertEquals("Calculus_-pages.zip", selectiveExportFilename(note, listOf(0, 1), PageExportFormat.PNG))
        assertEquals("Notebook-pages.pdf", selectiveExportFilename(note.copy(title = ""), listOf(0, 1), PageExportFormat.PDF))
    }
}
