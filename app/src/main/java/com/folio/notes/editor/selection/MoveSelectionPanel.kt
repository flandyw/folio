@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes.editor.selection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.page.PageThumbnail
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageFilter
import com.folio.notes.model.displayTitle
import com.folio.notes.model.organizePages
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

/** Picks the page a lasso selection moves to, searchable by page title, with a thumbnail per page. */
@Composable internal fun MoveSelectionPanel(
    note: Notebook, sourceId: String, picked: CanvasSelection, model: FolioViewModel,
    onDismiss: () -> Unit, onMoved: () -> Unit
) {
    val textKeyboard = LocalSoftwareKeyboardController.current
    var destinationQuery by rememberSaveable(sourceId) { mutableStateOf("") }
    val destinations = remember(note.pages, sourceId, destinationQuery) {
        organizePages(note.pages, destinationQuery, PageFilter.ALL).filter { it.value.id != sourceId }
    }
    FolioPanel(title = "Move ${picked.size} item${if (picked.size == 1) "" else "s"} to page", onDismissRequest = onDismiss) {
        OutlinedTextField(destinationQuery, { destinationQuery = it }, Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24),
            singleLine = true, label = { Text("Find destination page") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { textKeyboard?.hide() }),
            trailingIcon = { if (destinationQuery.isNotEmpty()) IconButton({ destinationQuery = ""; textKeyboard?.hide() }) { Icon(Icons.Rounded.Close, "Clear destination search") } })
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (destinations.isEmpty()) item {
                EmptyHint("No destination pages match.")
                TextButton({ destinationQuery = "" }) { Text("Show all destinations") }
            }
            items(destinations, key = { it.value.id }) { (index, destination) ->
                Surface(onClick = {
                    model.moveSelectionToPage(sourceId, destination.id, picked)
                    model.reportNotice("Moved to ${destination.displayTitle(index)}")
                    onMoved()
                }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        PageThumbnail(note.id, destination, model.thumbnails, Modifier.width(40.dp).height(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(destination.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Page ${index + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
