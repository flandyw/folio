@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.editor.EditorKeyBindings
import com.folio.notes.editor.toolbar.toolLabel
import com.folio.notes.model.Tool
import com.folio.notes.ui.FolioPanel

internal val LocalSettingsTarget = compositionLocalOf<MutableState<String>?> { null }
internal fun Modifier.settingsTarget(title: String): Modifier = composed {
    val target = LocalSettingsTarget.current
    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(target?.value, title) {
        if (title.isNotBlank() && target?.value.equals(title, true)) {
            highlighted = true
            androidx.compose.runtime.withFrameNanos { }
            requester.bringIntoView()
            kotlinx.coroutines.delay(2200)
            highlighted = false
            if (target?.value.equals(title, true)) target?.value = ""
        }
    }
    bringIntoViewRequester(requester).then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small) else Modifier)
}

@Composable internal fun largeEditorControls(): Boolean {
    val p = rememberPrefs()
    val large by rememberPref(p, AppPrefs.UX_LARGE_CONTROLS) { it.getBoolean(AppPrefs.UX_LARGE_CONTROLS, AppPrefs.DEFAULT_UX_LARGE_CONTROLS) }
    return large
}

@Composable internal fun ToolKeySettings() {
    val prefs = rememberPrefs()
    val raw by rememberPref(prefs, AppPrefs.UX_TOOL_BINDINGS) { it.getString(AppPrefs.UX_TOOL_BINDINGS, null) }
    val bindings = remember(raw) { EditorKeyBindings.decode(raw) }
    var editing by remember { mutableStateOf(false) }
    SettingsLinkRow("Customise tool keys", "Choose a different letter for each drawing tool.", { editing = true })
    if (editing) {
        val fields = remember { mutableStateMapOf<Tool, String>().apply { bindings.forEach { (key, tool) -> put(tool, key.toString()) } } }
        val valid = fields.size == 6 && fields.values.all { it.length == 1 && it[0] in 'A'..'Z' } && fields.values.distinct().size == fields.size
        FolioPanel("Tool letter shortcuts", { editing = false }) {
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.keys.toList().forEach { tool -> OutlinedTextField(fields[tool].orEmpty(), { fields[tool] = it.uppercase().filter { c -> c in 'A'..'Z' }.takeLast(1) }, Modifier.fillMaxWidth(), label = { Text(toolLabel(tool)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false), singleLine = true) }
                if (!valid) Text("Choose a unique letter A–Z for each tool.", color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton({ prefs.write { remove(AppPrefs.UX_TOOL_BINDINGS) }; editing = false }) { Text("Reset") }
                    Button({ prefs.write { putString(AppPrefs.UX_TOOL_BINDINGS, EditorKeyBindings.encode(fields.entries.associate { it.value.single() to it.key })) }; editing = false }, enabled = valid) { Text("Save keys") }
                }
            }
        }
    }
}

@Composable internal fun showEditorToolNames(): Boolean {
    val p = rememberPrefs()
    val labels by rememberPref(p, AppPrefs.UX_LABELS) { it.getBoolean(AppPrefs.UX_LABELS, AppPrefs.DEFAULT_UX_LABELS) }
    return labels
}
