package com.folio.notes.math

import android.graphics.Color
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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
            }, modifier = Modifier.size(1.dp), onRelease = { it.removeAllViews(); view.destroy() })
        }
        compose.waitForIdle()
        val formulas = listOf(
            "\\Pr(X \\le 3)", "\\Pr(A \\mid B)", "\\Pr(X=x)", "\\Pr(X \\geq 4)",
            "\\Pr(X \\le 3)=\\sum_{x=0}^{3}\\binom{n}{x}p^x(1-p)^{n-x}",
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
                    assertEquals("true", view.evaluate("Array.from(document.fonts).some(f => f.status === 'loaded')"))
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
}
