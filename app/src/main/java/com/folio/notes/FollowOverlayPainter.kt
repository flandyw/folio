package com.folio.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.DisplayMetrics
import android.util.TypedValue

/**
 * Writing-follow and marking guides drawn over a page: response margins, answer areas, the landing
 * baseline, the zoom pane's source window, the edge strip and a boxed mark allocation, plus the
 * shape-assist hint. All take the accent [color]; [unit] is how many canvas units one dp is, so
 * dashes and widths stay screen-sized whatever the zoom.
 */
internal class FollowOverlayPainter {
    private val region = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val landing = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hintBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xED202624.toInt() }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** A canvas response's margins, running down from [top] to [bottom]. */
    fun column(canvas: Canvas, column: WritingLane, top: Float, bottom: Float, unit: Float, color: Int) {
        region.color = color
        region.alpha = 0x80
        region.strokeWidth = unit
        region.pathEffect = DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)
        canvas.drawLine(column.left, top, column.left, bottom, region)
        canvas.drawLine(column.right, top, column.right, bottom, region)
    }

    fun areas(canvas: Canvas, areas: List<WritingLane>, unit: Float, color: Int) {
        region.color = color
        region.alpha = 0xCC
        region.strokeWidth = 1.5f * unit
        region.pathEffect = DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)
        areas.distinct().forEach { r -> canvas.drawRect(r.left, r.top, r.right, r.bottom, region) }
    }

    /** The zoom pane's source frame, with a resize grip in its bottom-right corner. */
    fun window(canvas: Canvas, window: PeekAnchor, unit: Float, color: Int) {
        region.color = color
        region.alpha = 255
        region.strokeWidth = 2f * unit
        region.pathEffect = null
        canvas.drawRect(window.left, window.top, window.right, window.bottom, region)
        val handle = 12f * unit
        canvas.drawLine(window.right - handle, window.bottom, window.right, window.bottom - handle, region)
        canvas.drawLine(window.right - handle * .6f, window.bottom, window.right, window.bottom - handle * .6f, region)
    }

    /** The strip at the leading edge that shows there is room ahead on the line. */
    fun edgeStrip(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, alpha: Int) {
        region.color = color
        region.alpha = alpha
        region.style = Paint.Style.FILL
        canvas.drawRect(left, top, right, bottom, region)
        region.style = Paint.Style.STROKE
    }

    /** A rectangle drawn around a printed mark allocation. */
    fun markRegion(canvas: Canvas, r: WritingLane, unit: Float, color: Int) {
        region.color = color
        region.style = Paint.Style.FILL
        region.alpha = 0x26
        region.pathEffect = null
        canvas.drawRect(r.left, r.top, r.right, r.bottom, region)
        region.style = Paint.Style.STROKE
        region.alpha = 0xFF
        region.strokeWidth = 2f * unit
        canvas.drawRect(r.left, r.top, r.right, r.bottom, region)
    }

    /**
     * Where the next line will begin: a short dashed baseline and a start dot, at a constant
     * on-screen size. [lineLength] caps the baseline for a guide that runs the whole page.
     */
    fun landingGuide(canvas: Canvas, guide: WritingGuide, ltr: Boolean, lineLength: Float, unit: Float, color: Int) {
        val start = if (ltr) guide.left else guide.right
        val length = minOf(guide.right - guide.left, lineLength)
        if (!start.isFinite() || !guide.y.isFinite() || length <= 0f) return
        landing.color = color
        landing.style = Paint.Style.STROKE
        landing.alpha = 0x70
        landing.strokeWidth = 1.5f * unit
        landing.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 4f * unit), 0f)
        canvas.drawLine(start, guide.y, if (ltr) start + length else start - length, guide.y, landing)
        landing.style = Paint.Style.FILL
        landing.pathEffect = null
        landing.alpha = 0xB0
        canvas.drawCircle(start, guide.y, 3f * unit, landing)
    }

    /**
     * Screen-sized shape-assist feedback above the tip at view pixel ([tipX], [tipY]), kept inside
     * [visible]; nothing is drawn when the view is too small to hold it.
     */
    fun shapeHint(canvas: Canvas, tipX: Float, tipY: Float, visible: android.graphics.Rect, metrics: DisplayMetrics) {
        val unit = metrics.density
        hintText.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
        val label = "Move to adjust · lift to keep"
        val font = hintText.fontMetrics
        val h = font.descent - font.ascent + 16f * unit
        val w = hintText.measureText(label) + 24f * unit
        if (visible.width() < w + 16f * unit || visible.height() < h + 16f * unit) return
        val left = (tipX - w / 2f).coerceIn(visible.left + 8f * unit, visible.right - w - 8f * unit)
        val top = (tipY - h - 36f * unit).coerceIn(visible.top + 8f * unit, visible.bottom - h - 8f * unit)
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2f, h / 2f, hintBackground)
        canvas.drawText(label, left + 12f * unit, top + 8f * unit - font.ascent, hintText)
    }
}
