package com.folio.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Content footprints are cached independently of camera movement, including off-origin objects. */
@Composable
fun CanvasNavigator(page: NotePage, viewport: Rect, onNavigate: (Float, Float) -> Unit,
    onFit: (Rect) -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(page.id) { mutableStateOf(true) }
    val footprints = remember(page) {
        buildList {
            page.strokes.forEach { stroke ->
                if (stroke.points.isNotEmpty()) {
                    val pad = stroke.width.coerceAtLeast(1f) * 2f
                    add(Rect(stroke.points.minOf { it.x } - pad, stroke.points.minOf { it.y } - pad,
                        stroke.points.maxOf { it.x } + pad, stroke.points.maxOf { it.y } + pad))
                }
            }
            page.texts.forEach { add(Rect(it.x, it.y, it.x + it.width, it.y + InkRenderer.textHeight(it))) }
            page.images.forEach { add(Rect(it.x, it.y, it.x + it.width, it.y + it.height)) }
        }
    }
    val content = remember(footprints, page.width, page.height) {
        footprints.reduceOrNull { a, b -> a.union(b) } ?: Rect(0f, 0f, page.width, page.height)
    }
    val world = content.union(viewport).union(Rect(-1f, -1f, 1f, 1f))
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(modifier.guardUiTouches(), shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, shadowElevation = 3.dp) {
        Column(Modifier.width(if (expanded) 192.dp else 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Rounded.Map, if (expanded) "Hide canvas navigator" else "Show canvas navigator")
                }
                if (expanded) Text("Navigator", style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                Text("Tap map to move", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.padding(8.dp).fillMaxWidth().height(112.dp).clipToBounds()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .semantics { contentDescription = "Canvas overview. Shaded areas show content; outlined area is your current view." }
                    .pointerInput(world) {
                        detectTapGestures { point ->
                            val scale = minOf(size.width / world.width, size.height / world.height) * .88f
                            val offset = Offset((size.width - world.width * scale) / 2f, (size.height - world.height * scale) / 2f)
                            onNavigate(world.left + (point.x - offset.x) / scale, world.top + (point.y - offset.y) / scale)
                        }
                    }) {
                    val scale = minOf(size.width / world.width, size.height / world.height) * .88f
                    val offset = Offset((size.width - world.width * scale) / 2f, (size.height - world.height * scale) / 2f)
                    fun point(x: Float, y: Float) = offset + Offset((x - world.left) * scale, (y - world.top) * scale)
                    footprints.forEach {
                        drawRect(ink.copy(alpha = .55f), point(it.left, it.top),
                            Size((it.width * scale).coerceAtLeast(2f), (it.height * scale).coerceAtLeast(2f)))
                    }
                    val origin = point(0f, 0f)
                    drawLine(ink, origin - Offset(4.dp.toPx(), 0f), origin + Offset(4.dp.toPx(), 0f))
                    drawLine(ink, origin - Offset(0f, 4.dp.toPx()), origin + Offset(0f, 4.dp.toPx()))
                    if (viewport.width > 0f && viewport.height > 0f) {
                        val at = point(viewport.left, viewport.top)
                        val extent = Size(viewport.width * scale, viewport.height * scale)
                        drawRect(primary.copy(alpha = .12f), at, extent)
                        drawRect(primary, at, extent, style = Stroke(2.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onHome) { Icon(Icons.Rounded.Home, "Return to canvas origin at 100 percent") }
                    Text(if (viewport.width > 0f) "${((viewport.left + viewport.right) / 2).roundToInt()}, ${((viewport.top + viewport.bottom) / 2).roundToInt()}" else "0, 0",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onFit(content) }) { Icon(Icons.Rounded.FitScreen, "Fit all canvas content") }
                }
            }
        }
    }
}

private fun Rect.union(other: Rect) = Rect(minOf(left, other.left), minOf(top, other.top),
    maxOf(right, other.right), maxOf(bottom, other.bottom))
