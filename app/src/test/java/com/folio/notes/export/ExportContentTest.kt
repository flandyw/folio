package com.folio.notes.export

import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import org.junit.Assert.*
import org.junit.Test

class ExportContentTest {
    private val ink = Stroke(Tool.PEN, 0xff000000.toInt(), 2f, listOf(InkPoint(10f, 10f)))

    @Test fun handwrittenStickyNeedsAnOverlayWithoutAnyPageInkOrTypedText() {
        val sticky = TextBox(x = 10f, y = 10f, stickyHeight = 200f, stickyInk = listOf(ink))
        assertTrue(pageHasAnnotations(NotePage(texts = listOf(sticky))))
    }

    @Test fun blankStickyStillHasVisiblePaper() {
        assertTrue(pageHasAnnotations(NotePage(texts = listOf(TextBox(x = 10f, y = 10f, stickyHeight = 200f)))))
    }

    @Test fun emptyPagesAndBlankOrdinaryTextNeedNoOverlay() {
        assertFalse(pageHasAnnotations(NotePage()))
        assertFalse(pageHasAnnotations(NotePage(texts = listOf(TextBox(x = 10f, y = 10f, text = " \n ")))))
    }

    @Test fun ordinaryAnnotationsStillNeedAnOverlay() {
        assertTrue(pageHasAnnotations(NotePage(strokes = listOf(ink))))
        assertTrue(pageHasAnnotations(NotePage(texts = listOf(TextBox(x = 10f, y = 10f, text = "Working")))))
        assertTrue(pageHasAnnotations(NotePage(images = listOf(PageImage(x = 10f, y = 10f, width = 100f, height = 100f)))))
    }
}
