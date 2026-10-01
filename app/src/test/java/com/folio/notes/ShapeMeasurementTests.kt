package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class ShapeMeasurementTests {
    private fun draft(tool: Tool) = Stroke(tool = tool, color = 0, width = 2f, points = listOf(InkPoint(10f, 20f), InkPoint(110f, 70f)))

    @Test fun zoomAndPanMoveTheAnchorWithoutChangingDimensions() {
        val stroke = draft(Tool.RECTANGLE)
        val normal = ShapeMeasurement.from(stroke, GraphStyle.DEFAULT, 0f, 0f, 1f)!!
        val zoomed = ShapeMeasurement.from(stroke, GraphStyle.DEFAULT, 30f, -10f, 4f)!!
        assertEquals("100 × 50", normal.label)
        assertEquals(normal.label, zoomed.label)
        assertEquals(60f, normal.x, 0.001f)
        assertEquals(45f, normal.y, 0.001f)
        assertEquals(270f, zoomed.x, 0.001f)
        assertEquals(170f, zoomed.y, 0.001f)
    }

    @Test fun lineAndEllipseKeepTheirMeasurementFormats() {
        assertEquals("112 pt  27°", ShapeMeasurement.from(draft(Tool.LINE), GraphStyle.DEFAULT, 0f, 0f, 1f)!!.label)
        assertEquals("⌀ 100  r 38", ShapeMeasurement.from(draft(Tool.ELLIPSE), GraphStyle.DEFAULT, 0f, 0f, 1f)!!.label)
    }

    @Test fun freehandAndEmptyDraftsHaveNoTooltip() {
        assertNull(ShapeMeasurement.from(draft(Tool.PEN), GraphStyle.DEFAULT, 0f, 0f, 1f))
        assertNull(ShapeMeasurement.from(draft(Tool.LINE).copy(points = emptyList()), GraphStyle.DEFAULT, 0f, 0f, 1f))
    }
}
