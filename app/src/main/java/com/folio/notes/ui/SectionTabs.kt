package com.folio.notes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The sections inside one destination (Progress, Planner): a strip of pills directly under the
 * heading, on the heading's inset, scrolling sideways when a pane is too narrow for all of them.
 * It never floats over content, so lists need no extra bottom room for it.
 */
@Composable
fun FolioSectionTabs(tabs: List<Pair<String, ImageVector>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = FolioDestinationInset, end = FolioDestinationInset, bottom = FolioSpacing.dp12).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
    ) {
        tabs.forEach { (label, icon) ->
            val active = label == selected
            val container by animateColorAsState(if (active) scheme.secondaryContainer else scheme.surfaceContainerLow, label = "sectionTab")
            Surface(
                Modifier.clip(CircleShape).selectable(active, onClick = { onSelect(label) }, role = Role.Tab),
                shape = CircleShape, color = container,
                contentColor = if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = FolioSpacing.dp16),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                ) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}
