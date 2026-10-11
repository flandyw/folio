@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A dock around the real page editor. Controls do not overlay the writing surface. */
@Composable
internal fun ZoomWritingPane(
    height: Dp, status: WritingFollowStatus, hand: WritingHand,
    onResize: (Float) -> Unit, onAhead: () -> Unit, onNext: () -> Unit,
    onBack: () -> Unit, onPause: () -> Unit, onOptions: () -> Unit, onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Surface(Modifier.fillMaxWidth().height(height), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Box(Modifier.fillMaxWidth().height(16.dp).guardUiTouches()
                .semantics { contentDescription = "Drag to resize zoom pane" }
                .pointerInput(density) {
                    detectVerticalDragGestures { change, amount ->
                        change.consume()
                        onResize(with(density) { -amount.toDp().value })
                    }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).guardUiTouches(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (hand == WritingHand.RIGHT) Arrangement.Start else Arrangement.End) {
                Text("Zoom pane", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                PaneButton(Icons.AutoMirrored.Rounded.Undo, "Back view", status.canGoBack, onBack)
                PaneButton(Icons.AutoMirrored.Rounded.ArrowForward, "Reveal ahead on this line", true, onAhead)
                PaneButton(Icons.AutoMirrored.Rounded.KeyboardReturn, "Next line", true, onNext)
                PaneButton(if (status.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (status.paused) "Resume writing follow" else "Pause writing follow", true, onPause)
                PaneButton(Icons.Rounded.Tune, "Writing follow options", true, onOptions)
                PaneButton(Icons.Rounded.Close, "Close zoom pane", true, onClose)
            }
            Text(status.message, Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}

@Composable private fun PaneButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                                  enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
    }
}
