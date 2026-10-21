@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.settings.write
import com.folio.notes.ui.FolioPanel
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun SavedWorkspacesPanel(state: FolioState, model: FolioViewModel, onDismiss: () -> Unit) {
    val prefs = rememberPrefs()
    val raw by rememberPref(prefs, AppPrefs.UX_WORKSPACES) { it.getString(AppPrefs.UX_WORKSPACES, "[]") }
    val list = remember(raw) { runCatching { JSONArray(raw) }.getOrDefault(JSONArray()) }
    val entries = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    var name by remember { mutableStateOf("") }
    fun save(items: List<JSONObject>) { prefs.write { putString(AppPrefs.UX_WORKSPACES, JSONArray(items).toString()) } }
    FolioPanel("Saved workspaces", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save a notebook pair, page positions, zoom, pane order, page link and split. Workspaces are kept on this device.")
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Workspace name") }, singleLine = true)
            Button({ model.workspaceSnapshot(name.trim())?.let { snapshot -> save((entries.filterNot { it.optString("name").equals(name.trim(), true) } + snapshot).takeLast(20)); name = "" } }, enabled = name.isNotBlank() && state.companion != null) { Text("Save current pair") }
            if (state.companion == null) Text("Open a companion document to save a pair.")
            entries.forEach { item ->
                val available = state.notes.any { it.id == item.optString("editor") } && state.notes.any { it.id == item.optString("companion") }
                Row {
                    TextButton({ if (model.restoreWorkspace(item)) onDismiss() }, enabled = available, modifier = Modifier.weight(1f)) { Text(item.optString("name")) }
                    TextButton({ save(entries.filterNot { it === item }) }) { Text("Delete") }
                }
                if (!available) Text("A document in this workspace was deleted or is unavailable.", style = MaterialTheme.typography.bodySmall)
            }
        }
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
