package com.folio.notes.math

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.min

/**
 * One rendered formula standing in for its source inside a text box or sticky note's layout. It sits on
 * the text baseline (a display formula takes its own line, centred), shrinks to the line if it is wider
 * than the box, and fades with the box's opacity like the words around it.
 */
internal class FormulaSpan(
    private val picture: TextMathBitmaps.Snippet,
    private val display: Boolean,
    private val lineWidth: Float,
    private val alpha: Int,
) : ReplacementSpan() {
    private val fit = if (picture.width > lineWidth && lineWidth > 1f) lineWidth / picture.width else 1f
    private val w get() = picture.width * fit
    private val ascent get() = picture.baseline * fit
    private val descent get() = (picture.height - picture.baseline) * fit
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            // Only ever grow the line: a small formula keeps the text's own line height.
            fm.ascent = min(fm.ascent, -ceil(ascent).toInt()); fm.top = min(fm.top, fm.ascent)
            fm.descent = maxOf(fm.descent, ceil(descent).toInt()); fm.bottom = maxOf(fm.bottom, fm.descent)
        }
        return if (display) ceil(lineWidth).toInt() else ceil(w).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val left = if (display) x + (lineWidth - w) / 2f else x
        dst.set(left, y - ascent, left + w, y + descent)
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(picture.bitmap, null, dst, bitmapPaint)
    }
}
