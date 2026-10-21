package com.folio.notes.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.folio.notes.model.InkPoint
import com.folio.notes.model.PageImage
import com.folio.notes.editor.selection.SelectionChrome
import com.folio.notes.model.TextBox

/** Corners of a placed picture, clockwise from top-left, in page units. */
internal fun imageCorners(image: PageImage) = arrayOf(
    image.x to image.y, (image.x + image.width) to image.y,
    (image.x + image.width) to (image.y + image.height), image.x to (image.y + image.height))

/**
 * Paints the lasso loop, selection frame and handles, text-box and picture outlines and the crop
 * frame. [unit] is how many canvas units one dp is, so the chrome stays screen-sized at any zoom.
 */
internal class SelectionChromePainter {
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E2F6FBA; style = Paint.Style.FILL }
    private val lassoEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val lassoHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val lassoGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x802F6FBA.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6FBA.toInt(); style = Paint.Style.STROKE }
    private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000; style = Paint.Style.FILL }
    private val textBoxEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val imageEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val imageHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6FBA.toInt(); style = Paint.Style.FILL }
    private val imageHandleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val frameEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val frameLink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val frameGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val cropScrim = Paint().apply { color = Color.argb(120, 0, 0, 0); style = Paint.Style.FILL }
    private val cropFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }

    /**
     * The loop being drawn: a white-haloed blue line over a light wash, a faint guide back to the
     * start, and a start dot that grows into a ring once the tip is close enough to close the loop.
     */
    fun lasso(canvas: Canvas, loop: List<InkPoint>, unit: Float) {
        val polygon = Path().apply { moveTo(loop.first().x, loop.first().y); loop.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        canvas.drawPath(polygon, lassoFill)
        val open = Path().apply { moveTo(loop.first().x, loop.first().y); loop.drop(1).forEach { lineTo(it.x, it.y) } }
        lassoHalo.strokeWidth = 4f * unit
        lassoEdge.strokeWidth = 1.75f * unit
        lassoEdge.pathEffect = DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)
        canvas.drawPath(open, lassoHalo)
        canvas.drawPath(open, lassoEdge)
        val start = loop.first(); val tip = loop.last()
        lassoGuide.strokeWidth = unit
        lassoGuide.pathEffect = DashPathEffect(floatArrayOf(2f * unit, 4f * unit), 0f)
        canvas.drawLine(tip.x, tip.y, start.x, start.y, lassoGuide)
        val closing = loop.size > 8 && hypot(tip.x - start.x, tip.y - start.y) < 28f * unit
        val r = (if (closing) 7f else 4f) * unit
        handleRing.strokeWidth = 1.75f * unit
        canvas.drawCircle(start.x, start.y, r, handleFill)
        canvas.drawCircle(start.x, start.y, r, handleRing)
    }

    /** A dashed outline around a text box while it is dragged, so its extent is visible. */
    fun textBox(canvas: Canvas, box: TextBox, unit: Float) {
        val margin = 2f * unit
        textBoxEdge.strokeWidth = unit
        textBoxEdge.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        canvas.drawRect(box.x - margin, box.y - margin, box.x + box.width + margin,
            box.y + InkRenderer.textHeight(box) + margin, textBoxEdge)
    }

    /**
     * Dashed frame around the lasso selection [box] (left, top, right, bottom) with direct handles
     * when [handles] is set: a plain dot on the bottom-right corner resizes about the center, a
     * ringed dot with a circular arrow above the top edge rotates about it.
     */
    fun frame(canvas: Canvas, box: FloatArray, unit: Float, handles: Boolean) {
        val radius = SelectionChrome.HANDLE_RADIUS_DP * unit
        frameEdge.strokeWidth = unit
        frameEdge.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        frameLink.strokeWidth = unit
        frameGlyph.strokeWidth = 1.5f * unit
        lassoHalo.strokeWidth = 3.5f * unit
        canvas.drawRect(box[0], box[1], box[2], box[3], lassoHalo)
        canvas.drawRect(box[0], box[1], box[2], box[3], frameEdge)
        if (!handles) return
        val cx = (box[0] + box[2]) / 2f
        val rotateY = box[1] - SelectionChrome.ROTATE_LIFT_DP * unit
        lassoHalo.strokeWidth = 3.5f * unit
        canvas.drawLine(cx, box[1], cx, rotateY + radius, lassoHalo)
        canvas.drawLine(cx, box[1], cx, rotateY + radius, frameLink)
        handleRing.strokeWidth = 1.75f * unit
        // White discs with a blue ring and a soft drop shadow: legible over any ink colour.
        fun handle(hx: Float, hy: Float) {
            canvas.drawCircle(hx, hy + unit, radius + unit, handleShadow)
            canvas.drawCircle(hx, hy, radius, handleFill)
            canvas.drawCircle(hx, hy, radius, handleRing)
        }
        handle(box[2], box[3]); handle(cx, rotateY)
        frameGlyph.color = 0xFF2F6FBA.toInt()
        // Circular arrow: 300° of arc plus a V head at its end (420° == 60°).
        val r = 4f * unit
        canvas.drawArc(RectF(cx - r, rotateY - r, cx + r, rotateY + r), 120f, 300f, false, frameGlyph)
        val end = Math.toRadians(60.0)
        val ex = (cx + r * cos(end)).toFloat()
        val ey = (rotateY + r * sin(end)).toFloat()
        // Unit tangent of clockwise travel at 60°; wings fan the backward
        // direction ±25° into a V, in page units.
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val head = 2.75f * unit
        val spread = Math.toRadians(25.0)
        fun wing(flip: Double): Pair<Float, Float> {
            val a = flip * spread
            val bx = -tx
            val by = -ty
            val wx = bx * cos(a) - by * sin(a)
            val wy = bx * sin(a) + by * cos(a)
            return (ex + head * wx).toFloat() to (ey + head * wy).toFloat()
        }
        val (x1, y1) = wing(1.0)
        val (x2, y2) = wing(-1.0)
        canvas.drawLine(ex, ey, x1, y1, frameGlyph)
        canvas.drawLine(ex, ey, x2, y2, frameGlyph)
    }

    /** A dashed outline with corner handles around the selected picture. */
    fun image(canvas: Canvas, image: PageImage, unit: Float, withHandle: Boolean) {
        val margin = 2f * unit
        imageEdge.strokeWidth = unit
        imageEdge.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        imageHandleEdge.strokeWidth = unit
        canvas.drawRect(image.x - margin, image.y - margin,
            image.x + image.width + margin, image.y + image.height + margin, imageEdge)
        if (!withHandle) return
        val r = SelectionChrome.HANDLE_RADIUS_DP * unit
        imageCorners(image).forEach { (cx, cy) ->
            canvas.drawCircle(cx, cy, r, imageHandle)
            canvas.drawCircle(cx, cy, r, imageHandleEdge)
        }
    }

    /** The crop frame over [image], [inset] being its left/top/right/bottom shares of the displayed picture. */
    fun crop(canvas: Canvas, image: PageImage, inset: FloatArray, unit: Float) {
        val l = image.x + inset[0] * image.width; val r = image.x + (1f - inset[2]) * image.width
        val t = image.y + inset[1] * image.height; val b = image.y + (1f - inset[3]) * image.height
        val x2 = image.x + image.width; val y2 = image.y + image.height
        // Dim only what lies inside the picture; a frame dragged outwards (restoring the photo) extends past it.
        val ct = t.coerceAtLeast(image.y); val cb = b.coerceAtMost(y2)
        canvas.drawRect(image.x, image.y, x2, ct, cropScrim)
        canvas.drawRect(image.x, cb, x2, y2, cropScrim)
        canvas.drawRect(image.x, ct, l.coerceAtLeast(image.x), cb, cropScrim)
        canvas.drawRect(r.coerceAtMost(x2), ct, x2, cb, cropScrim)
        cropFrame.strokeWidth = 1.5f * unit
        canvas.drawRect(l, t, r, b, cropFrame)
        val mx = (l + r) / 2f; val my = (t + b) / 2f
        val radius = SelectionChrome.HANDLE_RADIUS_DP * unit
        imageHandleEdge.strokeWidth = unit
        listOf(l to t, r to t, l to b, r to b, mx to t, mx to b, l to my, r to my).forEach { (cx, cy) ->
            canvas.drawCircle(cx, cy, radius, imageHandle)
            canvas.drawCircle(cx, cy, radius, imageHandleEdge)
        }
    }
}
