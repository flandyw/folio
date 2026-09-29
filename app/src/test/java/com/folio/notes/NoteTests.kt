package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class NoteTests {
    @Test fun duplicatePdfPageRetainsBackgroundAndInkWithIndependentIdentity() {
        val source = NotePage(width = 600f, height = 400f, paper = Paper.PLAIN, pdfIndex = 3,
            strokes = listOf(Stroke(Tool.PEN, -1, 2f, listOf(InkPoint(10f, 20f)))))
        val next = NotePage()
        val original = Notebook(title = "Lecture", pages = listOf(source, next))
        val copied = original.withDuplicatedPage(0)
        assertEquals(3, copied.pages.size)
        assertEquals(source, copied.pages[0])
        assertEquals(next, copied.pages[2])
        assertNotEquals(source.id, copied.pages[1].id)
        assertEquals(source, copied.pages[1].copy(id = source.id))
        assertEquals(2, original.pages.size)
        assertEquals(copied, NoteCodec.decode(NoteCodec.encode(copied)))
        val edited = copied.copy(pages = copied.pages.mapIndexed { i, page -> if (i == 1) page.copy(strokes = emptyList()) else page })
        assertEquals(source.strokes, edited.pages[0].strokes)
        assertTrue(edited.pages[1].strokes.isEmpty())
    }
    @Test fun duplicateLastPageAppendsAndInvalidPageLeavesNotebookUntouched() {
        val note = Notebook(title = "Notes")
        assertEquals(2, note.withDuplicatedPage(0).pages.size)
        assertEquals(note, note.withDuplicatedPage(-1))
        assertEquals(note, note.withDuplicatedPage(1))
    }
    @Test fun documentZoomKeepsThePointUnderTheFingers() {
        // A point 150 px into a page, 50 px below the viewport top, stays at 50 px after 2x zoom.
        assertEquals(250, DocumentViewport.zoomScroll(100, 50f, 2f))
        assertEquals(100, DocumentViewport.zoomScroll(250, 50f, 0.5f))
        assertEquals(-100f, DocumentViewport.zoomPan(0f, 600f, 1000f, 1800f, 2f), .001f)
    }
    @Test fun documentPanCannotLoseThePagesOutsideTheViewport() {
        // Zoomed in, a pan stops with a page edge on a viewport edge.
        assertEquals(400f, DocumentViewport.clampPan(900f, 1800f, 1000f), .001f)
        assertEquals(-400f, DocumentViewport.clampPan(-900f, 1800f, 1000f), .001f)
        // Zoomed out, the page still pans: it may be pushed aside, but never off screen.
        assertEquals(100f, DocumentViewport.clampPan(500f, 800f, 1000f), .001f)
        assertEquals(-100f, DocumentViewport.clampPan(-500f, 800f, 1000f), .001f)
        assertEquals(0f, DocumentViewport.clampPan(0f, 800f, 1000f), .001f)
        // A page exactly as wide as the viewport has nowhere to go.
        assertEquals(0f, DocumentViewport.clampPan(80f, 1000f, 1000f), .001f)
    }
    @Test fun customOpacitySurvivesSaveAndReload() {
        val note = Notebook(title = "Custom ink", pages = listOf(NotePage(strokes = listOf(
            Stroke(Tool.HIGHLIGHTER, -1234, 21f, listOf(InkPoint(10f, 20f)), .45f),
            Stroke(Tool.PEN, -9876, 1.5f, listOf(InkPoint(30f, 40f, .8f)), .7f)
        ))))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }
    @Test fun oldHighlighterRetainsItsOriginalOpacity() {
        val note = Notebook(title = "Old ink", pages = listOf(NotePage(strokes = listOf(
            Stroke(Tool.HIGHLIGHTER, -1234, 20f, listOf(InkPoint(10f, 20f)))
        ))))
        val json = org.json.JSONObject(NoteCodec.encode(note))
        json.getJSONArray("pages").getJSONObject(0).getJSONArray("strokes").getJSONObject(0).remove("opacity")
        assertEquals(72f / 255f, NoteCodec.decode(json.toString()).pages.first().strokes.first().opacity, .0001f)
    }
    @Test fun notebookRoundTripPreservesPdfInkAndOrganization() {
        val note = Notebook(title = "Lecture • 你好", folderId = "folder-1", cover = 3, starred = true, pages = listOf(
            NotePage(width = 840f, height = 630f, paper = Paper.PLAIN, pdfIndex = 2, strokes = listOf(
                Stroke(Tool.PEN, -1234567, 2.5f, listOf(InkPoint(4f, 8f, .3f), InkPoint(20f, 40f, 1.2f))),
                Stroke(Tool.ELLIPSE, -12, 8f, listOf(InkPoint(0f, 0f), InkPoint(80f, 120f)))
            )), NotePage(paper = Paper.RULED)
        ))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }
    @Test fun eraserHitsBetweenSparseSamples() {
        val stroke = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(0f, 0f), InkPoint(100f, 0f)))
        assertTrue(InkGeometry.hits(stroke, InkPoint(50f, 3f), 3f))
        assertFalse(InkGeometry.hits(stroke, InkPoint(50f, 15f), 3f))
    }
    @Test fun scribbleEraseKeepsDistantDenseInkAndCopiesOnlyWhenItHits() {
        val scrub = Stroke(Tool.PEN, 0, 2f, listOf(
            InkPoint(0f, 0f), InkPoint(40f, 3f), InkPoint(0f, 6f),
            InkPoint(40f, 9f), InkPoint(0f, 12f), InkPoint(40f, 15f)))
        assertTrue(InkGeometry.isScribble(scrub.points))
        val distant = (0 until 2_000).map { index ->
            Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(100f + index, 100f)))
        }
        val target = Stroke(Tool.LINE, 0, 2f, listOf(InkPoint(20f, 0f), InkPoint(20f, 15f)))
        val boundsOf: (Stroke) -> FloatArray = { stroke ->
            val x = stroke.points.first().x
            if (stroke === target) floatArrayOf(20f, 0f, 20f, 15f)
            else floatArrayOf(x, 100f, x, 100f)
        }
        assertSame(distant, InkGeometry.scribbleErase(distant, scrub, 14f, boundsOf = boundsOf))
        val withTarget = distant + target
        val survivors = InkGeometry.scribbleErase(withTarget, scrub, 14f, boundsOf = boundsOf)
        assertEquals(distant.size, survivors.size)
        assertTrue(survivors.indices.all { survivors[it] === distant[it] })
    }
    @Test fun livePressureWidthsStayCorrectAsSplineSettles() {
        val builder = InkRenderer.IncrementalPenStroke()
        val points = ArrayList<InkPoint>()
        for (index in 0 until 320) {
            points += InkPoint(index * 1.7f,
                (index % 17 - 8) * 1.3f + (index / 70) * 12f,
                0.3f + (index % 11) * 0.12f)
            val live = builder.update(points)
            if (live.centre.size < 2) continue
            val widths = live.widths!!
            assertTrue(widths.size >= live.centre.size)
            for (point in live.centre.indices) {
                assertEquals(InkRenderer.penPressureScale(live.centre[point].pressure),
                    widths[point], 0.00001f)
            }
        }
    }
    @Test fun eraserUsesRectangleEdgesNotDiagonal() {
        val stroke = Stroke(Tool.RECTANGLE, 0, 2f, listOf(InkPoint(0f, 0f), InkPoint(100f, 100f)))
        assertTrue(InkGeometry.hits(stroke, InkPoint(50f, 0f), 3f))
        assertFalse(InkGeometry.hits(stroke, InkPoint(50f, 50f), 3f))
    }
    @Test fun eraserUsesEllipseBoundaryForReverseDrag() {
        val stroke = Stroke(Tool.ELLIPSE, 0, 2f, listOf(InkPoint(100f, 100f), InkPoint(0f, 0f)))
        assertTrue(InkGeometry.hits(stroke, InkPoint(50f, 0f), 3f))
        assertFalse(InkGeometry.hits(stroke, InkPoint(50f, 50f), 3f))
    }
    @Test fun eraserHandlesSingleDotsAndZeroLengthLines() {
        val dot = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(2f, 3f)))
        assertTrue(InkGeometry.hits(dot, InkPoint(2f, 3f), 1f))
        assertEquals(5f, InkGeometry.segmentDistance(InkPoint(3f, 4f), InkPoint(0f, 0f), InkPoint(0f, 0f)), .001f)
    }
    @Test fun lassoSelectsStrokesFullyInsideTheLoop() {
        val loop = listOf(InkPoint(0f, 0f), InkPoint(100f, 0f), InkPoint(50f, 100f))
        assertTrue(InkGeometry.lassoSelects(loop, Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(50f, 50f)))))
        assertFalse(InkGeometry.lassoSelects(loop, Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(150f, 50f)))))
        // A stroke crossing the boundary stays put rather than being half selected.
        assertFalse(InkGeometry.lassoSelects(loop, Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(50f, 50f), InkPoint(50f, 200f)))))
    }
    @Test fun lassoHandlesShapesAndRejectsLoopsTooSmallToEnclose() {
        val loop = listOf(InkPoint(0f, 0f), InkPoint(200f, 0f), InkPoint(200f, 200f), InkPoint(0f, 200f))
        val rectangle = Stroke(Tool.RECTANGLE, 0, 2f, listOf(InkPoint(20f, 20f), InkPoint(120f, 120f)))
        assertTrue(InkGeometry.lassoSelects(loop, rectangle))
        assertFalse(InkGeometry.lassoSelects(listOf(InkPoint(0f, 0f), InkPoint(10f, 0f)), rectangle))
        assertFalse(InkGeometry.lassoSelects(emptyList(), rectangle))
    }
    @Test(expected = IllegalArgumentException::class) fun futureSchemaFailsInsteadOfLosingData() {
        NoteCodec.decode(NoteCodec.encode(Notebook(title = "Future")).replace("\"version\":1", "\"version\":2"))
    }
}
