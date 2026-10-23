package com.folio.notes.editor.layout

import com.folio.notes.app.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomLimitTest {
    @Test fun preferenceUsesFiniteBoundedValues() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(AppPrefs.DEFAULT_MAX_ZOOM, AppPrefs.maxZoom(it), 0f)
        }
        assertEquals(1f, AppPrefs.maxZoom(-10f), 0f)
        assertEquals(8f, AppPrefs.maxZoom(100f), 0f)
        assertEquals(6f, AppPrefs.maxZoom(6f), 0f)
    }

    @Test fun pinchRestoreAndFitRespectTheChosenLimit() {
        val camera = InfiniteViewport().apply { maxZoom = 6f }
        camera.scaleBy(100f, 100f, 200f)
        assertEquals(6f, camera.zoom, 0f)
        assertEquals(100f, (100f - camera.x) / camera.zoom, .001f)
        assertEquals(200f, (200f - camera.y) / camera.zoom, .001f)
        camera.restore(0f, 0f, 20f)
        assertEquals(6f, camera.zoom, 0f)
        camera.fit(0f, 0f, 1f, 1f, 1000f, 1000f)
        assertEquals(6f, camera.zoom, 0f)
        assertEquals(.5f, (500f - camera.x) / camera.zoom, .001f)
        camera.scaleBy(.0001f, 0f, 0f)
        assertEquals(.1f, camera.zoom, 0f)
    }

    @Test fun loweringTheLimitKeepsTheFocusedWorldPoint() {
        val camera = InfiniteViewport()
        camera.restore(-300f, -400f, 6f)
        val worldX = (100f - camera.x) / camera.zoom
        val worldY = (200f - camera.y) / camera.zoom
        camera.maxZoom = 2f
        camera.scaleBy(1f, 100f, 200f)
        assertEquals(2f, camera.zoom, 0f)
        assertEquals(worldX, (100f - camera.x) / camera.zoom, .001f)
        assertEquals(worldY, (200f - camera.y) / camera.zoom, .001f)
    }
}
