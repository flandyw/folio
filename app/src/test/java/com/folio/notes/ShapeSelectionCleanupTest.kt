package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class ShapeSelectionCleanupTest {
    private fun line(y: Float = 20f, layer: Int = 0) = Stroke(Tool.PEN, 0x123456, 3f,
        (0..40).map { InkPoint(10f + it * 5f, y, .5f) }, .6f, layer = layer)

    @Test fun replacesOnlySelectedInkInPlaceAndUndoRestoresOriginalSamples() {
        val first = line()
        val selected = line(40f)
        val last = line(60f)
        val page = NotePage(strokes = listOf(first, selected, last))
        val result = ShapeSelectionCleanup.plan(page, CanvasSelection(strokes = listOf(selected)), ShapeAssistOptions())
        assertEquals(1, result.changed)
        assertSame(first, result.content.strokes[0])
        assertSame(last, result.content.strokes[2])
        assertEquals(Tool.LINE, result.content.strokes[1].tool)
        assertEquals(selected.layer, result.content.strokes[1].layer)
        assertEquals(selected.opacity, result.content.strokes[1].opacity)
        val edit = requireNotNull(PageJournal.diff(page.content(), result.content))
        val inverse = PageJournal.invert(edit, page.content())
        assertEquals(page.content(), PageJournal.apply(result.content, inverse))
        assertEquals(result.content.strokes[1], result.selection.strokes.single())
    }

    @Test fun respectsHiddenLockedAndUnselectedLayers() {
        val locked = line(layer = 1)
        val hidden = line(layer = 2)
        val unpicked = line(layer = 0)
        val page = NotePage(strokes = listOf(locked, hidden, unpicked), layers = listOf(
            PageLayer(0, "Base"), PageLayer(1, "Locked", locked = true), PageLayer(2, "Hidden", visible = false)))
        val result = ShapeSelectionCleanup.plan(page, CanvasSelection(strokes = listOf(locked, hidden)), ShapeAssistOptions())
        assertEquals(0, result.changed)
        assertEquals(page.content(), result.content)
    }

    @Test fun tidiesStickyInkInLocalCoordinatesAndKeepsTextImagesAndOtherNotes() {
        val ink = line()
        val selected = TextBox(x = 300f, y = 400f, text = "Remember", stickyHeight = 200f, stickyInk = listOf(ink))
        val other = selected.copy(id = "other")
        val image = PageImage(id = "photo", x = 0f, y = 0f, width = 20f, height = 30f)
        val page = NotePage(texts = listOf(selected, other), images = listOf(image))
        val result = ShapeSelectionCleanup.plan(page, CanvasSelection(texts = listOf(selected)), ShapeAssistOptions())
        assertEquals(1, result.changed)
        assertEquals(listOf(ink.points.first().copy(pressure = 1f), ink.points.last().copy(pressure = 1f)), result.content.texts[0].stickyInk.single().points)
        assertEquals(selected.text, result.content.texts[0].text)
        assertEquals(selected.x, result.content.texts[0].x)
        assertSame(other, result.content.texts[1])
        assertSame(image, result.content.images.single())
    }

    @Test fun disabledFamiliesCreateNoEditAndAlreadyCleanShapesStayUntouched() {
        val source = line()
        val clean = requireNotNull(ShapeRecognition.tidy(source)).single()
        val page = NotePage(strokes = listOf(source, clean))
        val result = ShapeSelectionCleanup.plan(page, CanvasSelection(strokes = page.strokes), ShapeAssistOptions(lines = false))
        assertEquals(0, result.changed)
        assertNull(PageJournal.diff(page.content(), result.content))
        assertSame(clean, ShapeCleanup.strokes(listOf(clean), ShapeAssistOptions()).strokes.single())
    }

    @Test fun joinsABoxDrawnLineByLineAtItsFirstStrokeAndUndoRestoresEveryStroke() {
        fun edge(ax: Float, ay: Float, bx: Float, by: Float) = Stroke(Tool.PEN, 0x123456, 3f,
            (0..40).map { InkPoint(ax + (bx - ax) * it / 40f, ay + (by - ay) * it / 40f, .5f) }, .6f, layer = 0)
        val before = line(400f)
        val sides = listOf(edge(100f, 100f, 300f, 100f), edge(300f, 100f, 300f, 220f), edge(300f, 220f, 100f, 220f), edge(100f, 220f, 100f, 100f))
        val between = line(500f)
        val page = NotePage(strokes = listOf(before, sides[0], sides[1], between, sides[2], sides[3]))
        val result = ShapeSelectionCleanup.plan(page, CanvasSelection(strokes = sides), ShapeAssistOptions())
        assertEquals(4, result.changed)
        assertEquals(1, result.shapes)
        assertEquals(3, result.content.strokes.size)
        assertSame(before, result.content.strokes[0])
        assertEquals(Tool.RECTANGLE, result.content.strokes[1].tool)
        assertSame(between, result.content.strokes[2])
        assertEquals(listOf(result.content.strokes[1]), result.selection.strokes)
        val edit = requireNotNull(PageJournal.diff(page.content(), result.content))
        assertEquals(page.content(), PageJournal.apply(result.content, PageJournal.invert(edit, page.content())))
    }
}
