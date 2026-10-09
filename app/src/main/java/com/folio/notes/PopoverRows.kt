package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An action list anchored to its trigger, leaving the surrounding page visible. */
@Composable internal fun FolioActionPopover(
    title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    FolioPopover(onDismiss) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        content()
    }
}

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

/** The same inset action rows in both the editor palettes and the app's menus. */
@Composable internal fun PopoverRow(
    icon: ImageVector?, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit
) {
    FolioMenuItem(text = { Text(label) }, onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, null) } }, enabled = enabled, destructive = destructive)
}

/** A labelled run of rows; every group of a popover is visible at once instead of nested. */
@Composable internal fun PopoverGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
