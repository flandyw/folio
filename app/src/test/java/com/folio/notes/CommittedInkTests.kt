package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class CommittedInkTests {
    private fun stroke(x: Float) = Stroke(Tool.PEN, 0, 3f, listOf(InkPoint(x, 0f)))

    @Test fun densePageOnlyPaintsNewInk() {
        val page = List(10_000) { stroke(it.toFloat()) }
        assertEquals(page.size, appendedInkStart(page, page))
        assertEquals(page.size, appendedInkStart(page, page.toList()))
        assertEquals(page.size, appendedInkStart(page, page + stroke(10_000f)))
    }

    @Test fun undoEraseReplaceAndReorderRequireReplay() {
        val page = List(20) { stroke(it.toFloat()) }
        assertEquals(0, appendedInkStart(page, page.dropLast(1)))
        assertEquals(0, appendedInkStart(page, page.filterIndexed { i, _ -> i != 10 }))
        assertEquals(0, appendedInkStart(page, page.mapIndexed { i, s -> if (i == 10) s.copy(color = 1) else s }))
        assertEquals(0, appendedInkStart(page, page.reversed()))
        assertEquals(0, appendedInkStart(page, page.map { it.copy() }))
        assertEquals(0, appendedInkStart(emptyList(), page))
        assertEquals(0, appendedInkStart(page, emptyList()))
    }
}
