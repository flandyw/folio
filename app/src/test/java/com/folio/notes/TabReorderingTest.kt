package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TabReorderingTest {
    private val order = listOf("a", "b", "c", "d")
    private val widths = mapOf("a" to 88f, "b" to 200f, "c" to 128f, "d" to 100f)

    private fun slots(ids: List<String>, rtl: Boolean = false): Map<String, TabSlotBounds> {
        var left = 0f
        return (if (rtl) ids.reversed() else ids).associateWith { id ->
            TabSlotBounds(left, widths.getValue(id)).also { left += it.width + 4f }
        }
    }

    @Test fun neighboursWaitUntilTheirRestingMidpointIsCrossed() {
        val bounds = slots(order)
        val threshold = bounds.getValue("b").middle
        assertSame(order, reorderedTabs(order, "a", threshold, bounds))
        assertEquals(listOf("b", "a", "c", "d"), reorderedTabs(order, "a", threshold + 1f, bounds))
    }

    @Test fun aFastDragCanCrossSeveralTabsInEitherDirection() {
        assertEquals(listOf("b", "c", "d", "a"), reorderedTabs(order, "a", 1000f, slots(order)))
        assertEquals(listOf("d", "a", "b", "c"), reorderedTabs(order, "d", -100f, slots(order)))
    }

    @Test fun movingSlotsUnderAStationaryPointerDoesNotSwapBack() {
        val pointerMiddle = slots(order).getValue("b").middle + 1f
        val moved = reorderedTabs(order, "a", pointerMiddle, slots(order))
        assertEquals(listOf("b", "a", "c", "d"), moved)
        assertSame(moved, reorderedTabs(moved, "a", pointerMiddle, slots(moved)))
        val back = slots(moved).getValue("b").middle - 1f
        assertEquals(order, reorderedTabs(moved, "a", back, slots(moved)))
    }

    @Test fun rightToLeftUsesTheOppositePhysicalDirection() {
        val bounds = slots(order, rtl = true)
        assertEquals(listOf("b", "a", "c", "d"), reorderedTabs(order, "a", bounds.getValue("b").middle - 1f, bounds, rightToLeft = true))
        assertEquals(listOf("d", "a", "b", "c"), reorderedTabs(order, "d", 1000f, bounds, rightToLeft = true))
    }

    @Test fun missingOrInvalidGeometryNeverReordersUnmeasuredTabs() {
        assertSame(order, reorderedTabs(order, "missing", 1000f, slots(order)))
        assertSame(order, reorderedTabs(order, "a", Float.NaN, slots(order)))
        assertSame(order, reorderedTabs(order, "a", 1000f, slots(order) - "b"))
        assertSame(emptyList<String>(), reorderedTabs(emptyList(), "a", 1000f, emptyMap()))
    }
}
