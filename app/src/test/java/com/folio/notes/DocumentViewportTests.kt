package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentViewportTests {
    private fun page(index: Int, offset: Int, size: Int) = DocumentViewport.VisiblePage(index, offset, size)

    @Test fun mostlyVisibleNextPageBeatsSliverAbove() {
        assertEquals(1, DocumentViewport.currentPage(listOf(page(0, -800, 1000), page(1, 212, 1000)), 0, 1000, 2))
    }

    @Test fun mostlyVisiblePreviousPageWinsWhenScrollingBack() {
        assertEquals(0, DocumentViewport.currentPage(listOf(page(0, -200, 1000), page(1, 812, 1000)), 0, 1000, 2))
    }

    @Test fun usesVisibleSpaceNotFractionOfDifferentSizedPages() {
        assertEquals(1, DocumentViewport.currentPage(listOf(page(0, 0, 100), page(1, 112, 2000)), 0, 1000, 2))
    }

    @Test fun equalOverlapsPreferViewportCentreThenEarlierPage() {
        assertEquals(1, DocumentViewport.currentPage(listOf(page(0, -100, 200), page(1, 400, 100)), 0, 1000, 2))
        assertEquals(0, DocumentViewport.currentPage(listOf(page(1, 500, 500), page(0, 0, 500)), 0, 1000, 2))
    }

    @Test fun paddingAndClippingAreIncludedInOverlap() {
        assertEquals(1, DocumentViewport.currentPage(listOf(page(0, -500, 600), page(1, 112, 1000)), -80, 920, 2))
    }

    @Test fun trailingAddButtonCannotBecomeCurrent() {
        assertEquals(1, DocumentViewport.currentPage(listOf(page(1, -900, 1000), page(2, 112, 500)), 0, 1000, 2))
    }

    @Test fun currentPageBelowViewportTopRetainsItsRestorationOffset() {
        val position = NotebookPosition("page-2", WorkspaceViewport(scrollOffset = -212))
        assertEquals(position, NotebookPositionCodec.decode(NotebookPositionCodec.encode(position)))
    }

    @Test fun emptyOrUnmeasuredLayoutDoesNotChangeCurrentPage() {
        assertNull(DocumentViewport.currentPage(emptyList(), 0, 1000, 2))
        assertNull(DocumentViewport.currentPage(listOf(page(0, 0, 1000)), 0, 0, 2))
        assertNull(DocumentViewport.currentPage(listOf(page(0, 0, 1000)), 0, 1000, 0))
        assertNull(DocumentViewport.currentPage(listOf(page(0, -1000, 1000), page(1, 1000, 1000)), 0, 1000, 2))
    }
}
