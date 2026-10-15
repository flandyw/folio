package com.folio.notes

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val LocalNotebookToolbarPreferences = staticCompositionLocalOf<android.content.SharedPreferences?> { null }

/** Notebook-keyed route: the application engine owns content; the visit owns presentation and native handles. */
@Composable internal fun EditorScreen(state: FolioState, model: FolioViewModel, finger: Boolean, haptics: Boolean,
    shapeRecognitionSetting: Boolean, onSettings: () -> Unit, onExport: () -> Unit,
    notebookActions: @Composable (() -> Unit) -> Unit = {}, music: MusicStage? = null,
    showBack: Boolean = true, onShareLongPress: (() -> Unit)? = null,
    onChatGpt: (() -> Unit)? = null, onOpenDocument: (() -> Unit)? = null) {
    val feature: EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val editorState by feature.state.collectAsStateWithLifecycle()
    val note = editorState.active ?: return
    val settings = rememberPrefs()
    val localTools by rememberPref(settings, AppPrefs.UX_LOCAL_TOOLS) { it.getBoolean(AppPrefs.UX_LOCAL_TOOLS, AppPrefs.DEFAULT_UX_LOCAL_TOOLS) }
    key(note.id, localTools) {
        val context = LocalContext.current
        val prefs = remember(context, note.id, localTools) {
            val global = context.getSharedPreferences("ink-tools", 0)
            if (!localTools) global else context.getSharedPreferences("ink-tools-${note.id}", 0).also { scoped ->
                if (!scoped.contains("initialised")) scoped.edit().apply {
                    global.all.filterKeys { key -> Tool.entries.any { key.startsWith(it.name + ".") } }.forEach { (key, value) -> when (value) {
                        is String -> putString(key, value); is Int -> putInt(key, value); is Float -> putFloat(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
                    } }
                    putBoolean("initialised", true)
                }.apply()
            }
        }
        val appPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
        val toolbarPrefs = remember(context, note.id, localTools) {
            if (!localTools) appPrefs else context.getSharedPreferences("toolbar-${note.id}", 0).also { scoped ->
                if (!scoped.contains("initialised")) scoped.edit().apply {
                    ToolbarLayoutState.KEYS.forEach { key -> appPrefs.getString(key, null)?.let { putString(key, it) } }
                    putBoolean("initialised", true)
                }.apply()
            }
        }
        val tab = editorState.tabs.find { it.notebookId == note.id }
        val editor = rememberEditorSession(note.id, prefs,
            tab?.tool ?: AppPrefs.defaultTool(appPrefs.getString(AppPrefs.DEFAULT_TOOL, null)),
            tab?.viewport ?: WorkspaceViewport())
        CompositionLocalProvider(LocalEditorSurfaces provides editor.surfaces, LocalDocumentInput provides editor.documentInput, LocalNotebookToolbarPreferences provides toolbarPrefs) {
            EditorWorkspaceContent(editor, editorState, model, finger, haptics, shapeRecognitionSetting,
                onSettings, onExport, notebookActions, music, showBack, onShareLongPress, onChatGpt, onOpenDocument)
        }
    }
}
