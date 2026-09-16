package com.folio.notes.math

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private data class FormulaKey(val latex: String, val display: Boolean, val fontPx: Float, val density: Float, val color: Int)

// No Context/View references. Eviction does not recycle images still displayed by Compose.
private val images = object : LruCache<FormulaKey, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: FormulaKey, value: Bitmap) = value.allocationByteCount
}
private val renderingSlots = Semaphore(2)

@Stable
private class FormulaState(val key: FormulaKey, val style: TextStyle, val fallbackWidth: Dp, val fallbackHeight: Dp) {
    var bitmap by mutableStateOf(images.get(key))
    var session by mutableStateOf<CompletableDeferred<FrameLayout>?>(null)
    val width: Dp get() = bitmap?.let { (it.width / key.density).dp } ?: fallbackWidth
    val height: Dp get() = bitmap?.let { (it.height / key.density).dp } ?: fallbackHeight
}

@Composable
private fun rememberFormula(latex: String, display: Boolean, style: TextStyle): FormulaState {
    val density = LocalDensity.current
    val fontSize = style.fontSize.takeIf { it.isSp } ?: 16.sp
    val color = style.color.takeIf { it != Color.Unspecified } ?: LocalContentColor.current
    val fontPx = with(density) { fontSize.toPx() }
    val key = FormulaKey(latex, display, fontPx, density.density, color.toArgb())
    val resolved = style.copy(fontSize = fontSize, color = color)
    val measurer = rememberTextMeasurer()
    val fallback = remember(latex, resolved, density) { measurer.measure(latex.ifEmpty { " " }, resolved).size }
    return remember(key, resolved) {
        FormulaState(key, resolved, with(density) { fallback.width.toDp() }, with(density) { fallback.height.toDp() })
    }
}

/** Generic offline math block. Only a cache miss briefly mounts a local WebView. */
@Composable
fun KaTeXMath(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val state = rememberFormula(latex, displayMode, textStyle)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        FormulaContent(state, Modifier.horizontalScroll(rememberScrollState()))
    }
}

/** Native Text placeholder; oversized inline formulas scroll as a single, unbroken fragment. */
@Composable
fun rememberKaTeXInlineContent(latex: String, textStyle: TextStyle, maxWidth: Dp): InlineTextContent {
    val state = rememberFormula(latex, false, textStyle)
    val density = LocalDensity.current
    val width = state.width.coerceAtMost(maxWidth).coerceAtLeast(1.dp)
    val height = state.height.coerceAtLeast(1.dp)
    return InlineTextContent(
        Placeholder(with(density) { width.toSp() }, with(density) { height.toSp() }, PlaceholderVerticalAlign.TextCenter)
    ) {
        FormulaContent(state, Modifier.horizontalScroll(rememberScrollState()))
    }
}

@Composable
private fun FormulaContent(state: FormulaState, modifier: Modifier = Modifier) {
    // The effect is inside the placeholder: formulas omitted by maxLines don't start a renderer.
    LaunchedEffect(state) {
        if (state.bitmap != null) return@LaunchedEffect
        renderingSlots.withPermit {
            var view: KaTeXWebView? = null
            try {
                // A previous identical request may have filled the cache while we queued.
                state.bitmap = images.get(state.key)
                if (state.bitmap == null) {
                    val hostReady = CompletableDeferred<FrameLayout>()
                    state.session = hostReady
                    val bitmap = withTimeoutOrNull(10_000) {
                        val host = hostReady.await()
                        val renderer = KaTeXWebView(host.context)
                        view = renderer
                        host.addView(renderer)
                        val key = state.key
                        val color = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
                            (key.color shr 16) and 255, (key.color shr 8) and 255,
                            key.color and 255, (key.color ushr 24) / 255f)
                        renderer.render(key.latex, key.display, key.fontPx / key.density, color, key.density)
                    }
                    if (bitmap != null) {
                        images.put(state.key, bitmap)
                        state.bitmap = bitmap
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Missing/broken WebView, assets or pathological input: native source stays visible.
            } finally {
                view?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.stopLoading()
                    it.destroy()
                }
                state.session = null
            }
        }
    }
    Box(modifier.height(state.height.coerceAtLeast(1.dp))) {
        val bitmap = state.bitmap
        if (bitmap == null) {
            Text(state.key.latex.ifEmpty { " " }, style = state.style)
        } else {
            Image(bitmap.asImageBitmap(), state.key.latex,
                Modifier.size(state.width, state.height), contentScale = ContentScale.None)
        }
        state.session?.let { hostReady ->
            // Attached and VISIBLE for WebView's visual-state callback, but never shown over text.
            AndroidView(
                factory = { context -> FrameLayout(context.applicationContext).also { hostReady.complete(it) } },
                modifier = Modifier.size(1.dp).alpha(0f),
                onRelease = { it.removeAllViews() },
            )
        }
    }
}
