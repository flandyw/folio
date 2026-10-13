package com.folio.notes

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a page's eraser looks like right now, in screen pixels: the ring's radius (already scaled by
 * pen pressure) and whether it is cutting or just hovering. A page reports it while the pen is over
 * it; the editor draws the ring at the pen's window position, so it is not clipped to the page.
 */
internal data class EraserBrush(val radiusPx: Float, val whole: Boolean, val cutting: Boolean)

/** The eraser as the editor knows it without a page's help: used where the pen is over no page. */
internal class EraserPreview(val radius: Float, val pageWidth: Float, val whole: Boolean)

/** Where a pen tip is in the window, whether it is hovering or touching. */
internal class PenAt(val x: Float, val y: Float, val contact: Boolean)

/**
 * The pen's position across the whole window, fed from the activity before any view or Compose
 * node sees the event, so a ring can follow the pen over page gaps, margins and notes hanging off
 * a page. Pen-only: a finger or a palm never moves it.
 */
internal class PenPointer {
    var at by mutableStateOf<PenAt?>(null)
        private set
    private val chrome = HashSet<Any>()
    /** True while the pen is over a toolbar, menu or other control that guards its own touches. */
    var overChrome by mutableStateOf(false)
        private set

    fun observe(event: MotionEvent) {
        val index = (0 until event.pointerCount).firstOrNull { isStylusPointer(event, it) } ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_EXIT -> at = null
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> at = PenAt(event.getX(index), event.getY(index), false)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> at = PenAt(event.getX(index), event.getY(index), true)
            // A lifted pen is hovering again until Android says otherwise.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> at = PenAt(event.getX(index), event.getY(index), false)
        }
    }

    fun enterChrome(owner: Any) { if (chrome.add(owner)) overChrome = true }
    fun leaveChrome(owner: Any) { if (chrome.remove(owner)) overChrome = chrome.isNotEmpty() }
}

internal val LocalPenPointer = staticCompositionLocalOf { PenPointer() }

/**
 * Paints the eraser ring. Rubber is a solid ring with a centre dot; whole-stroke is dashed with no
 * dot, since it removes what the ring touches rather than the area inside it. The fill deepens once
 * the tip is cutting so hovering and erasing look different. [unit] is how many
 * canvas units one dp is, so the ring's chrome stays screen-sized on any canvas.
 */
internal class EraserRingPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // A white halo under a dark neutral edge reads on paper, dark PDFs and ink alike.
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD92B3440.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); style = Paint.Style.STROKE }
    private val dotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD92B3440.toInt(); style = Paint.Style.FILL }

    fun draw(canvas: Canvas, x: Float, y: Float, radius: Float, unit: Float, cutting: Boolean, whole: Boolean) {
        fill.color = if (cutting) 0x3A2B3440 else 0x142B3440
        canvas.drawCircle(x, y, radius, fill)
        halo.strokeWidth = 3.5f * unit
        edge.strokeWidth = (if (cutting) 1.75f else 1.25f) * unit
        edge.pathEffect = if (whole) DashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f) else null
        canvas.drawCircle(x, y, radius, halo)
        canvas.drawCircle(x, y, radius, edge)
        if (!whole) {
            canvas.drawCircle(x, y, 2.5f * unit, dotHalo)
            canvas.drawCircle(x, y, 1.5f * unit, dot)
        }
    }
}
