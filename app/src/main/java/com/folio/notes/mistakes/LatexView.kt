package com.folio.notes.mistakes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Compose front end for the local LaTeX engine.
 *
 * [MeasurerLatexMetrics] measures glyphs with the platform's own text stack (so a formula always
 * uses the fonts actually installed on the device) while [LatexLayoutEngine] decides where every
 * glyph, rule and radical goes. Nothing here parses LaTeX; it only paints the draw list the pure
 * engine produced, which is why the engine stays JVM-testable.
 *
 * Inline formulas are embedded in surrounding Markdown text as measured [InlineTextContent]
 * placeholders; display formulas get their own centred, horizontally scrollable line.
 */
class MeasurerLatexMetrics(
    private val measurer: TextMeasurer,
    private val density: Density,
) : LatexMetrics {
    private data class Key(val text: String, val size: Float, val italic: Boolean, val bold: Boolean, val font: LatexFont)

    private class Entry(val metrics: LatexGlyphMetrics, val layout: TextLayoutResult)

    private val cache = LinkedHashMap<Key, Entry>()

    override fun measure(text: String, size: Float, italic: Boolean, bold: Boolean, font: LatexFont): LatexGlyphMetrics =
        entry(text, size, italic, bold, font)?.metrics ?: LatexGlyphMetrics(0f, 0f, 0f)

    /** The measured run backing a [MathDraw.Glyph], ready to be painted. */
    fun layoutResult(text: String, size: Float, italic: Boolean, bold: Boolean, font: LatexFont): TextLayoutResult? =
        entry(text, size, italic, bold, font)?.layout

    override fun ruleThickness(size: Float): Float = max(1f, size * 0.045f)

    private fun entry(text: String, size: Float, italic: Boolean, bold: Boolean, font: LatexFont): Entry? {
        if (text.isEmpty() || size <= 0f) return null
        val key = Key(text, size, italic, bold, font)
        cache[key]?.let { return it }
        val style = TextStyle(
            fontSize = with(density) { size.toSp() },
            fontFamily = font.family,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = Color.Unspecified,
        )
        val result = measurer.measure(
            AnnotatedString(text),
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
        )
        val baseline = result.getLineBaseline(0)
        val entry = Entry(
            LatexGlyphMetrics(
                width = result.size.width.toFloat(),
                ascent = baseline.toFloat(),
                depth = (result.size.height - baseline).toFloat(),
            ),
            result,
        )
        if (cache.size > CACHE_LIMIT) cache.clear()
        cache[key] = entry
        return entry
    }

    private companion object {
        const val CACHE_LIMIT = 1024
    }
}

private val LatexFont.family: FontFamily
    get() = when (this) {
        LatexFont.Serif -> FontFamily.Serif
        LatexFont.SansSerif -> FontFamily.SansSerif
        LatexFont.Monospace -> FontFamily.Monospace
        LatexFont.Cursive -> FontFamily.Cursive
    }

/** Font size in pixels for a surrounding [TextStyle], falling back to a sensible body size. */
fun mathFontSizePx(base: TextStyle, density: Density): Float {
    val value = base.fontSize.value
    val sp = if (value.isFinite() && value > 0f) value else DEFAULT_MATH_SP
    return with(density) { sp.sp.toPx() }
}

/** The ARGB colour a formula should be painted with, given the surrounding text style. */
fun mathColor(base: TextStyle, fallback: Color): Long {
    val color = if (base.color == Color.Unspecified) fallback else base.color
    return color.toArgb().toLong() and 0xFFFFFFFFL
}

/** A metrics instance shared by one paragraph's inline formulas and its display formulas. */
@Composable
fun rememberLatexMetrics(): MeasurerLatexMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) { MeasurerLatexMetrics(measurer, density) }
}

/** Lays out [latex] with the given metrics, returning an empty box when the input is unparseable. */
fun layoutLatex(
    latex: String,
    metrics: LatexMetrics,
    baseSizePx: Float,
    style: MathStyle,
    color: Long,
): LatexBox = runCatching {
    LatexLayoutEngine(metrics, baseSizePx, color).layout(LatexParser.parse(latex), style)
}.getOrDefault(LatexBox.EMPTY)

/**
 * Builds the inline placeholder for a formula sitting inside a line of text. Returns null when the
 * formula has no visible extent, in which case the caller falls back to plain unicode text.
 */
@OptIn(ExperimentalTextApi::class)
fun inlineMathContent(
    latex: String,
    base: TextStyle,
    metrics: MeasurerLatexMetrics,
    density: Density,
    color: Long,
): InlineTextContent? {
    val box = layoutLatex(latex, metrics, mathFontSizePx(base, density), MathStyle.Text, color)
    if (box.width <= 0.01f || box.height <= 0.01f) return null
    val widthDp = with(density) { box.width.toDp() }
    val heightDp = with(density) { box.height.toDp() }
    val placeholder = Placeholder(
        width = with(density) { box.width.toSp() },
        height = with(density) { box.height.toSp() },
        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
    )
    return InlineTextContent(placeholder) { _ -> LatexMathCanvas(box, metrics, widthDp, heightDp) }
}

/** Centred display formula; scrolls horizontally when it is wider than the screen. */
@Composable
fun LatexMathDisplay(
    latex: String,
    modifier: Modifier = Modifier,
    base: TextStyle = LocalTextStyle.current,
    scrollable: Boolean = true,
) {
    if (latex.isBlank()) return
    val metrics = rememberLatexMetrics()
    val density = LocalDensity.current
    val fallback = LocalContentColor.current
    val color = mathColor(base, fallback)
    val sizePx = mathFontSizePx(base, density) * 1.1f
    val box = remember(latex, sizePx, color) { layoutLatex(latex, metrics, sizePx, MathStyle.Display, color) }
    val scrollModifier = if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    Box(
        modifier.fillMaxWidth().then(scrollModifier).padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (box.width > 0.01f && box.height > 0.01f) {
            val widthDp = with(density) { box.width.toDp() }
            val heightDp = with(density) { box.height.toDp() }
            LatexMathCanvas(box, metrics, widthDp, heightDp)
        } else {
            // Nothing measurable: show the source so the content is never lost.
            Text(latex, style = base)
        }
    }
}

@Composable
private fun LatexMathCanvas(box: LatexBox, metrics: MeasurerLatexMetrics, width: Dp, height: Dp) {
    Canvas(Modifier.size(width, height)) {
        val originY = box.ascent
        box.draws.forEach { draw ->
            when (draw) {
                is MathDraw.Glyph -> {
                    val layout = metrics.layoutResult(draw.text, draw.size, draw.italic, draw.bold, draw.font)
                        ?: return@forEach
                    val baseline = layout.getLineBaseline(0).toFloat()
                    drawText(
                        layout,
                        color = Color(draw.color),
                        topLeft = Offset(draw.x, originY + draw.baseline - baseline),
                    )
                }
                is MathDraw.Rule -> drawRect(
                    color = Color(draw.color),
                    topLeft = Offset(draw.x, originY + draw.y),
                    size = Size(draw.width, draw.height),
                )
                is MathDraw.Line -> drawLine(
                    color = Color(draw.color),
                    start = Offset(draw.x1, originY + draw.y1),
                    end = Offset(draw.x2, originY + draw.y2),
                    strokeWidth = draw.thickness,
                )
                is MathDraw.Border -> drawRect(
                    color = Color(draw.color),
                    topLeft = Offset(draw.x, originY + draw.y),
                    size = Size(draw.width, draw.height),
                    style = Stroke(width = draw.thickness),
                )
                is MathDraw.Polyline -> drawPoints(
                    points = draw.points.map { Offset(it.x, originY + it.y) },
                    pointMode = PointMode.Polygon,
                    color = Color(draw.color),
                    strokeWidth = draw.thickness,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private const val DEFAULT_MATH_SP = 15f
