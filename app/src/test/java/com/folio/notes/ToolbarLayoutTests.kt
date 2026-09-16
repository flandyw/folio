package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTests {
    @Test fun defaultShowsSixPrimaryWithShapesGrouped() {
        val layout = ToolbarLayouts.default()
        assertEquals(ToolbarLayouts.DEFAULT_ORDER, layout.order)
        assertTrue(layout.hidden.isEmpty())
        assertEquals(6, layout.primary.size)
        assertEquals(listOf(ToolbarSlot.HAND), layout.overflow)
        assertEquals(
            listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE),
            ToolbarSlot.SHAPES.tools
        )
    }

    @Test fun primaryCountBetweenFiveAndSeven() {
        val five = ToolbarLayouts.normalize(null, null, 5, null)
        assertEquals(5, five.primary.size)
        assertEquals(2, five.overflow.size)
        val seven = ToolbarLayouts.normalize(null, null, 7, null)
        assertEquals(7, seven.primary.size)
        assertTrue(seven.overflow.isEmpty())
        val clamped = ToolbarLayouts.normalize(null, null, 99, null)
        assertEquals(ToolbarLayouts.MAX_PRIMARY, clamped.maxPrimary)
    }

    @Test fun hiddenSlotsLeaveStripAndOverflow() {
        val layout = ToolbarLayouts.normalize(null, setOf(ToolbarSlot.HAND, ToolbarSlot.LASSO), 7, null)
        assertTrue(ToolbarSlot.HAND in layout.hidden)
        assertTrue(ToolbarSlot.HAND !in layout.visible)
        assertTrue(ToolbarSlot.HAND !in layout.primary)
        assertTrue(ToolbarSlot.HAND !in layout.overflow)
    }

    @Test fun normalizeRepairsDuplicatesAndUnknowns() {
        val layout = ToolbarLayouts.normalize(
            listOf(ToolbarSlot.HAND, ToolbarSlot.HAND, ToolbarSlot.PEN),
            setOf(ToolbarSlot.PEN),
            6,
            listOf(" p1 ", "", "p1", "p2")
        )
        assertEquals(ToolbarSlot.HAND, layout.order.first())
        assertEquals(ToolbarSlot.values().size, layout.order.distinct().size)
        assertEquals(ToolbarSlot.values().size, layout.order.size)
        assertEquals(listOf("p1", "p2"), layout.pinnedPresetIds)
    }

    @Test fun moveClampsAndIgnoresBadIndices() {
        val order = ToolbarLayouts.DEFAULT_ORDER
        assertEquals(order, ToolbarLayouts.move(order, -1, 3))
        assertEquals(order, ToolbarLayouts.move(order, 99, 0))
        val moved = ToolbarLayouts.move(order, 0, order.lastIndex)
        assertEquals(ToolbarSlot.PEN, moved.last())
        assertEquals(ToolbarSlot.SHAPES, moved.first())
        assertEquals(order, ToolbarLayouts.move(order, 2, 2))
    }

    @Test fun orderAndPinnedRoundTrip() {
        val order = listOf(ToolbarSlot.HAND, ToolbarSlot.PEN, ToolbarSlot.TEXT)
        assertEquals(order, ToolbarLayouts.decodeOrder(ToolbarLayouts.encodeOrder(order)))
        assertEquals(null, ToolbarLayouts.decodeOrder(null))
        assertEquals(null, ToolbarLayouts.decodeOrder("  "))
        val hidden = setOf(ToolbarSlot.ERASER)
        assertEquals(hidden, ToolbarLayouts.decodeHidden(ToolbarLayouts.encodeHidden(hidden)))
        assertEquals(emptySet<ToolbarSlot>(), ToolbarLayouts.decodeHidden(""))
        val pinned = listOf("a", "b")
        assertEquals(pinned, ToolbarLayouts.decodePinned(ToolbarLayouts.encodePinned(pinned)))
    }
}
