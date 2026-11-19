package com.folio.notes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSelectionInvertTest {
    @Test fun invertKeepsEveryOtherPageInReadingOrder() {
        assertEquals(listOf(0, 2, 4, 5), invertExportSelection(setOf(3, 1), 6).toList())
    }

    @Test fun invertingTwiceRestoresTheSelection() {
        val picked = setOf(0, 4, 7)
        assertEquals(picked, invertExportSelection(invertExportSelection(picked, 9), 9))
    }

    @Test fun invertSwapsAllAndNothing() {
        assertEquals((0 until 4).toSet(), invertExportSelection(emptySet(), 4))
        assertTrue(invertExportSelection((0 until 4).toSet(), 4).isEmpty())
    }

    @Test fun invertIgnoresPagesOutsideTheNotebook() {
        assertEquals(setOf(1, 2), invertExportSelection(setOf(0, 9, -1), 3))
        assertTrue(invertExportSelection(setOf(0), 0).isEmpty())
    }

    @Test fun countsShowSelectedAndExcluded() {
        assertEquals("3 selected · 9 excluded", exportSelectionCounts(setOf(0, 1, 2), 12))
        assertEquals("0 selected · 5 excluded", exportSelectionCounts(emptySet(), 5))
        assertEquals("2 selected · 0 excluded", exportSelectionCounts(setOf(0, 1, 7), 2))
        assertEquals("0 selected · 0 excluded", exportSelectionCounts(emptySet(), 0))
    }
}
