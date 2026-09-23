package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class WritingGuidesTests {
    private val width = 840
    private val height = 500
    private fun raster() = IntArray(width * height) { -1 }
    private fun line(pixels: IntArray, left: Int, right: Int, y: Int, thickness: Int = 1) {
        for (row in y until y + thickness) for (x in left..right) pixels[row * width + x] = 0xFF222222.toInt()
    }
    private fun detect(pixels: IntArray) = WritingGuides.detect(pixels, width, height, 840f, 500f)

    @Test fun detectsPrintedEndpointsAndMergesAntialiasedRows() {
        val pixels = raster()
        line(pixels, 95, 650, 100, 2)
        line(pixels, 95, 650, 132, 2)
        line(pixels, 95, 650, 170, 2)
        val guides = detect(pixels)
        assertEquals(3, guides.size)
        assertEquals(WritingGuide(95f, 650f, 100.5f), guides[0])
        assertEquals(32f, guides[1].y - guides[0].y, 0f)
        assertEquals(38f, guides[2].y - guides[1].y, 0f)
        // Changing raster resolution leaves page coordinates unchanged.
        val doubled = IntArray(width * height * 4) { i -> pixels[(i / (width * 2) / 2) * width + (i % (width * 2) / 2)] }
        val scaled = WritingGuides.detect(doubled, width * 2, height * 2, 840f, 500f)
        assertEquals(guides.size, scaled.size)
        assertEquals(guides[0].left, scaled[0].left, .5f)
        assertEquals(guides[0].right, scaled[0].right, .5f)
        assertEquals(guides[0].y, scaled[0].y, .5f)
    }
    @Test fun detectedShortAnswerRuleControlsReturnInsteadOfPageMargin() {
        val pixels = raster()
        line(pixels, 120, 460, 100)
        line(pixels, 130, 450, 134)
        val guides = detect(pixels)
        val follow = WritingFollow()
        val beforeEnd = listOf(InkPoint(420f, 88f), InkPoint(430f, 100f))
        val atEnd = listOf(InkPoint(450f, 88f), InkPoint(459f, 100f))
        assertNull(follow.advanceFor(beforeEnd, guides, 2f, WritingHand.RIGHT, 0))
        val advance = follow.advanceFor(atEnd, guides, 2f, WritingHand.RIGHT, 0)!!
        assertEquals(130f, advance.startX(WritingHand.RIGHT), 0f)
        assertEquals(34f, advance.to.y - advance.from.y, 0f)
    }
    @Test fun ignoresTextSolidBarsIsolatedUnderlinesAndTableBorders() {
        val pixels = raster()
        for (y in listOf(30, 60)) for (x in 50..700 step 12) line(pixels, x, x + 6, y, 8)
        line(pixels, 80, 700, 100, 8)
        line(pixels, 80, 700, 130, 8)
        line(pixels, 80, 700, 200)
        for (y in listOf(300, 330, 360)) line(pixels, 80, 700, y)
        for (y in 300..360) { pixels[y * width + 80] = 0xFF000000.toInt(); pixels[y * width + 700] = 0xFF000000.toInt() }
        assertTrue(detect(pixels).isEmpty())
    }
    @Test fun staysInAnswerColumnAndDoesNotCrossQuestionGaps() {
        val first = WritingGuide(50f, 350f, 100f)
        val otherColumn = WritingGuide(450f, 750f, 120f)
        val next = WritingGuide(55f, 350f, 132f)
        val nextQuestion = WritingGuide(50f, 350f, 250f)
        val guides = listOf(first, otherColumn, next, nextQuestion)
        assertEquals(next, WritingGuides.next(first, guides))
        assertNull(WritingGuides.next(next, guides))
        assertNull(WritingGuides.next(otherColumn, guides))
    }
    @Test fun nativeRuledPaperUsesRenderedMarginsAndSpacing() {
        val guides = WritingGuides.ruled(840f, 1188f)
        assertEquals(WritingGuide(36f, 804f, 70f), guides.first())
        assertEquals(28f, guides[1].y - guides[0].y, 0f)
        assertTrue(guides.last().y < 1188f)
    }

    @Test fun detectsMultipleAreasOnOnePageAndSelectsByBothCoordinates() {
        val pixels = raster()
        for (y in listOf(100, 132, 270, 302)) line(pixels, 50, 350, y)
        for (y in listOf(100, 132)) line(pixels, 450, 750, y)
        val guides = detect(pixels)
        val areas = WritingGuides.regions(guides.reversed())
        assertEquals(listOf(WritingLane(50f, 72f, 350f, 132f),
            WritingLane(450f, 72f, 750f, 132f), WritingLane(50f, 242f, 350f, 302f)), areas)
        assertEquals(areas[1], WritingGuides.regionAt(areas, 500f, 90f))
        assertEquals(areas[2], WritingGuides.regionAt(areas, 100f, 280f))
        assertNull(WritingGuides.regionAt(areas, 400f, 100f))
        assertNull(WritingGuides.regionAt(areas, 100f, 200f))
        assertNull(FollowNavigation.next(132f, areas[0], guides, 32f))
    }

    @Test fun emptyOrIsolatedGuidesDoNotCreateAnswerAreas() {
        assertTrue(WritingGuides.regions(emptyList()).isEmpty())
        assertTrue(WritingGuides.regions(listOf(WritingGuide(50f, 350f, 100f))).isEmpty())
    }
}
