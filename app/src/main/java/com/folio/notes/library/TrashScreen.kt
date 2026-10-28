@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.text.DateFormat
import java.util.Date

@Composable internal fun TrashScreen(state: FolioState, model: FolioViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    val available = state.trash.map { it.notebook.id }.filterNot { it in state.trashBusyIds }.toSet()
    val enabled = !state.loading && !state.loadFailed && !state.saveFailed
    Column(modifier) {
        FolioScreenHeading("Trash", leading = {
            IconButton(onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Library") }
        })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = FolioDestinationInset,
            end = FolioDestinationInset, bottom = FolioDestinationInset), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            item {
                Text("Notebooks stay here until you restore or delete them. Trash is not backed up.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.trash.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    OutlinedButton({ model.restoreNotebooks(available) }, enabled = enabled && available.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                        Text("Restore all")
                    }
                    TextButton({ deleting = available }, enabled = enabled && available.isNotEmpty(), shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Empty Trash") }
                }
            }
            if (state.trash.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.loading) "Loading Trash…" else "Trash is empty", style = MaterialTheme.typography.titleMedium)
                    Text("Notebooks you delete will appear here.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.trash, key = { it.notebook.id }) { entry ->
                val note = entry.notebook
                val busy = note.id in state.trashBusyIds
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val date = remember(entry.deletedAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.deletedAt)) }
                        Text("${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"} · Deleted $date",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            TextButton({ model.restoreNotebooks(setOf(note.id)) }, enabled = enabled && !busy, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.RestoreFromTrash, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Restore")
                            }
                            TextButton({ deleting = setOf(note.id) }, enabled = enabled && !busy, shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Icon(Icons.Rounded.DeleteForever, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Delete forever")
                            }
                            if (busy) Text("Saving…", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    deleting?.let { ids ->
        val selected = state.trash.filter { it.notebook.id in ids }
        AlertDialog(onDismissRequest = { deleting = null }, modifier = Modifier.guardUiTouches(),
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            title = { Text(if (selected.size == 1) "Delete “${selected.single().notebook.title}” forever?" else "Delete ${selected.size} notebooks forever?") },
            text = { Text("This permanently deletes the notebooks and all their pages, ink and attached files from this device. You cannot undo this.") },
            dismissButton = { TextButton({ deleting = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
            confirmButton = {
                TextButton({ model.deleteTrashedNotebooks(ids); deleting = null }, enabled = enabled && ids.any { it in available },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text("Delete forever") }
            })
    }
}
