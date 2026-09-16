package com.folio.notes.mistakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders ExamTrack Markdown + LaTeX offline.
 *
 * Markdown splitting, math-delimiter detection (`$…$`, `$$…$$`, `\(…\)`, `\[…\]`), plain-text
 * fallbacks and accessibility strings are local ([RichTextParser], pure Kotlin, unit tested).
 * Every math segment is laid out by the in-repo LaTeX engine ([LatexParser] → [LatexLayoutEngine])
 * and painted by [LatexMathDisplay] / an [InlineTextContent] placeholder, so formulas sit inside
 * the surrounding `Text` with exactly measured dimensions. If a formula has no measurable extent
 * the segment falls back to readable unicode text instead of vanishing, and a parse failure falls
 * back to the raw source. Nothing here can throw.
 */
@Composable
fun RichText(
    source: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (source.isBlank()) return
    val blocks = remember(source) {
        runCatching { RichTextParser.parse(source) }.getOrDefault(emptyList())
            .ifEmpty { listOf(RichBlock.Para(listOf(RichInline.Run(source)))) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RichBlock.Para -> InlineParagraph(block.inlines, style, maxLines, overflow)
                is RichBlock.Heading -> InlineParagraph(
                    block.inlines,
                    when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.merge(style),
                    maxLines, overflow
                )
                is RichBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = style, color = MaterialTheme.colorScheme.primary)
                            InlineParagraph(item, style, maxLines, overflow, Modifier.weight(1f))
                        }
                    }
                }
                is RichBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}.", style = style, color = MaterialTheme.colorScheme.primary)
                            InlineParagraph(item, style, maxLines, overflow, Modifier.weight(1f))
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
                            maxLines, overflow, Modifier.weight(1f)
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
                is RichBlock.DisplayMath -> LatexMathDisplay(block.latex, base = style)
                RichBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun InlineParagraph(
    inlines: List<RichInline>,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    modifier: Modifier = Modifier,
) {
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
    val metrics = rememberLatexMetrics()
    val density = LocalDensity.current
    val color = mathColor(style, LocalContentColor.current)
    val fallbackBackground = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { section ->
            when (section) {
                is RichInline.Math -> LatexMathDisplay(section.latex, base = style)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = section as List<RichInline>
                    // Measurement needs a composable scope, so placeholders are built before the
                    // annotated string rather than inside its builder.
                    val contents = ArrayList<InlineTextContent?>(items.size)
                    for (item in items) {
                        contents += if (item is RichInline.Math && !item.display) {
                            inlineMathContent(item.latex, style, metrics, density, color)
                        } else null
                    }
                    val inlineContent = mutableMapOf<String, InlineTextContent>()
                    val annotated = buildAnnotatedString {
                        var mathId = 0
                        var mathIndex = 0
                        items.forEach { inline ->
                            when (inline) {
                                is RichInline.Run -> withStyle(
                                    SpanStyle(
                                        fontWeight = if (inline.bold) FontWeight.SemiBold else null,
                                        fontStyle = if (inline.italic) FontStyle.Italic else null,
                                        fontFamily = if (inline.code) FontFamily.Monospace else null,
                                        background = if (inline.code) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Unspecified,
                                        textDecoration = if (inline.strike) TextDecoration.LineThrough else null
                                    )
                                ) { append(inline.text) }
                                is RichInline.Math -> {
                                    val fallback = LatexParser.plainText(inline.latex).ifEmpty { inline.latex }
                                    val content = contents.getOrNull(mathIndex)
                                    mathIndex++
                                    if (content != null) {
                                        val id = "math${mathId++}"
                                        appendInlineContent(id, fallback.ifEmpty { " " })
                                        inlineContent[id] = content
                                    } else {
                                        withStyle(
                                            SpanStyle(
                                                fontFamily = FontFamily.Serif,
                                                fontStyle = FontStyle.Italic,
                                                background = fallbackBackground
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
