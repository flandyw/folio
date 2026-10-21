package com.folio.notes.editor

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.folio.notes.editor.layout.DocumentInputCoordinator
import com.folio.notes.editor.layout.DocumentPageGeometry
import com.folio.notes.editor.selection.ObjectLift
import com.folio.notes.editor.sticky.StickyLift
import com.folio.notes.editor.toolbar.ToolOptions
import com.folio.notes.editor.toolbar.ToolPreset
import com.folio.notes.exam.ExamAttempt
import com.folio.notes.ink.EraserBrush
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageFilter
import com.folio.notes.model.PageImage
import com.folio.notes.model.Paper
import com.folio.notes.model.Tool
import com.folio.notes.workspace.WorkspaceViewport

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
            set(value) { if (selected.value != value) { flushSave(); selected.value = value; options.value = ToolOptions.load(prefs, value) } }
        override fun component1() = value
        override fun component2(): (Tool) -> Unit = { value = it }
    }
    // A slider drag changes the options every frame; the page reads [options] at once, but the
    // preference write waits until the drag settles so it never competes with drawing.
    private val saver = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSave: Pair<ToolOptions, Tool>? = null
    private val flush = Runnable { flushSave() }
    fun flushSave() {
        saver.removeCallbacks(flush)
        pendingSave?.let { (value, owner) -> value.save(prefs, owner) }
        pendingSave = null
    }
    fun changeOptions(value: ToolOptions) {
        options.value = value
        pendingSave = value to tool.value
        saver.removeCallbacks(flush)
        saver.postDelayed(flush, 250)
    }
    fun optionsFor(value: Tool): ToolOptions = if (value == tool.value) options.value else { flushSave(); ToolOptions.load(prefs, value) }
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
    val searchMatches = mutableStateOf<List<Int>>(emptyList())
    val searchMatch = mutableIntStateOf(0)
    val searchCaption = mutableStateOf("")
    /** The results bar reopens the search it came from: PDF text or the notes. */
    val searchFromPdf = mutableStateOf(false)
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

/** A tool name from saved instance state; null if this build no longer has it. */
private fun savedTool(name: String?): Tool? = name?.takeIf(String::isNotEmpty)?.let { runCatching { Tool.valueOf(it) }.getOrNull() }

@Composable internal fun rememberEditorSession(noteId: String, prefs: SharedPreferences,
    initialTool: Tool, initialViewport: WorkspaceViewport): EditorSession {
    val tools = rememberSaveable(noteId, saver = Saver<EditorToolState, List<String>>(
        save = { listOf(it.tool.value.name, it.previousTool.value.name, it.eraserReturn.value?.name.orEmpty()) },
        restore = { values -> EditorToolState(prefs, savedTool(values.getOrNull(0)) ?: initialTool, savedTool(values.getOrNull(1)) ?: Tool.PEN).also {
            it.eraserReturn.value = savedTool(values.getOrNull(2))
        } },
    )) { EditorToolState(prefs, initialTool) }
    val viewport = rememberSaveable(noteId, saver = Saver<EditorViewportState, List<String>>(
        save = { listOf(it.zoom.floatValue.toString(), it.pan.floatValue.toString(), it.anchor?.pageId.orEmpty(), it.anchor?.localY?.toString().orEmpty()) },
        restore = { values ->
            val zoom = values.getOrNull(0)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            val pan = values.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() }
            val localY = values.getOrNull(3)?.toFloatOrNull()?.takeIf { it.isFinite() }
            EditorViewportState(if (zoom == null || pan == null) initialViewport else WorkspaceViewport(zoom = zoom, pan = pan)).also { v ->
                if (values.getOrNull(2)?.isNotEmpty() == true && localY != null) v.anchor = DocumentPageGeometry.Anchor(values[2], localY)
            }
        },
    )) { EditorViewportState(initialViewport) }
    val browser = rememberSaveable(noteId, saver = Saver<EditorBrowserState, List<String>>(
        save = { listOf(it.query.value, it.filter.value.name, it.number.value, it.grid.value.toString(), it.jumpExpanded.value.toString()) },
        restore = { EditorBrowserState().apply { query.value = it[0]; filter.value = runCatching { PageFilter.valueOf(it[1]) }.getOrDefault(PageFilter.ALL); number.value = it[2]; grid.value = it[3].toBoolean(); jumpExpanded.value = it[4].toBoolean() } },
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

/** A visit uses page identity so reordering cannot turn Back into an unrelated page jump. */
internal data class EditorVisit(val pageId: String, val anchor: DocumentPageGeometry.Anchor?,
    val zoom: Float, val pan: Float, val pageZoom: Float, val pagePan: Float, val camera: WorkspaceViewport?)
