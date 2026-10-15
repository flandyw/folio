@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

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

@Composable internal fun UxAppearanceSettings() {
    SettingsGroup("Controls and discovery") {
        SettingsPrefSwitch(AppPrefs.UX_LABELS, false, "Show tool names", "Show names beside the active tool and pinned presets.")
        SettingsPrefSwitch(AppPrefs.UX_LARGE_CONTROLS, false, "Larger controls", "Use larger editor controls and selection actions.")
        SettingsPrefSwitch(AppPrefs.UX_HINTS, true, "Shortcut hints", "Show a dismissible shortcut tip in the editor and library.")
    }
}

@Composable internal fun UxWritingSettings() {
    SettingsGroup("Keyboard and object placement") {
        ToolKeySettings()
        SettingsPrefSwitch(AppPrefs.UX_TOOL_KEYS, true, "Tool letter shortcuts", "P pen, E eraser, H highlighter, L lasso, V hand, T text. Only on the canvas.")
        SettingsPrefSwitch(AppPrefs.UX_SNAP, false, "Snap moved selections", "Snap selections to 12-unit spacing and page margins. Hold Shift to bypass snapping.")
        SettingsPrefSwitch(AppPrefs.UX_LOCAL_TOOLS, false, "Remember tools per notebook", "Restore this notebook’s last tool and preset. Turn off to use global defaults.")
    }
}

@Composable internal fun UxLibrarySettings() {
    val prefs = rememberPrefs()
    SettingsGroup("Notebook list swipes") {
        for ((title, key, fallback) in listOf(Triple("Swipe right", AppPrefs.UX_SWIPE_RIGHT, "FAVOURITE"), Triple("Swipe left", AppPrefs.UX_SWIPE_LEFT, "TRASH"))) {
            val selected by rememberPref(prefs, key) { AppPrefs.uxSwipe(it.getString(key, null), fallback) }
            SettingsChipRow(title, listOf("FAVOURITE", "TRASH", "TAGS", "MOVE", "SHARE").map { it to it.lowercase().replaceFirstChar(Char::uppercase) }, selected,
                { prefs.write { putString(key, it) } })
        }
    }
}

@Composable internal fun UxStylusPractice(action: StylusShortcut) {
    var guide by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("Double-tap your pencil in this area, or double-tap the practice pad.") }
    var current by remember { mutableStateOf(Tool.PEN) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember(context) { StylusShortcutManager(context) }
    val run by rememberUpdatedState<() -> Unit> {
        val effect = StylusShortcuts.effect(action, current, Tool.PEN)
        if (effect is StylusShortcutEffect.SwitchTool) current = effect.tool
        result = when (effect) {
            is StylusShortcutEffect.SwitchTool -> "Selected ${toolLabel(current)}"
            StylusShortcutEffect.OpenPalette -> "Colour palette would open"
            StylusShortcutEffect.Undo -> "The last action would be undone"
            StylusShortcutEffect.NextLine -> "The next writing line would open when writing follow is active"
            StylusShortcutEffect.None -> "Pencil shortcut is disabled"
        }
    }
    DisposableEffect(manager) { manager.start { run() }; onDispose { manager.stop() } }
    SettingsGroup("Try your pencil shortcut") {
        Surface(Modifier.fillMaxWidth().heightIn(min = 96.dp).pointerInput(action) { detectTapGestures(onDoubleTap = { run() }) }, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(result, Modifier.padding(16.dp))
        }
        TextButton({ guide = true }) { Text("Gestures and shortcuts") }
    }
    if (guide) GestureGuide { guide = false }
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
                fields.keys.toList().forEach { tool -> OutlinedTextField(fields[tool].orEmpty(), { fields[tool] = it.uppercase().filter { c -> c in 'A'..'Z' }.takeLast(1) }, Modifier.fillMaxWidth(), label = { Text(toolLabel(tool)) }, singleLine = true) }
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
