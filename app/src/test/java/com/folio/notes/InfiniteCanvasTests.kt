package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InfiniteCanvasTests {
    @Test fun panHasNoPageBoundary() {
        val camera = InfiniteViewport()
        camera.pan(-10000f, 25000f)
        assertEquals(-10000f, camera.x, 0f)
        assertEquals(25000f, camera.y, 0f)
    }

    @Test fun pinchKeepsTheWorldPointUnderTheFingers() {
        val camera = InfiniteViewport()
        camera.pan(-2000f, 3000f)
        val worldX = (150f - camera.x) / camera.zoom
        val worldY = (400f - camera.y) / camera.zoom
        camera.scaleBy(2f, 150f, 400f)
        assertEquals(worldX, (150f - camera.x) / camera.zoom, .001f)
        assertEquals(worldY, (400f - camera.y) / camera.zoom, .001f)
        camera.scaleBy(100f, 150f, 400f)
        assertEquals(8f, camera.zoom, 0f)
        assertEquals(worldX, (150f - camera.x) / camera.zoom, .001f)
        camera.reset()
        assertEquals(0f, camera.x, 0f)
        assertEquals(0f, camera.y, 0f)
        assertEquals(1f, camera.zoom, 0f)
    }

    @Test fun invalidZoomDoesNotCorruptCamera() {
        val camera = InfiniteViewport()
        camera.scaleBy(Float.NaN, 20f, 20f)
        camera.scaleBy(-1f, 20f, 20f)
        assertEquals(1f, camera.zoom, 0f)
    }

    @Test fun canvasAndNegativeCoordinatesSurviveStorageAndBackup() {
        val page = NotePage(infinite = true, strokes = listOf(
            Stroke(Tool.PEN, 123, 3f, listOf(InkPoint(-4000f, 9000f)))) ,
            texts = listOf(TextBox(x = -600f, y = -900f, text = "Outside the page")))
        val notebook = Notebook(title = "Canvas", pages = listOf(page))
        assertEquals(notebook, NoteCodec.decode(NoteCodec.encode(notebook)))
        val summary = NoteMetaCodec.decode(NoteMetaCodec.encode(notebook)).pages.single()
        assertTrue(summary.infinite)
        assertFalse(summary.loaded)
        assertEquals(page, NotePageCodec.decode(NotePageCodec.encode(page), summary))
        val legacy = JSONObject(NoteMetaCodec.encode(notebook))
        legacy.getJSONArray("pages").getJSONObject(0).remove("infinite")
        assertFalse(NoteMetaCodec.decode(legacy.toString()).pages.single().infinite)
    }

    @Test fun exportIncludesInkOnBothSidesOfTheOriginalPage() {
        val page = NotePage(infinite = true, strokes = listOf(
            Stroke(Tool.LINE, 123, 4f, listOf(InkPoint(-2000f, -3000f), InkPoint(8000f, 9000f)))))
        val exported = InkRenderer.exportPage(page)
        assertTrue(exported.width > 10000f)
        assertTrue(exported.height > 12000f)
        assertTrue(exported.strokes.single().points.all { it.x > 0f && it.y > 0f && it.x < exported.width && it.y < exported.height })
        assertEquals(-2000f, page.strokes.single().points.first().x, 0f)
    }
}
