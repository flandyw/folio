package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BookmarkPanel(notes: List<Notebook>, onDismiss: () -> Unit, onOpen: (String, Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val entries = remember(notes, query) {
        notes.sortedByDescending { it.updated }.flatMap { note ->
            note.pages.withIndex().filter { (index, page) ->
                page.bookmarked && query.trim().split(Regex("\\s+")).all { word ->
                    "${note.title} ${page.displayTitle(index)} Page ${index + 1}".contains(word, true)
                }
            }.map { note to it }
        }
    }
    FolioPanel(title = "Bookmarked pages", onDismissRequest = onDismiss) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            label = { Text("Find a notebook or page") }, singleLine = true)
        Text("${entries.size} bookmarks across your library", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.isEmpty()) item {
                Text(if (query.isBlank()) "Bookmark pages from the editor or page browser to find them here." else "No bookmarks match your search.")
            }
            items(entries, key = { (note, page) -> "${note.id}/${page.value.id}" }) { (note, entry) ->
                Surface(onClick = { onOpen(note.id, entry.index) }, shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(entry.value.displayTitle(entry.index), style = MaterialTheme.typography.titleSmall)
                            Text("${note.title} · Page ${entry.index + 1}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
