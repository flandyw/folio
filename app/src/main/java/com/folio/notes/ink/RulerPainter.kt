package com.folio.notes.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws the straightedge ([RulerLine]) in page units: a translucent band with millimetre ticks on
 * both edges and its angle in the middle. [unit] converts dp-equivalent sizes to page units, so the
 * band, ticks and label keep their on-screen size at every zoom.
 */
internal class RulerPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun draw(canvas: Canvas, line: RulerLine, unit: Float, half: Float, halfLength: Float) {
        canvas.save()
        canvas.translate(line.cx, line.cy)
        canvas.rotate(line.degrees)
        fill.color = FILL
        canvas.drawRect(-halfLength, -half, halfLength, half, fill)
        edgePaint.color = EDGE
        edgePaint.strokeWidth = 1.5f * unit
        canvas.drawLine(-halfLength, -half, halfLength, -half, edgePaint)
        canvas.drawLine(-halfLength, half, halfLength, half, edgePaint)
        val mm = 72f / 25.4f
        // Smallest tick that stays at least 4 dp apart on screen.
        val step = listOf(1, 5, 10, 50).first { it * mm >= 4f * unit || it == 50 }
        val count = (halfLength / (step * mm)).toInt()
        tickPaint.color = EDGE
        for (k in -count..count) {
            val mark = k * step
            val x = mark * mm
            val long = mark % 10 == 0; val mid = mark % 5 == 0
            val length = (if (long) 12f else if (mid) 8f else 5f) * unit
            tickPaint.strokeWidth = (if (long) 1.25f else 0.9f) * unit
            canvas.drawLine(x, -half, x, -half + length, tickPaint)
            canvas.drawLine(x, half, x, half - length, tickPaint)
        }
        // The angle reads upright whichever way the ruler has been turned.
        val turned = RulerLine.normalize(line.degrees)
        if (turned > 90f || turned < -90f) canvas.rotate(180f)
        labelPaint.color = EDGE
        labelPaint.textSize = 13f * unit
        canvas.drawText("${line.readout()}°", 0f, labelPaint.textSize * 0.35f, labelPaint)
        canvas.restore()
    }

    private companion object {
        val FILL = 0xB8F3F6FA.toInt()
        val EDGE = 0xFF41506A.toInt()
    }
}
