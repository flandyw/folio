@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.editor.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioViewModel
import com.folio.notes.model.Notebook
import com.folio.notes.model.displayTitle
import com.folio.notes.ui.FolioPanel

@Composable internal fun BatchPagesPanel(note: Notebook, model: FolioViewModel, onExport: () -> Unit, onDismiss: () -> Unit) {
    var selected by remember(note.id) { mutableStateOf<Set<String>>(emptySet()) }
    var target by remember { mutableStateOf("1") }
    val ids = note.pages.map { it.id }.filter { it in selected }
    FolioPanel("Select pages", onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().weight(1f, false)) {
            Text("${ids.size} of ${note.pages.size} pages selected. Move is one undo step; duplicates and deletions are undone page by page.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ selected = note.pages.map { it.id }.toSet() }, enabled = ids.size < note.pages.size) { Text("Select all") }
                TextButton({ selected = emptySet() }, enabled = ids.isNotEmpty()) { Text("Clear selection") }
                TextButton({
                    ids.forEach { id -> model.state.value.active?.pages?.indexOfFirst { it.id == id }?.takeIf { it >= 0 }?.let(model::duplicatePage) }
                    onDismiss()
                }, enabled = ids.isNotEmpty()) { Text("Duplicate") }
                TextButton({ model.exportSelection = note.pages.indices.filter { note.pages[it].id in selected }.toSet(); onDismiss(); onExport() }, enabled = ids.isNotEmpty()) { Text("Export") }
                TextButton({ ids.asReversed().forEach { id -> model.state.value.active?.pages?.indexOfFirst { it.id == id }?.takeIf { it >= 0 }?.let(model::deletePage) }; model.reportNotice("${com.folio.notes.model.countOf(ids.size, "page")} deleted · Undo brings ${if (ids.size == 1) "it" else "them"} back"); onDismiss() }, enabled = ids.isNotEmpty() && ids.size < note.pages.size) { Text("Delete pages") }
            }
            Row {
                OutlinedTextField(target, { target = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), label = { Text("Destination page position") }, supportingText = { Text("Enter a position from 1 to ${note.pages.size}. At least one page must remain in the notebook.") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
                TextButton({
                    val position = target.toIntOrNull()?.minus(1) ?: return@TextButton
                    model.movePages(note.id, ids.toSet(), position)
                    onDismiss()
                }, enabled = ids.isNotEmpty() && target.toIntOrNull()?.let { it in 1..note.pages.size } == true) { Text("Move") }
            }
            LazyColumn(Modifier.weight(1f, false), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(note.pages, key = { _, page -> page.id }) { index, page ->
                    Row(Modifier.fillMaxWidth().toggleable(page.id in selected, role = Role.Checkbox,
                        onValueChange = { checked -> selected = if (checked) selected + page.id else selected - page.id }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(page.id in selected, null)
                        Text(page.displayTitle(index), Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
