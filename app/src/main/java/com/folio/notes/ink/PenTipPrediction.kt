package com.folio.notes.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.folio.notes.ink.pen.StrokePrediction
import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke

/**
 * The throwaway tail drawn past a live pen stroke's tip so ink seems to keep up with the pen. The
 * platform's [MotionEventPredictor] supplies the path when it can; otherwise a simple two-sample guess
 * ([StrokePrediction.predict]) fills in. Nothing drawn here ever enters the stroke.
 */
internal class PenTipPrediction {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val guess = FloatArray(2)
    /** The platform/Kalman model of where the stylus is heading; created with the first pen stroke. */
    private var predictor: MotionEventPredictor? = null
    /** Predicted pen path as page-unit x/y pairs: first the drawn tip, then up to a few frames ahead. */
    private val tail = FloatArray(2 * POINTS)
    private val raw = FloatArray(2 * POINTS)
    private var count = 0
    /** True while the motion predictor has nothing to offer, so the simple two-sample guess keeps the tip responsive. */
    var idle = true
        private set

    /** Feeds the stylus stream to the motion predictor. Creating it lazily keeps finger-only use free of it. */
    fun record(view: View, event: MotionEvent) {
        val model = predictor ?: MotionEventPredictor.newInstance(view).also { predictor = it }
        model.record(event)
    }

    fun clear() { count = 0; idle = true }

    /**
     * After a move is applied, asks the predictor where the pen goes next and shifts that path to start at
     * [tip], the point actually drawn (the pen filter can hold the drawn tip a pixel behind the real pen).
     * [index] is the pen's pointer in [event]; the view's camera maps screen pixels to page units.
     */
    fun refresh(event: MotionEvent, index: Int, tip: InkPoint, originX: Float, originY: Float, scale: Float) {
        val ahead = predictor?.predict()
        if (ahead == null) { clear(); return }
        idle = false
        val at = ahead.findPointerIndex(ahead.getPointerId(0)).coerceAtLeast(0)
        var n = 0
        for (h in 0 until ahead.historySize) {
            if (n >= POINTS) break
            raw[2 * n] = (ahead.getHistoricalX(at, h) - originX) / scale
            raw[2 * n + 1] = (ahead.getHistoricalY(at, h) - originY) / scale
            n++
        }
        if (n < POINTS) {
            raw[2 * n] = (ahead.getX(at) - originX) / scale
            raw[2 * n + 1] = (ahead.getY(at) - originY) / scale
            n++
        }
        val rawX = (event.getX(index) - originX) / scale
        val rawY = (event.getY(index) - originY) / scale
        count = StrokePrediction.clip(raw, n, tip.x, tip.y, tip.x - rawX, tip.y - rawY,
            scale, StrokePrediction.MAX_TAIL_PX, tail)
    }

    /** Draws the predicted tail for [stroke] past its tip; false when there is none and the caller may fall back. */
    fun drawTail(canvas: Canvas, stroke: Stroke): Boolean {
        if (count < 2) return false
        styleFor(stroke)
        for (i in 1 until count) {
            canvas.drawLine(tail[2 * i - 2], tail[2 * i - 1], tail[2 * i], tail[2 * i + 1], paint)
        }
        return true
    }

    /** Draws the two-sample guess past [stroke]'s tip at the view's [scale]; false when the stroke is too short to guess. */
    fun drawGuess(canvas: Canvas, stroke: Stroke, scale: Float): Boolean {
        if (!StrokePrediction.predict(stroke.points, scale, guess)) return false
        val tip = styleFor(stroke)
        canvas.drawLine(tip.x, tip.y, guess[0], guess[1], paint)
        return true
    }

    private fun styleFor(stroke: Stroke): InkPoint {
        val tip = stroke.points.last()
        paint.color = stroke.color
        paint.alpha = (Color.alpha(stroke.color) * stroke.opacity).toInt().coerceIn(0, 255)
        paint.strokeWidth = stroke.width * InkRenderer.penPressureScale(tip.pressure)
        return tip
    }

    private companion object {
        /** Most predicted points kept; the platform model rarely returns more than a handful. */
        const val POINTS = 24
    }
}
