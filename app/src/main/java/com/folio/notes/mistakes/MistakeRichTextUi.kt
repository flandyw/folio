package com.folio.notes.mistakes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders ExamTrack Markdown + LaTeX offline. Never throws: any parse failure falls
 * back to the raw source as plain text so a card always shows something readable.
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
                is RichBlock.DisplayMath -> MathDisplay(block.latex, style)
                RichBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun InlineParagraph(
    inlines: List<RichInline>,
    style: androidx.compose.ui.text.TextStyle,
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
                                    SpanStyle(
                                        fontWeight = if (inline.bold) FontWeight.SemiBold else null,
                                        fontStyle = if (inline.italic) FontStyle.Italic else null,
                                        fontFamily = if (inline.code) FontFamily.Monospace else null,
                                        background = if (inline.code) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Unspecified,
                                        textDecoration = if (inline.strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                    )
                                ) { append(inline.text) }
                                is RichInline.Math -> {
                                    val nodes = runCatching { MathParser.parse(inline.latex) }.getOrDefault(
                                        listOf(MathNode.Text(inline.latex))
                                    )
                                    if (!MathParser.isComplex(nodes)) {
                                        val unicode = MathParser.toUnicode(nodes)
                                        withStyle(
                                            SpanStyle(
                                                fontFamily = FontFamily.Serif,
                                                fontStyle = FontStyle.Italic,
                                                background = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)
                                            )
                                        ) { append(unicode) }
                                    } else {
                                        val id = "math${mathId++}"
                                        val fallback = MathParser.toUnicode(nodes)
                                        appendInlineContent(id, fallback)
                                        val hasFrac = nodes.any { it is MathNode.Frac || (it is MathNode.Group && MathParser.isComplex(it.children)) }
                                        val width = (fallback.length * 8 + 20).coerceIn(32, 220).sp
                                        val height = (if (hasFrac) 34 else 24).sp
                                        inlineContent[id] = InlineTextContent(
                                            Placeholder(
                                                width = width, height = height,
                                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                                            )
                                        ) { MathInline(nodes, style) }
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

@Composable
private fun MathInline(nodes: List<MathNode>, base: androidx.compose.ui.text.TextStyle) {
    val mathStyle = base.merge(
        androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic
        )
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        nodes.forEach { MathNodeView(it, mathStyle, display = false) }
    }
}

@Composable
fun MathDisplay(latex: String, base: androidx.compose.ui.text.TextStyle = LocalTextStyle.current) {
    val nodes = remember(latex) {
        runCatching { MathParser.parse(latex) }.getOrDefault(listOf(MathNode.Text(latex)))
    }
    val style = base.merge(
        androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = (base.fontSize.value.takeIf { it > 0 } ?: 16f).sp * 1.1f
        )
    )
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            nodes.forEach { MathNodeView(it, style, display = true) }
        }
    }
}

@Composable
private fun MathNodeView(node: MathNode, style: androidx.compose.ui.text.TextStyle, display: Boolean) {
    when (node) {
        is MathNode.Text -> {
            // Single letters read as variables (italic); longer runs stay upright-ish.
            val italic = node.value.length == 1 && node.value[0].isLetter()
            Text(node.value, style = style.merge(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal))
        }
        is MathNode.Sym -> Text(node.value, style = style.merge(fontStyle = FontStyle.Normal))
        is MathNode.Func -> Text(
            node.name + " ", // thin space after upright operators like sin, log
            style = style.merge(fontStyle = FontStyle.Normal, fontWeight = FontWeight.Medium)
        )
        MathNode.ThinSpace -> Spacer(Modifier.width(4.dp))
        MathNode.QuadSpace -> Spacer(Modifier.width(14.dp))
        MathNode.LineBreak -> if (display) Spacer(Modifier.width(8.dp)) else Spacer(Modifier.width(4.dp))
        is MathNode.Group -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { MathNodeView(it, style, display) }
        }
        is MathNode.Frac -> {
            val small = style.merge(fontSize = style.fontSize * 0.82f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    node.num.forEach { MathNodeView(it, small, display) }
                }
                HorizontalDivider(
                    Modifier.width(28.dp).padding(vertical = 1.dp),
                    thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    node.den.forEach { MathNodeView(it, small, display) }
                }
            }
            Spacer(Modifier.width(2.dp))
        }
        is MathNode.Sqrt -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                node.index?.let {
                    Column { it.forEach { n -> MathNodeView(n, style.merge(fontSize = style.fontSize * 0.65f), display) } }
                }
                Text("√", style = style.merge(fontStyle = FontStyle.Normal))
                Column(horizontalAlignment = Alignment.Start) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        node.body.forEach { MathNodeView(it, style, display) }
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
        }
        is MathNode.SupSub -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                node.base?.forEach { MathNodeView(it, style, display) }
                if (node.sup != null || node.sub != null) {
                    Column {
                        node.sup?.let {
                            Row { it.forEach { n -> MathNodeView(n, style.merge(fontSize = style.fontSize * 0.68f), display) } }
                        }
                        node.sub?.let {
                            Row { it.forEach { n -> MathNodeView(n, style.merge(fontSize = style.fontSize * 0.68f), display) } }
                        }
                    }
                }
            }
        }
    }
}
