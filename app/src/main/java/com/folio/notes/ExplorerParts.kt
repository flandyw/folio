@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/* Pieces shared by the library and Tablet files: tags, breadcrumbs, search, rows and dialogs. */

@Composable internal fun NotebookTagsPanel(notes: List<Notebook>, suggestions: List<String>, onDismiss: () -> Unit, onApply: (List<String>, List<String>) -> Unit) {
    val existing = remember(notes) { NotebookTags.normalize(notes.flatMap { it.tags }, Int.MAX_VALUE) }
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var input by rememberSaveable { mutableStateOf("") }
    val labels = NotebookTags.normalize(existing + suggestions + added, Int.MAX_VALUE)
    val pending = NotebookTags.normalize(added + listOf(input), Int.MAX_VALUE)
    val tooMany = notes.any { note -> NotebookTags.normalize(note.tags.filterNot { label -> removed.any { it.equals(label, true) } } + pending, Int.MAX_VALUE).size > NotebookTags.MAX_TAGS }
    fun commitTag() {
        val label = input.trim()
        if (label.isNotEmpty()) {
            added = NotebookTags.normalize(added + label, Int.MAX_VALUE)
            removed = removed.filterNot { it.equals(label, true) }
            input = ""
        }
    }
    FolioPanel(if (notes.size == 1) "Notebook tags" else "Tag ${notes.size} notebooks", onDismiss) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp24).weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text(if (notes.size == 1) "Labels make notebooks easier to find." else "Mixed tags stay on their original notebooks until you change them.", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                OutlinedTextField(input, { input = it.take(NotebookTags.MAX_LENGTH) }, Modifier.weight(1f), label = { Text("New tag") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitTag() }))
                FilledTonalIconButton(::commitTag, enabled = input.isNotBlank(), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add tag") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                labels.forEach { label ->
                    val count = notes.count { n -> n.tags.any { it.equals(label, true) } }
                    val active = label in added || (count > 0 && label !in removed)
                    FilterChip(active, {
                        when {
                            label in added -> { added = added - label; if (count > 0) removed = removed + label }
                            label in removed -> { removed = removed - label; added = added + label }
                            count == notes.size -> removed = removed + label
                            else -> added = added + label
                        }
                    }, { Text(if (count in 1 until notes.size && label !in added && label !in removed) "$label · Mixed" else label) },
                        leadingIcon = { Icon(if (active) Icons.Rounded.Check else Icons.Rounded.Sell, null, Modifier.size(18.dp)) })
                }
            }
            Text(if (tooMany) "A notebook has too many tags. Remove a tag before saving." else "Up to ${NotebookTags.MAX_TAGS} tags per notebook", style = MaterialTheme.typography.bodySmall, color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Button({ onApply(pending, removed) }, enabled = !tooMany, shapes = ButtonDefaults.shapes()) { Text("Save tags") }
        }
    }
}

@Composable internal fun ExplorerBreadcrumbs(crumbs: List<Pair<String, () -> Unit>>, drag: NotebookDragState? = null,
    folderIds: List<String?>? = null, onHoverOpen: ((String?) -> Unit)? = null) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().then(if (drag != null) Modifier.notebookDragScroll(drag, horizontal = true) { scroll.scrollBy(it) } else Modifier)
        .horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, (label, action) ->
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(action, shapes = ButtonDefaults.shapes(), modifier = if (folderIds != null) Modifier.notebookDropTarget(drag,
                NotebookDropDestination.Folder(folderIds[i], if (i == 0) "Unfiled" else label), onHoverOpen = onHoverOpen?.let { open -> { open(folderIds[i]) } }) else Modifier) { Text(label, maxLines = 1) }
        }
    }
}

@Composable internal fun ExplorerSearch(query: String, onQuery: (String) -> Unit, hint: String) {
    val focus = LocalFocusManager.current
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text(hint) }, singleLine = true, shape = FolioShapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery(""); focus.clearFocus() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } })
}

@Composable internal fun ExplorerPlace(label: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = FolioShapes.large, color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().semantics { selected = active }) {
        Row(Modifier.padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Icon(icon, null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun ExplorerRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Surface(onClick = onClick, modifier = modifier, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(icon, null, Modifier.padding(FolioSpacing.dp12).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) } },
            trailingContent = trailing, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

@Composable internal fun ExplorerEmpty(icon: ImageVector, title: String, detail: String, action: @Composable () -> Unit = {}) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(icon, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            action()
        }
    }
}

@Composable internal fun ExplorerSelectionBar(count: Int, total: Int, onAll: () -> Unit, onDone: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count of $total selected", Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall)
                TextButton(onAll, shapes = ButtonDefaults.shapes()) { Text(if (count == total && total > 0) "Deselect all" else "Select all") }
                TextButton(onDone, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable internal fun ExplorerConfirm(title: String, detail: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.guardUiTouches(),
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), title = { Text(title) }, text = { Text(detail) },
        dismissButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = { TextButton(onConfirm, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(action) } })
}
