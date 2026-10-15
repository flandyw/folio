@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal fun Modifier.notebookRangeSelect(enabled: Boolean, grid: LazyGridState, ids: List<String>, selected: List<String>, onChange: (List<String>) -> Unit): Modifier = composed {
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(selected)
    var dragY by remember { mutableStateOf<Float?>(null) }
    var extendScrolling by remember { mutableStateOf<((Offset) -> Unit)?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(dragY != null) {
        while (dragY != null) {
            val y = dragY!!; val height = grid.layoutInfo.viewportSize.height
            val delta = when { y < 70f -> -20f; y > height - 70f -> 20f; else -> 0f }
            if (delta != 0f) { grid.scrollBy(delta); extendScrolling?.invoke(dragPosition) }
            kotlinx.coroutines.delay(32)
        }
    }
    pointerInput(enabled, ids) {
        if (enabled) {
            var anchor: Int? = null
            var original = emptyList<String>()
            var position = Offset.Zero
            fun extend(at: Offset) {
                val key = grid.layoutInfo.visibleItemsInfo.firstOrNull { at.x >= it.offset.x && at.x <= it.offset.x + it.size.width && at.y >= it.offset.y && at.y <= it.offset.y + it.size.height }?.key as? String ?: return
                val index = ids.indexOf(key).takeIf { it >= 0 } ?: return
                val start = anchor ?: index.also { anchor = it }
                change((original + ids.subList(minOf(start, index), maxOf(start, index) + 1)).distinct())
            }
            detectDragGestures(onDragStart = { position = it; original = current; anchor = null; dragY = it.y; dragPosition = it; extendScrolling = ::extend; extend(it) },
                onDrag = { event, delta -> event.consume(); position += delta; dragY = position.y; dragPosition = position; extend(position) },
                onDragEnd = { dragY = null; anchor = null; extendScrolling = null }, onDragCancel = { dragY = null; anchor = null; extendScrolling = null })
        }
    }
}

@Composable internal fun ContinueWorkingCard(notes: List<Notebook>, thumbnails: PageThumbnailCache, onOpen: (String) -> Unit) {
    val note = remember(notes) { notes.filterNot { it.hiddenFromLibrary }.maxByOrNull { it.updated } } ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    // The page the notebook was left on, from the per-notebook position store the editor writes.
    val pageId = remember(note.id, note.pages) {
        NotebookPositionCodec.decode(context.getSharedPreferences("notebook_positions", 0).getString(note.id, null))?.pageId
    }
    val index = (note.pages.indexOfFirst { it.id == pageId }).takeIf { it >= 0 } ?: 0
    val page = note.pages.getOrNull(index)
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.roundToPx() }
    var preview by remember(note.id, page?.id, page?.revision) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(note.id, page?.id, page?.revision) { page?.let { preview = thumbnails.thumbnail(note.id, it, widthPx) } }
    val image = remember(preview) { preview?.asImageBitmap() }
    val progress = if (note.pages.isEmpty()) 0f else (index + 1f) / note.pages.size
    ElevatedCard({ onOpen(note.id) }, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(72.dp).height(96.dp).clip(FolioShapes.small).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, FolioShapes.small), contentAlignment = Alignment.Center) {
                if (image != null) Image(image, "Preview of page ${index + 1} of ${note.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(Icons.AutoMirrored.Rounded.MenuBook, null)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Continue working", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Page ${index + 1} of ${note.pages.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable internal fun SavedLibraryViews(current: JSONObject, naming: Boolean, onNaming: (Boolean) -> Unit, onRestore: (JSONObject) -> Unit) {
    val prefs = rememberPrefs()
    val raw by rememberPref(prefs, AppPrefs.UX_SAVED_VIEWS) { it.getString(AppPrefs.UX_SAVED_VIEWS, "[]") }
    val entries = remember(raw) { runCatching { JSONArray(raw) }.getOrDefault(JSONArray()) }
    var name by remember { mutableStateOf("") }
    fun save(values: List<JSONObject>) { prefs.write { putString(AppPrefs.UX_SAVED_VIEWS, JSONArray(values).toString()) } }
    val values = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
    // Saved views are niche: saving lives in the sort menu; only existing views take a row.
    if (values.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEach { item ->
            InputChip(false, { onRestore(item) }, { Text(item.optString("name", "Saved view")) }, trailingIcon = {
                TextButton({ save(values.filterNot { it === item }) }) { Text("×", Modifier.semanticsLabel("Delete saved view ${item.optString("name")}")) }
            })
        }
    }
    if (naming) AlertDialog(onDismissRequest = { onNaming(false) }, title = { Text("Save library view") },
        text = { OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton({ save((values.filterNot { it.optString("name").equals(name.trim(), true) } + JSONObject(current.toString()).put("name", name.trim())).takeLast(20)); onNaming(false); name = "" }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton({ onNaming(false) }) { Text("Cancel") } })
}

internal fun ExamFilter.toUxJson() = JSONObject().apply {
    subject?.let { put("subject", it.name) }; year?.let { put("year", it) }; company?.let { put("company", it) }; type?.let { put("type", it.name) }
    unit?.let { put("unit", it) }; status?.let { put("status", it.name) }; belowShare?.let { put("below", it.toDouble()) }; put("redo", needsRedo); put("incomplete", incomplete)
}
internal fun uxExamFilter(item: JSONObject): ExamFilter = ExamFilter(
    subject = VceSubject.entries.find { it.name == item.optString("subject") }, year = item.optInt("year").takeIf { item.has("year") }, company = item.optString("company").takeIf { it.isNotBlank() },
    type = ExamType.entries.find { it.name == item.optString("type") }, unit = item.optInt("unit").takeIf { item.has("unit") }, status = ExamStatus.entries.find { it.name == item.optString("status") },
    belowShare = item.optDouble("below").toFloat().takeIf { it.isFinite() }, needsRedo = item.optBoolean("redo"), incomplete = item.optBoolean("incomplete"))

@Composable internal fun LibraryShortcutHint() {
    val prefs = rememberPrefs()
    val show by rememberPref(prefs, AppPrefs.UX_HINTS, AppPrefs.UX_LIBRARY_HINT_DISMISSED) { it.getBoolean(AppPrefs.UX_HINTS, AppPrefs.DEFAULT_UX_HINTS) && !it.getBoolean(AppPrefs.UX_LIBRARY_HINT_DISMISSED, false) }
    if (show) Row(Modifier.fillMaxWidth()) {
        Text("List view supports custom swipe actions. In Select mode, drag across cards to select a range.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton({ prefs.write { putBoolean(AppPrefs.UX_LIBRARY_HINT_DISMISSED, true) } }) { Text("Dismiss") }
    }
}
