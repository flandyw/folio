@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorInteractionState
import com.folio.notes.editor.SelectionPlacementPanel
import com.folio.notes.editor.layout.DocumentInputCoordinator
import com.folio.notes.ink.recognition.HandwritingTidyPanel
import com.folio.notes.ink.recognition.InkConversionDraft
import com.folio.notes.ink.recognition.InkConversionPreview
import com.folio.notes.ink.shapes.ShapeCleanupPanel
import com.folio.notes.library.semanticsLabel
import com.folio.notes.model.Notebook
import com.folio.notes.model.displayTitle
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared actions always operate on the complete multi-page selection, never one page's subset. */
@Composable internal fun EditorDocumentSelection(note: Notebook, model: FolioViewModel, interaction: EditorInteractionState,
    input: DocumentInputCoordinator, quickColors: List<Int>, modifier: Modifier = Modifier) {
    val selection by interaction.documentSelection
    if (selection.isEmpty()) return
    var aligning by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var resizeFactor by remember { mutableFloatStateOf(1f) }
    var tidying by remember { mutableStateOf(false) }
    var tidyingWriting by remember { mutableStateOf(false) }
    var restyling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var conversionDraft by remember { mutableStateOf<InkConversionDraft?>(null) }
    var converting by remember { mutableStateOf(false) }
    val ink = selection.values.flatMap { it.strokes + it.texts.flatMap { box -> box.stickyInk } }
    val count = selection.values.sumOf { it.strokes.size + it.texts.size + it.images.size }
    val pages = selection.size
    // Same light, outlined pill as the single-page lasso menu, so both selections look like one feature.
    Surface(modifier.semanticsLabel("Selection options"), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            Text(if (pages > 1) "$count selected · $pages pages" else "$count selected",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VerticalDivider(Modifier.padding(start = FolioSpacing.dp8, end = FolioSpacing.dp4).height(20.dp))
            SelectionAction(Icons.Rounded.ContentCopy, "Copy selection", onClick = input::copySelection)
            SelectionAction(Icons.Rounded.DynamicFeed, "Duplicate selection", onClick = input::duplicateSelection)
            if (ink.isNotEmpty()) SelectionAction(Icons.Rounded.Palette, "Style selection") { restyling = true }
            SelectionAction(Icons.Rounded.DeleteOutline, "Delete selection", destructive = true, onClick = input::deleteSelection)
            Box {
                SelectionAction(Icons.Rounded.MoreHoriz, "More selection options") { more = true }
                FolioMenuPopover(more, { more = false }, title = if (pages > 1) "$pages pages selected" else "Selection") {
                    FolioMenuItem({ Text("Align and distribute…") }, { more = false; aligning = true })
                    if (ink.isNotEmpty()) {
                        FolioMenuItem({ Text("Tidy up shapes…") }, { more = false; tidying = true })
                        FolioMenuItem({ Text("Tidy up handwriting…") }, { more = false; tidyingWriting = true })
                        FolioMenuItem({ Text(if (converting) "Reading handwriting…" else "Convert to text") }, {
                            more = false
                            if (!converting) {
                                converting = true
                                scope.launch {
                                    try { conversionDraft = input.prepareTextConversion() }
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
            SelectionAction(Icons.Rounded.Close, "Deselect", onClick = input::clearSelection)
        }
    }
    conversionDraft?.let { draft -> InkConversionPreview(draft, model, { conversionDraft = null }) { conversionDraft = null; input.clearSelection() } }
    if (aligning) SelectionPlacementPanel(note, selection, model, { aligning = false }, input::clearSelection)
    if (tidying) ShapeCleanupPanel(note, model, selection, { tidying = false }, input::clearSelection)
    if (tidyingWriting) HandwritingTidyPanel(note, model, selection, { tidyingWriting = false }, input::clearSelection)
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
