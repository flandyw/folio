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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Shared actions always operate on the complete multi-page selection, never one page's subset. */
@Composable internal fun EditorDocumentSelection(note: Notebook, model: FolioViewModel, interaction: EditorInteractionState,
    input: DocumentInputCoordinator, quickColors: List<Int>, modifier: Modifier = Modifier) {
    val selection by interaction.documentSelection
    if (selection.isEmpty()) return
    var moving by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var resizeFactor by remember { mutableFloatStateOf(1f) }
    var tidying by remember { mutableStateOf(false) }
    var restyling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var converting by remember { mutableStateOf(false) }
    val ink = selection.values.flatMap { it.strokes + it.texts.flatMap { box -> box.stickyInk } }
    val count = selection.values.sumOf { it.strokes.size + it.texts.size + it.images.size }
    HorizontalFloatingToolbar(expanded = true, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("$count · ${selection.size}p", style = MaterialTheme.typography.labelMedium)
        IconButton(input::copySelection) { Icon(Icons.Rounded.ContentCopy, "Copy document selection") }
        Box {
            IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Document selection options") }
            FolioMenuPopover(more, { more = false }, title = "${selection.size} pages selected") {
                FolioMenuItem({ Text("Duplicate selection") }, { more = false; input.duplicateSelection() })
                if (ink.isNotEmpty()) {
                    FolioMenuItem({ Text("Tidy up shapes…") }, { more = false; tidying = true })
                    FolioMenuItem({ Text("Restyle ink") }, { more = false; restyling = true })
                    FolioMenuItem({ Text(if (converting) "Reading handwriting…" else "Convert to text") }, {
                        more = false
                        if (!converting) {
                            converting = true
                            scope.launch {
                                try { input.convertSelectionToText() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { model.reportError("Couldn't read this handwriting: ${e.message.orEmpty()}") }
                                finally { converting = false }
                            }
                        }
                    })
                }
                FolioMenuItem({ Text("Rotate 90°") }, { more = false; input.transformSelection(1f, 90f) })
                FolioMenuItem({ Text("Resize selection") }, { more = false; resizing = true })
                FolioMenuItem({ Text("Move to a page") }, { more = false; moving = true })
            }
        }
        IconButton(input::deleteSelection) { Icon(Icons.Rounded.DeleteOutline, "Delete document selection") }
        IconButton(input::clearSelection) { Icon(Icons.Rounded.Close, "Clear document selection") }
    }
    if (tidying) ShapeCleanupPanel(note, model, selection, { tidying = false }, input::clearSelection)
    if (restyling) RestyleSelectionPanel(ink, quickColors,
        onDismiss = { restyling = false }, onApply = { color, width, opacity, style -> input.restyleSelection(color, width, opacity, style); restyling = false })
    if (resizing) FolioPanel("Resize document selection", { resizing = false }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${(resizeFactor * 100).toInt()}%")
            Slider(resizeFactor, { resizeFactor = it }, valueRange = .2f..3f)
            FilledTonalButton({ if (input.transformSelection(resizeFactor, 0f)) resizing = false }, Modifier.fillMaxWidth()) { Text("Apply") }
        }
    }
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
