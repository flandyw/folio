package com.folio.notes.ink

import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import com.folio.notes.storage.ThumbnailKeys
import org.junit.Assert.*
import org.junit.Test

class AndroidxInkTest {
    @Test fun thumbnailsInvalidateThePreviousRenderer() {
        assertTrue(ThumbnailKeys.isStale("page-420-7.jpg", "page", 7, 420))
        assertFalse(ThumbnailKeys.isStale(ThumbnailKeys.name("page", 7, 420), "page", 7, 420))
    }

    @Test fun renderWidthsPreservePressureAndTaper() {
        InkTaper.configure(.6f)
        try {
            val stroke = Stroke(Tool.PEN, -1, 3f,
                (0..40).map { InkPoint(it * 2f, 0f, .25f + it * .035f) })
            val geometry = InkRenderer.rendered(stroke)
            geometry.centre.indices.forEach { i ->
                assertEquals(geometry.widths!![i], AndroidxInk.widthScale(stroke, geometry, i), 0f)
            }
            val highlighter = stroke.copy(tool = Tool.HIGHLIGHTER)
            val highlight = InkRenderer.rendered(highlighter)
            highlight.centre.indices.forEach { i ->
                assertEquals(1f, AndroidxInk.widthScale(highlighter, highlight, i), 0f)
            }
        } finally { InkTaper.configure(0f) }
    }

    @Test fun brushesTakeStoredPressureWhileTheBallpointTakesItsWidth() {
        val points = (0..20).map { InkPoint(it * 3f, 0f, .4f + it * .05f) }
        val pen = Stroke(Tool.PEN, -1, 3f, points)
        val penGeometry = InkRenderer.rendered(pen)
        val fountain = pen.copy(style = com.folio.notes.model.StrokeStyle.FOUNTAIN)
        val fountainGeometry = InkRenderer.rendered(fountain)
        fountainGeometry.centre.indices.forEach { i ->
            assertEquals(AndroidxInk.widthScale(pen, penGeometry, i) / 2f, AndroidxInk.inputPressure(pen, penGeometry, i), 1e-6f)
            assertEquals(fountainGeometry.centre[i].pressure / 2f, AndroidxInk.inputPressure(fountain, fountainGeometry, i), 1e-6f)
        }
        val chisel = Stroke(Tool.HIGHLIGHTER, -1, 12f, points, style = com.folio.notes.model.StrokeStyle.CALLIGRAPHY)
        val chiselGeometry = InkRenderer.rendered(chisel)
        chiselGeometry.centre.indices.forEach { assertEquals(.5f, AndroidxInk.inputPressure(chisel, chiselGeometry, it), 0f) }
    }

    @Test fun loneHighlighterDotKeepsItsPressureScale() {
        val dot = Stroke(Tool.HIGHLIGHTER, -1, 3f, listOf(InkPoint(4f, 4f, .6f)))
        val geometry = InkRenderer.rendered(dot)
        assertEquals(1, geometry.centre.size)
        assertEquals(geometry.centre[0].pressure, AndroidxInk.widthScale(dot, geometry, 0), 0f)
    }

    @Test fun liveMeshPrefixNeverChangesWithinAnOwner() {
        val points = ArrayList<InkPoint>()
        val pen = InkRenderer.IncrementalPenStroke()
        val highlighter = InkRenderer.IncrementalHighlighterStroke()
        var penOwner: AndroidxInk.Geometry? = null
        var penPrefix = emptyList<InkPoint>()
        var widths = emptyList<Float>()
        var highlightPrefix = emptyList<InkPoint>()
        repeat(160) { n ->
            points += InkPoint(n * 1.5f, kotlin.math.sin(n * .3f) * 8f, .3f + (n % 30) * .04f)
            val p = pen.update(points)
            if (penOwner === p.ink) {
                assertEquals(penPrefix, p.centre.take(penPrefix.size))
                widths.indices.forEach { i -> assertEquals(widths[i], p.widths!![i], 0f) }
            }
            penOwner = p.ink
            assertTrue(p.stableCount in 0..p.centre.size)
            penPrefix = p.centre.take(p.stableCount)
            widths = penPrefix.indices.map { AndroidxInk.widthScale(Stroke(Tool.PEN, -1, 3f, points), p, it) }
            val h = highlighter.update(points)
            assertEquals(highlightPrefix, h.centre.take(highlightPrefix.size))
            assertTrue(h.stableCount in 0..h.centre.size)
            highlightPrefix = h.centre.take(h.stableCount)
        }
    }
}
