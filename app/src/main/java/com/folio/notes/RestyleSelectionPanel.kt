@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Restyles the selected strokes: colour, thickness, opacity and (for shapes) line style. A control
 * left alone keeps each stroke's own value, so [onApply] receives null for it.
 */
@Composable internal fun RestyleSelectionPanel(
    originals: List<Stroke>, quickColors: List<Int>,
    onDismiss: () -> Unit, onApply: (Int?, Float?, Float?, StrokeStyle?) -> Unit
) {
    val style = remember(originals) { InkGeometry.styleOf(originals) }
    var color by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var fade by remember { mutableStateOf(false) }
    var opacity by remember { mutableFloatStateOf(style?.opacity ?: 1f) }
    var lineStyle by remember { mutableStateOf<StrokeStyle?>(null) }
    val hasShape = remember(originals) { originals.any { it.tool in ShapeTools } }
    FolioPanel(title = "Restyle selection", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp32), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {

            Text("${originals.size} ${if (originals.size == 1) "stroke" else "strokes"}. Leave a control alone to keep it as it is.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(color == null, { color = null }, { Text("Keep") })
                Spacer(Modifier.width(FolioSpacing.dp6))
                quickColors.forEach { option -> InkColorDot(option, color == option, { color = option }, touch = 38.dp, dot = 26.dp, label = "Selection colour") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Thickness", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
                Slider(scale, { scale = it }, valueRange = 0.5f..3f, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.ROOT, "%.1fx", scale), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            if (hasShape) {
                Text("Line style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    FilterChip(lineStyle == null, { lineStyle = null }, { Text("Keep") })
                    FilterChip(lineStyle == StrokeStyle.SOLID, { lineStyle = StrokeStyle.SOLID }, { Text("Solid") })
                    FilterChip(lineStyle == StrokeStyle.DASHED, { lineStyle = StrokeStyle.DASHED }, { Text("Dashed") })
                    FilterChip(lineStyle == StrokeStyle.DOTTED, { lineStyle = StrokeStyle.DOTTED }, { Text("Dotted") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    listOf(StrokeStyle.LONG_DASH, StrokeStyle.DASH_DOT, StrokeStyle.DENSE_DOTS).forEach { style ->
                        FilterChip(lineStyle == style, { lineStyle = style }, { Text(style.label) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Opacity", style = MaterialTheme.typography.labelMedium)
                    Text("Off keeps each stroke's own fade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(fade, { fade = it })
            }
            if (fade) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Slider(opacity, { opacity = it }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ onApply(color, scale.takeIf { it != 1f }, opacity.takeIf { fade }, lineStyle) }, shapes = ButtonDefaults.shapes()) { Text("Apply") }
            }
        }
    }
}
