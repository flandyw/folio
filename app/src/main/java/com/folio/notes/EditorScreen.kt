package com.folio.notes

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Notebook-keyed route: the application engine owns content; the visit owns presentation and native handles. */
@Composable internal fun EditorScreen(state: FolioState, model: FolioViewModel, finger: Boolean, haptics: Boolean,
    shapeRecognitionSetting: Boolean, onSettings: () -> Unit, onExport: () -> Unit,
    notebookActions: @Composable (() -> Unit) -> Unit = {}, music: MusicStage? = null,
    showBack: Boolean = true, onShareLongPress: (() -> Unit)? = null,
    onChatGpt: (() -> Unit)? = null, onOpenDocument: (() -> Unit)? = null) {
    val feature: EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val editorState by feature.state.collectAsStateWithLifecycle()
    val note = editorState.active ?: return
    key(note.id) {
        val context = LocalContext.current
        val prefs = remember(context) { context.getSharedPreferences("ink-tools", 0) }
        val appPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
        val tab = editorState.tabs.find { it.notebookId == note.id }
        val editor = rememberEditorSession(note.id, prefs,
            tab?.tool ?: AppPrefs.defaultTool(appPrefs.getString(AppPrefs.DEFAULT_TOOL, null)),
            tab?.viewport ?: WorkspaceViewport())
        CompositionLocalProvider(LocalEditorSurfaces provides editor.surfaces, LocalDocumentInput provides editor.documentInput) {
            EditorWorkspaceContent(editor, editorState, model, finger, haptics, shapeRecognitionSetting,
                onSettings, onExport, notebookActions, music, showBack, onShareLongPress, onChatGpt, onOpenDocument)
        }
    }
}
