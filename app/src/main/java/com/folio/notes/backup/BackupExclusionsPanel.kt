@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.backup

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.folio.notes.library.matchesQuery
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

/** Exclusions are by identity, so renaming or moving an imported textbook keeps the choice. */
@Composable internal fun BackupExclusionsPanel(
    notes: List<Notebook>, excludedIds: Set<String>,
    onExcluded: (Set<String>, Boolean) -> Unit, onDismiss: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(notes, query) {
        notes.filter { matchesQuery(it, query) }.sortedBy { it.title.lowercase() }
    }
    val impact = remember(notes, excludedIds) { BackupExclusionImpact.of(notes, excludedIds) }
    FolioPanel("Backup exclusions", onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            // One scrolling list, so the rows stay reachable when the keyboard leaves little room.
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item(key = "header") {
                    Column(Modifier.padding(bottom = if (visible.isEmpty()) 0.dp else FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Text("Select notebooks to exclude from automatic and portable library backups. Changes save immediately. You can still export any notebook individually.", style = MaterialTheme.typography.bodyMedium)
                        if (impact.nothingProtected) Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                            Row(Modifier.padding(FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                Icon(FolioIcons.Warning, contentDescription = null)
                                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                    Text("Every notebook is excluded", style = MaterialTheme.typography.titleSmall)
                                    Text("Library backups will save none of your notebooks. Include at least one, or export notebooks individually.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else if (notes.isNotEmpty()) Text(impact.protectedSummary, style = MaterialTheme.typography.titleSmall)
                        Text("${impact.excludedSummary} Older restore points may still contain excluded notebooks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search notebooks") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; keyboard?.hide() }) { Icon(FolioIcons.Close, "Clear notebook search") } })
                        if (visible.isEmpty()) {
                            Text(if (notes.isEmpty()) "No notebooks yet." else "No notebooks match “${query.trim()}”.", Modifier.padding(vertical = FolioSpacing.dp12))
                            if (query.isNotBlank()) TextButton({ query = "" }) { Text("Show all notebooks") }
                        } else {
                            if (query.isNotBlank()) Text("${visible.size} matching notebook${if (visible.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
                            val visibleIds = visible.map { it.id }.toSet()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                TextButton({ onExcluded(visibleIds, true) }, enabled = visible.any { it.id !in excludedIds }) { Text("Exclude shown") }
                                TextButton({ onExcluded(visibleIds, false) }, enabled = visible.any { it.id in excludedIds }) { Text("Include shown") }
                            }
                        }
                    }
                }
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
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End), shapes = ButtonDefaults.shapes()) { Text("Done") }
        }
    }
}
