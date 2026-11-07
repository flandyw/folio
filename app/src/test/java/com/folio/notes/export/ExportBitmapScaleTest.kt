package com.folio.notes.export

import com.folio.notes.model.NotePage
import org.junit.Assert.*
import org.junit.Test

class ExportBitmapScaleTest {
    @Test fun largeCanvasesCannotExceedTheBitmapBudgetInEitherPdfMode() {
        val sizes = listOf(840f to 30_000f, 50_000f to 1188f, 100_000f to 100_000f)
        for ((width, height) in sizes) {
            val page = NotePage(width = width, height = height, infinite = true)
            for (preferred in listOf(2f, 1680f / width)) {
                val scale = exportBitmapScale(page, preferred)
                val pixelsWide = (width * scale).toInt().coerceAtLeast(1)
                val pixelsHigh = (height * scale).toInt().coerceAtLeast(1)
                assertTrue("$pixelsWide × $pixelsHigh", pixelsWide <= 2800 && pixelsHigh <= 2800)
                assertTrue(pixelsWide.toLong() * pixelsHigh * 4 <= 32_000_000)
                assertTrue(scale > 0f && scale <= preferred)
            }
        }
    }

    @Test fun ordinaryPagesKeepTheirRequestedQuality() {
        val page = NotePage(width = 840f, height = 1188f)
        assertEquals(2f, exportBitmapScale(page, 2f), 0f)
        assertEquals(1f, exportBitmapScale(page, 1f), 0f)
        assertEquals(.25f, exportBitmapScale(page, .25f), 0f)
    }

    @Test fun scalingKeepsTheAspectRatioForTallAndWidePages() {
        for ((width, height) in listOf(1000f to 20_000f, 20_000f to 1000f)) {
            val scale = exportBitmapScale(NotePage(width = width, height = height), 2f)
            assertEquals(2800f, maxOf(width * scale, height * scale), .001f)
            assertEquals(140f, minOf(width * scale, height * scale), .001f)
        }
    }
}
