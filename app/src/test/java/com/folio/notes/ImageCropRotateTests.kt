package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class ImageCropRotateTests {
    private val upright = PageImage(id = "img", x = 100f, y = 100f, width = 200f, height = 100f)

    @Test fun rotationNormalizesToRightAngles() {
        assertEquals(0, PageImage.normalizeRotation(0))
        assertEquals(90, PageImage.normalizeRotation(90))
        assertEquals(180, PageImage.normalizeRotation(180))
        assertEquals(270, PageImage.normalizeRotation(270))
        assertEquals(0, PageImage.normalizeRotation(360))
        assertEquals(90, PageImage.normalizeRotation(-270))
        assertEquals(0, PageImage.normalizeRotation(44))
        assertEquals(90, PageImage.normalizeRotation(80))
        assertEquals(0, PageImage.normalizeRotation(720 + 350))
    }

    @Test fun clockwiseTurnSwapsFrameAboutItsCentre() {
        val turned = upright.rotatedClockwise()
        assertEquals(90, turned.rotation)
        assertEquals(100f, turned.width, .001f)
        assertEquals(200f, turned.height, .001f)
        // Centre (200, 150) is preserved.
        assertEquals(150f, turned.x, .001f)
        assertEquals(50f, turned.y, .001f)
        assertTrue(turned.isSideways())
        assertFalse(upright.isSideways())
    }

    @Test fun counterClockwiseTurnSwapsFrameAboutItsCentre() {
        val turned = upright.rotatedCounterClockwise()
        assertEquals(270, turned.rotation)
        assertEquals(100f, turned.width, .001f)
        assertEquals(200f, turned.height, .001f)
        assertEquals(150f, turned.x, .001f)
        assertEquals(50f, turned.y, .001f)
    }

    @Test fun fourClockwiseTurnsReturnExactlyHome() {
        val round = (1..4).fold(upright) { acc, _ -> acc.rotatedClockwise() }
        assertEquals(0, round.normalizedRotation())
        assertEquals(upright.x, round.x, .001f)
        assertEquals(upright.y, round.y, .001f)
        assertEquals(upright.width, round.width, .001f)
        assertEquals(upright.height, round.height, .001f)
    }

    @Test fun clockwiseThenCounterClockwiseIsIdentity() {
        val round = upright.rotatedClockwise().rotatedCounterClockwise()
        assertEquals(upright, round)
    }

    @Test fun cropValidationRejectsDegenerateRectangles() {
        assertTrue(PageImage.isValidCrop(0f, 0f, 1f, 1f))
        assertTrue(PageImage.isValidCrop(0.1f, 0.1f, 0.9f, 0.9f))
        assertFalse(PageImage.isValidCrop(0.5f, 0.5f, 0.52f, 0.9f))
        assertFalse(PageImage.isValidCrop(0f, 0f, 1f, 0.01f))
        assertFalse(PageImage.isValidCrop(-0.1f, 0f, 1f, 1f))
        assertFalse(PageImage.isValidCrop(0f, 0f, 1.1f, 1f))
        assertFalse(PageImage.isValidCrop(0.6f, 0f, 0.4f, 1f))
        assertFalse(PageImage.isValidCrop(Float.NaN, 0f, 1f, 1f))
    }

    @Test fun uprightCropRescalesFrameAboutItsCentre() {
        // Keep the middle half horizontally: width halves, height unchanged, centre kept.
        val cropped = upright.withCrop(0.25f, 0f, 0.75f, 1f)
        assertEquals(100f, cropped.width, .001f)
        assertEquals(100f, cropped.height, .001f)
        assertEquals(150f, cropped.x, .001f)
        assertEquals(100f, cropped.y, .001f)
        assertTrue(cropped.isCropped())
        assertEquals(0.5f, cropped.cropWidth(), .001f)
    }

    @Test fun sidewaysCropSwapsShareRolesSoAspectSurvives() {
        val sideways = upright.rotatedClockwise()
        // Full-height, half-width in source terms; sideways frame scales the other way.
        val cropped = sideways.withCrop(0f, 0.25f, 1f, 0.75f)
        // Source half-height maps onto frame width for a sideways picture.
        assertEquals(50f, cropped.width, .001f)
        assertEquals(200f, cropped.height, .001f)
    }

    @Test fun invalidCropLeavesPictureUntouched() {
        assertEquals(upright, upright.withCrop(0.5f, 0.5f, 0.51f, 0.9f))
        assertEquals(upright, upright.withCrop(-1f, 0f, 1f, 1f))
    }

    @Test fun resetCropGrowsFrameBackAboutItsCentre() {
        val cropped = upright.withCrop(0.25f, 0.25f, 0.75f, 0.75f)
        assertTrue(cropped.isCropped())
        val reset = cropped.withResetCrop()
        assertFalse(reset.isCropped())
        assertEquals(upright.width, reset.width, .01f)
        assertEquals(upright.height, reset.height, .01f)
        // Centre preserved through crop and reset.
        assertEquals(200f, reset.x + reset.width / 2f, .01f)
        assertEquals(150f, reset.y + reset.height / 2f, .01f)
    }

    @Test fun transformsExposeClampedSourceAndDrawRects() {
        val fractions = ImageTransforms.sourceFractions(upright)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), fractions, .001f)
        val pixels = ImageTransforms.sourcePixels(800, 600, upright.withCrop(0.25f, 0.25f, 0.75f, 0.75f))
        assertEquals(200f, pixels[0], .001f)
        assertEquals(150f, pixels[1], .001f)
        assertEquals(600f, pixels[2], .001f)
        assertEquals(450f, pixels[3], .001f)
        // Upright draws into its own box.
        assertArrayEquals(
            floatArrayOf(100f, 100f, 300f, 200f),
            ImageTransforms.rotatedDrawRect(upright), .001f
        )
        // Sideways draws into the transposed box about the same centre.
        val sideways = upright.rotatedClockwise()
        val transposed = ImageTransforms.rotatedDrawRect(sideways)
        assertEquals(200f, (transposed[0] + transposed[2]) / 2f, .001f)
        assertEquals(150f, (transposed[1] + transposed[3]) / 2f, .001f)
        assertEquals(sideways.height, transposed[2] - transposed[0], .001f)
        assertEquals(sideways.width, transposed[3] - transposed[1], .001f)
    }

    @Test fun rotationAndCropSurviveSaveAndReload() {
        val image = upright.rotatedClockwise().withCrop(0.1f, 0.1f, 0.9f, 0.8f)
        val restored = InkCodec.decodeImages(InkCodec.encodeImages(listOf(image))).single()
        assertEquals(image, restored)
        val note = Notebook(title = "Edited", pages = listOf(NotePage(images = listOf(image))))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }

    @Test fun imagesSavedBeforeTransformsStillOpenUprightAndFull() {
        val json = InkCodec.encodeImages(listOf(upright))
        json.getJSONObject(0).remove("rotation")
        json.getJSONObject(0).remove("cropLeft")
        val restored = InkCodec.decodeImages(json).single()
        assertEquals(0, restored.normalizedRotation())
        assertFalse(restored.isCropped())
        assertEquals(upright, restored)
    }

    @Test fun corruptCropFallsBackToFullPhoto() {
        val json = InkCodec.encodeImages(listOf(upright))
        json.getJSONObject(0).put("cropLeft", 0.9f)
        json.getJSONObject(0).put("cropRight", 0.91f)
        val restored = InkCodec.decodeImages(json).single()
        assertFalse(restored.isCropped())
    }
}

class TypedTextEditingTests {
    @Test fun widthAndOpacityClampToTheirLimits() {
        val box = TextBox(x = 0f, y = 0f, text = "Hi")
        assertEquals(TextBox.MIN_WIDTH, box.withWidth(1f).width, .001f)
        assertEquals(TextBox.MAX_WIDTH, box.withWidth(99_999f).width, .001f)
        assertEquals(TextBox.MIN_OPACITY, box.withOpacity(0f).opacity, .001f)
        assertEquals(TextBox.MAX_OPACITY, box.withOpacity(5f).opacity, .001f)
    }

    @Test fun opacityAndWidthSurviveSaveAndReload() {
        val box = TextBox(x = 10f, y = 20f, width = 500f, text = "Faded heading", opacity = 0.4f, bold = true)
        assertEquals(listOf(box), InkCodec.decodeTexts(InkCodec.encodeTexts(listOf(box))))
        val note = Notebook(title = "Notes", pages = listOf(NotePage(texts = listOf(box))))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }

    @Test fun textSavedBeforeOpacityStillOpensOpaque() {
        val box = TextBox(x = 5f, y = 5f, text = "Legacy")
        val json = InkCodec.encodeTexts(listOf(box))
        json.getJSONObject(0).remove("opacity")
        val restored = InkCodec.decodeTexts(json).single()
        assertEquals(1f, restored.opacity, .001f)
        assertEquals(box, restored)
    }

    @Test fun movingKeepsWidthAndOpacity() {
        val box = TextBox(x = 10f, y = 20f, width = 500f, text = "Note", opacity = 0.5f)
        val moved = box.moved(15f, -8f)
        assertEquals(25f, moved.x, .001f)
        assertEquals(12f, moved.y, .001f)
        assertEquals(500f, moved.width, .001f)
        assertEquals(0.5f, moved.opacity, .001f)
    }
}
