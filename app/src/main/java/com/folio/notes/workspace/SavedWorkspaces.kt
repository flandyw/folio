@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.ui.semanticsLabel
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.settings.write
import com.folio.notes.ui.FolioPanel
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun SavedWorkspacesPanel(state: FolioState, model: FolioViewModel, onDismiss: () -> Unit) {
    val prefs = rememberPrefs()
    val raw by rememberPref(prefs, AppPrefs.UX_WORKSPACES) { it.getString(AppPrefs.UX_WORKSPACES, "[]") }
    val entries = remember(raw) { SavedWorkspaceList.parse(raw) }
    var name by rememberSaveable { mutableStateOf("") }
    var renameIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var renameAttempted by rememberSaveable { mutableStateOf(false) }
    fun save(items: List<JSONObject>) { prefs.write { putString(AppPrefs.UX_WORKSPACES, JSONArray(items).toString()) } }
    FolioPanel("Saved workspaces", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save a notebook pair, page positions, zoom, pane order, page link and split. Workspaces are kept on this device.")
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Workspace name") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences))
            Button({ model.workspaceSnapshot(name.trim())?.let { snapshot -> save((entries.filterNot { it.optString("name").equals(name.trim(), true) } + snapshot).takeLast(20)); name = "" } }, enabled = name.isNotBlank() && state.companion != null) { Text("Save current pair") }
            if (state.companion == null) Text("Close this panel and use the toolbar’s split button to open a second document, then save the pair here.")
            if (entries.isEmpty()) Text("No saved workspaces yet. Save a document pair to reopen both pages and their layout together.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEachIndexed { index, item ->
                val available = state.notes.any { it.id == item.optString("editor") } && state.notes.any { it.id == item.optString("companion") }
                Row {
                    TextButton({ if (model.restoreWorkspace(item)) onDismiss() }, enabled = available, modifier = Modifier.weight(1f)) { Text(item.optString("name"), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    TextButton({ save(entries.filterNot { it === item }) }, modifier = Modifier.semanticsLabel("Remove workspace ${item.optString("name")}")) { Text("Remove") }
                }
                Row {
                    TextButton({ renameIndex = index; renameDraft = item.optString("name"); renameAttempted = false }, modifier = Modifier.semanticsLabel("Rename workspace ${item.optString("name")}")) { Text("Rename") }
                    TextButton({ save(SavedWorkspaceList.move(entries, index, -1)) }, enabled = index > 0, modifier = Modifier.semanticsLabel("Move workspace ${item.optString("name")} up")) { Text("Move up") }
                    TextButton({ save(SavedWorkspaceList.move(entries, index, 1)) }, enabled = index < entries.lastIndex, modifier = Modifier.semanticsLabel("Move workspace ${item.optString("name")} down")) { Text("Move down") }
                }
                if (!available) Text("A document in this workspace was deleted or is unavailable.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    renameIndex?.let { index ->
        val error = SavedWorkspaceList.renameError(entries, index, renameDraft)
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text("Rename workspace") },
            text = {
                OutlinedTextField(renameDraft, { renameDraft = it.take(80) }, label = { Text("Workspace name") }, singleLine = true,
                    isError = renameAttempted && error != null,
                    supportingText = { if (renameAttempted && error != null) Text(error) })
            },
            confirmButton = {
                TextButton({
                    renameAttempted = true
                    if (error == null) {
                        save(SavedWorkspaceList.rename(entries, index, renameDraft))
                        renameIndex = null
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton({ renameIndex = null }) { Text("Cancel") } }
        )
    }
}

@Composable internal fun LinkedPageOffset(state: FolioState, model: FolioViewModel) {
    val prefs = rememberPrefs()
    val key = AppPrefs.UX_LINK_OFFSET + ".${state.activeId}.${state.companion?.notebookId}"
    val stored by rememberPref(prefs, key) { it.getInt(key, 0) }
    var draft by remember(key, stored) { mutableStateOf(stored.toString()) }
    OutlinedTextField(draft, { draft = it.take(8) }, Modifier.fillMaxWidth(), label = { Text("Companion page offset") }, singleLine = true,
        supportingText = { Text("+2 links editor page 5 to companion page 7. 0 uses the existing PDF page matching.") },
        isError = draft.toIntOrNull()?.let { it !in -99999..99999 } != false,
        trailingIcon = { TextButton({ val value = draft.toIntOrNull() ?: return@TextButton; prefs.write { putInt(key, value.coerceIn(-99999, 99999)) }; model.selectPage(state.pageIndex) }, enabled = draft.toIntOrNull()?.let { it in -99999..99999 } == true) { Text("Apply") } })
}
