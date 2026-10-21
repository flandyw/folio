package com.folio.notes.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
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
    /** A run of window-space points as x, y, diameter triples, drawn with [clip] (window space) applied. Immutable. */
    private class Chunk(val points: FloatArray, val color: Int, val clip: RectF)

    private val surface = SurfaceView(decor.context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = false; isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    @Volatile private var surfaceReady = false
    private var renderer: CanvasFrontBufferedRenderer<Chunk>? = null
    private var dirty = false
    private var lastX = 0f; private var lastY = 0f; private var lastDiameter = 0f
    private var hasLast = false
    private val where = IntArray(2)
    private val window = RectF()
    private val wipe = Runnable { wipeNow() }

    /** Render-thread state. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceReady = width > 0 && height > 0 }
        override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
    }

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Chunk> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Chunk) {
            val p = param.points
            canvas.save()
            canvas.clipRect(param.clip)
            paint.color = param.color; fill.color = param.color
            if (p.size == 3) canvas.drawCircle(p[0], p[1], p[2] / 2f, fill)
            var i = 3
            while (i + 2 < p.size) {
                paint.strokeWidth = p[i + 2]
                canvas.drawLine(p[i - 3], p[i - 2], p[i], p[i + 1], paint)
                i += 3
            }
            canvas.restore()
        }
        // Nothing is committed: the page draws the finished stroke, and the front buffer is simply wiped.
        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Chunk>) = Unit
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
        surfaceReady = false; dirty = false; hasLast = false
    }

    /** True when strokes can be drawn now; false until the surface exists (the page then simply draws alone). */
    val ready: Boolean get() = surfaceReady && renderer != null

    /** A new stroke: any ink still on screen from the last one is wiped first, then the stroke starts afresh. */
    fun begin() {
        surface.removeCallbacks(wipe)
        if (dirty) wipeNow()
        hasLast = false
    }

    /**
     * Draws [points] from index [from] on, converted from page units to the window with the view's own
     * placement ([originX], [originY], [scale] as in the view) and clipped to [localClip] (view pixels).
     */
    fun append(view: View, originX: Float, originY: Float, scale: Float, localClip: RectF, color: Int,
        strokeWidth: Float, points: List<InkPoint>, from: Int) {
        val target = renderer ?: return
        if (from >= points.size || !surfaceReady) return
        view.getLocationInWindow(where)
        val count = points.size - from
        val carry = if (hasLast) 1 else 0
        val out = FloatArray(3 * (count + carry))
        var o = 0
        if (carry == 1) { out[o++] = lastX; out[o++] = lastY; out[o++] = lastDiameter }
        for (i in from until points.size) {
            val p = points[i]
            lastX = where[0] + originX + p.x * scale
            lastY = where[1] + originY + p.y * scale
            lastDiameter = strokeWidth * InkRenderer.penPressureScale(p.pressure) * scale
            out[o++] = lastX; out[o++] = lastY; out[o++] = lastDiameter
        }
        hasLast = true
        window.set(localClip.left + where[0], localClip.top + where[1], localClip.right + where[0], localClip.bottom + where[1])
        dirty = true
        try { target.renderFrontBufferedLayer(Chunk(out, color, RectF(window))) } catch (_: Throwable) { }
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
        dirty = false; hasLast = false
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
