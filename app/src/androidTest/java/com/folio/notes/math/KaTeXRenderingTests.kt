package com.folio.notes.math

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real local assets + WebView capture. Run on an isolated test device, including airplane mode. */
class KaTeXRenderingTests {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineShellRendersProbabilityAndVceNotationAndKeepsFailuresReadable() {
        lateinit var view: KaTeXWebView
        compose.setContent {
            AndroidView(factory = { context ->
                FrameLayout(context).apply {
                    view = KaTeXWebView(context)
                    addView(view)
                }
            }, modifier = Modifier.size(1.dp).alpha(0f), onRelease = { it.removeAllViews(); view.destroy() })
        }
        compose.waitForIdle()
        val formulas = listOf(
            "\\Pr(X \\le 3)", "\\Pr(A \\mid B)", "\\Pr(X=x)", "\\Pr(X \\geq 4)",
            "\\Pr(X \\le 3)=\\sum_{x=0}^{3}\\binom{n}{x}p^x(1-p)^{n-x}",
            "\\boxed{\\frac{1}{2}}", "f(3)=7", "f'(7)=3",
            "\\frac{dy}{dx}", "\\sqrt{x^2+1}", "\\binom{n}{r}",
            "\\int_a^b f(x)\\,dx", "\\sum_{i=1}^{n} x_i",
            "\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix}",
            "f(x)=\\begin{cases}x^2 & x \\ge 0 \\\\ -x & x < 0\\end{cases}",
            "\\left|\\frac{x-1}{x+2}\\right|", "\\alpha+\\beta+\\sin x"
        )
        runBlocking {
            withContext(Dispatchers.Main) {
                withTimeout(60_000) {
                    assertTrue(view.settings.blockNetworkLoads)
                    assertFalse(view.settings.allowFileAccess)
                    assertFalse(view.settings.allowContentAccess)
                    val density = view.resources.displayMetrics.density
                    for (formula in formulas) for (display in listOf(false, true)) {
                        for (color in listOf("#202020", "#eeeeee")) {
                            val bitmap = view.render(formula, display, 18f, color, density)
                            val result = JSONObject(view.evaluate("window.folioResult"))
                            assertTrue(formula, result.getBoolean("rendered"))
                            assertFalse(formula, result.getBoolean("error"))
                            assertTrue(bitmap.width > 4 && bitmap.height > 4)
                            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            assertTrue("Blank capture: $formula", pixels.any { Color.alpha(it) > 0 })
                            val expected = Color.parseColor(color)
                            assertTrue("Wrong theme color: $formula", pixels.any { Color.alpha(it) > 200 && (it and 0xffffff) == (expected and 0xffffff) })
                            bitmap.recycle()
                        }
                    }
                    // Reuse a small viewport for a tall box, just as list snippets and review
                    // answers share pool slots. Any-ink checks alone accept a missing denominator.
                    for (fontSize in listOf(12f, 18f, 32f)) {
                        view.render("f", false, fontSize, "#eeeeee", density).recycle()
                        val boxed = view.render("\\boxed{\\frac{1}{2}}", true, fontSize, "#eeeeee", density)
                        val denominator = JSONObject(view.evaluate("""
                            (() => {
                                const node = Array.from(document.querySelectorAll('.katex-html .mord'))
                                    .find(n => n.children.length === 0 && n.textContent === '2');
                                const r = node.getBoundingClientRect();
                                return {left:r.left, top:r.top, right:r.right, bottom:r.bottom};
                            })()
                        """.trimIndent()))
                        assertInkInRect(boxed, denominator, density, "Missing boxed denominator at $fontSize")
                        val border = JSONObject(view.evaluate("""
                            (() => {
                                const r = document.querySelector('.fbox').getBoundingClientRect();
                                return {left:r.left, top:r.bottom - 1, right:r.right, bottom:r.bottom};
                            })()
                        """.trimIndent()))
                        assertInkInRect(boxed, border, density, "Missing bottom box border at $fontSize")
                        boxed.recycle()
                    }
                    assertEquals("true", view.evaluate("Array.from(document.fonts).some(f => f.status === 'loaded')"))
                    // Fully transparent ink is genuinely blank, not a dropped frame: it must not be
                    // retried into an exception, and the renderer must keep working afterwards.
                    view.render("\\alpha", false, 18f, "rgba(0,0,0,0.000)", density).recycle()
                    val afterBlank = view.render("\\alpha+\\beta", false, 18f, "#202020", density)
                    assertTrue("Blank capture poisoned the renderer", afterBlank.hasInk())
                    afterBlank.recycle()
                    view.render("\\frac{", false, 18f, "#202020", density)
                    val malformed = JSONObject(view.evaluate("window.folioResult"))
                    assertTrue(malformed.getBoolean("error"))
                    assertTrue(malformed.getString("text").contains("\\frac{"))
                    view.render("\\href{https://example.com}{click}", false, 18f, "#202020", density)
                    assertEquals("0", view.evaluate("document.querySelectorAll('a, img, iframe').length"))
                    view.render("</script>\";window.injected=true;//", false, 18f, "#202020", density)
                    assertEquals("false", view.evaluate("window.injected === true"))
                }
            }
        }
    }
    @Test fun inkProbeFindsThinMarksBetweenFormerGridSamples() {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        try {
            assertFalse(bitmap.hasInk())
            bitmap.setPixel(5, 5, Color.WHITE)
            assertTrue(bitmap.hasInk())
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertInkInRect(bitmap: Bitmap, rect: JSONObject, density: Float, message: String) {
        val left = (rect.getDouble("left") * density).toInt().coerceAtLeast(0)
        val top = (rect.getDouble("top") * density).toInt().coerceAtLeast(0)
        val right = kotlin.math.ceil(rect.getDouble("right") * density).toInt()
        val bottom = kotlin.math.ceil(rect.getDouble("bottom") * density).toInt()
        assertTrue("$message: outside bitmap", right <= bitmap.width && bottom <= bitmap.height)
        assertTrue(message, (top until bottom).any { y ->
            (left until right).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
        })
    }
}
