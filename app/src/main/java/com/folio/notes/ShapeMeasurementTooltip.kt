@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Passive M3 tooltip with the Expressive pill shape, measured in screen dp rather than page units. */
@Composable internal fun ShapeMeasurementTooltip(state: State<ShapeMeasurement?>) {
    val measurement = state.value ?: return
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip(shape = MaterialTheme.shapes.extraLarge) {
                        Text(measurement.label, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                },
                state = rememberTooltipState(initialIsVisible = true, isPersistent = true),
                focusable = false,
                enableUserInput = false
            ) { Box(Modifier.size(1.dp)) }
        }
    ) { measurables, constraints ->
        // Only the anchor follows the page transform. Material sizes and positions the popup.
        val anchor = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val x = measurement.x.roundToInt().coerceIn(0, (constraints.maxWidth - anchor.width).coerceAtLeast(0))
        val y = measurement.y.roundToInt().coerceIn(0, (constraints.maxHeight - anchor.height).coerceAtLeast(0))
        layout(constraints.maxWidth, constraints.maxHeight) { anchor.place(x, y) }
    }
}
