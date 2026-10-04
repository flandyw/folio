package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.ceil

/** First stroke needing paint; a changed prefix requires clearing and replaying the layer. */
internal fun appendedInkStart(previous: List<Stroke>, current: List<Stroke>): Int {
    if (previous === current) return current.size
    if (current.size < previous.size) return 0
    for (i in previous.indices) if (previous[i] !== current[i]) return 0
    return previous.size
}

/**
 * Screen-resolution, transparent ink for the current viewport. Stable writing frames cost one
 * bitmap draw, independent of page density; a commit paints only its appended strokes. Paper,
 * pictures and text stay outside this layer so their ordering and independent edits are preserved.
 * The viewport key also supports negative coordinates on infinite canvases.
 */
internal class CommittedInkCache(
    private val maxPixels: Long = 8_000_000L,
    /** Called on the UI thread when a deferred raster has landed, so the view can draw it. */
    private val onRasterReady: () -> Unit = {}
) {
    /** One raster being built off the UI thread for an exact viewport and scale. */
    private class Job(val strokes: List<Stroke>, val area: Rect, val pixelsPerUnit: Float) {
        @Volatile var cancelled = false
        @Volatile var failed = false
        @Volatile var bitmap: Bitmap? = null
    }

    private var job: Job? = null
    /** Set once a worker raster could not be built (out of memory); drawing then stays inline. */
    private var deferBroken = false
    private val staleDestination = RectF()
    private var bitmap: Bitmap? = null
    private var strokes: List<Stroke> = emptyList()
    private val viewport = Rect()
    private val clip = Rect()
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 0f

    fun clear() {
        job?.cancelled = true; job = null
        // Do not recycle: the hardware renderer may still hold the previous frame's bitmap.
        bitmap = null
        strokes = emptyList()
        scale = 0f
    }

    /**
     * Gives the raster up without losing it: a page that scrolls away and comes back (or whose view
     * is recycled for another page) finds it again instead of paying for the whole page a second time.
     */
    fun release() {
        val layer = bitmap
        if (layer != null && strokes.isNotEmpty() && scale > 0f) InkRasterStore.put(strokes, viewport, scale, layer)
        clear()
    }

    /**
     * Starts building the raster for [area] now, so it is ready (or nearly) by the first frame that
     * needs it. A raster already held, in flight, or kept from an earlier visit is left alone.
     */
    fun prewarm(current: List<Stroke>, area: Rect, pixelsPerUnit: Float) {
        if (deferBroken || area.isEmpty || current.size < DEFER_MIN_STROKES || !pixelsPerUnit.isFinite() || pixelsPerUnit <= 0f) return
        val width = ceil(area.width() * pixelsPerUnit.toDouble()).toInt()
        val height = ceil(area.height() * pixelsPerUnit.toDouble()).toInt()
        if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) return
        // Only a cache with nothing at all: while it holds a raster or a build, the frame that needs
        // a different one asks for it itself, so a pinch cannot queue a bitmap per step.
        if (bitmap != null || job != null) return
        clip.set(area)
        obtainDeferred(current, pixelsPerUnit)
    }

    /**
     * Installs the raster for the current viewport if one is ready or kept, and reports true; otherwise
     * makes sure one is being built and reports false. It also reports true when deferring has been
     * given up on, so the caller carries on and rasterizes inline. A viewport or scale the cache is not holding used to be
     * rasterized inside the frame that asked for it, which is what a dense page entering the screen
     * paid for in one go.
     */
    private fun obtainDeferred(current: List<Stroke>, pixelsPerUnit: Float): Boolean {
        val running = job
        if (running != null) {
            if (running.failed) { job = null; deferBroken = true; return true }
            if (running.area == clip && running.pixelsPerUnit == pixelsPerUnit &&
                appendedInkStart(running.strokes, current) == running.strokes.size) {
                val done = running.bitmap ?: return false
                install(done, running.strokes, pixelsPerUnit)
                job = null
                return true
            }
            running.cancelled = true; job = null
        }
        InkRasterStore.take(current, clip, pixelsPerUnit)?.let { kept ->
            install(kept.bitmap, kept.strokes, pixelsPerUnit)
            return true
        }
        val width = ceil(clip.width() * pixelsPerUnit.toDouble()).toInt()
        val height = ceil(clip.height() * pixelsPerUnit.toDouble()).toInt()
        val next = Job(current, Rect(clip), pixelsPerUnit)
        job = next
        InkRasterWorker.submit {
            try {
                if (next.cancelled) return@submit
                val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(layer)
                canvas.scale(pixelsPerUnit, pixelsPerUnit)
                canvas.translate(-next.area.left.toFloat(), -next.area.top.toFloat())
                // Straight from the strokes: the view's geometry caches belong to the UI thread.
                for (stroke in next.strokes) {
                    if (next.cancelled) return@submit
                    if (InkRenderer.boundsVisible(InkRenderer.rawBounds(stroke), stroke.width, next.area)) {
                        InkRenderer.drawRendered(canvas, stroke, InkRenderer.rendered(stroke))
                    }
                }
                next.bitmap = layer
            } catch (_: Throwable) {
                next.failed = true
            }
            if (!next.cancelled) InkRasterWorker.onMain(onRasterReady)
        }
        return false
    }

    private fun install(layer: Bitmap, drawn: List<Stroke>, pixelsPerUnit: Float) {
        bitmap = layer
        strokes = drawn
        viewport.set(clip)
        scale = pixelsPerUnit
    }

    /** Reproject existing pixels during navigation, without rasterizing or allocating. */
    fun drawSnapshot(canvas: Canvas, current: List<Stroke>, required: Rect): Boolean {
        val layer = bitmap ?: return false
        if (strokes !== current || !viewport.contains(required)) return false
        canvas.drawBitmap(layer, null, destination, paint)
        return true
    }

    fun draw(
        canvas: Canvas,
        current: List<Stroke>,
        pixelsPerUnit: Float,
        boundsOf: (Stroke) -> FloatArray,
        renderOf: (Stroke) -> InkRenderer.RenderedStroke,
        rasterViewport: Rect? = null,
        /** When true, pen strokes draw as one uniform path (preview); settled frames use full taper. */
        fastPreview: Boolean = false,
        /** Build a dense page's raster off the UI thread instead of inside this frame (finite pages). */
        deferred: Boolean = false
    ) {
        if (rasterViewport != null) clip.set(rasterViewport) else canvas.getClipBounds(clip)
        if (clip.isEmpty || current.isEmpty()) {
            clear()
            return
        }
        val width = ceil(clip.width() * pixelsPerUnit.toDouble()).toInt()
        val height = ceil(clip.height() * pixelsPerUnit.toDouble()).toInt()
        // Bound memory per visible page (32 MB). Oversized surfaces keep full vector quality.
        if (!pixelsPerUnit.isFinite() || pixelsPerUnit <= 0f || width <= 0 || height <= 0 ||
            width.toLong() * height > maxPixels) {
            clear()
            drawRange(canvas, current, 0, clip, boundsOf, renderOf, fastPreview)
            return
        }
        val held = bitmap
        val holdsThisView = held != null && viewport == clip && scale == pixelsPerUnit
        if (!holdsThisView && deferred && !fastPreview && !deferBroken && current.size >= DEFER_MIN_STROKES &&
            !obtainDeferred(current, pixelsPerUnit)) {
            // Until the new raster lands, what the cache last held is still the closest picture.
            if (held != null && scale > 0f) {
                staleDestination.set(viewport.left.toFloat(), viewport.top.toFloat(),
                    viewport.left + held.width / scale, viewport.top + held.height / scale)
                canvas.drawBitmap(held, null, staleDestination, paint)
            }
            return
        }
        var layer = bitmap
        val viewportChanged = layer == null || viewport != clip || scale != pixelsPerUnit
        if (layer == null || layer.width != width || layer.height != height) {
            layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = layer
        }
        val start = if (viewportChanged) 0 else appendedInkStart(strokes, current)
        if (viewportChanged || strokes !== current) {
            if (start == 0) layer.eraseColor(Color.TRANSPARENT)
            val recording = Canvas(layer)
            recording.scale(pixelsPerUnit, pixelsPerUnit)
            recording.translate(-clip.left.toFloat(), -clip.top.toFloat())
            drawRange(recording, current, start, clip, boundsOf, renderOf, fastPreview)
            viewport.set(clip)
            scale = pixelsPerUnit
            strokes = current
        }
        destination.set(clip.left.toFloat(), clip.top.toFloat(),
            clip.left + width / pixelsPerUnit, clip.top + height / pixelsPerUnit)
        canvas.drawBitmap(layer, null, destination, paint)
    }

    private fun drawRange(
        canvas: Canvas, strokes: List<Stroke>, start: Int, clip: Rect,
        boundsOf: (Stroke) -> FloatArray, renderOf: (Stroke) -> InkRenderer.RenderedStroke,
        fastPreview: Boolean = false
    ) {
        for (i in start until strokes.size) {
            val stroke = strokes[i]
            if (InkRenderer.boundsVisible(boundsOf(stroke), stroke.width, clip)) {
                val rendered = renderOf(stroke)
                if (fastPreview) InkRenderer.drawPreview(canvas, stroke, rendered)
                else InkRenderer.drawRendered(canvas, stroke, rendered)
            }
        }
    }

    private companion object {
        /** Below this a page rasterizes inline in well under a frame; dense pages go to the worker. */
        const val DEFER_MIN_STROKES = 40
    }
}

/**
 * Finished page rasters of views that have gone away, so a page scrolled out of view and back
 * reuses its picture instead of rebuilding it. Process-wide and bounded by bytes; an entry names
 * the exact stroke list, viewport and scale it was drawn for, and is handed to one view at a time
 * because that view then appends to it in place.
 */
internal object InkRasterStore {
    class Kept(val strokes: List<Stroke>, val bitmap: Bitmap)
    private class Entry(val strokes: List<Stroke>, val viewport: Rect, val scale: Float, val bitmap: Bitmap)

    private const val MAX_BYTES = 64L * 1024 * 1024
    private val entries = ArrayDeque<Entry>()

    @Synchronized fun put(strokes: List<Stroke>, viewport: Rect, scale: Float, bitmap: Bitmap) {
        entries.addLast(Entry(strokes, Rect(viewport), scale, bitmap))
        var bytes = entries.sumOf { it.bitmap.allocationByteCount.toLong() }
        // Dropped, never recycled: a frame in flight may still be reading the bitmap.
        while (bytes > MAX_BYTES && entries.size > 1) bytes -= entries.removeFirst().bitmap.allocationByteCount
    }

    /** The newest raster drawn for these strokes (or an earlier prefix of them) at this viewport and scale. */
    @Synchronized fun take(strokes: List<Stroke>, viewport: Rect, scale: Float): Kept? {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.scale != scale || entry.viewport != viewport) continue
            if (appendedInkStart(entry.strokes, strokes) != entry.strokes.size) continue
            entries.removeAt(i)
            return Kept(entry.strokes, entry.bitmap)
        }
        return null
    }

    @Synchronized fun clear() = entries.clear()
}

/** One low-priority thread for page rasters, so a burst of pages queues instead of competing with the UI thread. */
internal object InkRasterWorker {
    private val pool by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ink-raster").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    fun submit(task: () -> Unit) { pool.execute(task) }
    fun onMain(task: () -> Unit) { main.post(task) }
}
