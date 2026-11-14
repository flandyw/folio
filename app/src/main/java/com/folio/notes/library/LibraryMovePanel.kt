@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.library

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.folio.notes.model.Folder
import com.folio.notes.storage.LibraryFolders
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioSpacing

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
    val nameError = LibraryFolders.nameError(folders, newFolder, null)
    var createFailed by remember { mutableStateOf(false) }
    fun createAndMove() {
        if (nameError != null) return
        if (onCreateAndMove(newFolder.trim())) onDismiss() else createFailed = true
    }
    val visible = remember(folders, query, excluded) { val words = query.trim().split(Regex("\\s+")); folders.filter { folder -> folder.id !in excluded && LibraryFolders.label(folders, folder.id).let { label -> words.all { label.contains(it, ignoreCase = true) } } }.sortedBy { LibraryFolders.label(folders, it.id).lowercase() } }
    FolioPanel(title, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find a folder") },
                leadingIcon = { Icon(FolioIcons.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchKeyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; searchKeyboard?.hide() }) { Icon(FolioIcons.Close, "Clear folder search") } })
            TextButton({ onMove(null) }, enabled = !single || currentFolder != null) {
                Icon(FolioIcons.FolderOff, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (single && currentFolder == null) "$rootLabel · current" else rootLabel)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            if (visible.isEmpty()) item {
                Text(if (query.isNotBlank()) "No folders match your search. Try a different name." else if (allowCreate) "Choose $rootLabel or create a folder below." else "No other folders available. Choose $rootLabel to move to the top level.",
                    Modifier.padding(vertical = FolioSpacing.dp12), style = MaterialTheme.typography.bodySmall)
            }
            items(visible, key = { it.id }) { folder ->
                val here = single && currentFolder == folder.id
                ListItem(modifier = Modifier.clickable(enabled = !here, onClickLabel = "Move to ${folder.name}") { onMove(folder.id) },
                    headlineContent = { Text(folder.name, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    supportingContent = { if (folder.parentId != null) Text(LibraryFolders.label(folders, folder.parentId)) },
                    leadingContent = { Icon(if (here) FolioIcons.Check else FolioIcons.FolderOpen, null, tint = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { if (here) Text("Current", style = MaterialTheme.typography.labelMedium) })
            }
        }
        if (allowCreate) HorizontalDivider()
        if (allowCreate) Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (creating) {
                val newFolderFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { newFolderFocus.requestFocus() }
                OutlinedTextField(newFolder, { newFolder = it; createFailed = false }, Modifier.fillMaxWidth().focusRequester(newFolderFocus), singleLine = true, label = { Text("New folder name") }, isError = (nameError != null && newFolder.isNotEmpty()) || createFailed,
                    supportingText = { Text(if (createFailed) "Could not create the folder. Your notebooks have not moved. Try again."
                        else nameError?.takeIf { newFolder.isNotEmpty() } ?: "Create at the top level and move the selected notebooks into it.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { createAndMove() }))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                    TextButton({ creating = false; newFolder = ""; createFailed = false }) { Text("Cancel new folder") }
                    Button({ createAndMove() }, enabled = nameError == null) { Text("Create & move") }
                }
            } else TextButton({ creating = true }) { Icon(FolioIcons.FolderAdd, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("New folder") }
        }
    }
}
