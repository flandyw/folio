package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class SelectionMenuGeometryTests {
    private val viewport = SelectionMenuRect(0f, 0f, 600f, 800f)
    private fun place(selection: SelectionMenuRect, area: SelectionMenuRect = viewport, width: Int = 200) =
        SelectionMenuGeometry.place(selection, area, width, 48, 8f, 48f, 16f)

    @Test fun centersOnTheSelectionAndLeavesRoomForTheRotateHandle() {
        assertEquals(SelectionMenuPosition(200, 204), place(SelectionMenuRect(200f, 300f, 400f, 400f)))
    }

    @Test fun usesBelowWhenTheSelectionIsNearTheTop() {
        assertEquals(SelectionMenuPosition(200, 116), place(SelectionMenuRect(200f, 40f, 400f, 100f)))
    }

    @Test fun clampsToThePaneRatherThanTheWholeWindow() {
        val pane = SelectionMenuRect(700f, 100f, 950f, 700f)
        assertEquals(SelectionMenuPosition(708, 204), place(SelectionMenuRect(720f, 300f, 850f, 400f), pane))
    }

    @Test fun partiallyVisibleSelectionsUseTheirVisibleCenter() {
        assertEquals(SelectionMenuPosition(8, 204), place(SelectionMenuRect(-500f, 300f, 40f, 400f)))
        assertEquals(SelectionMenuPosition(392, 204), place(SelectionMenuRect(550f, 300f, 1100f, 400f)))
    }

    @Test fun viewportSizedSelectionUsesAStableShelfBelowTheToolbar() {
        val area = SelectionMenuRect(0f, 120f, 600f, 800f)
        assertEquals(SelectionMenuPosition(200, 128), place(SelectionMenuRect(-100f, 0f, 700f, 1200f), area))
    }

    @Test fun offscreenSelectionsAndImpossibleMenusDoNotGetPlaced() {
        assertNull(place(SelectionMenuRect(700f, 200f, 800f, 400f)))
        assertNull(place(SelectionMenuRect(200f, -300f, 300f, -10f)))
        assertNull(place(SelectionMenuRect(20f, 100f, 60f, 200f), SelectionMenuRect(0f, 0f, 100f, 400f)))
    }
}
