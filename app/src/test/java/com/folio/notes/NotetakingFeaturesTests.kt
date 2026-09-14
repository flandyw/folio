package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class StrokeStyleTests {
    private fun line(style: StrokeStyle) =
        Stroke(Tool.LINE, 0xFF112233.toInt(), 2f, listOf(InkPoint(0f, 0f), InkPoint(100f, 50f)), style = style)

    @Test fun dashedAndDottedStylesSurviveSaveAndReload() {
        listOf(StrokeStyle.SOLID, StrokeStyle.DASHED, StrokeStyle.DOTTED).forEach { style ->
            val strokes = listOf(line(style))
            assertEquals(strokes, InkCodec.decodeStrokes(InkCodec.encodeStrokes(strokes)))
        }
    }

    @Test fun inkSavedBeforeStylesExistedStillOpensAsSolid() {
        val strokes = listOf(line(StrokeStyle.DASHED))
        val json = InkCodec.encodeStrokes(strokes)
        json.getJSONObject(0).remove("style")
        assertEquals(listOf(line(StrokeStyle.SOLID)), InkCodec.decodeStrokes(json))
        // An unknown style name never breaks an old notebook.
        val broken = InkCodec.encodeStrokes(strokes)
        broken.getJSONObject(0).put("style", "WAVY")
        assertEquals(StrokeStyle.SOLID, InkCodec.decodeStrokes(broken).single().style)
    }

    @Test fun restylingLineStyleOnlyTouchesShapes() {
        val shape = line(StrokeStyle.SOLID)
        val pen = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(5f, 5f)))
        val restyled = InkGeometry.restyle(listOf(shape, pen), style = StrokeStyle.DASHED)
        assertEquals(StrokeStyle.DASHED, restyled[0].style)
        // Freehand ink keeps its solid look so pressure strokes never fragment.
        assertEquals(StrokeStyle.SOLID, restyled[1].style)
        assertEquals(shape.points, restyled[0].points)
        // A null style leaves everything exactly as it was.
        assertEquals(listOf(shape, pen), InkGeometry.restyle(listOf(shape, pen), style = null))
    }

    @Test fun dashPatternsAreNullOnlyForSolid() {
        assertNull(InkRenderer.dashEffect(StrokeStyle.SOLID, 2f))
        assertNotNull(InkRenderer.dashEffect(StrokeStyle.DASHED, 2f))
        assertNotNull(InkRenderer.dashEffect(StrokeStyle.DOTTED, 2f))
    }
}

class TextFormatTests {
    @Test fun alignmentAndUnderlineSurviveSaveAndReload() {
        val note = Notebook(title = "Formatted", pages = listOf(NotePage(
            texts = listOf(
                TextBox(x = 10f, y = 20f, text = "Centred heading", align = TextAlignMode.CENTER, bold = true),
                TextBox(x = 10f, y = 80f, text = "Right note", align = TextAlignMode.RIGHT, underline = true)
            )
        )))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }

    @Test fun textSavedBeforeFormattingExistedStillOpensLeftAligned() {
        val box = TextBox(x = 5f, y = 5f, text = "Legacy")
        val json = InkCodec.encodeTexts(listOf(box))
        json.getJSONObject(0).remove("align")
        json.getJSONObject(0).remove("underline")
        val restored = InkCodec.decodeTexts(json).single()
        assertEquals(TextAlignMode.LEFT, restored.align)
        assertFalse(restored.underline)
        assertEquals(box, restored)
    }

    @Test fun unknownAlignmentFallsBackToLeft() {
        val json = InkCodec.encodeTexts(listOf(TextBox(x = 0f, y = 0f, text = "Hi")))
        json.getJSONObject(0).put("align", "JUSTIFIED")
        assertEquals(TextAlignMode.LEFT, InkCodec.decodeTexts(json).single().align)
    }
}

class ToolPresetTests {
    private fun options() = ToolOptions(0xFF112233.toInt(), 2.5f, 0.8f, true)

    @Test fun presetsSurviveSaveAndReload() {
        val presets = listOf(
            ToolPreset(name = "Fine black", tool = Tool.PEN, color = 1, width = 1.4f, opacity = 1f),
            ToolPreset(name = "Dashed blue", tool = Tool.LINE, color = 2, width = 2f, opacity = 1f, style = StrokeStyle.DASHED)
        )
        assertEquals(presets, ToolPresets.decode(ToolPresets.encode(presets)))
    }

    @Test fun unusableStoredPresetsAreSkipped() {
        assertTrue(ToolPresets.decode(null).isEmpty())
        assertTrue(ToolPresets.decode("not json").isEmpty())
        // Blank names, unknown tools and non-drawing tools never come back.
        val stored = """[{"name":"","tool":"PEN","color":1,"width":2,"opacity":1},
            {"name":"Ghost","tool":"NOPE","color":1,"width":2,"opacity":1},
            {"name":"Eraser fav","tool":"ERASER","color":1,"width":2,"opacity":1},
            {"name":"Good","tool":"PEN","color":5,"width":2,"opacity":1}]"""
        assertEquals(listOf("Good"), ToolPresets.decode(stored).map { it.name })
    }

    @Test fun savingAPresetReplacesTheOneWithTheSameNameAndCapsTheList() {
        val first = ToolPreset(name = "Revision", tool = Tool.PEN, color = 1, width = 1f, opacity = 1f)
        val replacement = ToolPreset(name = "revision", tool = Tool.HIGHLIGHTER, color = 2, width = 3f, opacity = 0.5f)
        val updated = ToolPresets.upsert(listOf(first), replacement)
        assertEquals(1, updated.size)
        assertEquals(Tool.HIGHLIGHTER, updated.single().tool)
        assertTrue(ToolPresets.remove(updated, updated.single().id).isEmpty())
        val many = (1..ToolPresets.MAX_PRESETS + 5).map {
            ToolPreset(name = "P$it", tool = Tool.PEN, color = it, width = 2f, opacity = 1f)
        }
        assertEquals(ToolPresets.MAX_PRESETS, ToolPresets.decode(ToolPresets.encode(many)).size)
    }

    @Test fun onlyDrawingToolsBecomePresets() {
        assertNotNull(ToolPresets.fromOptions("Pen", Tool.PEN, options()))
        assertNotNull(ToolPresets.fromOptions("Shape", Tool.RECTANGLE, options(), StrokeStyle.DOTTED))
        assertNull(ToolPresets.fromOptions("Eraser", Tool.ERASER, options()))
        assertNull(ToolPresets.fromOptions("Hand", Tool.HAND, options()))
        assertNull(ToolPresets.fromOptions("   ", Tool.PEN, options()))
        assertEquals(ToolPresets.MAX_NAME, ToolPresets.normalizedName("x".repeat(60)).length)
    }
}

class NotebookTextSearchTests {
    private fun page(vararg texts: String) = NotePage(
        texts = texts.map { TextBox(x = 0f, y = 0f, text = it) }
    )

    @Test fun blankQueriesFindNothing() {
        val pages = listOf(page("Hello world"))
        assertTrue(NotebookTextSearch.search(pages, "").isEmpty())
        assertTrue(NotebookTextSearch.search(pages, "   ").isEmpty())
    }

    @Test fun matchingIsCaseInsensitiveAndRankedFirst() {
        val pages = listOf(
            page("Quadratic formula"),
            page("quadratic quadratic quadratic"),
            page("Unrelated notes")
        )
        val hits = NotebookTextSearch.search(pages, "QUADRATIC")
        assertEquals(listOf(1, 0), hits.map { it.pageIndex })
        assertEquals(3, hits.first().matchCount)
        assertTrue(hits.first().snippet.contains("quadratic", ignoreCase = true))
    }

    @Test fun everyTermMustAppearAcrossTheBoxesOfOnePage() {
        val pages = listOf(page("Ohm's law", "V = IR"))
        // Both terms on the same page count, even split across two boxes.
        val hits = NotebookTextSearch.search(pages, "ohm IR")
        assertEquals(1, hits.size)
        assertEquals(2, hits.single().matchCount)
        assertTrue(NotebookTextSearch.search(pages, "ohm capacitance").isEmpty())
    }
}

class InkStampTests {
    @Test fun everyElementInsertsEditableInk() {
        InkStamps.kinds.forEach { kind ->
            val strokes = InkStamps.make(kind, 420f, 594f)
            assertTrue("$kind is empty", strokes.isNotEmpty())
            assertTrue("$kind has no samples", strokes.all { it.points.size >= 2 })
        }
    }

    @Test fun stampShapesHaveTheirExpectedStructure() {
        assertEquals(3, InkStamps.make(InkStamps.Kind.ARROW, 0f, 0f).size)
        assertEquals(5, InkStamps.make(InkStamps.Kind.DOUBLE_ARROW, 0f, 0f).size)
        assertEquals(10, InkStamps.make(InkStamps.Kind.STAR, 0f, 0f).size)
        assertEquals(2, InkStamps.make(InkStamps.Kind.CHECKBOX, 0f, 0f).size)
        // A star is centred on the requested point (within the star's own asymmetry:
        // the top point reaches further than the two bottom points).
        val star = InkStamps.make(InkStamps.Kind.STAR, 100f, 200f)
        val bounds = InkGeometry.bounds(star)!!
        assertEquals(100f, (bounds[0] + bounds[2]) / 2f, 1f)
        assertEquals(200f, (bounds[1] + bounds[3]) / 2f, 12f)
    }

    @Test fun `stamps carry the chosen look and survive a codec round trip`() {
        val strokes = InkStamps.make(InkStamps.Kind.ARROW, 10f, 10f, color = 123, width = 3.5f)
        assertTrue(strokes.all { it.color == 123 && it.width == 3.5f })
        assertEquals(strokes, InkCodec.decodeStrokes(InkCodec.encodeStrokes(strokes)))
    }
}

class LassoMixedSelectionTests {
    private val loop = listOf(
        InkPoint(0f, 0f), InkPoint(500f, 0f), InkPoint(500f, 500f), InkPoint(0f, 500f)
    )

    @Test fun aRectangleIsSelectedOnlyWhenWhollyEnclosed() {
        assertTrue(InkGeometry.lassoSelectsRect(loop, 10f, 10f, 100f, 100f))
        // One corner outside is enough to leave the box alone, like a half-crossed stroke.
        assertFalse(InkGeometry.lassoSelectsRect(loop, 10f, 10f, 600f, 100f))
        assertFalse(InkGeometry.lassoSelectsRect(loop, 490f, 490f, 510f, 510f))
        // A degenerate loop never selects anything.
        assertFalse(InkGeometry.lassoSelectsRect(listOf(InkPoint(0f, 0f)), 10f, 10f, 20f, 20f))
    }

    @Test fun textAndPicturesFollowTheSameEnclosureRule() {
        val inside = TextBox(x = 20f, y = 30f, width = 200f, text = "Note")
        assertTrue(InkGeometry.lassoSelectsText(loop, inside, height = 40f))
        assertFalse(InkGeometry.lassoSelectsText(loop, inside.copy(x = 450f, width = 200f), height = 40f))
        val picture = PageImage(x = 50f, y = 50f, width = 100f, height = 100f)
        assertTrue(InkGeometry.lassoSelectsImage(loop, picture))
        assertFalse(InkGeometry.lassoSelectsImage(loop, picture.copy(x = 450f)))
    }

    @Test fun combinedBoundsCoverInkTextAndPictures() {
        val strokes = listOf(Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(10f, 10f))))
        val texts = listOf(TextBox(x = 400f, y = 400f, width = 100f, text = "Hi"))
        val images = listOf(PageImage(x = 200f, y = 300f, width = 50f, height = 60f))
        val bounds = InkGeometry.selectionBounds(strokes, texts, images, { 30f })!!
        assertEquals(10f, bounds[0], .001f)
        assertEquals(10f, bounds[1], .001f)
        assertEquals(500f, bounds[2], .001f)
        assertEquals(430f, bounds[3], .001f)
        val center = InkGeometry.selectionCenter(strokes, texts, images, { 30f })!!
        assertEquals(255f, center.x, .001f)
        assertEquals(220f, center.y, .001f)
        assertNull(InkGeometry.selectionBounds(emptyList(), emptyList(), emptyList(), { 0f }))
        assertNull(InkGeometry.selectionCenter(emptyList(), emptyList(), emptyList(), { 0f }))
    }

    @Test fun rotatingMovesTextAndPicturesWithoutChangingTheirSize() {
        val center = InkPoint(100f, 100f)
        val texts = InkGeometry.rotateTexts(listOf(TextBox(x = 100f, y = 50f, text = "A", size = 26f)), center, 90f)
        assertEquals(150f, texts.single().x, .01f)
        assertEquals(100f, texts.single().y, .01f)
        assertEquals(26f, texts.single().size, .001f)
        val images = InkGeometry.rotateImages(listOf(PageImage(x = 100f, y = 50f, width = 40f, height = 20f)), center, 90f)
        assertEquals(150f, images.single().x, .01f)
        assertEquals(100f, images.single().y, .01f)
        assertEquals(40f, images.single().width, .001f)
        // Four right angles bring a box exactly home, matching stroke rotation.
        val round = (1..4).fold(listOf(TextBox(x = 100f, y = 50f, text = "A"))) { acc, _ -> InkGeometry.rotateTexts(acc, center, 90f) }
        assertEquals(100f, round.single().x, .01f)
        assertEquals(50f, round.single().y, .01f)
    }

    @Test fun scalingKeepsTextAndPicturesInProportionAndClamped() {
        val center = InkPoint(0f, 0f)
        val texts = InkGeometry.scaleTexts(listOf(TextBox(x = 100f, y = 100f, width = 200f, text = "A", size = 20f)), center, 2f)
        assertEquals(200f, texts.single().x, .001f)
        assertEquals(400f, texts.single().width, .001f)
        assertEquals(40f, texts.single().size, .001f)
        // Sizes clamp instead of vanishing or exploding.
        val tiny = InkGeometry.scaleTexts(listOf(TextBox(x = 0f, y = 0f, text = "A", size = 20f)), center, 0.01f)
        assertEquals(TextBox.MIN_SIZE, tiny.single().size, .001f)
        assertEquals(TextBox.MIN_WIDTH, tiny.single().width, .001f)
        val huge = InkGeometry.scaleTexts(listOf(TextBox(x = 0f, y = 0f, text = "A", size = 20f)), center, 100f)
        assertEquals(TextBox.MAX_SIZE, huge.single().size, .001f)
        val images = InkGeometry.scaleImages(listOf(PageImage(x = 100f, y = 100f, width = 100f, height = 50f)), center, 2f)
        assertEquals(200f, images.single().x, .001f)
        assertEquals(200f, images.single().width, .001f)
        assertEquals(100f, images.single().height, .001f)
        val smallImages = InkGeometry.scaleImages(listOf(PageImage(x = 0f, y = 0f, width = 100f, height = 100f)), center, 0.01f)
        assertEquals(PageImage.MIN_SIZE, smallImages.single().width, .001f)
    }

    @Test fun aMixedSelectionCountsEveryItem() {
        assertTrue(CanvasSelection().isEmpty())
        assertFalse(CanvasSelection().isNotEmpty())
        val full = CanvasSelection(
            strokes = listOf(Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(0f, 0f)))),
            texts = listOf(TextBox(x = 0f, y = 0f, text = "A"), TextBox(x = 0f, y = 0f, text = "B")),
            images = listOf(PageImage(x = 0f, y = 0f, width = 50f, height = 50f))
        )
        assertTrue(full.isNotEmpty())
        assertEquals(4, full.size)
        assertEquals(2, CanvasSelection(texts = full.texts).size)
    }
}
