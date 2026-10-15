@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun BatchPagesPanel(note: Notebook, model: FolioViewModel, onExport: () -> Unit, onDismiss: () -> Unit) {
    var selected by remember(note.id) { mutableStateOf<Set<String>>(emptySet()) }
    var target by remember { mutableStateOf("1") }
    var confirmDelete by remember { mutableStateOf(false) }
    val ids = note.pages.map { it.id }.filter { it in selected }
    FolioPanel("Select pages", onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().weight(1f, false)) {
            Text("${ids.size} selected. Move is one undo step; duplicates and deletions are undone page by page.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ selected = note.pages.map { it.id }.toSet() }) { Text("Select all") }
                TextButton({ selected = emptySet() }) { Text("Clear") }
                TextButton({
                    ids.forEach { id -> model.state.value.active?.pages?.indexOfFirst { it.id == id }?.takeIf { it >= 0 }?.let(model::duplicatePage) }
                    onDismiss()
                }, enabled = ids.isNotEmpty()) { Text("Duplicate") }
                TextButton({ model.exportSelection = note.pages.indices.filter { note.pages[it].id in selected }.toSet(); onDismiss(); onExport() }, enabled = ids.isNotEmpty()) { Text("Export") }
                TextButton({ confirmDelete = true }, enabled = ids.isNotEmpty() && ids.size < note.pages.size) { Text("Delete") }
            }
            Row {
                OutlinedTextField(target, { target = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), label = { Text("Move block to position") }, singleLine = true)
                TextButton({
                    val position = target.toIntOrNull()?.minus(1) ?: return@TextButton
                    model.movePages(note.id, ids.toSet(), position)
                    onDismiss()
                }, enabled = ids.isNotEmpty() && target.toIntOrNull()?.let { it in 1..note.pages.size } == true) { Text("Move") }
            }
            LazyColumn(Modifier.weight(1f, false), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(note.pages, key = { _, page -> page.id }) { index, page ->
                    Row { Checkbox(page.id in selected, { checked -> selected = if (checked) selected + page.id else selected - page.id }); TextButton({ selected = if (page.id in selected) selected - page.id else selected + page.id }) { Text(page.displayTitle(index)) } }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${ids.size} pages?") }, text = { Text("You can restore them with Undo while this notebook is open.") },
        confirmButton = { TextButton({ ids.asReversed().forEach { id -> model.state.value.active?.pages?.indexOfFirst { it.id == id }?.takeIf { it >= 0 }?.let(model::deletePage) }; confirmDelete = false; onDismiss() }) { Text("Delete pages") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } })
}
