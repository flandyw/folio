@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester

internal val LocalWorkspaceCommands = compositionLocalOf<List<EditorCommand>> { emptyList() }

internal data class EditorCommand(val title: String, val keys: String = "", val enabled: Boolean = true,
    val reason: String = "", val run: () -> Unit)

internal val editorShortcutHelp = listOf(
    "Ctrl + K" to "Find an action", "P / E / H / L / V / T" to "Default tool letters (customise in Settings › Writing)",
    "Hold Space" to "Temporarily use the hand", "Ctrl + C / X / V" to "Copy / cut / paste selection",
    "Ctrl + A / D" to "Select all / duplicate selection", "Delete" to "Delete selection",
    "Ctrl + Tab / Ctrl + Shift + Tab" to "Next / previous notebook", "Ctrl + \\" to "Expand editor / restore split", "Page Up / Page Down" to "Previous / next page",
    "Ctrl + Home / End" to "First / last page", "Ctrl + N" to "New notebook",
    "Ctrl + Enter" to "Insert a page, or apply a text draft", "Ctrl + Shift + D" to "Duplicate page",
    "Ctrl + F" to "Find in document", "Ctrl + G" to "Go to a page", "Ctrl + 0" to "Reset zoom",
    "Ctrl + Z" to "Undo", "Ctrl + Shift + Z / Ctrl + Y" to "Redo",
    "Alt + Left / Right" to "Back / forward in navigation history",
    "Alt + Down / Up" to "Next / previous writing line (when writing follow is active)",
    "Alt + Backspace" to "Return to the previous writing view", "Escape" to "Cancel crop or clear selection"
)

@Composable internal fun EditorCommandPanel(commands: List<EditorCommand>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var highlighted by remember(query) { mutableIntStateOf(0) }
    val matches = commands.filter { it.title.contains(query.trim(), true) }
    val available = matches.filter { it.enabled }
    val active = available.getOrNull(highlighted.coerceIn(0, (available.size - 1).coerceAtLeast(0)))
    fun run(command: EditorCommand?) { if (command != null && command.enabled) { onDismiss(); command.run() } }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    FolioPanel("Find an action", onDismiss) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth().weight(1f, false).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown -> { if (available.isNotEmpty()) highlighted = (highlighted + 1) % available.size; true }
                    Key.DirectionUp -> { if (available.isNotEmpty()) highlighted = (highlighted + available.size - 1) % available.size; true }
                    Key.Enter, Key.NumPadEnter -> { run(active); true }
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            }, label = { Text("Search actions") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { run(active) }))
            Column(Modifier.fillMaxWidth().weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (matches.isEmpty()) Text("No matching actions. Try page, tool, search or export.")
            matches.forEach { command ->
                val bring = remember(command.title) { BringIntoViewRequester() }
                LaunchedEffect(active?.title) { if (active === command) bring.bringIntoView() }
                TextButton({ run(command) }, enabled = command.enabled, modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bring),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (active === command) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                    Column(Modifier.weight(1f)) { Text(command.title); if (!command.enabled && command.reason.isNotBlank()) Text(command.reason, style = MaterialTheme.typography.bodySmall) }
                    if (command.keys.isNotBlank()) Text(command.keys, style = MaterialTheme.typography.labelSmall)
                }
            }
            }
        }
    }
}

@Composable internal fun GestureGuide(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val gestures = listOf(
        "Pencil double-tap" to "OnePlus/OPPO pencils: configure the action in Settings › Stylus & touch. Other pens use their system shortcut.",
        "Pen side button" to "On supported pens, hold the primary side button before touching the page to erase. Release it before your next stroke to use the selected tool.",
        "Finger navigation" to "With Draw with a finger off, use a finger to navigate. Two fingers pan and zoom. Palm rejection stays active.",
        "Undo and redo" to "When enabled, tap with two fingers to undo or three to redo. You can always use toolbar buttons instead.",
        "Share faster" to "Long-press Share for its configured quick export. Choose the action in Settings › Workflow.",
        "Notebook list swipes" to "Swipe a notebook in list view. Choose your left and right swipe actions in Settings › Library.",
        "Selection" to "Choose Lasso and loop around ink, text or pictures. Use its menu to copy, align, save a snippet or convert handwriting.",
        "Writing follow" to "Alt+Down / Up changes writing line; Alt+Backspace returns. On infinite canvases, first choose Write a response.",
        "Pages" to "Long-press and drag a page in the unfiltered page list, or use Move to page number. Use Select pages for batch actions.",
        "Sticky notes" to "Tap to focus a note, tap again to type. Drag to move; use its corner grip to resize.",
        "Keyboard" to "Ctrl+K finds an action. Tool letters work only on the canvas, outside text entry and panels."
    )
    FolioPanel("Gestures and shortcuts", onDismiss) {
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Find a gesture") }, singleLine = true)
            gestures.filter { (it.first + it.second).contains(query.trim(), true) }.forEach { (title, detail) ->
                Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
