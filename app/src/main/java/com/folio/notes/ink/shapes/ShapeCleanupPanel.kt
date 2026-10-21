@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.ink.shapes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageLayers
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A preview keeps cleanup deliberate, including when live hold recognition is switched off. */
@Composable internal fun ShapeCleanupPanel(
    note: Notebook, model: FolioViewModel, selected: Map<String, CanvasSelection>,
    onDismiss: () -> Unit, onApplied: () -> Unit,
) {
    val prefs = rememberPrefs()
    // Freeze the pages and selection for this preview. The commit checks the same revisions.
    val pages = remember { note.pages.filter { it.id in selected }.associateBy { it.id } }
    val picked = remember { selected.toMap() }
    var options by remember { mutableStateOf(ShapeAssistOptions.decode(prefs.getString(AppPrefs.SHAPE_ASSIST_OPTIONS, null))) }
    val prepared by produceState<Pair<ShapeAssistOptions, Map<String, ShapeSelectionCleanup.Plan>>?>(null, options) {
        value = options to withContext(Dispatchers.Default) {
            pages.mapValues { (id, page) -> ShapeSelectionCleanup.plan(page, picked.getValue(id), options) }
        }
    }
    val results = prepared?.takeIf { it.first == options }?.second
    val count = results?.values?.sumOf { it.shapes } ?: 0
    val sources = results?.values?.sumOf { it.changed } ?: 0
    var after by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    FolioPanel("Tidy up shapes", onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                results == null -> "Finding shapes…"
                count == 0 -> "No shapes to tidy with these options."
                else -> "$count ${if (count == 1) "shape" else "shapes"} ready to tidy" +
                    if (sources > count) " (from $sources strokes)." else "."
            }, style = MaterialTheme.typography.titleMedium)
            Text("Preview the selected ink before applying. Boxes drawn line by line, outlines drawn in pieces and arrows with a separate head become one shape. Handwriting and anything not recognised stay as drawn. Colour, line weight, transparency and layers are preserved.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!after, { after = false }, label = { Text("Original") })
                FilterChip(after, { after = true }, label = { Text("Tidied") })
            }
            pages.forEach { (id, page) ->
                if (pages.size > 1) Text(page.title.ifBlank { "Page ${note.pages.indexOfFirst { it.id == id } + 1}" }, style = MaterialTheme.typography.labelLarge)
                val selection = if (after) results?.getValue(id)?.selection ?: picked.getValue(id) else picked.getValue(id)
                // Show selected sticky ink in page coordinates, exactly where it belongs.
                val ink = selection.strokes + selection.texts.flatMap { box -> box.stickyInk.map { StickyNotes.toPage(box, it) } }
                Surface(shape = FolioShapes.medium, color = Color.White) {
                    ShapeInkPreview(ink, Modifier.fillMaxWidth().height(180.dp))
                }
            }
            ShapeAssistOptionControls(options, { options = it; failure = null })
            if (failure != null) Text(failure!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onDismiss, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            FilledTonalButton({
                val edits = results.orEmpty().mapNotNull { (id, result) -> PageJournal.diff(pages.getValue(id).content(), result.content)?.let { id to it } }.toMap()
                val live = model.state.value.active
                val stale = live?.id != note.id || pages.any { (id, old) ->
                    val current = live?.pages?.firstOrNull { it.id == id }
                    current == null || current.revision != old.revision || !current.loaded ||
                        picked.getValue(id).strokes.any { !PageLayers.editable(current.layers, it.layer) } ||
                        picked.getValue(id).texts.any { !PageLayers.editable(current.layers, it.layer) }
                }
                if (!stale && edits.isNotEmpty() && model.commitDocumentEdits(note.id, edits, pages.mapValues { it.value.revision })) {
                    onApplied(); onDismiss()
                } else failure = "The selection changed. Close this preview and select the ink again."
            }, modifier = Modifier.weight(1f), enabled = count > 0 && failure == null, shapes = ButtonDefaults.shapes()) { Text("Tidy $count") }
        }
    }
}

