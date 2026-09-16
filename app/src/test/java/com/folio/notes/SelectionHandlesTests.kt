package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionHandlesTests {
    private val bounds = floatArrayOf(0f, 0f, 100f, 80f)

    @Test fun cornerPressGrabsResize() {
        assertEquals(
            InkGeometry.SelectionHandle.RESIZE,
            InkGeometry.selectionHandleAt(bounds, InkPoint(100f, 80f))
        )
        assertEquals(
            InkGeometry.SelectionHandle.RESIZE,
            InkGeometry.selectionHandleAt(bounds, InkPoint(85f, 65f))
        )
    }

    @Test fun pressAboveTopCenterGrabsRotate() {
        assertEquals(
            InkGeometry.SelectionHandle.ROTATE,
            InkGeometry.selectionHandleAt(bounds, InkPoint(50f, -52f))
        )
    }

    @Test fun interiorAndDistantPressesHitNothing() {
        assertEquals(InkGeometry.SelectionHandle.NONE, InkGeometry.selectionHandleAt(bounds, InkPoint(50f, 40f)))
        assertEquals(InkGeometry.SelectionHandle.NONE, InkGeometry.selectionHandleAt(bounds, InkPoint(50f, -200f)))
        assertEquals(InkGeometry.SelectionHandle.NONE, InkGeometry.selectionHandleAt(bounds, InkPoint(200f, 200f)))
        assertEquals(InkGeometry.SelectionHandle.NONE, InkGeometry.selectionHandleAt(floatArrayOf(), InkPoint(0f, 0f)))
    }

    @Test fun resizeWinsWhenHandlesOverlapOnTinySelections() {
        val tiny = floatArrayOf(0f, 0f, 10f, 10f)
        assertEquals(
            InkGeometry.SelectionHandle.RESIZE,
            InkGeometry.selectionHandleAt(tiny, InkPoint(10f, 10f))
        )
    }

    @Test fun rotationDeltaTakesTheShortWayAround() {
        assertEquals(20f, InkGeometry.rotationDelta(10f, 30f), 0.001f)
        assertEquals(-20f, InkGeometry.rotationDelta(30f, 10f), 0.001f)
        assertEquals(20f, InkGeometry.rotationDelta(170f, -170f), 0.001f)
        assertEquals(-20f, InkGeometry.rotationDelta(-170f, 170f), 0.001f)
        assertEquals(0f, InkGeometry.rotationDelta(45f, 45f), 0.001f)
        assertEquals(90f, InkGeometry.angleOf(InkPoint(0f, 0f), InkPoint(0f, 10f)), 0.001f)
        assertEquals(0f, InkGeometry.angleOf(InkPoint(0f, 0f), InkPoint(10f, 0f)), 0.001f)
    }
}
