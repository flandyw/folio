package com.folio.notes.export

import org.junit.Assert.*
import org.junit.Test

class ExportPageRangeTest {
    @Test fun stickyPaperAndHandwrittenNotesCountAsPdfAnnotations() {
        val note = com.folio.notes.model.TextBox(x = 0f, y = 0f, stickyHeight = 120f)
        assertTrue(pageHasAnnotations(com.folio.notes.model.NotePage(texts = listOf(note))))
        assertFalse(pageHasAnnotations(com.folio.notes.model.NotePage(texts = listOf(note.copy(stickyHeight = 0f)))))
    }
    @Test fun liveRangeCanClearThePreviousSelection() {
        var selected = parsePageRange("1-3", 6).toSet()
        assertEquals(setOf(0, 1, 2), selected)
        selected = parsePageRange("not-a-page", 6).toSet()
        assertTrue(selected.isEmpty())
        assertTrue(parsePageRange("", 6).isEmpty())
    }

    @Test fun rangesStayInReadingOrderWithoutRepeatingPages() {
        assertEquals(listOf(0, 1, 2, 4), parsePageRange("3-1, 2, 5", 6))
        assertEquals("1–3, 5", formatExportSelection(parsePageRange("3-1, 2, 5", 6)))
    }

    @Test fun rangesCannotExportOutsideTheNotebook() {
        assertEquals(listOf(0, 1, 2), parsePageRange("1-99", 3))
        assertTrue(parsePageRange("99", 3).isEmpty())
        assertTrue(parsePageRange("1-3", 0).isEmpty())
    }
}
