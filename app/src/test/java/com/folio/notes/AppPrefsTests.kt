package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class AppPrefsTests {
    @Test fun toolFallsBackToPen() {
        assertEquals(Tool.PEN, AppPrefs.defaultTool(null))
        assertEquals(Tool.PEN, AppPrefs.defaultTool("BOGUS"))
        assertEquals(Tool.HIGHLIGHTER, AppPrefs.defaultTool("HIGHLIGHTER"))
        assertEquals(Tool.HAND, AppPrefs.defaultTool("HAND"))
    }

    @Test fun paperFallsBackToMathsGrid() {
        assertEquals(Paper.MATH_GRID, AppPrefs.defaultPaper(null))
        assertEquals(Paper.MATH_GRID, AppPrefs.defaultPaper("BOGUS"))
        assertEquals(Paper.PLAIN, AppPrefs.defaultPaper("PLAIN"))
    }

    @Test fun libraryDefaultsFallBack() {
        assertEquals(LibrarySort.RECENT, AppPrefs.librarySort(null))
        assertEquals(LibrarySort.RECENT, AppPrefs.librarySort("BOGUS"))
        assertEquals(LibrarySort.NAME, AppPrefs.librarySort("NAME"))
        assertEquals(LibraryKind.ALL, AppPrefs.libraryKind(null))
        assertEquals(LibraryKind.ALL, AppPrefs.libraryKind("BOGUS"))
        assertEquals(LibraryKind.PDFS, AppPrefs.libraryKind("PDFS"))
    }

    @Test fun palmWindowIsClamped() {
        assertEquals(500L, AppPrefs.palmMs(null))
        assertEquals(500L, AppPrefs.palmMs(500L))
        assertEquals(0L, AppPrefs.palmMs(0L))
        assertEquals(0L, AppPrefs.palmMs(-10L))
        assertEquals(1500L, AppPrefs.palmMs(9999L))
    }

    @Test fun timerMinutesAreClamped() {
        assertEquals(90, AppPrefs.timerCustomMinutes(null))
        assertEquals(1, AppPrefs.timerCustomMinutes(0))
        assertEquals(480, AppPrefs.timerCustomMinutes(999))
        assertEquals(15, AppPrefs.timerReadingMinutes(null))
        assertEquals(0, AppPrefs.timerReadingMinutes(-5))
        assertEquals(60, AppPrefs.timerReadingMinutes(999))
    }

    @Test fun exportScaleAndSplitAndTextAreClamped() {
        assertEquals(2f, AppPrefs.pngScale(null))
        assertEquals(2f, AppPrefs.pngScale(Float.NaN))
        assertEquals(1f, AppPrefs.pngScale(0f))
        assertEquals(3f, AppPrefs.pngScale(99f))
        assertEquals(0.5f, AppPrefs.splitFraction(null))
        assertEquals(0.5f, AppPrefs.splitFraction(Float.NaN))
        assertEquals(SplitPanes.MIN_FRACTION, AppPrefs.splitFraction(0f))
        assertEquals(SplitPanes.MAX_FRACTION, AppPrefs.splitFraction(1f))
        assertEquals(26f, AppPrefs.textSize(null))
        assertEquals(12f, AppPrefs.textSize(1f))
        assertEquals(72f, AppPrefs.textSize(999f))
    }

    @Test fun pdfExportModeDefaultsToPreserve() {
        assertEquals(PdfExportMode.PRESERVE, AppPrefs.pdfExportMode(null))
        assertEquals(PdfExportMode.PRESERVE, AppPrefs.pdfExportMode("BOGUS"))
        assertEquals(PdfExportMode.PRESERVE, AppPrefs.pdfExportMode("PRESERVE"))
        assertEquals(PdfExportMode.RASTERISE, AppPrefs.pdfExportMode("RASTERISE"))
    }

    @Test fun coverIndexStaysInRange() {
        assertTrue(CoverColors.isNotEmpty())
        assertEquals(0, AppPrefs.defaultCover(-5))
        assertEquals(CoverColors.lastIndex, AppPrefs.defaultCover(999))
        assertEquals(0, AppPrefs.defaultCover(0))
    }
}
