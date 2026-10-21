package com.folio.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The laser tool's presenter trail, in page units. Each stroke keeps its samples' times; the
 * tail of a long stroke fades while it is drawn and a whole stroke fades shortly after the pen
 * lifts, so circling and underlining stay readable without the screen filling up. Nothing reaches
 * the page, the journal or undo.
 */
internal class LaserTrail {
    private class Sample(val x: Float, val y: Float, val time: Long)
    private class Trail(val samples: ArrayList<Sample> = ArrayList(), var liftedAt: Long = -1L)
    private val strokes = ArrayList<Trail>()
    /** True while the pen is down drawing a trail stroke. */
    var down = false
        private set
    /** Where a hovering pen points while the laser is in hand, or null. */
    var hover: InkPoint? = null
    /** The stroke a spanning document gesture is feeding, or null. */
    private var documentStroke: Trail? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    val visible get() = strokes.isNotEmpty() || hover != null

    /** Drops the trail and the pointer at once, as when another tool is picked. */
    fun clear() { strokes.clear(); down = false; hover = null }

    /** Starts a presenter stroke at [start]. */
    fun begin(start: InkPoint) {
        down = true
        hover = null
        // Older strokes stay to fade on their own; only the very oldest go when there are many.
        while (strokes.size >= MAX_STROKES) strokes.removeAt(0)
        strokes += Trail()
        add(start)
    }

    fun add(p: InkPoint) {
        strokes.lastOrNull()?.samples?.add(Sample(p.x, p.y, SystemClock.uptimeMillis()))
    }

    /** Ends the stroke being drawn; it lingers, then fades out. */
    fun lift() {
        down = false
        strokes.lastOrNull()?.let { if (it.liftedAt < 0) it.liftedAt = SystemClock.uptimeMillis() }
    }

    /** Extends the trail from a document gesture that crosses pages. */
    fun addDocument(points: List<InkPoint>) {
        val stroke = documentStroke ?: Trail().also {
            while (strokes.size >= MAX_STROKES) strokes.removeAt(0)
            documentStroke = it; strokes += it
        }
        val now = SystemClock.uptimeMillis()
        points.forEach { stroke.samples += Sample(it.x, it.y, now) }
    }

    fun finishDocument() {
        documentStroke?.liftedAt = SystemClock.uptimeMillis()
        documentStroke = null
    }

    /** How strongly a sample still shows: its tail fade while drawing times its stroke's fade after lift. */
    private fun alpha(sample: Sample, stroke: Trail, now: Long): Float {
        val age = now - sample.time
        val tail = if (age <= TAIL_MS) 1f else 1f - (age - TAIL_MS).toFloat() / FADE_MS
        val after = if (stroke.liftedAt < 0) 1f else 1f - (now - stroke.liftedAt - LINGER_MS).toFloat() / FADE_MS
        return min(tail, after).coerceIn(0f, 1f)
    }

    /**
     * A glowing trail: a wide soft halo, the coloured line and a bright core. Runs of samples with
     * the same (quantized) opacity share one path, so overlapping round joins never darken it.
     * [showHead] draws the pointer dot. Returns true while something is still fading, so the
     * caller keeps animating.
     */
    fun draw(canvas: Canvas, unit: Float, inkColor: Int, showHead: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val horizon = TAIL_MS + FADE_MS
        strokes.removeAll { stroke ->
            // Samples that have fully faded go, so a long stroke never costs more than its visible tail.
            var drop = 0
            while (drop < stroke.samples.size - 1 && now - stroke.samples[drop].time > horizon) drop++
            if (drop > 0) stroke.samples.subList(0, drop).clear()
            stroke.samples.isEmpty() || (stroke.liftedAt >= 0 && now - stroke.liftedAt >= LINGER_MS + FADE_MS) ||
                (stroke.liftedAt >= 0 && stroke.samples.size == 1 && now - stroke.samples[0].time > horizon)
        }
        val width = WIDTH * unit
        val colour = inkColor or 0xFF000000.toInt()
        var fading = false
        fun stroke(alpha: Float) {
            if (alpha <= 0f) return
            paint.color = colour; paint.alpha = (alpha * 0.22f * 255).toInt(); paint.strokeWidth = width * 3.2f
            canvas.drawPath(path, paint)
            paint.alpha = (alpha * 255).toInt(); paint.strokeWidth = width
            canvas.drawPath(path, paint)
            paint.color = Color.WHITE; paint.alpha = (alpha * 0.85f * 255).toInt(); paint.strokeWidth = width * 0.35f
            canvas.drawPath(path, paint)
        }
        for (trail in strokes) {
            val samples = trail.samples
            if (trail.liftedAt >= 0 || now - samples[0].time > TAIL_MS) fading = true
            if (samples.size == 1) {
                val a = alpha(samples[0], trail, now)
                dotPaint.color = colour; dotPaint.alpha = (a * 255).toInt()
                canvas.drawCircle(samples[0].x, samples[0].y, width * 0.5f, dotPaint)
                continue
            }
            path.rewind()
            var bucket = -1
            for (i in 1 until samples.size) {
                val from = samples[i - 1]; val to = samples[i]
                val a = min(alpha(from, trail, now), alpha(to, trail, now))
                val b = (a * ALPHA_STEPS).roundToInt()
                if (b != bucket) {
                    if (bucket > 0) stroke(bucket.toFloat() / ALPHA_STEPS)
                    path.rewind(); path.moveTo(from.x, from.y); bucket = b
                }
                path.lineTo(to.x, to.y)
            }
            if (bucket > 0) stroke(bucket.toFloat() / ALPHA_STEPS)
        }
        // The pointer itself: the live tip while drawing, or where a hovering pen points.
        val head = if (down) strokes.lastOrNull()?.samples?.lastOrNull()?.let { InkPoint(it.x, it.y) } else hover
        if (head != null && showHead) {
            dotPaint.color = colour; dotPaint.alpha = 0x40
            canvas.drawCircle(head.x, head.y, width * 2.4f, dotPaint)
            dotPaint.alpha = 0xFF
            canvas.drawCircle(head.x, head.y, width * 1.1f, dotPaint)
            dotPaint.color = Color.WHITE; dotPaint.alpha = 0xD0
            canvas.drawCircle(head.x, head.y, width * 0.45f, dotPaint)
        }
        return fading
    }

    private companion object {
        /**
         * Trail timing: a stroke's tail starts fading once it is [TAIL_MS] old, and a lifted
         * stroke stays for [LINGER_MS] before fading; each fade takes [FADE_MS].
         */
        const val TAIL_MS = 1400L
        const val LINGER_MS = 450L
        const val FADE_MS = 450L
        const val MAX_STROKES = 24
        const val ALPHA_STEPS = 12
        /** Trail width in dp-equivalent units. */
        const val WIDTH = 4f
    }
}
