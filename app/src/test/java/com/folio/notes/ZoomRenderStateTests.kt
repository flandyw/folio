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
        assertTrue(state.usePreview(1f, false, 889))
        assertFalse(state.usePreview(1f, false, 890))
    }

    @Test fun briefPauseBetweenPinchesDoesNotRebuildDetail() {
        val state = ZoomRenderState()
        state.usePreview(1f, false, 0)
        assertTrue(state.usePreview(2f, false, 10))
        assertTrue(state.usePreview(2f, false, 70))
        assertTrue(state.usePreview(1f, false, 80))
        assertTrue(state.usePreview(1f, false, 169))
        assertFalse(state.usePreview(1f, false, 170))
    }

    @Test fun nativePanStaysInPreviewAndPageResetStartsFresh() {
        val state = ZoomRenderState()
        assertTrue(state.usePreview(1f, true, 0))
        assertTrue(state.usePreview(1f, true, 500))
        assertTrue(state.usePreview(1f, false, 589))
        assertFalse(state.usePreview(1f, false, 590))
        state.reset()
        assertFalse(state.usePreview(3f, false, 591))
    }
}
