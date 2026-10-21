package com.folio.notes.editor.toolbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.folio.notes.ui.FolioSpacing

/** What a running clock in the toolbar is doing, which picks its icon tint. */
internal enum class SessionTone { RUNNING, PAUSED, ALERT }

/**
 * The one live clock in the editor toolbar: a quiet capsule with an icon and the time.
 * [reserve] sizes the time with tabular digits so ticking never reflows the tool strip.
 */
@Composable internal fun ToolbarSessionPill(
    icon: ImageVector,
    value: String,
    tone: SessionTone,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reserve: String? = "0:00:00",
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val minWidth = reserve?.let {
        with(LocalDensity.current) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() }
    } ?: 0.dp
    val iconTint = when (tone) {
        SessionTone.RUNNING -> colors.primary
        SessionTone.PAUSED -> colors.onSurfaceVariant
        SessionTone.ALERT -> colors.error
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(DockButtonHeight).semantics { contentDescription = description },
        shape = CircleShape,
        color = colors.surfaceContainerHighest,
        contentColor = if (tone == SessionTone.RUNNING) colors.onSurface else colors.onSurfaceVariant
    ) {
        Row(Modifier.padding(start = FolioSpacing.dp10, end = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
            Icon(icon, null, Modifier.size(16.dp), tint = iconTint)
            Text(value, Modifier.widthIn(min = minWidth), style = style, maxLines = 1, softWrap = false,
                color = if (tone == SessionTone.ALERT) colors.error else androidx.compose.ui.graphics.Color.Unspecified)
        }
    }
}
