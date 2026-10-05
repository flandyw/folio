@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The same searchable destination chooser for one notebook or a shelf selection. */
@Composable internal fun LibraryMovePanel(
    title: String, folders: List<Folder>, currentFolder: String? = null, single: Boolean = false,
    onMove: (String?) -> Unit, onCreateAndMove: (String) -> Boolean, onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var newFolder by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    val visible = remember(folders, query) { folders.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    FolioPanel(title, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find a folder") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear folder search") } })
            TextButton({ onMove(null) }, enabled = !single || currentFolder != null) {
                Icon(Icons.Rounded.FolderOff, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (single && currentFolder == null) "Unfiled · current" else "Unfiled")
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            if (visible.isEmpty()) item {
                Text(if (folders.isEmpty()) "Create a folder below to organise these notebooks." else "No folders match.",
                    Modifier.padding(vertical = FolioSpacing.dp12), style = MaterialTheme.typography.bodySmall)
            }
            items(visible, key = { it.id }) { folder ->
                ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(Icons.Rounded.FolderOpen, null) },
                    trailingContent = { TextButton({ onMove(folder.id) }, enabled = !single || currentFolder != folder.id) {
                        Text(if (single && currentFolder == folder.id) "Current" else "Move here")
                    } })
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (creating) {
                OutlinedTextField(newFolder, { newFolder = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("New folder name") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                    TextButton({ creating = false }) { Text("Cancel") }
                    Button({ if (onCreateAndMove(newFolder.trim())) onDismiss() }, enabled = newFolder.isNotBlank()) { Text("Create & move") }
                }
            } else TextButton({ creating = true }) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("New folder") }
        }
    }
}
