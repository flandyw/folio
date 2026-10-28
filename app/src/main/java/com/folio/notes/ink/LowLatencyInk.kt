package com.folio.notes.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import com.folio.notes.model.InkPoint
import kotlin.math.hypot

/**
 * An optional low-latency layer for the pen stroke in progress (Settings › Writing › Pen feel, off by default).
 *
 * The page normally shows a live stroke after a full UI-thread frame. This puts one transparent
 * SurfaceView over the window and draws each new piece of the stroke straight into a front buffer, which
 * is scanned out without waiting for the next frame. The page keeps drawing the same stroke underneath,
 * so if this layer is late, missing or fails, the ink still shows; the layer only brings the leading edge
 * forward and is wiped a couple of frames after pen-up, when the page has drawn the finished stroke.
 *
 * Only opaque pen ink on the page is drawn here. Nothing from this layer is ever stored.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class LowLatencyInk private constructor(private val decor: ViewGroup) {
    /**
     * New window-space points as x, y, diameter triples plus the predicted tail (x, y pairs, drawn at the tip's
     * width), clipped to [clip] (window space). [first] starts a new stroke on the render thread. Immutable.
     */
    private class Chunk(val points: FloatArray, val tail: FloatArray, val color: Int, val clip: RectF, val first: Boolean)

    private val surface = SurfaceView(decor.context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = false; isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    @Volatile private var surfaceReady = false
    private var renderer: CanvasFrontBufferedRenderer<Chunk>? = null
    private var dirty = false
    private var first = true
    /** The last chunk drew a tail, so the next must be sent even without new points, to wipe it. */
    private var hadTail = false
    private var lastPageX = 0f; private var lastPageY = 0f; private var along = 0f
    private val where = IntArray(2)
    private val window = RectF()
    private val wipe = Runnable { wipeNow() }

    /** Render-thread state: every point of the stroke so far (to redraw what an old tail covered) and that tail's box. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawn = FloatArray(3 * 256)
    private var drawnCount = 0
    private var widest = 0f
    private val tailBox = RectF()

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceReady = width > 0 && height > 0 }
        override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
    }

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Chunk> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Chunk) {
            if (param.first) { drawnCount = 0; widest = 0f; tailBox.setEmpty() }
            val before = drawnCount
            val p = param.points
            if (3 * drawnCount + p.size > drawn.size) drawn = drawn.copyOf(maxOf(2 * drawn.size, 3 * drawnCount + p.size))
            p.copyInto(drawn, 3 * drawnCount)
            drawnCount += p.size / 3
            for (i in 2 until p.size step 3) widest = maxOf(widest, p[i])
            paint.color = param.color; fill.color = param.color
            canvas.save()
            canvas.clipRect(param.clip)
            // The last tail was a guess: wipe its box and redraw the real stroke beneath it (as androidx.ink does).
            if (!tailBox.isEmpty) {
                canvas.save()
                canvas.clipRect(tailBox)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawSegments(canvas, 0, before, tailBox)
                canvas.restore()
            }
            drawSegments(canvas, maxOf(before - 1, 0), drawnCount, null)
            tailBox.setEmpty()
            val t = param.tail
            if (t.size >= 4 && drawnCount > 0) {
                val d = drawn[3 * drawnCount - 1]
                paint.strokeWidth = d
                tailBox.set(t[0], t[1], t[0], t[1])
                for (i in 2 until t.size step 2) {
                    canvas.drawLine(t[i - 2], t[i - 1], t[i], t[i + 1], paint)
                    tailBox.union(t[i], t[i + 1])
                }
                tailBox.inset(-(maxOf(d, widest) / 2f + 2f), -(maxOf(d, widest) / 2f + 2f))
            }
            canvas.restore()
        }
        // Nothing is committed: the page draws the finished stroke, and the front buffer is simply wiped.
        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Chunk>) = Unit
    }

    /** Draws stroke points [from] until [to] as round-capped segments, only those touching [box] when given. */
    private fun drawSegments(canvas: Canvas, from: Int, to: Int, box: RectF?) {
        val d = drawn
        if (to - from == 1 && drawnCount == 1) {
            if (box == null || box.contains(d[0], d[1])) canvas.drawCircle(d[0], d[1], d[2] / 2f, fill)
            return
        }
        for (i in from + 1 until to) {
            val a = 3 * (i - 1); val b = 3 * i
            if (box != null) {
                val r = maxOf(d[a + 2], d[b + 2]) / 2f
                if (minOf(d[a], d[b]) - r > box.right || maxOf(d[a], d[b]) + r < box.left ||
                    minOf(d[a + 1], d[b + 1]) - r > box.bottom || maxOf(d[a + 1], d[b + 1]) + r < box.top) continue
            }
            paint.strokeWidth = d[b + 2]
            canvas.drawLine(d[a], d[a + 1], d[b], d[b + 1], paint)
        }
    }

    private fun attach(): Boolean = try {
        surface.holder.addCallback(holderCallback)
        decor.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        renderer = CanvasFrontBufferedRenderer(surface, callback)
        true
    } catch (_: Throwable) { detach(); false }

    private fun detach() {
        surface.removeCallbacks(wipe)
        try { renderer?.release(true) } catch (_: Throwable) { }
        renderer = null
        surface.holder.removeCallback(holderCallback)
        if (surface.parent === decor) decor.removeView(surface)
        surfaceReady = false; dirty = false; first = true
    }

    /** True when strokes can be drawn now; false until the surface exists (the page then simply draws alone). */
    val ready: Boolean get() = surfaceReady && renderer != null

    /** A new stroke: any ink still on screen from the last one is wiped first, then the stroke starts afresh. */
    fun begin() {
        surface.removeCallbacks(wipe)
        if (dirty) wipeNow()
        first = true; hadTail = false
    }

    /**
     * Draws [points] from index [from] on and the predicted [tail] ([tailCount] page-unit x/y pairs starting at the
     * drawn tip), converted from page units to the window with the view's own placement ([originX], [originY],
     * [scale] as in the view) and clipped to [localClip] (view pixels). The start thins like the page's live stroke.
     */
    fun append(view: View, originX: Float, originY: Float, scale: Float, localClip: RectF, color: Int,
        strokeWidth: Float, points: List<InkPoint>, from: Int, tail: FloatArray, tailCount: Int) {
        val target = renderer ?: return
        if (!surfaceReady || (from >= points.size && tailCount < 2 && !first && !hadTail)) return
        view.getLocationInWindow(where)
        val ox = where[0] + originX; val oy = where[1] + originY
        val taper = InkTaper.length()
        val count = (points.size - from).coerceAtLeast(0)
        val out = FloatArray(3 * count)
        var o = 0
        for (i in from until points.size) {
            val p = points[i]
            if (first && i == from) along = 0f else along += hypot(p.x - lastPageX, p.y - lastPageY)
            lastPageX = p.x; lastPageY = p.y
            out[o++] = ox + p.x * scale
            out[o++] = oy + p.y * scale
            out[o++] = strokeWidth * InkRenderer.penPressureScale(p.pressure) * InkTaper.scaleAt(along, taper) * scale
        }
        val n = if (tailCount >= 2) tailCount else 0
        hadTail = n > 0
        val window2 = FloatArray(2 * n)
        for (i in 0 until n) { window2[2 * i] = ox + tail[2 * i] * scale; window2[2 * i + 1] = oy + tail[2 * i + 1] * scale }
        window.set(localClip.left + where[0], localClip.top + where[1], localClip.right + where[0], localClip.bottom + where[1])
        val chunk = Chunk(out, window2, color, RectF(window), first)
        if (count > 0) first = false
        dirty = true
        try { target.renderFrontBufferedLayer(chunk) } catch (_: Throwable) { }
    }

    /** The stroke is done (or was cancelled): wipe the layer once the page has had a couple of frames to draw it. */
    fun end() {
        if (!dirty) return
        surface.removeCallbacks(wipe)
        surface.postOnAnimationDelayed(wipe, WIPE_DELAY_MS)
    }

    /** Wipe now, e.g. when the stroke turned into a shape the page is drawing instead. */
    fun cancel() { surface.removeCallbacks(wipe); wipeNow() }

    private fun wipeNow() {
        if (!dirty) return
        dirty = false; first = true; hadTail = false
        try { renderer?.clear() } catch (_: Throwable) { }
    }

    companion object {
        /** About three frames at 60 Hz: long enough for the page to show the finished stroke, short enough not to linger. */
        private const val WIPE_DELAY_MS = 48L
        private val byWindow = java.util.WeakHashMap<View, LowLatencyInk>()

        /** The layer for [view]'s window, created on first use; null where it can't be (and callers just draw as usual). */
        fun obtain(view: View): LowLatencyInk? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val decor = view.rootView as? FrameLayout ?: return null
            byWindow[decor]?.let { return it }
            val created = LowLatencyInk(decor)
            if (!created.attach()) return null
            byWindow[decor] = created
            return created
        }

        /** Removes the layer from [view]'s window when the setting is turned off. */
        fun dispose(view: View) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val decor = view.rootView ?: return
            byWindow.remove(decor)?.detach()
        }
    }
}
