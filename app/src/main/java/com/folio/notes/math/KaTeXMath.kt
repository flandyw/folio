package com.folio.notes.math

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import kotlin.math.roundToInt

private data class MathRenderKey(
    val source: String, val display: Boolean, val fontPx: Float, val density: Float, val color: Int,
    val documentWidthPx: Int = 0, val lineHeightPx: Float = 0f,
) {
    val isDocument get() = documentWidthPx > 0
}

// No Context/View references. Eviction does not recycle images still displayed by Compose.
private val images = object : LruCache<MathRenderKey, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: MathRenderKey, value: Bitmap) = value.allocationByteCount
}

// Capture behavior is part of the cache format: old partial images still pass an ink check.
private const val KATEX_DISK_VERSION = "katex-0.18.7-document-v3"
private const val KATEX_DISK_MAX_BYTES = 32L * 1024 * 1024

private fun diskFile(appContext: Context, key: MathRenderKey): File {
    val raw = "${key.source}\n${key.display}\n${key.fontPx}\n${key.density}\n${key.color}\n${key.documentWidthPx}\n${key.lineHeightPx}"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val name = buildString(digest.size * 2 + 4) {
        digest.forEach { append(String.format(Locale.ROOT, "%02x", it)) }
        append(".png")
    }
    return File(File(appContext.cacheDir, "katex/$KATEX_DISK_VERSION"), name)
}

private suspend fun loadDiskBitmap(appContext: Context, key: MathRenderKey): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = diskFile(appContext, key)
            if (!file.isFile || file.length() <= 0 || file.length() > 8 * 1024 * 1024) return@runCatching null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
            if (bitmap.width < 1 || bitmap.height < 1 ||
                bitmap.width > 16384 || bitmap.height > 16384 ||
                bitmap.width.toLong() * bitmap.height > 4_000_000
            ) {
                runCatching { bitmap.recycle() }
                runCatching { file.delete() }
                return@runCatching null
            }
            // Self-heal entries cached before captures were verified: a blank one is a stale frame,
            // and keeping it would leave a permanent gap in every card rendered at this size.
            if (!bitmap.hasInk()) {
                runCatching { bitmap.recycle() }
                runCatching { file.delete() }
                return@runCatching null
            }
            file.setLastModified(System.currentTimeMillis())
            bitmap
        }.getOrNull()
    }

private suspend fun saveDiskBitmap(appContext: Context, key: MathRenderKey, bitmap: Bitmap) =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = diskFile(appContext, key)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!tmp.renameTo(file)) runCatching { tmp.delete() }
            trimDiskCache(file.parentFile)
        }
    }

private fun trimDiskCache(dir: File?) {
    runCatching {
        if (dir == null || !dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= KATEX_DISK_MAX_BYTES && files.size <= 1000) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= KATEX_DISK_MAX_BYTES) return
            total -= file.length()
            runCatching { file.delete() }
        }
    }
}

@Stable
private class MathRenderState(val key: MathRenderKey, val style: TextStyle, val fallback: DpSize, val description: String) {
    var bitmap by mutableStateOf(images.get(key))
    var session by mutableStateOf<CompletableDeferred<FrameLayout>?>(null)

    /** Captures display at their measured size, including the full document height. */
    val size: DpSize get() {
        val bmp = bitmap ?: return fallback
        val w = bmp.width / key.density
        val h = bmp.height / key.density
        return DpSize(w.dp, h.dp)
    }
}

/**
 * Renders of the same formula share one WebView pass. The dashboard shows a question in more than
 * one section at a time and lazy lists re-request the same formulas while scrolling; without this,
 * identical captures queue up one after another and later ones fall back to bare LaTeX.
 * Main-thread only, like the rest of the composable-side cache.
 */
private val inFlight = HashMap<MathRenderKey, CompletableDeferred<Bitmap?>>()

/** How long one capture may take once a renderer is in hand; queueing is bounded separately. */
private const val CAPTURE_TIMEOUT_MS = 20_000L

/** Give up waiting behind other formulas rather than blocking a card's coroutine forever. */
private const val QUEUE_TIMEOUT_MS = 60_000L

@Composable
private fun rememberRender(
    latex: String, display: Boolean, style: TextStyle,
    documentWidthPx: Int = 0, description: String = latex,
): MathRenderState {
    val density = LocalDensity.current
    val fontSize = style.fontSize.takeIf { it.isSp } ?: 16.sp
    val color = style.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    // Quantize sub-pixel sizes: imperceptible, but avoids a fresh render for float noise
    // when the text-scale slider or density conversion lands a fraction off.
    val fontPx = ((with(density) { fontSize.toPx() }) * 2).roundToInt() / 2f
    val lineHeightPx = if (documentWidthPx > 0) with(density) {
        (style.lineHeight.takeIf { it.isSp } ?: fontSize * 1.4f).toPx()
    } else 0f
    val key = MathRenderKey(latex, display, fontPx, density.density, color.toArgb(), documentWidthPx, lineHeightPx)
    val resolved = style.copy(fontSize = fontSize, color = color)
    val measurer = rememberTextMeasurer()
    val fallback = remember(description, resolved, density, documentWidthPx) {
        if (documentWidthPx > 0) DpSize.Zero // Documents measure their native fallback in the layout.
        else with(density) {
            val size = measurer.measure(description.ifEmpty { " " }, resolved).size
            DpSize(size.width.toDp(), size.height.toDp())
        }
    }
    return remember(key, resolved, description) { MathRenderState(key, resolved, fallback, description) }
}

/** Generic offline math block. Only a cache miss briefly mounts a local WebView. */
@Composable
fun KaTeXMath(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val state = rememberRender(latex, displayMode, textStyle)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        RenderedContent(state, Modifier.horizontalScroll(rememberScrollState()))
    }
}

/**
 * Layout an entire rich document in one KaTeX pass at the exact available width. Native prose
 * remains visible until capture succeeds. Rendering never depends on a Text placeholder being
 * laid out, and prose and math cannot disagree about line breaks, height or truncation.
 */
@Composable
fun KaTeXDocument(
    document: String,
    source: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    fallback: @Composable () -> Unit = { Text(source, style = textStyle) },
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (constraints.maxWidth <= 0 || !constraints.hasBoundedWidth) {
            fallback()
        } else {
            val state = rememberRender(document, true, textStyle, constraints.maxWidth, source)
            RenderedContent(state, Modifier.fillMaxWidth(), fallback)
        }
    }
}

/** Owns one pooled renderer for the duration of a single capture; returns the bitmap to cache. */
private suspend fun renderContent(appContext: Context, state: MathRenderState, onFresh: (Bitmap) -> Unit): Bitmap? =
    KaTeXPool.use(appContext) { renderer ->
        // A previous identical request may have filled memory while we queued.
        images.get(state.key)?.let { return@use it }
        val hostReady = CompletableDeferred<FrameLayout>()
        state.session = hostReady
        try {
            // Only the capture itself is time-boxed: waiting behind a full pool of cards is normal
            // on a dashboard, and timing out there used to leave bare LaTeX in place of the formula.
            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                val host = hostReady.await()
                (renderer.parent as? ViewGroup)?.removeView(renderer)
                host.addView(renderer)
                try {
                    val key = state.key
                    val color = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
                        (key.color shr 16) and 255, (key.color shr 8) and 255,
                        key.color and 255, (key.color ushr 24) / 255f)
                    if (key.isDocument) renderer.renderDocument(
                        JSONArray(key.source), key.fontPx / key.density, key.lineHeightPx / key.density,
                        key.documentWidthPx / key.density, color, key.density,
                    ) else renderer.render(key.source, key.display, key.fontPx / key.density, color, key.density)
                } finally {
                    (renderer.parent as? ViewGroup)?.removeView(renderer)
                }
            }
            if (bitmap == null) android.util.Log.w("FolioMath", "Offline content rendering timed out")
            // render() already rejects blank captures; never let one into either cache.
            if (bitmap != null && !bitmap.hasInk()) {
                runCatching { bitmap.recycle() }
                null
            } else {
                bitmap?.also {
                    images.put(state.key, it)
                    onFresh(it)
                }
            }
        } finally {
            state.session = null
        }
    }

@Composable
private fun RenderedContent(
    state: MathRenderState, modifier: Modifier = Modifier, fallback: (@Composable () -> Unit)? = null,
) {
    val appContext = LocalContext.current.applicationContext
    // The effect and its host belong to the whole document, independently of text line layout.
    LaunchedEffect(state) {
        // WebView and the shared in-flight/cache state belong to the Android UI thread.
        // In particular, resuming disk IO must not rely on a caller's Compose interceptor.
        withContext(Dispatchers.Main.immediate) {
            if (state.bitmap != null) return@withContext
            images.get(state.key)?.let {
                state.bitmap = it
                return@withContext
            }
            // Disk survives restarts: a repeated question needs no WebView at all.
            loadDiskBitmap(appContext, state.key)?.let {
                images.put(state.key, it)
                state.bitmap = it
                return@withContext
            }
            var fresh: Bitmap? = null
            // One capture serves every waiting occurrence of this formula.
            var pending = inFlight[state.key]
            val owner = pending == null
            if (owner) {
                pending = CompletableDeferred()
                inFlight[state.key] = pending!!
            }
            val result = pending!!
            if (owner) {
                try {
                    val rendered = renderContent(appContext, state) { fresh = it }
                    // An owner that is cancelled still has to release the waiters.
                    withContext(NonCancellable) { result.complete(rendered) }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { result.complete(null) }
                    throw cancelled
                } catch (failure: Exception) {
                    // Missing/broken WebView, assets or pathological input: native source stays visible.
                    android.util.Log.w("FolioMath", "Offline content rendering failed", failure)
                    withContext(NonCancellable) { result.complete(null) }
                } finally {
                    inFlight.remove(state.key)
                }
                // Persist after the pool slot is released; failures keep memory-only caching.
                fresh?.let {
                    try {
                        saveDiskBitmap(appContext, state.key, it)
                    } catch (_: Exception) {
                    }
                }
            }
            val shared = withTimeoutOrNull(QUEUE_TIMEOUT_MS) { result.await() }
            if (shared != null) state.bitmap = shared
            else if (!owner) {
                // A lazy-list owner can scroll away while this occurrence remains on screen.
                // Its cancellation must not strand the remaining document on source fallback.
                try {
                    state.bitmap = renderContent(appContext, state) { fresh = it }
                    fresh?.let { saveDiskBitmap(appContext, state.key, it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the complete native fallback on renderer failure.
                }
            }
        }
    }
    val contentModifier = if (state.key.isDocument && state.bitmap == null) modifier
        else modifier.height(state.size.height.coerceAtLeast(1.dp))
    Box(contentModifier) {
        val bitmap = state.bitmap
        if (bitmap == null) {
            if (fallback != null) fallback() else Text(state.description.ifEmpty { " " }, style = state.style)
        } else {
            Image(bitmap.asImageBitmap(), state.description,
                Modifier.size(state.size.width, state.size.height), contentScale = ContentScale.FillBounds)
        }
        state.session?.let { hostReady ->
            // Attached and VISIBLE for WebView's visual-state callback, but never shown over text.
            key(hostReady) {
                AndroidView(
                    factory = { context -> FrameLayout(context.applicationContext).also { hostReady.complete(it) } },
                    modifier = Modifier.size(1.dp).alpha(0f),
                    onRelease = { it.removeAllViews() },
                )
            }
        }
    }
}
