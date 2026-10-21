@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.ink.recognition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioViewModel
import com.folio.notes.ink.shapes.ShapeInkPreview
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageLayers
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Previews and applies a gentle handwriting clean-up on the selected ink: lines of writing are levelled and
 * uneven gaps eased. Strokes only slide, so it is still the writer's hand; one undo restores everything.
 */
@Composable internal fun HandwritingTidyPanel(
    note: Notebook, model: FolioViewModel, selected: Map<String, CanvasSelection>,
    onDismiss: () -> Unit, onApplied: () -> Unit,
) {
    // Freeze the pages and selection for this preview. The commit checks the same revisions.
    val pages = remember { note.pages.filter { it.id in selected }.associateBy { it.id } }
    val picked = remember { selected.toMap() }
    var options by remember { mutableStateOf(HandwritingTidy.Options()) }
    val prepared by produceState<Pair<HandwritingTidy.Options, Map<String, HandwritingSelectionTidy.Plan>>?>(null, options) {
        value = options to withContext(Dispatchers.Default) {
            pages.mapValues { (id, page) -> HandwritingSelectionTidy.plan(page, picked.getValue(id), options) }
        }
    }
    val results = prepared?.takeIf { it.first == options }?.second
    val moved = results?.values?.sumOf { it.moved } ?: 0
    val lines = results?.values?.sumOf { it.lines } ?: 0
    var after by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    FolioPanel("Tidy up handwriting", onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                results == null -> "Reading the writing…"
                moved == 0 -> "Nothing to tidy: this writing is already level and evenly spaced, or there isn't enough of it."
                else -> "$moved ${if (moved == 1) "stroke" else "strokes"} ready to move, across $lines ${if (lines == 1) "line" else "lines"}."
            }, style = MaterialTheme.typography.titleMedium)
            Text("Lines that drift up or down are levelled, and uneven gaps between letters and words are eased halfway. Strokes only slide, so your letters keep their shape, size and slant. Needs at least four strokes in a line; underlines, shapes and highlighter are left alone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!after, { after = false }, label = { Text("Original") })
                FilterChip(after, { after = true }, label = { Text("Tidied") })
            }
            pages.forEach { (id, page) ->
                if (pages.size > 1) Text(page.title.ifBlank { "Page ${note.pages.indexOfFirst { it.id == id } + 1}" }, style = MaterialTheme.typography.labelLarge)
                val selection = if (after) results?.getValue(id)?.selection ?: picked.getValue(id) else picked.getValue(id)
                Surface(shape = FolioShapes.medium, color = Color.White) {
                    ShapeInkPreview(selection.strokes, Modifier.fillMaxWidth().height(180.dp))
                }
            }
            OptionRow("Level lines", options.levelLines) { options = options.copy(levelLines = it); failure = null }
            OptionRow("Even out spacing", options.evenSpacing) { options = options.copy(evenSpacing = it); failure = null }
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
                        picked.getValue(id).strokes.any { !PageLayers.editable(current.layers, it.layer) }
                }
                if (!stale && edits.isNotEmpty() && model.commitDocumentEdits(note.id, edits, pages.mapValues { it.value.revision })) {
                    onApplied(); onDismiss()
                } else failure = "The selection changed. Close this preview and select the ink again."
            }, modifier = Modifier.weight(1f), enabled = moved > 0 && failure == null, shapes = ButtonDefaults.shapes()) { Text("Tidy $moved") }
        }
    }
}

@Composable private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}
