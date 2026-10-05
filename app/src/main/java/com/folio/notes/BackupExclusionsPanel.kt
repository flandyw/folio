@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Exclusions are by identity, so renaming or moving an imported textbook keeps the choice. */
@Composable internal fun BackupExclusionsPanel(
    notes: List<Notebook>, excludedIds: Set<String>,
    onExcluded: (Set<String>, Boolean) -> Unit, onDismiss: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(notes, query) {
        notes.filter { it.title.contains(query.trim(), ignoreCase = true) }.sortedBy { it.title.lowercase() }
    }
    val count = notes.count { it.id in excludedIds }
    FolioPanel("Backup exclusions", onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text("Select notebooks to exclude from automatic and portable library backups. Changes save immediately. You can still export any notebook individually.", style = MaterialTheme.typography.bodyMedium)
            Text("$count of ${notes.size} notebooks excluded. Older restore points may still contain excluded notebooks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search notebooks") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear notebook search") } })
            if (visible.isEmpty()) {
                Text(if (notes.isEmpty()) "No notebooks yet." else "No notebooks match “${query.trim()}”.", Modifier.padding(vertical = FolioSpacing.dp12))
                if (query.isNotBlank()) TextButton({ query = "" }) { Text("Show all notebooks") }
            } else {
                if (query.isNotBlank()) Text("${visible.size} matching notebook${if (visible.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(visible, key = { it.id }) { note ->
                        val excluded = note.id in excludedIds
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(excluded, role = Role.Checkbox,
                                onValueChange = { onExcluded(setOf(note.id), it) }).padding(vertical = FolioSpacing.dp8),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
                        ) {
                            Checkbox(excluded, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(note.title.ifBlank { "Untitled notebook" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (excluded) "Excluded from library backups" else "Included in library backups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End), shapes = ButtonDefaults.shapes()) { Text("Done") }
        }
    }
}
