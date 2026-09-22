package com.folio.notes.mistakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.folio.notes.math.KaTeXMath
import com.folio.notes.math.KaTeXPool
import com.folio.notes.math.rememberKaTeXInlineContent

internal fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale,
)

/** Native Markdown text with opaque math fragments rendered by bundled offline KaTeX. */
@Composable
fun RichText(
    source: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (source.isBlank()) return
    // Warm one renderer while the native text lays out, so the first formula
    // usually finds a loaded shell instead of paying for WebView + page load.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(source) { runCatching { KaTeXPool.prewarm(appContext) } }
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
                is RichBlock.DisplayMath -> KaTeXMath(block.latex, displayMode = true, textStyle = style)
                RichBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { section ->
            when (section) {
                is RichInline.Math -> KaTeXMath(section.latex, displayMode = true, textStyle = style)
                else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                    @Suppress("UNCHECKED_CAST")
                    val items = section as List<RichInline>
                    // Measurement needs a composable scope, so placeholders are built before the
                    // annotated string rather than inside its builder.
                    val contents = ArrayList<InlineTextContent?>(items.size)
                    for (item in items) {
                        contents += if (item is RichInline.Math && !item.display) {
                            rememberKaTeXInlineContent(item.latex, style, maxWidth)
                        } else null
                    }
                    val inlineContent = mutableMapOf<String, InlineTextContent>()
                    val annotated = buildAnnotatedString {
                        var mathId = 0
                        items.forEachIndexed { itemIndex, inline ->
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
                                    val id = "math${mathId++}"
                                    appendInlineContent(id, inline.latex.ifEmpty { " " })
                                    contents[itemIndex]?.let { inlineContent[id] = it }
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
