package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class ZoomRenderStateTests {
    @Test fun containerResizeUsesPreviewWithoutNativeGesture() {
        val state = ZoomRenderState()
        assertFalse(state.usePreview(1f, false, 0))
        repeat(100) { frame ->
            val scale = if (frame % 2 == 0) 2f else 1f
            assertTrue(state.usePreview(scale, false, (frame + 1) * 8L))
        }
        assertTrue(state.usePreview(1f, false, 979))
        assertFalse(state.usePreview(1f, false, 980))
    }

    @Test fun briefPauseBetweenPinchesDoesNotRebuildDetail() {
        val state = ZoomRenderState()
        state.usePreview(1f, false, 0)
        assertTrue(state.usePreview(2f, false, 10))
        assertTrue(state.usePreview(2f, false, 150))
        assertTrue(state.usePreview(1f, false, 160))
        assertTrue(state.usePreview(1f, false, 339))
        assertFalse(state.usePreview(1f, false, 340))
    }

    @Test fun nativePanStaysInPreviewAndPageResetStartsFresh() {
        val state = ZoomRenderState()
        assertTrue(state.usePreview(1f, true, 0))
        assertTrue(state.usePreview(1f, true, 500))
        assertTrue(state.usePreview(1f, false, 679))
        assertFalse(state.usePreview(1f, false, 680))
        state.reset()
        assertFalse(state.usePreview(3f, false, 681))
    }
}
