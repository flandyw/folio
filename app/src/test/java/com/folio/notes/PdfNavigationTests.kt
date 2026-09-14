package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PdfNavigationTests {
    @Test fun aRectMapsFromPdfSpaceOntoTheFolioPage() {
        val link = PdfLinks.mapLink(
            pageIndex = 2, x0 = 60f, y0 = 100f, x1 = 120f, y1 = 140f,
            cropX = 0f, cropY = 0f, cropW = 600f, cropH = 800f,
            pageW = 600f, pageH = 800f, target = PdfLinkTarget.Page(0)
        )!!
        assertEquals(2, link.pageIndex)
        assertEquals(60f, link.x, .001f)
        assertEquals(60f, link.width, .001f)
        // PDF y grows upwards; Folio y grows downwards.
        assertEquals(660f, link.y, .001f)
        assertEquals(40f, link.height, .001f)
    }

    @Test fun mappingScalesAndHonoursACropOrigin() {
        val full = PdfLinks.mapLink(
            pageIndex = 0, x0 = 0f, y0 = 0f, x1 = 600f, y1 = 800f,
            cropX = 0f, cropY = 0f, cropW = 600f, cropH = 800f,
            pageW = 300f, pageH = 400f, target = PdfLinkTarget.Page(1)
        )!!
        assertEquals(0f, full.x, .001f)
        assertEquals(0f, full.y, .001f)
        assertEquals(300f, full.width, .001f)
        assertEquals(400f, full.height, .001f)
        val offset = PdfLinks.mapLink(
            pageIndex = 0, x0 = 70f, y0 = 120f, x1 = 130f, y1 = 160f,
            cropX = 10f, cropY = 20f, cropW = 600f, cropH = 800f,
            pageW = 600f, pageH = 800f, target = PdfLinkTarget.Page(1)
        )!!
        assertEquals(60f, offset.x, .001f)
        assertEquals(660f, offset.y, .001f)
    }

    @Test fun aRectHangingOffTheCropIsClipped() {
        val link = PdfLinks.mapLink(
            pageIndex = 0, x0 = -50f, y0 = 100f, x1 = 100f, y1 = 140f,
            cropX = 0f, cropY = 0f, cropW = 600f, cropH = 800f,
            pageW = 600f, pageH = 800f, target = PdfLinkTarget.Page(0)
        )!!
        assertEquals(0f, link.x, .001f)
        assertEquals(100f, link.width, .001f)
    }

    @Test fun degenerateOrOffPageRectsAreNotLinks() {
        val target = PdfLinkTarget.Page(0)
        // Flat, empty and fully outside rectangles.
        assertNull(PdfLinks.mapLink(0, 60f, 100f, 60f, 140f, 0f, 0f, 600f, 800f, 600f, 800f, target))
        assertNull(PdfLinks.mapLink(0, 60f, 100f, 60.2f, 100.2f, 0f, 0f, 600f, 800f, 600f, 800f, target))
        assertNull(PdfLinks.mapLink(0, 700f, 100f, 800f, 140f, 0f, 0f, 600f, 800f, 600f, 800f, target))
        // Impossible dimensions anywhere in the chain.
        assertNull(PdfLinks.mapLink(0, 60f, 100f, 120f, 140f, 0f, 0f, 0f, 800f, 600f, 800f, target))
        assertNull(PdfLinks.mapLink(0, 60f, 100f, 120f, 140f, 0f, 0f, 600f, 800f, -600f, 800f, target))
    }

    @Test fun onlyOpenableSchemesBecomeUrlTargets() {
        assertEquals("https://example.com/paper", PdfLinks.urlTarget("https://example.com/paper"))
        assertEquals("HTTP://EXAMPLE.COM", PdfLinks.urlTarget("  HTTP://EXAMPLE.COM  "))
        assertEquals("mailto:tutor@school.vic.edu.au", PdfLinks.urlTarget("mailto:tutor@school.vic.edu.au"))
        assertNull(PdfLinks.urlTarget("javascript:alert(1)"))
        assertNull(PdfLinks.urlTarget("ftp://files.example.com/x.pdf"))
        assertNull(PdfLinks.urlTarget("http:"))
        assertNull(PdfLinks.urlTarget("not a link"))
        assertNull(PdfLinks.urlTarget(""))
        assertNull(PdfLinks.urlTarget(null))
    }

    @Test fun hitTestingForgivesFingers() {
        val link = PdfLink(0, 10f, 20f, 100f, 50f, PdfLinkTarget.Page(1))
        assertTrue(link.contains(50f, 40f))
        assertTrue(link.contains(10f, 20f))
        assertFalse(link.contains(200f, 200f))
        // Six page units past the edge still taps; further does not.
        assertTrue(link.contains(4f, 20f))
        assertFalse(link.contains(3.9f, 20f))
        assertFalse(link.contains(4f, 20f, slop = 0f))
    }

    @Test fun sanitisingKeepsOnlyFollowableBookmarks() {
        val entries = listOf(
            PdfOutlineEntry("  Chapter 1  ", 0, 0),
            PdfOutlineEntry("", 1, 0),
            PdfOutlineEntry("Lost", 9, 0),
            PdfOutlineEntry("Before", -1, 0),
            PdfOutlineEntry("Deep", 2, 12),
            PdfOutlineEntry("Flat", 2, -3)
        )
        val clean = PdfOutline.sanitize(entries, 3)
        assertEquals(listOf("Chapter 1", "Deep", "Flat"), clean.map { it.title })
        assertEquals(listOf(0, 8, 0), clean.map { it.depth })
        assertEquals(listOf(0, 2, 2), clean.map { it.pageIndex })
    }

    @Test fun sanitisingCapsRunawayOutlines() {
        val many = List(PdfOutline.MAX_ENTRIES + 5) { PdfOutlineEntry("Item $it", 0, 0) }
        assertEquals(PdfOutline.MAX_ENTRIES, PdfOutline.sanitize(many, 4).size)
    }
}
