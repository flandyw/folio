package com.folio.notes.mistakes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme

/**
 * Renders ExamTrack Markdown + LaTeX offline.
 *
 * Markdown splitting, math-delimiter detection (`$…$`, `$$…$$`, `\(…\)`, `\[…\]`),
 * plain-text fallbacks and accessibility strings are local ([RichTextParser], pure
 * Kotlin, unit tested). Every math segment itself is rendered by the
 * huarangmeng/latex renderer (MIT, bundled KaTeX fonts): display math via [Latex],
 * inline math via the shared measurer's `inlineContent()` so formulas sit inside
 * the surrounding `Text` with precisely measured placeholders. If measurement
 * fails, the segment falls back to a readable unicode rendering instead of
 * vanishing. Never throws: a parse failure falls back to raw source text.
 */
@Composable
fun RichText(
    source: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (source.isBlank()) return
    val blocks = remember(source) {
        runCatching { RichTextParser.parse(source) }.getOrDefault(emptyList())
            .ifEmpty { listOf(RichBlock.Para(listOf(RichInline.Run(source)))) }
    }
    val measurer = rememberLatexMeasurer()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RichBlock.Para -> InlineParagraph(block.inlines, style, maxLines, overflow, measurer = measurer)
                is RichBlock.Heading -> InlineParagraph(
                    block.inlines,
                    when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.merge(style),
                    maxLines, overflow, measurer = measurer
                )
                is RichBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = style, color = MaterialTheme.colorScheme.primary)
                            InlineParagraph(item, style, maxLines, overflow, Modifier.weight(1f), measurer)
                        }
                    }
                }
                is RichBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}.", style = style, color = MaterialTheme.colorScheme.primary)
                            InlineParagraph(item, style, maxLines, overflow, Modifier.weight(1f), measurer)
                        }
                    }
                }
                is RichBlock.Quote -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(
                            Modifier.width(3.dp),
                            thickness = 3.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = .6f)
                        )
                        InlineParagraph(
                            block.inlines, style.merge(fontStyle = FontStyle.Italic),
                            maxLines, overflow, Modifier.weight(1f), measurer
                        )
                    }
                }
                is RichBlock.Code -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        block.code, Modifier.fillMaxWidth().padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = (style.fontSize.value.takeIf { it > 0 } ?: 14f).sp * 0.92f,
                        maxLines = maxLines, overflow = overflow
                    )
                }
                is RichBlock.DisplayMath -> MathDisplay(block.latex, style)
                RichBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private typealias LatexMeasurer = com.hrm.latex.renderer.measure.LatexMeasurerState

@OptIn(ExperimentalTextApi::class)
@Composable
private fun InlineParagraph(
    inlines: List<RichInline>,
    style: androidx.compose.ui.text.TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    modifier: Modifier = Modifier,
    measurer: LatexMeasurer,
) {
    val mathConfig = LatexConfig(
        fontSize = (style.fontSize.value.takeIf { it > 0 } ?: 16f).sp,
        theme = LatexTheme.material3()
    )
    // Display math splits the paragraph so it can centre on its own line.
    val sections = remember(inlines) {
        val out = mutableListOf<Any>()
        var current: MutableList<RichInline> = mutableListOf()
        inlines.forEach {
            if (it is RichInline.Math && it.display) {
                if (current.isNotEmpty()) { out.add(current.toList()); current = mutableListOf() }
                out.add(it)
            } else current.add(it)
        }
        if (current.isNotEmpty()) out.add(current.toList())
        out.toList()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { section ->
            when (section) {
                is RichInline.Math -> MathDisplay(section.latex, style)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = section as List<RichInline>
                    val inlineContent = mutableMapOf<String, InlineTextContent>()
                    val annotated = buildAnnotatedString {
                        var mathId = 0
                        items.forEach { inline ->
                            when (inline) {
                                is RichInline.Run -> withStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        fontWeight = if (inline.bold) FontWeight.SemiBold else null,
                                        fontStyle = if (inline.italic) FontStyle.Italic else null,
                                        fontFamily = if (inline.code) FontFamily.Monospace else null,
                                        background = if (inline.code) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Unspecified,
                                        textDecoration = if (inline.strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                    )
                                ) { append(inline.text) }
                                is RichInline.Math -> {
                                    val latex = inline.latex
                                    val fallback = MathParser.toUnicode(
                                        runCatching { MathParser.parse(latex) }
                                            .getOrDefault(listOf(MathNode.Text(latex)))
                                    )
                                    // Precisely measured KaTeX rendering embedded in the text line;
                                    // null when the formula cannot be measured.
                                    val content = runCatching { measurer.inlineContent(latex, mathConfig) }.getOrNull()
                                    if (content != null) {
                                        val id = "math${mathId++}"
                                        appendInlineContent(id, fallback)
                                        inlineContent[id] = content
                                    } else {
                                        withStyle(
                                            androidx.compose.ui.text.SpanStyle(
                                                fontFamily = FontFamily.Serif,
                                                fontStyle = FontStyle.Italic,
                                                background = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)
                                            )
                                        ) { append(fallback) }
                                    }
                                }
                                RichInline.Break -> append("\n")
                            }
                        }
                    }
                    Text(annotated, style = style, maxLines = maxLines, overflow = overflow, inlineContent = inlineContent)
                }
            }
        }
    }
}

/** Centred display math rendered with KaTeX fonts; horizontally scrolls when too wide. */
@Composable
fun MathDisplay(latex: String, base: androidx.compose.ui.text.TextStyle = LocalTextStyle.current) {
    if (latex.isBlank()) return
    val config = LatexConfig(
        fontSize = (base.fontSize.value.takeIf { it > 0 } ?: 16f).sp * 1.1f,
        theme = LatexTheme.material3(),
        accessibilityEnabled = true
    )
    Box(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Latex(latex = latex, config = config)
    }
}
