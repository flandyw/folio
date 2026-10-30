package com.folio.notes.editor.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPlacementTest {
    @Test fun `a duplicate steps onward with room and back at the paper edge`() {
        assertEquals(18f, SelectionPlacement.duplicateStep(100f, 1000f, infinite = false))
        assertEquals(-23f, SelectionPlacement.duplicateStep(990f, 1000f, infinite = false))
        assertEquals(18f, SelectionPlacement.duplicateStep(990f, 1000f, infinite = true))
    }

    @Test fun `repeated duplicates at the edge stay on the paper and never land on an earlier copy`() {
        val ends = generateSequence(995f) { it + SelectionPlacement.duplicateStep(it, 1000f, infinite = false) }.take(10).toList()
        assertTrue(ends.all { it <= 1000f })
        assertEquals(ends.size, ends.toSet().size)
    }
}
