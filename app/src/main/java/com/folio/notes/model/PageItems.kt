package com.folio.notes.model

import com.folio.notes.editor.sticky.StickyNotes
import java.util.UUID
import kotlin.math.*

/** Horizontal alignment for typed text boxes, matching Notability's text controls. */
enum class TextAlignMode {
    LEFT, CENTER, RIGHT;
    companion object {
        fun safeValueOf(name: String): TextAlignMode = try { valueOf(name) } catch (_: Exception) { LEFT }
    }
}

/** One layer of a page. Items point at a layer by [id]; the list order is the draw order. */
data class PageLayer(
    val id: Int, val name: String,
    val visible: Boolean = true, val locked: Boolean = false
) {
    companion object { val BASE = PageLayer(0, "Layer 1") }
}

/**
 * Editable typed text on a page, positioned in page units from its top-left corner. The text wraps
 * at [width] and grows downwards, so a box needs no stored height and stays correct after edits.
 */
data class TextBox(
    val id: String = UUID.randomUUID().toString(),
    val x: Float, val y: Float, val width: Float = DEFAULT_WIDTH,
    val text: String = "", val size: Float = 26f,
    val color: Int = 0xFF303431.toInt(),
    val bold: Boolean = false, val italic: Boolean = false,
    val align: TextAlignMode = TextAlignMode.LEFT,
    val underline: Boolean = false,
    /** Faded text for annotations that should sit behind ink; 1 is fully opaque. */
    val opacity: Float = 1f,
    val layer: Int = 0,
    /** A sticky owns its local ink; ordinary text keeps these absent from the codec. */
    val stickyHeight: Float = 0f,
    val stickyInk: List<Stroke> = emptyList(),
    /** Paper colour of a sticky; 0 is the classic yellow. */
    val stickyColor: Int = 0
) {
    val isSticky: Boolean get() = stickyHeight > 0f
    fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    /** Same wording with a new wrap width, clamped to the limits the editor and lasso share. */
    fun withWidth(value: Float) = copy(width = value.coerceIn(if (isSticky) StickyNotes.SMALLEST_DIMENSION else MIN_WIDTH, MAX_WIDTH))
    /** Same wording faded to [value], clamped so text never vanishes entirely. */
    fun withOpacity(value: Float) = copy(opacity = value.coerceIn(MIN_OPACITY, MAX_OPACITY))
    companion object {
        const val DEFAULT_WIDTH = 360f
        const val MIN_WIDTH = 90f
        const val MAX_WIDTH = 1800f
        const val MIN_SIZE = 10f
        const val MAX_SIZE = 120f
        const val MIN_OPACITY = 0.15f
        const val MAX_OPACITY = 1f
        const val DEFAULT_OPACITY = 1f
    }
}

/**
 * A photo or figure placed on a page, stored as an image file beside the notebook with only its
 * placement kept in the page JSON. Coordinates are page units from the page's top-left corner,
 * matching [TextBox] so ink drawn over the picture lines up in exports and thumbnails.
 *
 * The frame itself stays axis-aligned so hit-testing, the resize handle and the lasso keep their
 * simple box math. [rotation] turns the bitmap's content inside that frame in 90° clockwise
 * steps, and the normalized [cropLeft]/[cropTop]/[cropRight]/[cropBottom] source rectangle
 * selects which part of the photo is shown. Both are non-destructive: the file on disk is never
 * rewritten, so every turn or crop undoes cleanly and survives saves, backups and exports.
 */
data class PageImage(
    val id: String = UUID.randomUUID().toString(),
    val x: Float, val y: Float, val width: Float, val height: Float,
    val rotation: Int = 0,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val layer: Int = 0
) {
    fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    /** Clockwise content rotation snapped to 0/90/180/270. */
    fun normalizedRotation(): Int = normalizeRotation(rotation)
    /** True once any crop edge has moved in from the full photo. */
    fun isCropped(): Boolean =
        cropLeft > 0f || cropTop > 0f || cropRight < 1f || cropBottom < 1f
    /** Width of the visible source as a share of the photo, 0..1. */
    fun cropWidth(): Float = (cropRight - cropLeft).coerceIn(0f, 1f)
    /** Height of the visible source as a share of the photo, 0..1. */
    fun cropHeight(): Float = (cropBottom - cropTop).coerceIn(0f, 1f)
    /** True while the content is sideways, so the frame's aspect runs the other way. */
    fun isSideways(): Boolean = normalizedRotation() % 180 != 0
    /** One 90° clockwise turn, swapping the frame so the picture never stretches. */
    fun rotatedClockwise(): PageImage {
        val cx = x + width / 2f
        val cy = y + height / 2f
        val newWidth = height.coerceIn(MIN_SIZE, MAX_SIZE)
        val newHeight = width.coerceIn(MIN_SIZE, MAX_SIZE)
        return copy(
            x = cx - newWidth / 2f,
            y = cy - newHeight / 2f,
            width = newWidth,
            height = newHeight,
            rotation = normalizeRotation(normalizedRotation() + 90)
        )
    }
    /** One 90° counter-clockwise turn, swapping the frame so the picture never stretches. */
    fun rotatedCounterClockwise(): PageImage {
        val cx = x + width / 2f
        val cy = y + height / 2f
        val newWidth = height.coerceIn(MIN_SIZE, MAX_SIZE)
        val newHeight = width.coerceIn(MIN_SIZE, MAX_SIZE)
        return copy(
            x = cx - newWidth / 2f,
            y = cy - newHeight / 2f,
            width = newWidth,
            height = newHeight,
            rotation = normalizeRotation(normalizedRotation() + 270)
        )
    }
    /**
     * Same picture showing only the [left]/[top]/[right]/[bottom] source rectangle. The frame
     * rescales about its centre so the visible photo keeps its aspect instead of stretching:
     * an upright frame scales width by the crop-width share and height by the crop-height share,
     * while a sideways frame swaps those shares because its content runs the other way.
     * Invalid rectangles leave the picture untouched.
     */
    fun withCrop(left: Float, top: Float, right: Float, bottom: Float): PageImage {
        if (!isValidCrop(left, top, right, bottom)) return this
        val oldW = cropWidth().takeIf { it > 0f } ?: 1f
        val oldH = cropHeight().takeIf { it > 0f } ?: 1f
        val newWShare = (right - left) / oldW
        val newHShare = (bottom - top) / oldH
        val (newWidth, newHeight) = if (isSideways())
            (width * newHShare).coerceIn(MIN_SIZE, MAX_SIZE) to
                (height * newWShare).coerceIn(MIN_SIZE, MAX_SIZE)
        else
            (width * newWShare).coerceIn(MIN_SIZE, MAX_SIZE) to
                (height * newHShare).coerceIn(MIN_SIZE, MAX_SIZE)
        val cx = x + width / 2f
        val cy = y + height / 2f
        return copy(
            x = cx - newWidth / 2f,
            y = cy - newHeight / 2f,
            width = newWidth,
            height = newHeight,
            cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom
        )
    }
    /** The full photo again, growing the frame back about its centre. */
    fun withResetCrop(): PageImage = withCrop(0f, 0f, 1f, 1f)
    companion object {
        const val MIN_SIZE = 16f
        const val MAX_SIZE = 2400f
        /** Half-size of the bottom-right resize handle, in page units. */
        const val HANDLE_HALF = 22f
        /** Smallest visible source share per axis, so a crop can never vanish to a line. */
        const val MIN_CROP_SPAN = 0.05f
        /** Clockwise content rotation snapped to 0/90/180/270. */
        fun normalizeRotation(value: Int): Int {
            val wrapped = ((value % 360) + 360) % 360
            return when {
                wrapped < 45 -> 0
                wrapped < 135 -> 90
                wrapped < 225 -> 180
                wrapped < 315 -> 270
                else -> 0
            }
        }
        /** True when the rectangle selects a real, non-degenerate part of the photo. */
        fun isValidCrop(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return false
            if (left < 0f || top < 0f || right > 1f || bottom > 1f) return false
            if (right - left < MIN_CROP_SPAN || bottom - top < MIN_CROP_SPAN) return false
            return true
        }
    }
}

/**
 * Pure crop/rotate math for placed photos, free of Android types so it stays unit-testable.
 * The frame itself never rotates: [rotatedDrawRect] is the axis-aligned rectangle to draw in a
 * canvas already turned about the frame's centre, which keeps the visible photo's aspect instead
 * of stretching it.
 */
object ImageTransforms {
    /** Visible source as fractions of the photo, clamped to 0..1 with a non-empty rectangle. */
    fun sourceFractions(image: PageImage): FloatArray {
        val left = image.cropLeft.coerceIn(0f, 1f)
        val top = image.cropTop.coerceIn(0f, 1f)
        val right = image.cropRight.coerceIn(0f, 1f)
        val bottom = image.cropBottom.coerceIn(0f, 1f)
        if (right - left < PageImage.MIN_CROP_SPAN || bottom - top < PageImage.MIN_CROP_SPAN) {
            return floatArrayOf(0f, 0f, 1f, 1f)
        }
        return floatArrayOf(left, top, right, bottom)
    }

    /** Visible source in bitmap pixels as `[left, top, right, bottom]`, at least one pixel wide. */
    fun sourcePixels(bitmapWidth: Int, bitmapHeight: Int, image: PageImage): FloatArray {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        val fractions = sourceFractions(image)
        val left = (bitmapWidth * fractions[0]).coerceIn(0f, (bitmapWidth - 1).toFloat())
        val top = (bitmapHeight * fractions[1]).coerceIn(0f, (bitmapHeight - 1).toFloat())
        val right = (bitmapWidth * fractions[2]).coerceIn(left + 1f, bitmapWidth.toFloat())
        val bottom = (bitmapHeight * fractions[3]).coerceIn(top + 1f, bitmapHeight.toFloat())
        return floatArrayOf(left, top, right, bottom)
    }

    /**
     * Rectangle to draw inside a canvas already rotated about the frame's centre, as
     * `[left, top, right, bottom]` in page units. Upright and upside-down frames draw into their
     * own box; sideways frames draw into the transposed box so a 90° turn never stretches.
     */
    fun rotatedDrawRect(image: PageImage): FloatArray {
        if (!image.isSideways()) {
            return floatArrayOf(image.x, image.y, image.x + image.width, image.y + image.height)
        }
        val cx = image.x + image.width / 2f
        val cy = image.y + image.height / 2f
        return floatArrayOf(cx - image.height / 2f, cy - image.width / 2f, cx + image.height / 2f, cy + image.width / 2f)
    }
}

/**
 * Everything a lasso loop picked up in one pass: ink plus the typed text boxes and placed
 * pictures it enclosed, so they move, copy, rotate, resize and delete together like one
 * GoodNotes-style selection. Strokes match by value (they carry no id); texts and pictures
 * match by id, which survives moves and restyles.
 */
data class CanvasSelection(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    val images: List<PageImage> = emptyList()
) {
    fun isEmpty(): Boolean = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
    /** Every selected item — ink, text and pictures — as one count for the selection bar. */
    val size: Int get() = strokes.size + texts.size + images.size
}
