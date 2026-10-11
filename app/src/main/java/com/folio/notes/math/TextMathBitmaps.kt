package com.folio.notes.math

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Rendered formulas for text boxes and sticky notes. The notebook canvas is drawn with plain
 * [android.graphics.Canvas] calls (also for thumbnails and exports), so it cannot wait for a
 * Compose-hosted WebView; it asks [peek] for a finished picture instead and shows the typed source until
 * [want] has had one made. Pictures come from the same pooled offline KaTeX renderer as the rest of Folio.
 *
 * [peek] and [want] are safe from any thread. Rendering happens on the main thread through a window
 * [host] (any attached view's root); nothing renders while no editor is on screen.
 */
internal object TextMathBitmaps {
    /** [size] is the text size in page units; pictures are rendered at [PIXELS_PER_UNIT] pixels per unit. */
    data class Key(val latex: String, val display: Boolean, val size: Float, val color: Int)

    /** Sizes are in page units: [width]/[height] of the picture and the [baseline] distance from its top. */
    class Snippet(val bitmap: Bitmap, val width: Float, val height: Float, val baseline: Float)

    const val PIXELS_PER_UNIT = 3f
    private const val CAPTURE_TIMEOUT_MS = 20_000L

    private val images = object : LruCache<Key, Snippet>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Snippet) = value.bitmap.allocationByteCount
    }
    private val pending = LinkedHashSet<Key>()
    private val failed = HashSet<Key>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host = WeakReference<ViewGroup>(null)
    private var running = false

    /** Page text sizes are quantised so a hairline of float noise never asks for a fresh picture. */
    fun quantize(size: Float): Float = (size * 2f).toInt() / 2f

    fun trimMemory() { synchronized(images) { images.evictAll() } }

    fun peek(key: Key): Snippet? = synchronized(images) { images.get(key) }

    /** Rendered, or given up on: either way the layout for [key] will not change any more. */
    fun settled(key: Key): Boolean = peek(key) != null || synchronized(this) { key in failed }

    /** Queues [key] for rendering unless it is done, in progress or has failed before. */
    fun want(key: Key) {
        synchronized(this) {
            if (peek(key) != null || key in failed || !pending.add(key)) return
        }
        scope.launch { pump() }
    }

    /** Called by editors when they appear: formulas render through the window this view belongs to. */
    fun attach(root: ViewGroup) {
        host = WeakReference(root)
        if (synchronized(this) { pending.isNotEmpty() }) scope.launch { pump() }
    }

    fun addListener(listener: () -> Unit) { synchronized(listeners) { listeners += listener } }
    fun removeListener(listener: () -> Unit) { synchronized(listeners) { listeners -= listener } }

    private fun notifyReady() {
        val all = synchronized(listeners) { listeners.toList() }
        all.forEach { it() }
    }

    private suspend fun pump() {
        if (running) return
        running = true
        try {
            while (true) {
                val root = host.get() ?: return
                val key = synchronized(this) { pending.firstOrNull() } ?: return
                val ok = render(root.context.applicationContext, root, key)
                synchronized(this) { pending.remove(key); if (!ok) failed += key }
                if (ok) notifyReady()
            }
        } finally { running = false }
    }

    private suspend fun render(appContext: Context, root: ViewGroup, key: Key): Boolean {
        if (peek(key) != null) return true
        return try {
            KaTeXPool.use(appContext) { renderer ->
                val frame = FrameLayout(root.context).apply { alpha = 0f }
                root.addView(frame, ViewGroup.LayoutParams(1, 1))
                try {
                    frame.addView(renderer)
                    val color = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
                        (key.color shr 16) and 255, (key.color shr 8) and 255, key.color and 255, (key.color ushr 24) / 255f)
                    val snippet = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                        renderer.renderSnippet(key.latex, key.display, key.size, color, PIXELS_PER_UNIT)
                    }
                    if (snippet == null || !snippet.bitmap.hasInk()) { snippet?.bitmap?.recycle(); false }
                    else {
                        val bmp = snippet.bitmap
                        synchronized(images) {
                            images.put(key, Snippet(bmp, bmp.width / PIXELS_PER_UNIT, bmp.height / PIXELS_PER_UNIT, snippet.baseline))
                        }
                        true
                    }
                } finally {
                    (renderer.parent as? ViewGroup)?.removeView(renderer)
                    root.removeView(frame)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("FolioMath", "Text formula rendering failed", failure)
            false
        }
    }
}
