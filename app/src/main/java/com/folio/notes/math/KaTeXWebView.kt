package com.folio.notes.math

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.math.ceil

internal const val MATH_ORIGIN = "https://folio-math.invalid/"

/**
 * A tiny pool of pre-warmed renderers. Creating a WebView plus loading the local
 * KaTeX shell costs hundreds of milliseconds; reusing two warm instances makes
 * every cache miss after the first pay only for layout + capture.
 * All pool entry points must run on the Main thread that owns the WebViews.
 */
internal object KaTeXPool {
    private const val MAX_RENDERERS = 2
    private val slots = Semaphore(MAX_RENDERERS)
    private val idle = ArrayDeque<KaTeXWebView>()
    private var active = 0

    private fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    suspend fun <T> use(appContext: Context, block: suspend (KaTeXWebView) -> T): T {
        return slots.withPermit {
            val renderer = acquire(appContext)
            active++
            try {
                block(renderer)
            } finally {
                active--
                if (renderer.dead) runCatching { renderer.stopLoading(); renderer.destroy() }
                else release(renderer)
            }
        }
    }

    private fun acquire(context: Context): KaTeXWebView {
        while (idle.isNotEmpty()) {
            val candidate = idle.removeFirst()
            if (!candidate.dead) return candidate
            runCatching { candidate.destroy() }
        }
        return KaTeXWebView(context.applicationContext)
    }

    private fun release(view: KaTeXWebView) {
        if (view.dead || idle.size >= MAX_RENDERERS) runCatching { view.stopLoading(); view.destroy() }
        else idle.addLast(view)
    }

    /** Start loading one shell early so the first formula finds a warm renderer. */
    fun prewarm(appContext: Context) {
        if (!isMain() || idle.isNotEmpty() || active + idle.size >= MAX_RENDERERS) return
        runCatching { idle.addLast(KaTeXWebView(appContext.applicationContext)) }
    }
}

/** An exact local asset allowlist; no file/content access, network, navigation or JS bridge. */
@SuppressLint("SetJavaScriptEnabled") // Required by the bundled KaTeX shell only.
internal class KaTeXWebView(context: Context) : WebView(context.applicationContext) {
    private val loaded = CompletableDeferred<Unit>()

    @Volatile
    internal var dead: Boolean = false
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            textZoom = 100
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path.orEmpty().removePrefix("/")
                val allowed = uri.toString() == MATH_ORIGIN + path &&
                    (path in setOf("math.html", "math.js", "math.css", "katex.min.js", "katex.min.css") ||
                        Regex("fonts/KaTeX_[A-Za-z0-9-]+\\.(woff2|woff|ttf)").matches(path))
                if (!allowed) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                val mime = when (path.substringAfterLast('.')) {
                    "html" -> "text/html"
                    "js" -> "application/javascript"
                    "css" -> "text/css"
                    "woff2" -> "font/woff2"
                    "woff" -> "font/woff"
                    else -> "font/ttf"
                }
                return try {
                    WebResourceResponse(mime, "utf-8", context.applicationContext.assets.open("katex/$path"))
                } catch (_: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Missing asset", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    dead = true
                    loaded.completeExceptionally(IllegalStateException("Local math shell unavailable"))
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dead = true
                loaded.completeExceptionally(IllegalStateException("Math renderer stopped"))
                // The pool destroys this view; never terminate Folio with Chromium.
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url == MATH_ORIGIN + "math.html") loaded.complete(Unit)
            }
        }
        loadUrl(MATH_ORIGIN + "math.html")
    }

    internal suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
    }

    /** Wait for local fonts before measuring; screenshot the actual KaTeX layout, never reimplement it. */
    suspend fun render(latex: String, display: Boolean, fontSize: Float, color: String, density: Float): Bitmap {
        loaded.await()
        val request = JSONObject().put("latex", latex).put("displayMode", display)
            .put("fontSize", fontSize).put("color", color)
        evaluate("window.renderMath($request); null")
        // Check immediately, then poll tightly: the shell usually answers within one frame.
        var result = evaluate("window.folioResult")
        while (result == "null") {
            delay(8)
            result = evaluate("window.folioResult")
        }
        val bounds = JSONObject(result)
        val width = ceil(bounds.getDouble("width") * density).toInt().coerceAtLeast(1)
        val height = ceil(bounds.getDouble("height") * density).toInt().coerceAtLeast(1)
        // Bound allocations for pathological source. The caller keeps readable native source on failure.
        require(width <= 16384 && height <= 16384 && width.toLong() * height <= 4_000_000)
        // Keep the full capture size if the tiny host is laid out again during the callback.
        layoutParams = layoutParams.apply { this.width = width; this.height = height }
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
        suspendCancellableCoroutine { continuation ->
            postVisualStateCallback(0, object : VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            })
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    }
}
