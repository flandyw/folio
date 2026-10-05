@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ChromeReaderMode
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One panel for every "choose a document" moment in the workspace: open another notebook, open it
 * beside the editor, or manage what is already open. The mode toggle and the link-pages switch sit
 * above the list, so a companion is fully set up by the time the document is tapped.
 */
@Composable internal fun WorkspacePickerPanel(
    purpose: PickerPurpose,
    state: FolioState,
    model: FolioViewModel,
    onDismiss: () -> Unit,
    onBrowseLibrary: (PickerPurpose, CompanionMode) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(state.companionMode) }
    val keyboard = LocalSoftwareKeyboardController.current
    val openIds = remember(state.tabs) { state.tabs.map { it.notebookId }.toSet() }
    val folders = remember(state.folders) { state.folders.associate { it.id to it.name } }
    val sections = remember(state.notes, query, openIds, folders) {
        WorkspacePicker.sections(state.notes, query, openIds, folders)
    }

    fun pick(note: Notebook) {
        keyboard?.hide()
        when (purpose) {
            PickerPurpose.COMPANION -> model.showCompanion(note.id, mode)
            PickerPurpose.OPEN -> model.open(note.id)
            // Tapping an open document in the tab list only brings it forward; the trailing
            // button is what closes it, so a stray tap never loses a document.
            PickerPurpose.TABS -> model.open(note.id)
        }
        onDismiss()
    }

    FolioPanel(title = WorkspacePicker.title(purpose), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            if (purpose == PickerPurpose.COMPANION) {
                Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                CompanionModeChips(mode) { mode = it }
                Text(WorkspacePicker.modeCaption(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ListItem(
                    headlineContent = { Text("Link pages") },
                    supportingContent = { Text("Turning a page in the editor also turns the companion.") },
                    leadingContent = { Icon(if (state.companionLinked) Icons.Rounded.Link else Icons.Rounded.LinkOff, null) },
                    trailingContent = { Switch(state.companionLinked, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().toggleable(state.companionLinked, role = Role.Switch, onValueChange = model::setCompanionLinked)
                )
            }
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("Search documents") },
                placeholder = { Text("Title, subject or folder") },
                singleLine = true,
                shape = FolioShapes.large,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, "Clear search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
        }

        if (sections.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
            ) {
                if (state.loading) ContainedLoadingIndicator(Modifier.size(48.dp).semanticsLabel("Loading documents"))
                else Icon(
                    if (query.isBlank()) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.SearchOff,
                    null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    WorkspacePicker.emptyCaption(purpose, state.loading, query, state.notes.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.loading && query.isNotBlank()) {
                    FilledTonalButton({ query = "" }, shapes = ButtonDefaults.shapes()) { Text("Clear search") }
                }
                if (!state.loading && state.notes.isEmpty()) {
                    FilledTonalButton({ keyboard?.hide(); onDismiss(); onBrowseLibrary(purpose, mode) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text("Browse library")
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)
            ) {
                sections.forEach { section ->
                    item(key = "label-${section.label}") {
                        Text(
                            section.label.uppercase(),
                            Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp12, top = FolioSpacing.dp12, bottom = FolioSpacing.dp4),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(section.notes, key = { it.id }) { note ->
                        val badge = WorkspacePicker.badge(note.id, state.activeId, state.companion?.notebookId, openIds)
                        val pdf = note.pages.any { it.pdfIndex != null }
                        ListItem(
                            headlineContent = {
                                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(WorkspacePicker.subtitle(note), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Surface(
                                    shape = FolioShapes.small,
                                    color = if (pdf) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = if (pdf) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (pdf) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.MenuBook,
                                            if (pdf) "PDF document" else "Notebook", Modifier.size(22.dp)
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                when {
                                    purpose == PickerPurpose.TABS -> IconButton(
                                        { model.closeTab(note.id) },
                                        enabled = state.tabs.any { it.notebookId == note.id },
                                        shapes = IconButtonDefaults.shapes()
                                    ) { Icon(Icons.Rounded.Close, "Close ${note.title}") }
                                    badge != null -> SuggestionChip(
                                        onClick = { pick(note) },
                                        label = { Text(badge) },
                                        icon = {
                                            Icon(
                                                if (badge == "In this pane") Icons.Rounded.Link else Icons.Rounded.Edit,
                                                null, Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                    else -> Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (badge == "In this pane") MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth().clickable { pick(note) }
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, top = FolioSpacing.dp8, bottom = FolioSpacing.dp16),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton({ keyboard?.hide(); onDismiss(); onBrowseLibrary(purpose, mode) }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text("Browse library")
            }
            Spacer(Modifier.width(FolioSpacing.dp8))
            TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        }
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
            Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
        ) {
            Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            CompanionModeChips(state.companionMode, model::setCompanionMode)
            Text(WorkspacePicker.modeCaption(state.companionMode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(
                headlineContent = { Text(WorkspacePicker.flipCaption(state.editorOnRight)) },
                supportingContent = { Text(WorkspacePicker.sideHint(state.editorOnRight)) },
                leadingContent = { Icon(Icons.Rounded.SwapHoriz, null) },
                trailingContent = { Switch(state.editorOnRight, model::setEditorOnRight) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().semanticsLabel(WorkspacePicker.flipAction(state.editorOnRight))
            )
            ListItem(
                headlineContent = { Text("Link pages") },
                supportingContent = { Text("Turning a page in the editor also turns the companion.") },
                leadingContent = { Icon(if (state.companionLinked) Icons.Rounded.Link else Icons.Rounded.LinkOff, null) },
                trailingContent = { Switch(state.companionLinked, model::setCompanionLinked) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth()
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
