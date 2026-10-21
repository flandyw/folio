package com.folio.notes.ink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.folio.notes.model.InkPoint
import com.folio.notes.model.PageImage

/**
 * The inline crop frame over a placed picture. [inset] holds the frame's left, top, right and bottom
 * edges as shares of the displayed picture; they may go negative to restore photo that an earlier
 * crop trimmed. Edge flags are 1 left, 2 top, 4 right, 8 bottom.
 */
internal object ImageCropFrame {
    fun contains(image: PageImage, inset: FloatArray, at: InkPoint): Boolean =
        at.x in (image.x + inset[0] * image.width)..(image.x + (1f - inset[2]) * image.width) &&
            at.y in (image.y + inset[1] * image.height)..(image.y + (1f - inset[3]) * image.height)

    /** The frame edges within [reach] of [at]; the nearer of two opposite edges wins. */
    fun edgesAt(image: PageImage, inset: FloatArray, at: InkPoint, reach: Float): Int {
        val l = image.x + inset[0] * image.width; val r = image.x + (1f - inset[2]) * image.width
        val t = image.y + inset[1] * image.height; val b = image.y + (1f - inset[3]) * image.height
        val inY = at.y > t - reach && at.y < b + reach
        val inX = at.x > l - reach && at.x < r + reach
        var edges = 0
        if (inY && abs(at.x - l) <= reach) edges = edges or 1
        if (inY && abs(at.x - r) <= reach && (edges and 1 == 0 || abs(at.x - r) < abs(at.x - l))) edges = (edges and 1.inv()) or 4
        if (inX && abs(at.y - t) <= reach) edges = edges or 2
        if (inX && abs(at.y - b) <= reach && (edges and 2 == 0 || abs(at.y - b) < abs(at.y - t))) edges = (edges and 2.inv()) or 8
        return edges
    }

    /** Moves the grabbed [edges] of [inset] to [at], keeping a minimum frame and the photo's own edges. */
    fun drag(image: PageImage, inset: FloatArray, edges: Int, at: InkPoint) {
        val min = minShare(image)
        fun clamp(value: Float, edge: Int, opposite: Float) = value.coerceIn(-room(image, edge), (1f - opposite - min).coerceAtLeast(-room(image, edge)))
        if (edges and 1 != 0) inset[0] = clamp((at.x - image.x) / image.width, 0, inset[2])
        if (edges and 4 != 0) inset[2] = clamp((image.x + image.width - at.x) / image.width, 2, inset[0])
        if (edges and 2 != 0) inset[1] = clamp((at.y - image.y) / image.height, 1, inset[3])
        if (edges and 8 != 0) inset[3] = clamp((image.y + image.height - at.y) / image.height, 3, inset[1])
    }

    /** The picture trimmed to the frame, or null when the frame changes nothing. */
    fun applied(image: PageImage, inset: FloatArray): PageImage? {
        val cw = image.cropWidth(); val ch = image.cropHeight()
        var l = image.cropLeft; var t = image.cropTop; var r = image.cropRight; var b = image.cropBottom
        val (il, it, ir, ib) = inset.toList()
        // Displayed edges map to photo edges according to the picture's rotation.
        when (image.normalizedRotation()) {
            0 -> { l += il * cw; r -= ir * cw; t += it * ch; b -= ib * ch }
            90 -> { l += it * cw; t += ir * ch; r -= ib * cw; b -= il * ch }
            180 -> { l += ir * cw; r -= il * cw; t += ib * ch; b -= it * ch }
            else -> { r -= it * cw; b -= ir * ch; l += ib * cw; t += il * ch }
        }
        val cropped = image.withCrop(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        if (cropped == image) return null
        // Keep the kept part where it was instead of shrinking about the centre.
        return cropped.copy(x = image.x + il * image.width, y = image.y + it * image.height)
    }

    private fun minShare(image: PageImage) = max(0.1f, PageImage.MIN_CROP_SPAN / min(image.cropWidth(), image.cropHeight()).coerceAtLeast(0.05f))

    /**
     * How far past the picture's current edge the photo still reaches, as a share of the displayed
     * frame (0 when that edge already shows the photo's own edge). Insets may go negative by this
     * much, so a crop is undone by dragging the same handles back out.
     */
    private fun room(image: PageImage, edge: Int): Float {
        val cw = image.cropWidth().coerceAtLeast(0.01f); val ch = image.cropHeight().coerceAtLeast(0.01f)
        val left = image.cropLeft / cw; val right = (1f - image.cropRight) / cw
        val top = image.cropTop / ch; val bottom = (1f - image.cropBottom) / ch
        // Displayed edges (0 left, 1 top, 2 right, 3 bottom) map to photo edges by rotation.
        return when (image.normalizedRotation()) {
            0 -> floatArrayOf(left, top, right, bottom)
            90 -> floatArrayOf((1f - image.cropBottom) / ch, image.cropLeft / cw, image.cropTop / ch, (1f - image.cropRight) / cw)
            180 -> floatArrayOf(right, bottom, left, top)
            else -> floatArrayOf(image.cropTop / ch, (1f - image.cropRight) / cw, (1f - image.cropBottom) / ch, image.cropLeft / cw)
        }[edge]
    }
}
