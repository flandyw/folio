package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PageImageTests {
    private val picture = PageImage(id = "img-1", x = 10f, y = 20f, width = 200f, height = 100f)

    @Test fun imagesSurviveThePortableAndPageCodecs() {
        val page = NotePage(images = listOf(picture, PageImage(id = "img-2", x = 0f, y = 0f, width = 60f, height = 60f)))
        val note = Notebook(title = "Photos", pages = listOf(page))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
        val summary = page.asSummary()
        assertEquals(page.images, NotePageCodec.decode(NotePageCodec.encode(page), summary).images)
    }

    @Test fun aSummaryDropsPicturesLikeItDropsInk() {
        val page = NotePage(strokes = listOf(Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(1f, 1f)))),
            texts = listOf(TextBox(x = 0f, y = 0f, text = "hi")), images = listOf(picture))
        val summary = page.asSummary()
        assertTrue(summary.images.isEmpty())
        assertFalse(summary.loaded)
    }

    @Test fun aPageFileWrittenBeforePicturesStillReads() {
        val page = NotePage(images = listOf(picture))
        val legacy = JSONObject(NotePageCodec.encode(page)).apply { remove("images") }.toString()
        val restored = NotePageCodec.decode(legacy, page.asSummary())
        assertTrue(restored.images.isEmpty())
        assertEquals(page.strokes, restored.strokes)
    }

    @Test fun imageHitTestingPicksTheTopmostPictureAndItsHandle() {
        val back = PageImage(id = "back", x = 0f, y = 0f, width = 100f, height = 100f)
        val front = PageImage(id = "front", x = 50f, y = 50f, width = 100f, height = 100f)
        assertEquals(front, InkGeometry.imageAt(listOf(back, front), InkPoint(75f, 75f)))
        assertEquals(back, InkGeometry.imageAt(listOf(back, front), InkPoint(10f, 10f)))
        assertNull(InkGeometry.imageAt(listOf(back, front), InkPoint(500f, 500f)))
        assertTrue(InkGeometry.imageHandleContains(front, InkPoint(150f, 150f)))
        assertFalse(InkGeometry.imageHandleContains(front, InkPoint(75f, 75f)))
    }

    @Test fun resizingKeepsTheAspectRatioAndClampsToLimits() {
        val resized = InkGeometry.resizeImage(picture, 400f)
        assertEquals(400f, resized.width, .001f)
        assertEquals(200f, resized.height, .001f)
        assertEquals(PageImage.MIN_SIZE, InkGeometry.resizeImage(picture, 1f).width, .001f)
        assertEquals(PageImage.MAX_SIZE, InkGeometry.resizeImage(picture, 99_999f).width, .001f)
        // A square stays square.
        val square = InkGeometry.resizeImage(PageImage(id = "s", x = 0f, y = 0f, width = 100f, height = 100f), 250f)
        assertEquals(250f, square.width, .001f)
        assertEquals(250f, square.height, .001f)
    }

    @Test fun fittingKeepsPhotosInsideThePage() {
        val (w, h) = InkGeometry.fitImage(2000f, 1000f, 500f)
        assertEquals(500f, w, .001f)
        assertEquals(250f, h, .001f)
        val (smallW, smallH) = InkGeometry.fitImage(100f, 80f, 500f)
        assertEquals(100f, smallW, .001f)
        assertEquals(80f, smallH, .001f)
    }

    @Test fun anArchiveRoundTripsItsPictures() {
        val note = Notebook(title = "Field trip", pages = listOf(NotePage(images = listOf(picture))))
        val bytes = ByteArrayOutputStream().also {
            NotebookArchive.write(note, null, mapOf("img-1" to byteArrayOf(9, 8, 7)), it)
        }.toByteArray()
        val restored = NotebookArchive.read(ByteArrayInputStream(bytes))
        assertEquals(note, restored.note)
        assertArrayEquals(byteArrayOf(9, 8, 7), restored.images.getValue("img-1"))
    }

    @Test fun anArchiveWithoutPicturesComesBackWithoutThem() {
        val note = Notebook(title = "Plain", pages = listOf(NotePage()))
        val bytes = ByteArrayOutputStream().also { NotebookArchive.write(note, null, it) }.toByteArray()
        val restored = NotebookArchive.read(ByteArrayInputStream(bytes))
        assertEquals(note, restored.note)
        assertTrue(restored.images.isEmpty())
    }

    @Test fun exportBoundsIncludePicturesOnAnInfiniteCanvas() {
        val page = NotePage(width = 840f, height = 1188f, infinite = true,
            images = listOf(PageImage(id = "far", x = -500f, y = -400f, width = 100f, height = 80f)))
        val exported = InkRenderer.exportPage(page)
        assertTrue(exported.width >= 840f + 500f)
        assertTrue(exported.height >= 1188f + 400f)
        // Stored coordinates move with the content, so the picture keeps its relative place.
        assertEquals(24f, exported.images.single().x, 1f)
        assertEquals(24f, exported.images.single().y, 1f)
    }
}
