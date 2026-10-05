package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A tile for the few everyday toggles at the top of a popover. */
@Composable internal fun PopoverTile(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
    active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit
) {
    Surface(onClick, modifier, enabled = enabled, shape = FolioShapes.large,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = (if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            .let { if (enabled) it else it.copy(alpha = 0.38f) }) {
        Column(Modifier.padding(vertical = FolioSpacing.dp10, horizontal = FolioSpacing.dp4),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One full-width action row in a popover; at least 44dp tall so it stays easy to hit. */
@Composable internal fun PopoverRow(
    icon: ImageVector?, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(onClick, enabled = enabled, shape = FolioShapes.medium, color = Color.Transparent, contentColor = tint) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = FolioSpacing.dp8),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp)) else Spacer(Modifier.width(20.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A labelled run of rows; every group of a popover is visible at once instead of nested. */
@Composable internal fun PopoverGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
