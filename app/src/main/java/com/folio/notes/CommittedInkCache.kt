package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil

/** First stroke needing paint; a changed prefix requires clearing and replaying the layer. */
internal fun appendedInkStart(previous: List<Stroke>, current: List<Stroke>): Int {
    if (previous === current) return current.size
    if (current.size < previous.size) return 0
    for (i in previous.indices) if (previous[i] !== current[i]) return 0
    return previous.size
}

/**
 * Screen-resolution, transparent ink for the current viewport. Stable writing frames cost one
 * bitmap draw, independent of page density; a commit paints only its appended strokes. Paper,
 * pictures and text stay outside this layer so their ordering and independent edits are preserved.
 * The viewport key also supports negative coordinates on infinite canvases.
 */
internal class CommittedInkCache(private val maxPixels: Long = 8_000_000L) {
    private var bitmap: Bitmap? = null
    private var strokes: List<Stroke> = emptyList()
    private val viewport = Rect()
    private val clip = Rect()
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 0f

    fun clear() {
        // Do not recycle: the hardware renderer may still hold the previous frame's bitmap.
        bitmap = null
        strokes = emptyList()
        scale = 0f
    }

    /** Reproject existing pixels during navigation, without rasterizing or allocating. */
    fun drawSnapshot(canvas: Canvas, current: List<Stroke>, required: Rect): Boolean {
        val layer = bitmap ?: return false
        if (strokes !== current || !viewport.contains(required)) return false
        canvas.drawBitmap(layer, null, destination, paint)
        return true
    }

    fun draw(
        canvas: Canvas,
        current: List<Stroke>,
        pixelsPerUnit: Float,
        boundsOf: (Stroke) -> FloatArray,
        renderOf: (Stroke) -> InkRenderer.RenderedStroke,
        rasterViewport: Rect? = null,
        /** When true, pen strokes draw as one uniform path (preview); settled frames use full taper. */
        fastPreview: Boolean = false
    ) {
        if (rasterViewport != null) clip.set(rasterViewport) else canvas.getClipBounds(clip)
        if (clip.isEmpty || current.isEmpty()) {
            clear()
            return
        }
        val width = ceil(clip.width() * pixelsPerUnit.toDouble()).toInt()
        val height = ceil(clip.height() * pixelsPerUnit.toDouble()).toInt()
        // Bound memory per visible page (32 MB). Oversized surfaces keep full vector quality.
        if (!pixelsPerUnit.isFinite() || pixelsPerUnit <= 0f || width <= 0 || height <= 0 ||
            width.toLong() * height > maxPixels) {
            clear()
            drawRange(canvas, current, 0, clip, boundsOf, renderOf, fastPreview)
            return
        }
        var layer = bitmap
        val viewportChanged = layer == null || viewport != clip || scale != pixelsPerUnit
        if (layer == null || layer.width != width || layer.height != height) {
            layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = layer
        }
        val start = if (viewportChanged) 0 else appendedInkStart(strokes, current)
        if (viewportChanged || strokes !== current) {
            if (start == 0) layer.eraseColor(Color.TRANSPARENT)
            val recording = Canvas(layer)
            recording.scale(pixelsPerUnit, pixelsPerUnit)
            recording.translate(-clip.left.toFloat(), -clip.top.toFloat())
            drawRange(recording, current, start, clip, boundsOf, renderOf, fastPreview)
            viewport.set(clip)
            scale = pixelsPerUnit
            strokes = current
        }
        destination.set(clip.left.toFloat(), clip.top.toFloat(),
            clip.left + width / pixelsPerUnit, clip.top + height / pixelsPerUnit)
        canvas.drawBitmap(layer, null, destination, paint)
    }

    private fun drawRange(
        canvas: Canvas, strokes: List<Stroke>, start: Int, clip: Rect,
        boundsOf: (Stroke) -> FloatArray, renderOf: (Stroke) -> InkRenderer.RenderedStroke,
        fastPreview: Boolean = false
    ) {
        for (i in start until strokes.size) {
            val stroke = strokes[i]
            if (InkRenderer.boundsVisible(boundsOf(stroke), stroke.width, clip)) {
                val rendered = renderOf(stroke)
                if (fastPreview) InkRenderer.drawPreview(canvas, stroke, rendered)
                else InkRenderer.drawRendered(canvas, stroke, rendered)
            }
        }
    }
}
