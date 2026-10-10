@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** The same searchable destination chooser for one notebook or a shelf selection. */
@Composable internal fun LibraryMovePanel(
    title: String, folders: List<Folder>, currentFolder: String? = null, single: Boolean = false,
    onMove: (String?) -> Unit, onCreateAndMove: (String) -> Boolean, onDismiss: () -> Unit,
    rootLabel: String = "Unfiled", allowCreate: Boolean = true, excluded: Set<String> = emptySet()
) {
    val searchKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var newFolder by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    val visible = remember(folders, query, excluded) { folders.filter { it.id !in excluded && LibraryFolders.label(folders, it.id).contains(query.trim(), ignoreCase = true) }.sortedBy { LibraryFolders.label(folders, it.id).lowercase() } }
    FolioPanel(title, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find a folder") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchKeyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; searchKeyboard?.hide() }) { Icon(Icons.Rounded.Close, "Clear folder search") } })
            TextButton({ onMove(null) }, enabled = !single || currentFolder != null) {
                Icon(Icons.Rounded.FolderOff, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (single && currentFolder == null) "$rootLabel · current" else rootLabel)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            if (visible.isEmpty()) item {
                Text(if (folders.isEmpty()) "Create a folder below to organise these notebooks." else "No folders match.",
                    Modifier.padding(vertical = FolioSpacing.dp12), style = MaterialTheme.typography.bodySmall)
            }
            items(visible, key = { it.id }) { folder ->
                val here = single && currentFolder == folder.id
                ListItem(modifier = Modifier.clickable(enabled = !here, onClickLabel = "Move to ${folder.name}") { onMove(folder.id) },
                    headlineContent = { Text(folder.name) },
                    supportingContent = { if (folder.parentId != null) Text(LibraryFolders.label(folders, folder.parentId)) },
                    leadingContent = { Icon(if (here) Icons.Rounded.Check else Icons.Rounded.FolderOpen, null, tint = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { TextButton({ onMove(folder.id) }, enabled = !single || currentFolder != folder.id) {
                        Text(if (single && currentFolder == folder.id) "Current" else "Move here")
                    } })
            }
        }
        if (allowCreate) HorizontalDivider()
        if (allowCreate) Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (creating) {
                val newFolderFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { newFolderFocus.requestFocus() }
                OutlinedTextField(newFolder, { newFolder = it.take(120) }, Modifier.fillMaxWidth().focusRequester(newFolderFocus), singleLine = true, label = { Text("New folder name") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newFolder.isNotBlank() && onCreateAndMove(newFolder.trim())) onDismiss() }))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                    TextButton({ creating = false }) { Text("Cancel") }
                    Button({ if (onCreateAndMove(newFolder.trim())) onDismiss() }, enabled = newFolder.isNotBlank()) { Text("Create & move") }
                }
            } else TextButton({ creating = true }) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("New folder") }
        }
    }
}
