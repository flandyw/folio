@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioApplication
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.backup.LibraryAutoBackup
import com.folio.notes.editor.selection.SelectionPlacement
import com.folio.notes.export.ShareShortcut
import com.folio.notes.ink.InkRenderer
import com.folio.notes.mistakes.MistakesViewModel
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.settings.LocalOpenBackupSettings
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.settings.write
import com.folio.notes.storage.InkCodec
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content
import com.folio.notes.ui.FolioPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable internal fun EditorUxStatus(state: FolioState, model: FolioViewModel, onGuide: () -> Unit) {
    val prefs = rememberPrefs()
    val hints by rememberPref(prefs, AppPrefs.UX_HINTS, AppPrefs.UX_EDITOR_HINT_DISMISSED) { it.getBoolean(AppPrefs.UX_HINTS, AppPrefs.DEFAULT_UX_HINTS) && !it.getBoolean(AppPrefs.UX_EDITOR_HINT_DISMISSED, false) }
    val quickShare by rememberPref(prefs, AppPrefs.SHARE_LONG_PRESS) { AppPrefs.shareShortcut(it.getString(AppPrefs.SHARE_LONG_PRESS, null)) }
    Column {
        if (state.saveFailed) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(8.dp).semantics { liveRegion = LiveRegionMode.Assertive }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(state.saveFailureReason ?: "Changes have not saved. Keep Folio open and retry.", Modifier.weight(1f))
                TextButton(model::retrySave, enabled = !state.retryingSave) { Text(if (state.retryingSave) "Retrying…" else "Retry save") }
            }
        }
        if (hints) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (quickShare == ShareShortcut.OFF) "Tip: Ctrl+K finds any action." else "Tip: Ctrl+K finds any action. Hold Share to ${quickShare.label.replaceFirstChar(Char::lowercase)}.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onGuide) { Text("Guide") }
            TextButton({ prefs.write { putBoolean(AppPrefs.UX_EDITOR_HINT_DISMISSED, true) } }) { Text("Dismiss") }
        }
    }
}

@Composable internal fun ProtectionPanel(state: FolioState, model: FolioViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    val last by rememberPref(prefs, LibraryAutoBackup.LAST_SUCCESS) { it.getLong(LibraryAutoBackup.LAST_SUCCESS, 0L) }
    val error by rememberPref(prefs, LibraryAutoBackup.LAST_ERROR) { it.getString(LibraryAutoBackup.LAST_ERROR, null) }
    val configured by rememberPref(prefs, LibraryAutoBackup.TREE_URI) { it.getString(LibraryAutoBackup.TREE_URI, null) != null }
    val openBackup = LocalOpenBackupSettings.current
    val progress by LibraryAutoBackup.progress.collectAsState()
    val study by (context.applicationContext as FolioApplication).focalStudy.state.collectAsState()
    val mistakeModel: MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val mistakes by mistakeModel.state.collectAsState()
    FolioPanel("Saving, sync and backups", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("On this device", style = MaterialTheme.typography.titleSmall)
            Text(if (state.saveFailed) state.saveFailureReason ?: "Save failed. Your latest changes remain open." else if (state.saving) "Saving your latest changes…" else "All current edits have been saved on this device.")
            if (state.saveFailed) Button(model::retrySave, enabled = !state.retryingSave) { Text("Retry save") }
            Text("Focal study sync", style = MaterialTheme.typography.titleSmall)
            Text(study.syncStatus)
            Text("${study.pendingCount} study records waiting to sync.")
            Text("Focal mistake sync", style = MaterialTheme.typography.titleSmall)
            Text(mistakes.status)
            mistakes.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton({ mistakeModel.requestSync(force = true) }, enabled = mistakes.userId != null && !mistakes.busy) { Text("Retry mistake sync") }
            Text("Focal sync covers study records and mistake review. Your notebook files need a library backup.")
            Text("Library backup", style = MaterialTheme.typography.titleSmall)
            Text(if (last == 0L) "No successful automatic backup recorded." else "Last successful automatic backup: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))}")
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (state.activeId in state.backupExcludedNotebookIds) Text("This notebook is excluded from library backups.", color = MaterialTheme.colorScheme.error)
            Text("Library backups include local practice notebooks and exclude Trash and the separate music collection. Reusable snippets and workspace preferences are also device-local. Manage the backup folder, exclusions and restore points in Settings › Backup.")
            if (progress != null) Text(progress!!, style = MaterialTheme.typography.bodySmall)
            Button({ LibraryAutoBackup.requestNow(context) }, enabled = configured && progress == null) { Text("Back up now") }
            if (!configured) Text("Choose a backup folder in Settings › Backup first.")
            if (openBackup != null) TextButton({ onDismiss(); openBackup() }) { Text("Backup setup and restore") }
        }
    }
}

@Composable internal fun EditorHistoryPanel(model: FolioViewModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    val entries = model.undoDescriptions()
    FolioPanel("Undo history", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Next action is first. Page ink history applies to the current page; document and page-organisation actions follow their recorded order.")
            if (entries.isEmpty()) Text("Nothing to undo on this page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEachIndexed { index, label -> Text("${index + 1}. $label") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(model::undo, enabled = state.canUndo && !state.documentHistoryBusy) { Text("Undo next") }
                TextButton(model::redo, enabled = state.canRedo && !state.documentHistoryBusy) { Text("Redo") }
            }
        }
    }
}

@Composable internal fun SelectionPlacementPanel(note: Notebook, selection: Map<String, CanvasSelection>, model: FolioViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snapshots = remember { selection.mapNotNull { (id, picked) -> note.pages.firstOrNull { it.id == id }?.let { id to (it to picked) } }.toMap() }
    var busy by remember { mutableStateOf(false) }
    FolioPanel("Align and distribute", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Each selected stroke, text box or picture is an item. On a spanning selection, items are aligned within each page. All affected pages change in one undoable action.")
            if (snapshots.values.none { it.second.size >= 2 }) Text("Select two or more items on a page to align them; select three to distribute them.")
            listOf("Left", "Centre horizontally", "Right", "Top", "Centre vertically", "Bottom", "Distribute horizontally", "Distribute vertically").forEach { action ->
                TextButton({ busy = true; scope.launch {
                    try {
                        val edits = snapshots.mapNotNull { (id, pair) ->
                            val (page, picked) = pair
                            if (action.startsWith("Distribute") && picked.size < 3) null else PageJournal.diff(page.content(), SelectionPlacement.apply(page, picked, action, InkRenderer::textHeight))?.let { id to it }
                        }.toMap()
                        if (edits.isEmpty()) { onDismiss(); return@launch }
                        if (model.commitDocumentEdits(note.id, edits, snapshots.mapValues { it.value.first.revision })) { onDone(); onDismiss() }
                        else model.reportError("The selection changed. Select it again before aligning.")
                    } finally { busy = false }
                } }, enabled = !busy && snapshots.values.any { it.second.size >= if (action.startsWith("Distribute")) 3 else 2 }) { Text(action) }
            }
        }
    }
}

@Composable internal fun SnippetsPanel(note: Notebook, page: NotePage, selection: CanvasSelection, model: FolioViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val root = remember { File(context.filesDir, "ux-snippets") }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() { entries = withContext(Dispatchers.IO) { root.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty() } }
    LaunchedEffect(Unit) { refresh() }
    FolioPanel("Reusable snippets", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name this selection") }, singleLine = true)
            Button({ busy = true; scope.launch {
                try {
                    val images = selection.images.map { it to (model.repository.loadImageBytes(note.id, it.id) ?: error("Selected picture is unavailable")) }
                    withContext(Dispatchers.IO) {
                        root.mkdirs(); val id = UUID.randomUUID().toString()
                        val json = JSONObject().put("name", name).put("strokes", InkCodec.encodeStrokes(selection.strokes)).put("texts", InkCodec.encodeTexts(selection.texts)).put("images", InkCodec.encodeImages(selection.images))
                        images.forEach { (image, bytes) -> File(root, "$id-${image.id}.jpg").writeBytes(bytes) }
                        val target = android.util.AtomicFile(File(root, "$id.json")); val stream = target.startWrite()
                        try { stream.write(json.toString().toByteArray()); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
                    }
                    name = ""; refresh()
                } catch (e: Exception) { model.reportError("Couldn't save snippet: ${e.message.orEmpty()}") } finally { busy = false }
            } }, enabled = selection.isNotEmpty() && name.isNotBlank() && !busy) { Text("Save selection") }
            if (selection.isEmpty()) Text("Select ink, text or pictures to save a new snippet.")
            if (entries.isEmpty()) Text("Saved snippets appear here and work across notebooks. They are kept on this device separately from library backups.")
            entries.forEach { file ->
                val json by produceState<JSONObject?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { JSONObject(file.readText()) }.getOrNull() } }
                val entry = json
                if (entry != null) Row {
                    TextButton({ busy = true; scope.launch { try {
                        val clip = CanvasSelection(InkCodec.decodeStrokes(entry.optJSONArray("strokes")), InkCodec.decodeTexts(entry.optJSONArray("texts")), InkCodec.decodeImages(entry.optJSONArray("images")))
                        val bytes = withContext(Dispatchers.IO) { clip.images.associate { it.id to File(root, "${file.nameWithoutExtension}-${it.id}.jpg").readBytes() } }
                        model.pasteSnippet(clip, bytes); onDismiss()
                    } catch (e: Exception) { model.reportError("Couldn't insert snippet: ${e.message.orEmpty()}") } finally { busy = false } } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(entry.optString("name", "Snippet")) }
                    TextButton({ scope.launch { withContext(Dispatchers.IO) { root.listFiles()?.filter { it.name.startsWith(file.nameWithoutExtension) }?.forEach { it.delete() } }; refresh() } }, enabled = !busy) { Text("Delete") }
                }
            }
        }
    }
}
