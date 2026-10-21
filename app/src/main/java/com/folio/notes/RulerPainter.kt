package com.folio.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * A translucent straightedge with millimetre and centimetre ticks along both edges (A4-sized
 * pages are in points, 72 to the inch) and its angle in the middle. Ticks thin out when zoomed
 * far enough out that they would crowd. [unit] is how many canvas units one dp is; [half] and
 * [halfLength] are the band's half thickness and half length in page units.
 */
internal class RulerPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = EDGE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = EDGE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = EDGE }

    fun draw(canvas: Canvas, line: RulerLine, unit: Float, half: Float, halfLength: Float) {
        canvas.save()
        canvas.translate(line.cx, line.cy)
        canvas.rotate(line.degrees)
        canvas.drawRect(-halfLength, -half, halfLength, half, fill)
        edge.strokeWidth = 1.5f * unit
        canvas.drawLine(-halfLength, -half, halfLength, -half, edge)
        canvas.drawLine(-halfLength, half, halfLength, half, edge)
        val mm = 72f / 25.4f
        // Smallest tick that stays at least 4 dp apart on screen.
        val step = listOf(1, 5, 10, 50).first { it * mm >= 4f * unit || it == 50 }
        val count = (halfLength / (step * mm)).toInt()
        for (k in -count..count) {
            val mark = k * step
            val x = mark * mm
            val long = mark % 10 == 0; val mid = mark % 5 == 0
            val length = (if (long) 12f else if (mid) 8f else 5f) * unit
            tick.strokeWidth = (if (long) 1.25f else 0.9f) * unit
            canvas.drawLine(x, -half, x, -half + length, tick)
            canvas.drawLine(x, half, x, half - length, tick)
        }
        // The angle reads upright whichever way the ruler has been turned.
        val turned = RulerLine.normalize(line.degrees)
        if (turned > 90f || turned < -90f) canvas.rotate(180f)
        label.textSize = 13f * unit
        canvas.drawText("${line.readout()}°", 0f, label.textSize * 0.35f, label)
        canvas.restore()
    }

    private companion object {
        val FILL = 0xB8F3F6FA.toInt()
        val EDGE = 0xFF41506A.toInt()
    }
}
