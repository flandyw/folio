package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PdfReferenceTests {
    @Test fun zoomStepsClampInsideTheCameraRange() {
        assertEquals(PdfReference.zoomStep(1f, 1), PdfReference.clampZoom(1f * PdfReference.ZOOM_STEP), 0.0001f)
        assertEquals(1f, PdfReference.zoomStep(1f, 0), 0.0001f)
        assertEquals(PdfReference.MAX_ZOOM, PdfReference.zoomStep(5f, 4), 0.0001f)
        assertEquals(PdfReference.MIN_ZOOM, PdfReference.zoomStep(0.4f, -4), 0.0001f)
        assertEquals(1f, PdfReference.clampZoom(Float.NaN), 0.0001f)
    }

    @Test fun fittingFramesThePageWidthAndLeavesAMargin() {
        val page = PdfReference.fitZoom(600f, 800f, 900f, 1200f, PdfFit.PAGE)
        assertEquals(900f * (1f - 2 * PdfReference.FIT_MARGIN) / 600f, page, 0.001f)
        assertTrue("page fit leaves vertical room", 800f * page < 1200f)
        val width = PdfReference.fitZoom(600f, 800f, 900f, 1200f, PdfFit.WIDTH)
        assertTrue("width fit is wider than page fit", width > page)
        assertTrue("width fit fills the pane", 600f * width <= 900f)
        assertEquals(1f, PdfReference.fitZoom(600f, 800f, 900f, 1200f, PdfFit.ACTUAL), 0.0001f)
    }

    @Test fun degenerateSizesFallBackToUnzoomed() {
        assertEquals(1f, PdfReference.fitZoom(0f, 800f, 900f, 1200f, PdfFit.WIDTH), 0.0001f)
        assertEquals(1f, PdfReference.fitZoom(600f, 800f, 0f, 1200f, PdfFit.PAGE), 0.0001f)
        assertEquals(1f, PdfReference.fitZoom(Float.NaN, 800f, 900f, 1200f, PdfFit.PAGE), 0.0001f)
    }

    @Test fun jumpsAcceptNumbersPageWordsAndEnds() {
        assertEquals(11, PdfReference.parseJump("12", 0, 20))
        assertEquals(11, PdfReference.parseJump("p 12", 0, 20))
        assertEquals(11, PdfReference.parseJump("Page.12", 0, 20))
        assertEquals(19, PdfReference.parseJump("last", 0, 20))
        assertEquals(0, PdfReference.parseJump("first", 5, 20))
        assertEquals(19, PdfReference.parseJump("end", 5, 20))
    }

    @Test fun jumpsMoveRelativeAndClampToTheDocument() {
        assertEquals(4, PdfReference.parseJump("+3", 1, 20))
        assertEquals(19, PdfReference.parseJump("+3", 18, 20))
        assertEquals(0, PdfReference.parseJump("-2", 1, 20))
        assertEquals(19, PdfReference.parseJump("999", 0, 20))
        assertEquals(0, PdfReference.parseJump("0", 5, 20))
        assertEquals(2, PdfReference.parseJump("  +1  ", 1, 20))
    }

    @Test fun anUnparseableJumpLeavesThePageAlone() {
        assertNull(PdfReference.parseJump("", 0, 20))
        assertNull(PdfReference.parseJump("front", 0, 20))
        assertNull(PdfReference.parseJump("12.", 0, 20))
        assertNull(PdfReference.parseJump("+", 0, 20))
        assertNull(PdfReference.parseJump("12", 0, 0))
        assertTrue(PdfReference.jumpHint(3, 20).contains("4 of 20"))
    }

    @Test fun hitsAreWalkedInReadingOrderAndWrapAround() {
        val hits = PdfSearch.search(
            listOf(PdfPageText(2, "mark"), PdfPageText(5, "mark mark"), PdfPageText(0, "mark")),
            "mark"
        )
        val pages = PdfReference.hitPages(hits)
        assertEquals(listOf(0, 2, 5), pages)
        assertEquals(5, PdfReference.nextHit(2, pages, forward = true))
        assertEquals(0, PdfReference.nextHit(2, pages, forward = false))
        assertEquals(0, PdfReference.nextHit(99, pages, forward = true))
        assertEquals(0, PdfReference.nextHit(5, pages, forward = true))
        assertEquals(5, PdfReference.nextHit(-1, pages, forward = false))
        assertNull(PdfReference.nextHit(0, emptyList(), forward = true))
    }

    @Test fun pageLabelsNameBothTheNotebookAndTheSourcePdf() {
        assertEquals("3 / 12", PdfReference.pageLabel(2, 12, null))
        assertEquals("3 / 12 · PDF p.3", PdfReference.pageLabel(2, 12, 2))
        assertEquals("1 / 12 · PDF p.5", PdfReference.pageLabel(0, 12, 4))
    }

    @Test fun aDeliberateZoomSurvivesAPageTurnAndAResetDoesNot() {
        val zoomed = WorkspaceViewport(canvasZoom = 2.4f, canvasX = 120f, canvasY = -40f, scrollOffset = 88)
        val carried = PdfReference.carriedFrame(zoomed)
        assertEquals(2.4f, carried.canvasZoom, 0.0001f)
        assertEquals(0f, carried.canvasX, 0.0001f)
        assertEquals(0f, carried.canvasY, 0.0001f)
        assertEquals(0, carried.scrollOffset)
        assertEquals(WorkspaceViewport(), PdfReference.carriedFrame(WorkspaceViewport()))
    }
}
