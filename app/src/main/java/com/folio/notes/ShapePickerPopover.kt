package com.folio.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The three shapes drawn most recently, newest first; the first is the one the shape button re-arms. */
object ShapeRecents {
    const val KEY = "shape.recent"
    const val COUNT = 3
    private val DEFAULT = listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)

    fun decode(value: String?): List<Tool> {
        val parsed = value?.split(',')?.mapNotNull { name -> Tool.entries.firstOrNull { it.name == name.trim() }?.takeIf { it in ShapePickerTools } }.orEmpty().distinct()
        return (parsed + DEFAULT.filter { it !in parsed }).take(COUNT)
    }

    fun encode(shapes: List<Tool>) = shapes.joinToString(",") { it.name }

    fun push(shapes: List<Tool>, picked: Tool) = (listOf(picked) + shapes.filter { it != picked }).take(COUNT)
}

/**
 * Shared by the shape button and its overflow entry; previews use the actual ink renderer.
 * [shapes] lets a caller drop a shape it has no use for — the music reader leaves out
 * [Tool.GRAPH], which draws a notebook's axes rather than an annotation.
 */
@Composable internal fun ShapePickerPopover(
    tool: Tool,
    shapes: List<Tool> = ShapePickerTools.toList(),
    onPick: (Tool) -> Unit,
    onDismiss: () -> Unit
) {
    FolioPopover(onDismiss, width = 320.dp) {
        Text("Shapes", style = MaterialTheme.typography.titleMedium)
        Text("Draw · choose a shape, then drag", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ShapeGrid(shapes) { value ->
            ShapeTile(shapeName(value), selected = tool == value, onClick = { onPick(value) }) { color ->
                val strokes = remember(value, color) {
                    if (value == Tool.GRAPH) {
                        InkStamps.make(InkStamps.Kind.ARROW, 0f, 18f, 72f, color.toArgb(), 3f) +
                            InkStamps.make(InkStamps.Kind.ARROW, 0f, 0f, 72f, color.toArgb(), 3f).map { stroke ->
                                stroke.copy(points = stroke.points.map { InkPoint(-18f + it.y, -it.x) })
                            }
                    } else listOf(Stroke(value, color.toArgb(), 3f,
                        listOf(InkPoint(-36f, if (value == Tool.LINE) 18f else -30f), InkPoint(36f, 30f))))
                }
                ShapePreview(strokes)
            }
        }
    }
}

private fun shapeName(tool: Tool) = when (tool) {
    Tool.LINE -> "Line"
    Tool.GRAPH -> "Graph axes"
    else -> tool.name.lowercase().replaceFirstChar(Char::uppercase)
}

@Composable private fun <T> ShapeGrid(items: List<T>, tile: @Composable RowScope.(T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                row.forEach { tile(it) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun RowScope.ShapeTile(
    label: String, selected: Boolean = false, onClick: () -> Unit,
    preview: @Composable (Color) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = Modifier.weight(1f).semantics { this.selected = selected },
        shape = FolioShapes.medium,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp8), horizontalAlignment = Alignment.CenterHorizontally) {
            preview(LocalContentColor.current)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp))
        }
    }
}

@Composable private fun ShapePreview(strokes: List<Stroke>) {
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val scale = minOf(size.width / 90f, size.height / 90f)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(size.width / 2f, size.height / 2f)
            native.scale(scale, scale)
            strokes.forEach { InkRenderer.stroke(native, it) }
            native.restore()
        }
    }
}
