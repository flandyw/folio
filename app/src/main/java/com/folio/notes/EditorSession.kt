package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Visit-scoped presentation only. Never a second copy of notebook/page content. */
internal class EditorSession(val tools: EditorToolState, val viewport: EditorViewportState, val browser: EditorBrowserState) {
    val surfaces = EditorSurfaceRegistry()
    val interaction = EditorInteractionState()
    val documentInput = DocumentInputCoordinator(surfaces, interaction)
    val overlays = EditorOverlayState()
    val paper = EditorPaperState()
}

internal class EditorToolState(private val prefs: SharedPreferences, initial: Tool, previous: Tool = Tool.PEN) {
    private val selected = mutableStateOf(initial)
    val previousTool = mutableStateOf(previous)
    val options = mutableStateOf(ToolOptions.load(prefs, initial))
    val eraserReturn = mutableStateOf<Tool?>(null)
    // Updating a tool always loads its settings synchronously, including select-all and shortcuts.
    val tool = object : MutableState<Tool> {
        override var value: Tool
            get() = selected.value
            set(value) { if (selected.value != value) { selected.value = value; options.value = ToolOptions.load(prefs, value) } }
        override fun component1() = value
        override fun component2(): (Tool) -> Unit = { value = it }
    }
    fun changeOptions(value: ToolOptions) { options.value = value; value.save(prefs, tool.value) }
    fun select(value: Tool, singleStroke: Boolean): Boolean {
        if (value == tool.value) return false
        if (value == Tool.ERASER && singleStroke) eraserReturn.value = tool.value
        else if (tool.value == Tool.ERASER) eraserReturn.value = null
        tool.value = value
        return true
    }
    fun finishEraser(singleStroke: Boolean) {
        if (!singleStroke) return
        tool.value = eraserReturn.value ?: Tool.PEN
        eraserReturn.value = null
    }
    fun apply(preset: ToolPreset) {
        tool.value = preset.tool
        changeOptions(ToolOptions.load(prefs, preset.tool).copy(color = preset.color, width = preset.width,
            opacity = preset.opacity, style = preset.style))
    }
}

internal class EditorViewportState(initial: WorkspaceViewport) {
    val zoom = mutableFloatStateOf(initial.zoom)
    val pan = mutableFloatStateOf(initial.pan)
    val pageZoom = mutableFloatStateOf(1f)
    val pagePan = mutableFloatStateOf(0f)
    var anchor: DocumentPageGeometry.Anchor? by mutableStateOf(initial.documentPageId?.let { id ->
        initial.documentPageY?.takeIf(Float::isFinite)?.let { DocumentPageGeometry.Anchor(id, it) }
    })
}
internal class EditorInteractionState {
    val selection = mutableStateOf<Pair<String, CanvasSelection>?>(null)
    val documentSelection = mutableStateOf<Map<String, CanvasSelection>>(emptyMap())
    val stickyLift = mutableStateOf<StickyLift?>(null)
    /** The eraser as the page under the pen reports it; null while the pen is over no page. */
    val eraserBrush = mutableStateOf<EraserBrush?>(null)
    val objectLift = mutableStateOf<ObjectLift?>(null)
    val selectedImage = mutableStateOf<Pair<String, PageImage>?>(null)
    val cropActive = mutableStateOf(false)
    val navigating = mutableStateOf(false)
    val scrubbing = mutableStateOf(false)
    val transfers = EditorTransferCoordinator()
}
internal class EditorOverlayState {
    val palette = mutableStateOf(false)
    val noteSearch = mutableStateOf(false)
    val keyboardShortcuts = mutableStateOf(false)
    val clear = mutableStateOf(false)
    val markDialog = mutableStateOf(false)
    val focalReview = mutableStateOf<Pair<Notebook, ExamAttempt>?>(null)
    val pageBrowser = mutableStateOf(false)
    val namedPage = mutableStateOf<NotePage?>(null)
    val pageTitle = mutableStateOf("")
    val movingPage = mutableStateOf<String?>(null)
    val destinationPage = mutableStateOf("")
    val deletingPage = mutableStateOf<String?>(null)
    val rename = mutableStateOf(false)
    val renameTitle = mutableStateOf("")
    val pdfSearch = mutableStateOf(false)
    val pdfContents = mutableStateOf(false)
    val timer = mutableStateOf(false)
    val study = mutableStateOf(false)
    val exam = mutableStateOf(false)
}

@Composable internal fun rememberEditorSession(noteId: String, prefs: SharedPreferences,
    initialTool: Tool, initialViewport: WorkspaceViewport): EditorSession {
    val tools = rememberSaveable(noteId, saver = Saver<EditorToolState, List<String>>(
        save = { listOf(it.tool.value.name, it.previousTool.value.name, it.eraserReturn.value?.name.orEmpty()) },
        restore = { values -> EditorToolState(prefs, Tool.valueOf(values[0]), Tool.valueOf(values[1])).also {
            it.eraserReturn.value = values.getOrNull(2)?.takeIf(String::isNotEmpty)?.let(Tool::valueOf)
        } },
    )) { EditorToolState(prefs, initialTool) }
    val viewport = rememberSaveable(noteId, saver = Saver<EditorViewportState, List<String>>(
        save = { listOf(it.zoom.floatValue.toString(), it.pan.floatValue.toString(), it.anchor?.pageId.orEmpty(), it.anchor?.localY?.toString().orEmpty()) },
        restore = { values -> EditorViewportState(WorkspaceViewport(zoom = values[0].toFloat(), pan = values[1].toFloat())).also { v ->
            if (values.getOrNull(2)?.isNotEmpty() == true) v.anchor = DocumentPageGeometry.Anchor(values[2], values[3].toFloat())
        } },
    )) { EditorViewportState(initialViewport) }
    val browser = rememberSaveable(noteId, saver = Saver<EditorBrowserState, List<String>>(
        save = { listOf(it.query.value, it.filter.value.name, it.number.value, it.grid.value.toString(), it.jumpExpanded.value.toString()) },
        restore = { EditorBrowserState().apply { query.value = it[0]; filter.value = PageFilter.valueOf(it[1]); number.value = it[2]; grid.value = it[3].toBoolean(); jumpExpanded.value = it[4].toBoolean() } },
    )) { EditorBrowserState() }
    return remember(noteId, tools, viewport, browser) { EditorSession(tools, viewport, browser) }
}

internal class EditorBrowserState {
    val query = mutableStateOf("")
    val filter = mutableStateOf(PageFilter.ALL)
    val number = mutableStateOf("")
    val grid = mutableStateOf(false)
    val jumpExpanded = mutableStateOf(false)
}

internal class EditorPaperState {
    val open = mutableStateOf(false)
    val next = mutableStateOf(false)
    val nextPaper = mutableStateOf<Paper?>(null)
    val chosen = mutableStateOf(Paper.MATH_GRID)
    val saveDefault = mutableStateOf(false)
}
