package com.folio.notes.mistakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.math.KaTeXMath
import com.folio.notes.math.KaTeXDocument

internal fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale,
)

/** Math-rich documents share one offline layout pass; prose-only documents use native text. */
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
    val hasMath = remember(blocks) { RichTextParser.containsMath(source) }
    if (hasMath) {
        val document = remember(blocks) { RichTextDocument.encode(blocks) }
        KaTeXDocument(document, source, modifier, style)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
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
                is RichBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Text("•", style = style, color = MaterialTheme.colorScheme.primary)
                            InlineParagraph(item, style, maxLines, overflow, Modifier.weight(1f))
                        }
                    }
                }
                is RichBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Text("${index + 1}.", style = style, color = MaterialTheme.colorScheme.primary)
                            InlineParagraph(item, style, maxLines, overflow, Modifier.weight(1f))
                        }
                    }
                }
                is RichBlock.Quote -> Surface(
                    shape = FolioShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(Modifier.padding(FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
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
                    shape = FolioShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        block.code, Modifier.fillMaxWidth().padding(FolioSpacing.dp12),
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
    val annotated = buildAnnotatedString {
        inlines.forEach { inline ->
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
                is RichInline.Math -> append(inline.latex)
                RichInline.Break -> append("\n")
            }
        }
    }
    Text(annotated, modifier.fillMaxWidth(), style = style, maxLines = maxLines, overflow = overflow)
}
