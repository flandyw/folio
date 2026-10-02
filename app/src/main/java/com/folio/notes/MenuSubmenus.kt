package com.folio.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** How far right a submenu sits, clear of the parent menu's own width. */
private val SubmenuDx = 252.dp

/**
 * A menu row that opens a second-level menu beside itself. Material 3 has no submenu, so the child
 * menu is anchored to this row and offset clear of the parent menu; the caller dismisses its own
 * menu, so any action run from the submenu should close both.
 */
@Composable
internal fun SubmenuItem(
    label: String, icon: ImageVector, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true, content: @Composable () -> Unit
) {
    Box {
        DropdownMenuItem(
            text = { Text(label) },
            onClick = { onExpandedChange(!expanded) },
            leadingIcon = { Icon(icon, null) },
            trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open submenu") },
            enabled = enabled
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = DpOffset(SubmenuDx, 0.dp),
            modifier = Modifier.guardUiTouches()
        ) { content() }
    }
}

/** Section heading inside a menu or submenu. */
@Composable
internal fun MenuSectionHeader(label: String) {
    Text(label, Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}
