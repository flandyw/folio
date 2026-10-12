@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared actions always operate on the complete multi-page selection, never one page's subset. */
@Composable internal fun EditorDocumentSelection(note: Notebook, model: FolioViewModel, interaction: EditorInteractionState,
    input: DocumentInputCoordinator, quickColors: List<Int>, modifier: Modifier = Modifier) {
    val selection by interaction.documentSelection
    if (selection.isEmpty()) return
    var moving by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var restyling by remember { mutableStateOf(false) }
    val count = selection.values.sumOf { it.strokes.size + it.texts.size + it.images.size }
    HorizontalFloatingToolbar(expanded = true, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("$count · ${selection.size}p", style = MaterialTheme.typography.labelMedium)
        IconButton(input::copySelection) { Icon(Icons.Rounded.ContentCopy, "Copy document selection") }
        Box {
            IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Document selection options") }
            FolioMenuPopover(more, { more = false }, title = "${selection.size} pages selected") {
                FolioMenuItem({ Text("Duplicate selection") }, { more = false; input.duplicateSelection() })
                if (selection.values.any { it.strokes.isNotEmpty() }) FolioMenuItem({ Text("Restyle ink") }, { more = false; restyling = true })
                FolioMenuItem({ Text("Move to a page") }, { more = false; moving = true })
            }
        }
        IconButton(input::deleteSelection) { Icon(Icons.Rounded.DeleteOutline, "Delete document selection") }
        IconButton(input::clearSelection) { Icon(Icons.Rounded.Close, "Clear document selection") }
    }
    if (restyling) RestyleSelectionPanel(selection.values.flatMap { it.strokes }, quickColors,
        onDismiss = { restyling = false }, onApply = { color, width, opacity, style -> input.restyleSelection(color, width, opacity, style); restyling = false })
    if (moving) FolioPanel("Move document selection", { moving = false }) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(note.pages.filterNot { it.infinite }) { index, page ->
                FilledTonalButton({ if (!page.loaded) model.loadPage(page.id) else if (input.moveToPage(page.id)) moving = false },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(page.displayTitle(index) + if (!page.loaded) " · tap to load" else "")
                }
            }
        }
    }
}
