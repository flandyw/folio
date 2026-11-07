package com.folio.notes.export

import com.folio.notes.model.NotePage
import org.junit.Assert.*
import org.junit.Test

class PdfExportScaleTest {
    @Test fun largeAndNarrowCanvasesStayWithinBitmapBudget() {
        for ((width, height) in listOf(100_000f to 80_000f, 840f to 100_000f, 100_000f to 840f)) {
            val page = NotePage(width = width, height = height, infinite = true)
            for (preferred in listOf(2f, 1680f / width)) {
                val scale = exportBitmapScale(page, preferred)
                assertTrue(width * scale <= 2800.01f)
                assertTrue(height * scale <= 2800.01f)
                assertTrue(scale > 0f)
            }
        }
    }

    @Test fun ordinaryPagesRetainRequestedQuality() {
        assertEquals(2f, exportBitmapScale(NotePage(), 2f), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsFailBeforeBitmapAllocation() {
        exportBitmapScale(NotePage(width = Float.NaN), 2f)
    }
}
