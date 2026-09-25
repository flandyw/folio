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

    @Test fun galleryPngUsesSinglePageFilename() {
        val note = Notebook(title = "Calculus!", pages = List(4) { NotePage() })
        assertEquals("Calculus_-p2.png", galleryPngFilename(note, 1))
        assertEquals("Pictures/Folio", galleryRelativePath())
    }

    @Test fun pdfModeDefaultsToPreserveAndSurvivesUnknown() {
        assertEquals(PdfExportMode.PRESERVE, PdfExportMode.safeValueOf(null))
        assertEquals(PdfExportMode.PRESERVE, PdfExportMode.safeValueOf("BOGUS"))
        assertEquals(PdfExportMode.PRESERVE, PdfExportMode.safeValueOf("PRESERVE"))
        assertEquals(PdfExportMode.RASTERISE, PdfExportMode.safeValueOf("RASTERISE"))
    }

    @Test fun pdfQualityShownOnlyForPdfBackedNotebooks() {
        val native = Notebook(title = "Notes", pages = listOf(NotePage(pdfIndex = null)))
        val backed = Notebook(title = "Exam", pages = listOf(NotePage(pdfIndex = 0), NotePage()))
        assertFalse(hasPdfPages(native))
        assertFalse(shouldShowPdfQuality(native))
        assertTrue(hasPdfPages(backed))
        assertTrue(shouldShowPdfQuality(backed))
    }

    @Test fun exportRequestCarriesPdfMode() {
        val note = Notebook(title = "Exam", pages = listOf(NotePage(pdfIndex = 0)))
        val default = PageExportRequest(note, listOf(0), PageExportFormat.PDF)
        assertEquals(PdfExportMode.PRESERVE, default.pdfMode)
        val raster = PageExportRequest(note, listOf(0), PageExportFormat.PDF, PdfExportMode.RASTERISE)
        assertEquals(PdfExportMode.RASTERISE, raster.pdfMode)
        // Mode never changes page selection or format handling.
        assertEquals(listOf(0), normalizeExportIndices(raster.indices, note.pages.size))
    }

    @Test fun pageHasAnnotationsDetectsFolioContent() {
        assertFalse(pageHasAnnotations(NotePage()))
        assertFalse(pageHasAnnotations(NotePage(texts = listOf(TextBox(x = 0f, y = 0f, text = "  ")))))
        assertTrue(pageHasAnnotations(NotePage(strokes = listOf(Stroke(Tool.PEN, 0, 1f, listOf(InkPoint(0f, 0f)))))))
        assertTrue(pageHasAnnotations(NotePage(texts = listOf(TextBox(x = 0f, y = 0f, text = "working")))))
        assertTrue(pageHasAnnotations(NotePage(images = listOf(PageImage(x = 0f, y = 0f, width = 10f, height = 10f)))))
    }

    @Test fun identityExportRequiresFullOrderedPdfNotebook() {
        val pure = Notebook(title = "Exam", pages = listOf(NotePage(pdfIndex = 0), NotePage(pdfIndex = 1)))
        assertTrue(isIdentityPdfExport(pure, listOf(0, 1), 2))
        assertFalse(isIdentityPdfExport(pure, listOf(0), 2))
        assertFalse(isIdentityPdfExport(pure, listOf(1, 0), 2))
        val mixed = Notebook(title = "Mixed", pages = listOf(NotePage(pdfIndex = 0), NotePage(pdfIndex = null)))
        assertFalse(isIdentityPdfExport(mixed, listOf(0, 1), 2))
        val reordered = Notebook(title = "Reordered", pages = listOf(NotePage(pdfIndex = 1), NotePage(pdfIndex = 0)))
        assertFalse(isIdentityPdfExport(reordered, listOf(0, 1), 2))
    }
}
