@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ChromeReaderMode
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Controls that accompany notebook selection in the Library, without a second browser. */
@Composable internal fun WorkspaceLibraryControls(
    purpose: PickerPurpose,
    state: FolioState,
    model: FolioViewModel,
    mode: CompanionMode,
    onModeChange: (CompanionMode) -> Unit,
    onOpenNotebook: (String) -> Unit,
) {
    when (purpose) {
        PickerPurpose.COMPANION -> {
            CompanionModeChips(mode, onModeChange)
            Text(WorkspacePicker.modeCaption(mode), style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth().toggleable(state.companionLinked, role = Role.Switch, onValueChange = model::setCompanionLinked),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Link pages", style = MaterialTheme.typography.bodyLarge)
                    Text("Turning a page in the editor also turns the companion.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.companionLinked, null)
            }
        }
        PickerPurpose.TABS -> {
            val openNotes = state.tabs.mapNotNull { tab -> state.notes.find { it.id == tab.notebookId && !it.hiddenFromLibrary } }
            if (openNotes.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            ) {
                openNotes.forEach { note ->
                    InputChip(
                        selected = note.id == state.activeId,
                        onClick = { onOpenNotebook(note.id) },
                        label = { Text(note.title.ifBlank { "Untitled" }, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            IconButton({ model.closeTab(note.id) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Close, "Close ${note.title.ifBlank { "Untitled" }}")
                            }
                        },
                    )
                }
            }
        }
        PickerPurpose.OPEN -> Unit
    }
}

/**
 * Everything the divider's long-press used to hide behind four menu rows: what the pane is, how it
 * follows the editor, and the three actions that are one tap each. The gestures the divider
 * supports are written out, since they are otherwise undiscoverable.
 */
@Composable internal fun SplitOptionsPanel(
    state: FolioState,
    model: FolioViewModel,
    onDismiss: () -> Unit
) {
    FolioPanel(title = "Pane options", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
        ) {
            Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            CompanionModeChips(state.companionMode, model::setCompanionMode)
            Text(WorkspacePicker.modeCaption(state.companionMode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(
                headlineContent = { Text(WorkspacePicker.flipCaption(state.editorOnRight)) },
                supportingContent = { Text(WorkspacePicker.sideHint(state.editorOnRight)) },
                leadingContent = { Icon(Icons.Rounded.SwapHoriz, null) },
                trailingContent = { Switch(state.editorOnRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().toggleable(state.editorOnRight, role = Role.Switch, onValueChange = model::setEditorOnRight)
            )
            ListItem(
                headlineContent = { Text("Link pages") },
                supportingContent = { Text("Turning a page in the editor also turns the companion.") },
                leadingContent = { Icon(if (state.companionLinked) Icons.Rounded.Link else Icons.Rounded.LinkOff, null) },
                trailingContent = { Switch(state.companionLinked, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().toggleable(state.companionLinked, role = Role.Switch, onValueChange = model::setCompanionLinked)
            )
            HorizontalDivider()
            FolioButtonGroup(Modifier.fillMaxWidth(), menuItems = listOf(
                "Close pane" to { onDismiss(); model.dismissCompanion() }
            )) {
                clickableItem(onClick = { model.swapPaneSides() }, label = "Swap panes", icon = {
                    Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp))
                })
                clickableItem(onClick = { model.setSplitFraction(SplitPanes.EQUAL) }, label = "Equal split", icon = {
                    Icon(Icons.Rounded.Balance, null, Modifier.size(18.dp))
                })
            }
            HorizontalDivider()
            Text("Gestures", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            GestureHint(Icons.Rounded.DragHandle, "Drag the divider", "Resize the two panes; it settles at 30/70, 50/50 or 70/30.")
            GestureHint(Icons.Rounded.SwapHoriz, "Tap the divider", "Swap which pane holds the editor, in either mode.")
            GestureHint(Icons.Rounded.TouchApp, "Double-tap the divider", "Return to an equal split.")
            GestureHint(Icons.Rounded.Visibility, "Long-press the divider", "Open these options.")
        }
    }
}

/** One discoverable gesture, with its icon, in the options panel. */
@Composable private fun GestureHint(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Size each mode to its content; narrow panels can move a whole chip to the next row. */
@Composable private fun CompanionModeChips(mode: CompanionMode, onModeChange: (CompanionMode) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)
    ) {
        FilterChip(
            selected = mode == CompanionMode.SPLIT,
            onClick = { onModeChange(CompanionMode.SPLIT) },
            label = { Text("Split", maxLines = 1, softWrap = false) },
            leadingIcon = { Icon(Icons.Rounded.VerticalSplit, null, Modifier.size(18.dp)) }
        )
        FilterChip(
            selected = mode == CompanionMode.REFERENCE,
            onClick = { onModeChange(CompanionMode.REFERENCE) },
            label = { Text("Reference", maxLines = 1, softWrap = false) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ChromeReaderMode, null, Modifier.size(18.dp)) }
        )
    }
}
