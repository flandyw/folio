package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class CommittedInkRenderingTests {
    private fun stroke(x: Float, tool: Tool = Tool.PEN) = Stroke(tool, Color.BLUE, 5f,
        listOf(InkPoint(x, 20f), InkPoint(x + 10f, 40f), InkPoint(x + 20f, 20f)))

    @Test fun steadyFramesAndAppendsDoNotRenderOldStrokes() {
        val cache = CommittedInkCache()
        val canvas = Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
        val page = List(5_000) { stroke((it % 100).toFloat()) }
        var rendered = 0
        val render: (Stroke) -> InkRenderer.RenderedStroke = { rendered++; InkRenderer.rendered(it) }
        cache.draw(canvas, page, 1f, InkRenderer::rawBounds, render)
        assertEquals(5_000, rendered)
        repeat(30) { cache.draw(canvas, page, 1f, InkRenderer::rawBounds, render) }
        assertEquals(5_000, rendered)
        cache.draw(canvas, page + stroke(50f), 1f, InkRenderer::rawBounds, render)
        assertEquals(5_001, rendered)
    }

    @Test fun appendUndoRestyleAndViewportChangesMatchFreshRaster() {
        val cache = CommittedInkCache()
        val a = stroke(20f)
        val b = stroke(25f, Tool.HIGHLIGHTER)
        val c = stroke(35f, Tool.HIGHLIGHTER)
        fun verify(strokes: List<Stroke>, scale: Float = 1f, offset: Float = 0f) {
            fun output(cached: Boolean): Bitmap {
                val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(offset, offset)
                if (cached) cache.draw(canvas, strokes, scale, InkRenderer::rawBounds, InkRenderer::rendered)
                else strokes.forEach { InkRenderer.drawRendered(canvas, it, InkRenderer.rendered(it)) }
                return bitmap
            }
            assertTrue("Cached pixels must match a fresh draw", output(true).sameAs(output(false)))
        }
        verify(listOf(a))
        verify(listOf(a, b))
        verify(listOf(a, b, c))
        verify(listOf(a, b))
        verify(listOf(a.copy(color = Color.RED), b))
        verify(emptyList())
        verify(listOf(a, b), 2f, 10f)
        verify(listOf(a, b), 2f, -10f)
        cache.clear()
        verify(listOf(a, b))
    }

    @Test fun oversizedViewportFallsBackWithoutDroppingInk() {
        val cache = CommittedInkCache(maxPixels = 1)
        val page = listOf(stroke(20f))
        val actual = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        cache.draw(Canvas(actual), page, 1f, InkRenderer::rawBounds, InkRenderer::rendered)
        InkRenderer.drawRendered(Canvas(expected), page[0], InkRenderer.rendered(page[0]))
        assertTrue(actual.sameAs(expected))
    }
}
