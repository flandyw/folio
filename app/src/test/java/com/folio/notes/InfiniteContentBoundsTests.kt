package com.folio.notes

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class InfiniteContentBoundsTests {
    @Test fun emptyPageFallsBackToPageRect() {
        assertEquals(
            Rect(0f, 0f, 840f, 1188f),
            InkGeometry.contentBounds(emptyList(), emptyList(), emptyList(), { 20f }, 840f, 1188f)
        )
    }

    @Test fun strokesUnionWithOwnWidthPadding() {
        val strokes = listOf(
            Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(10f, 10f), InkPoint(30f, 40f))),
            Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(100f, 0f), InkPoint(100f, 0f)))
        )
        // Pad is width * 2: 4 for the first stroke, 8 for the second.
        assertEquals(
            Rect(6f, -8f, 108f, 44f),
            InkGeometry.contentBounds(strokes, emptyList(), emptyList(), { 20f }, 840f, 1188f)
        )
    }

    @Test fun emptyStrokesAreSkippedAndTextsAndImagesCount() {
        val texts = listOf(TextBox(x = -600f, y = -900f, width = 200f, text = "hi"))
        val images = listOf(PageImage(x = 500f, y = 600f, width = 100f, height = 50f))
        assertEquals(
            Rect(-600f, -900f, 600f, 650f),
            InkGeometry.contentBounds(
                listOf(Stroke(Tool.PEN, 0, 2f, emptyList())),
                texts, images, { 20f }, 840f, 1188f
            )
        )
    }
}
