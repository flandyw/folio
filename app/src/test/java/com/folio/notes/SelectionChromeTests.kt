package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class SelectionChromeTests {
    @Test fun handleSizeStaysInScreenDpDuringZoomAndResize() {
        for (density in listOf(1f, 2f, 3f)) for (zoom in listOf(0.25f, 1f, 4f, 10f)) {
            for (preview in listOf(0.2f, 1f, 3f)) {
                val unit = SelectionChrome.pageUnit(density, zoom, preview)
                val screenRadius = SelectionChrome.HANDLE_RADIUS_DP * unit * zoom * preview
                assertEquals(8f * density, screenRadius, 0.001f)
                val screenLift = SelectionChrome.ROTATE_LIFT_DP * unit * zoom * preview
                assertEquals(32f * density, screenLift, 0.001f)
            }
        }
    }

    @Test fun handleHitTargetsFollowTheirScreenSizedPositions() {
        for (zoom in listOf(0.25f, 1f, 4f, 10f)) {
            val unit = SelectionChrome.pageUnit(2f, zoom)
            val box = floatArrayOf(0f, 0f, 400f * unit, 400f * unit)
            val touch = SelectionChrome.TOUCH_RADIUS_DP * unit
            val lift = SelectionChrome.ROTATE_LIFT_DP * unit
            fun hit(x: Float, y: Float) = InkGeometry.selectionHandleAt(box, InkPoint(x, y), touch, lift)
            assertEquals(InkGeometry.SelectionHandle.ROTATE, hit(200f * unit, -lift))
            assertEquals(InkGeometry.SelectionHandle.RESIZE, hit(box[2] + 23f * unit, box[3]))
            assertEquals(InkGeometry.SelectionHandle.NONE, hit(box[2] + 25f * unit, box[3]))
            assertEquals(InkGeometry.SelectionHandle.NONE, hit(200f * unit, -lift - 25f * unit))
        }
    }
}
