@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compact icons retain full touch targets and spoken labels inside the floating toolbar.
 *
 * The active (highlighted) container is a circle: the toolbar's own corner is nearly square, so a
 * rounded square inside it read as a mismatched chip. Only the highlight has a visible shape, so
 * the circle keeps every state the same size and the row visually even.
 */
@Composable internal fun WritingFollowControl(
    icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()
    ) {
        IconButton(
            onClick, modifier = Modifier.size(40.dp), enabled = enabled,
            shape = CircleShape,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current
            )
        ) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

internal fun editorPaperLabel(p: Paper): String = when (p) {
    Paper.PLAIN -> "Plain"
    Paper.RULED -> "Ruled"
    Paper.SPLIT_RULED -> "Split ruled"
    Paper.DOTS -> "Dots"
    Paper.GRID -> "Grid"
    Paper.MATH_GRID -> "Maths grid"
    Paper.GRAPH -> "Graph (with axes)"
    Paper.MC_SHEET -> "Multiple choice"
    Paper.TIAN_GRID -> "Tian grid (田字格)"
    Paper.MI_GRID -> "Mi grid (米字格)"
}

@Composable internal fun EditorWorkspaceContent(editor: EditorSession,state: FolioState, model: FolioViewModel, finger: Boolean, haptics: Boolean, shapeRecognitionSetting: Boolean, onSettings: () -> Unit, onExport: () -> Unit, notebookActions: @Composable (() -> Unit) -> Unit = {}, music: MusicStage? = null, showBack: Boolean = true, onShareLongPress: (() -> Unit)? = null, onChatGpt: (() -> Unit)? = null, onOpenDocument: (() -> Unit)? = null) {
    val inputStylusActivity = LocalStylusActivity.current
    val note = state.active ?: return
    val page = state.page ?: return
    val context = LocalContext.current
    val session = state.tabs.find { it.notebookId == note.id }
    val prefs = context.getSharedPreferences("ink-tools", 0)
    val appPrefs = context.getSharedPreferences("preferences", 0)
    val preferences by rememberEditorPreferences(appPrefs, shapeRecognitionSetting)
    val palmRejectMs = preferences.palmRejectMs
    val panMultiplier = preferences.panMultiplier
    val pageFollowEnabled = preferences.pageFollowEnabled
    val snapEnabled = preferences.snapEnabled
    val shapeRecognition = preferences.shapeRecognition
    val eraserSingleStroke = preferences.eraserSingleStroke
    val eraserPressure = preferences.eraserPressure
    val scribbleToErase = preferences.scribbleToErase
    val scribbleSensitivity = preferences.scribbleSensitivity
    val eraserWholeStroke = preferences.eraserWholeStroke
    val shapeMeasurements = preferences.shapeMeasurements
    val multiTouchUndo = preferences.multiTouchUndo
    val graphStyle = preferences.graphStyle
    val singlePage = preferences.singlePage
    val focusMode = preferences.focusMode && music == null
    val showPageBoundaries = !focusMode
    fun undoEditor() { editor.documentInput.cancelNativeContacts(); model.undo() }
    fun redoEditor() { editor.documentInput.cancelNativeContacts(); model.redo() }
    val toolbarStyle by rememberPref(appPrefs, AppPrefs.TOOLBAR_STYLE) { AppPrefs.toolbarStyle(it.getString(AppPrefs.TOOLBAR_STYLE, null)) }
    val toolbarOptions by rememberPref(appPrefs, *ToolbarOptions.KEYS) { ToolbarOptions.read(it) }
    var tool by editor.tools.tool
    var previousTool by editor.tools.previousTool
    var palette by editor.overlays.palette

    // Starting prose is deliberate and local to this canvas visit, never a global preference.
    // Saved state, so rotating or resizing the window (which recreates the Activity) keeps both.
    val followSession = rememberEditorFollowState(note.id, page.id)
    var canvasResponse by followSession.canvasResponse
    var overviewReturn by followSession.overviewReturn
    val writingFollowEnabled = if (page.infinite) canvasResponse != null else pageFollowEnabled
    var writingFollowPaused by followSession.paused
    val autoDetectAnswerAreas by rememberPref(appPrefs, "follow.autoDetectAnswerAreas") { it.getBoolean("follow.autoDetectAnswerAreas", false) }
    val showAnswerAreas by rememberPref(appPrefs, "follow.showAnswerAreas") { it.getBoolean("follow.showAnswerAreas", true) }
    val writingHand by rememberPref(appPrefs, "writingHand", read = FollowPreferenceStore::hand)
    val followPreferences by rememberPref(appPrefs, *FollowPreferenceStore.keys, read = FollowPreferenceStore::read)
    val openFollowSettings = LocalOpenWritingFollowSettings.current ?: onSettings

    var followStatus by followSession.status
    val regionKey = "follow.region.${note.id}.${page.id}"
    val followCoordinator = remember(regionKey, appPrefs) { EditorFollowCoordinator(appPrefs, regionKey) }
    var writingRegion by followCoordinator.region
    var writingRegions by followCoordinator.regions
    val effectiveFollowPreferences = if (page.infinite)
        canvasResponse?.preferences(followPreferences) ?: followPreferences.copy(mode = FollowMode.TEXT, automaticReturn = false)
        else followPreferences
    var followMenu by remember { mutableStateOf(false) }
    // One peek view per notebook. A held peek closes on release; a tapped one stays until closed.
    val pinnedPeek = note.livePeekAnchor
    // Auto peek needs no pin: it is always the whole of the page being written on (a canvas has no page edge).
    val autoPeek by rememberPref(appPrefs, AppPrefs.AUTO_PEEK) { it.getBoolean(AppPrefs.AUTO_PEEK, false) }
    val peekAnchor = if (autoPeek && !page.infinite) PeekAnchor.wholePage(page) else pinnedPeek
    var peekMode by remember(note.id) { mutableStateOf<PeekMode?>(null) }
    // The view is fixed when the peek opens, so the page behind it changing can never re-frame it.
    var peekShown by remember(note.id) { mutableStateOf<PeekAnchor?>(null) }
    LaunchedEffect(peekMode) { if (peekMode == null) peekShown = null }
    LaunchedEffect(peekAnchor == null) { if (peekAnchor == null) peekMode = null }
    val peekOpen = peekMode != null && (peekShown ?: peekAnchor) != null
    // The read-only page inside a kept-open peek, for re-centring and pinning what it shows.
    val peekInkView = (peekShown ?: peekAnchor)?.let { editor.surfaces.find(EditorSurfaceRole.PEEK, it.pageId) }
    // "Go to" from a peek on a canvas frames the peeked area once that page's view is bound.
    var peekGoTo by remember(note.id) { mutableStateOf<PeekAnchor?>(null) }
    val quick = remember(prefs) { QuickColorsState(prefs) }
    val toolPresets = remember(prefs) { ToolPresetState(prefs) }
    val toolbarLayouts = rememberToolbarLayoutState(appPrefs)
    var options by editor.tools.options
    fun changeOptions(value: ToolOptions) = editor.tools.changeOptions(value)
    fun applyPreset(preset: ToolPreset) = editor.tools.apply(preset)
    fun setSnap(v: Boolean) { appPrefs.edit().putBoolean("mathSnap", v).apply() }
    // Eraser single-stroke + pressure + scribble-to-erase + whole-stroke + measurements + multitouch undo
    fun setEraserSingleStroke(v: Boolean) {
        appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, v).apply()
    }
    fun setEraserPressure(v: Boolean) { appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_PRESSURE, v).apply() }
    fun setScribbleToErase(v: Boolean) { appPrefs.edit().putBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, v).apply() }
    fun setEraserWholeStroke(v: Boolean) { appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, v).apply() }
    fun setShapeMeasurements(v: Boolean) { appPrefs.edit().putBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, v).apply() }
    fun setMultiTouchUndo(v: Boolean) { appPrefs.edit().putBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, v).apply() }
    // One encoded string, so the graph sheet and the page never disagree about the dressing.
    // Observe external pref changes (e.g. from ToolOptionsPanel): re-read when screen re-enters foreground

    val penHaptics = remember(context) { (context.applicationContext as? FolioApplication)?.penHaptics }
    fun selectTool(value: Tool): Boolean = editor.tools.select(value, eraserSingleStroke)
    fun finishSingleStrokeEraser() = editor.tools.finishEraser(eraserSingleStroke)
    DisposableEffect(penHaptics, haptics) {
        if (haptics) penHaptics?.start() else penHaptics?.stop()
        onDispose { penHaptics?.stop() }
    }
    val mainInkView = editor.surfaces.find(EditorSurfaceRole.MAIN, page.id)
    var zoomPaneOpen by rememberSaveable(note.id, page.id) { mutableStateOf(false) }
    var zoomTarget by followSession.zoomTarget
    var zoomWindow by rememberSaveable(note.id, page.id, stateSaver = Saver<PeekAnchor?, List<Any>>(
        save = { it?.let { a -> listOf(a.pageId, a.left, a.top, a.right, a.bottom) } ?: emptyList() },
        restore = { if (it.size == 5) PeekAnchor(it[0] as String, it[1] as Float, it[2] as Float, it[3] as Float, it[4] as Float) else null },
    )) { mutableStateOf<PeekAnchor?>(null) }
    val zoomInkView = editor.surfaces.find(EditorSurfaceRole.ZOOM, page.id)
    var zoomInitialState by followSession.zoomInitialState
    var zoomGesture by followSession.zoomGesture
    val zoomHeight by rememberPref(appPrefs, AppPrefs.ZOOM_PANE_HEIGHT) { AppPrefs.zoomPaneHeight(it.getFloat(AppPrefs.ZOOM_PANE_HEIGHT, AppPrefs.DEFAULT_ZOOM_PANE_HEIGHT)) }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(zoomPaneOpen) {
        if (zoomPaneOpen && zoomTarget == null) {
            zoomTarget = zoomWindow
            if (zoomTarget == null) zoomPaneOpen = false
        }
    }
    val editorDensity = LocalDensity.current
    val activeInkView = if (zoomPaneOpen) zoomInkView else mainInkView
    fun pasteInView() {
        val visible = activeInkView?.currentPeekAnchor()?.takeIf { it.pageId == page.id }
        model.pasteClipboard(visible?.let { InkPoint(it.left + 24f, it.top + 48f) })
    }
    val followView = activeInkView
    fun setWritingFollow(enabled: Boolean) {
        if (followView?.isWritingGesture == true) return
        if (page.infinite) {
            canvasResponse = if (enabled) {
                val anchor = followView?.currentPeekAnchor() ?: return
                CanvasWritingSession.start(WritingLane(anchor.left, anchor.top, anchor.right, anchor.bottom), followPreferences)
                    ?: return
            } else null
            if (enabled) selectTool(Tool.PEN)
        } else {
            appPrefs.edit().putBoolean("writingFollow", enabled).apply()
        }
        writingFollowPaused = false
        followView?.let { view ->
            view.canvasWritingSession = if (page.infinite) canvasResponse else null
            view.followPreferences = canvasResponse?.preferences(followPreferences) ?: followPreferences
            view.resumeWritingFollow()
            view.followEnabled = enabled && overviewReturn == null
        }
    }
    fun openZoomPane() {
        val view = mainInkView?.takeIf { it.page.id == page.id } ?: return
        if (view.isWritingGesture || peekOpen || overviewReturn != null) return
        val visible = view.currentPeekAnchor() ?: return
        val viewport = WritingLane(visible.left, visible.top, visible.right, visible.bottom)
        if (!writingFollowEnabled) setWritingFollow(true)
        val area = if (page.infinite) canvasResponse?.column ?: return
            else writingRegion ?: WritingGuides.regionAt(WritingGuides.regions(view.writingGuides), viewport.centerX,
                view.writingFollow.state.baselineY ?: viewport.top + viewport.height * .5f)
                ?: WritingLane(36f, 0f, page.width - 36f, page.height - 24f)
        if (!page.infinite && area != writingRegion && area.top > 0f) {
            writingRegion = area
            appPrefs.edit().putString(regionKey, "${area.left},${area.top},${area.right},${area.bottom}").apply()
        }
        zoomInitialState = view.writingFollow.state
        val start = view.writingFollow.state.baselineY ?: if (writingRegion == null && !page.infinite)
            viewport.top + viewport.height * .5f else null
        val window = ZoomWriting.initial(area, viewport, effectiveFollowPreferences.direction,
            effectiveFollowPreferences.spacing, start)
        zoomTarget = PeekAnchor(page.id, window.left, window.top, window.right, window.bottom)
        zoomWindow = zoomTarget
        view.holdWritingFollow(); view.clearSelection()
        zoomPaneOpen = true
        writingFollowPaused = false
    }
    fun closeZoomPane() {
        if (zoomInkView?.isWritingGesture == true) return
        val tracking = zoomInkView?.writingFollow?.state
        zoomInkView?.pauseWritingFollow()
        mainInkView?.liveInkPreview = null
        mainInkView?.followLandingPreview = null
        zoomPaneOpen = false; zoomGesture = false; zoomWindow = null
        writingFollowPaused = true
        tracking?.let { mainInkView?.writingFollow?.state = it }
        mainInkView?.writingIndent = zoomInkView?.writingIndent
    }
    BackHandler(enabled = zoomPaneOpen && !peekOpen) { closeZoomPane() }
    LaunchedEffect(writingFollowEnabled, zoomPaneOpen, zoomGesture) {
        if (zoomPaneOpen && !writingFollowEnabled && !zoomGesture) closeZoomPane()
    }
    fun applyStylusShortcut(action: StylusShortcut) {
        val effect = StylusShortcuts.effect(action, tool, previousTool)
        if (effect != StylusShortcutEffect.None) penHaptics?.pulse()
        when (effect) {
            is StylusShortcutEffect.SwitchTool -> selectTool(effect.tool)
            StylusShortcutEffect.OpenPalette -> palette = true
            StylusShortcutEffect.Undo -> undoEditor()
            StylusShortcutEffect.NextLine -> {
                // A next-line shortcut must not silently put free canvas work into prose mode.
                if (!writingFollowEnabled && !page.infinite) setWritingFollow(true)
                if (!peekOpen && overviewReturn == null) followView?.nextWritingLine()
            }
            StylusShortcutEffect.None -> Unit
        }
    }
    LaunchedEffect(tool) { if (StylusShortcuts.isDrawingTool(tool)) previousTool = tool }
    val shortcutHandler = rememberUpdatedState<(StylusShortcut) -> Unit> { applyStylusShortcut(it) }
    val shortcuts = remember(context) { StylusShortcutManager(context) }
    DisposableEffect(shortcuts) {
        shortcuts.start { shortcutHandler.value(StylusShortcut.of(appPrefs.getString(StylusShortcut.PREF_KEY, null))) }
        onDispose { shortcuts.stop() }
    }
    var selection by editor.interaction.selection
    val transfers = editor.interaction.transfers
    SideEffect {
        transfers.update(note.pages)
        editor.documentInput.configure(note, model, with(editorDensity) { if (showPageBoundaries || note.pages.any { it.pdfIndex != null }) 6.dp.toPx() else 0f })
    }
    // Each page's whole item (paper plus the workspace around it) and the sticky note being carried over all of them.

    var stickyLift by editor.interaction.stickyLift
    var objectLift by editor.interaction.objectLift
    var moveSelection by remember { mutableStateOf<Pair<String, CanvasSelection>?>(null) }
    val canPaste = context.getSystemService(android.content.ClipboardManager::class.java)?.hasPrimaryClip() == true
    val documentSelection by editor.interaction.documentSelection
    val selected = documentSelection[page.id] ?: selection?.takeIf { it.first == page.id }?.second ?: CanvasSelection()
    /** Selection frame in view fractions for the context menu; cleared with the page. */
    var selectionAnchor by remember(page.id) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(page.id) { selection = null }
    LaunchedEffect(tool) { selection = null; editor.documentInput.clearSelection() }
    // The bound canvas, so toolbar actions can drive it directly (select-all fallback, deselect).
    LaunchedEffect(effectiveFollowPreferences, writingHand) { followView?.suspendWritingFollow(clearBack = false) }
    // Changing tool mid-line (erase a word, then write again) keeps the line; it only stops movement.
    LaunchedEffect(tool) { followView?.holdWritingFollow() }
    fun configureFollow(view: InkView) {
        followCoordinator.configure(view, page.infinite, canvasResponse, effectiveFollowPreferences, writingFollowPaused,
            onStatus = { if (view === activeInkView) followStatus = it })
    }
    fun configureOverview(view: InkView) {
        configureFollow(view)
        view.navigationOnly = zoomPaneOpen
        view.writingWindow = if (zoomPaneOpen) zoomWindow else null
        view.onWritingWindowRequest = { anchor ->
            if (!zoomGesture) { zoomTarget = anchor; zoomWindow = anchor }
        }
    }
    // Text defaults live with the app, not the notebook, so a new label keeps the last look.
    var textSize by rememberSaveable { mutableFloatStateOf(appPrefs.getFloat("text.size", 26f)) }
    var textColor by rememberSaveable { mutableIntStateOf(appPrefs.getInt("text.color", 0xFF303431.toInt())) }
    var textBold by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.bold", false)) }
    var textItalic by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.italic", false)) }
    var textAlign by rememberSaveable { mutableStateOf(try { TextAlignMode.valueOf(appPrefs.getString("text.align", "LEFT") ?: "LEFT") } catch (_: Exception) { TextAlignMode.LEFT }) }
    var textUnderline by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.underline", false)) }
    var textOpacity by rememberSaveable { mutableFloatStateOf(appPrefs.getFloat("text.opacity", TextBox.DEFAULT_OPACITY)) }
    val textDraftSaver = remember { Saver<InlineTextSession?, String>(
        save = { draft -> draft?.let { org.json.JSONObject().put("page", it.pageId)
            .put("box", InkCodec.encodeTexts(listOf(it.box))).toString() } ?: "" },
        restore = { raw -> runCatching { val json = org.json.JSONObject(raw)
            InlineTextSession(json.getString("page"), InkCodec.decodeTexts(json.getJSONArray("box")).first()) }.getOrNull() }
    ) }
    val textEditorState = rememberSaveable(note.id, stateSaver = textDraftSaver) { mutableStateOf<InlineTextSession?>(null) }
    var textEditor by textEditorState
    val textKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Notebook-wide typed-text search and reusable diagram elements.
    var noteSearchOpen by editor.overlays.noteSearch
    val searchQuery = rememberSaveable(note.id) { mutableStateOf("") }
    var layersPopover by remember { mutableStateOf(false) }
    var inkConverting by remember { mutableStateOf(false) }
    // The straightedge is a toolbar switch for this visit. It sits on the current page: a page that
    // has not had it yet places it across the middle of the screen; one that has keeps where it was put.
    var rulerOn by remember(note.id) { mutableStateOf(false) }
    var rulers by remember(note.id) { mutableStateOf<Map<String, RulerLine>>(emptyMap()) }
    var keyboardShortcuts by editor.overlays.keyboardShortcuts
    var responseAttempts by rememberSaveable { mutableStateOf(false) }
    var markingColor by remember { mutableIntStateOf(appPrefs.getInt(Marking.PREF_COLOR, Marking.DEFAULT_COLOR)) }
    var markAssist by remember { mutableStateOf(appPrefs.getBoolean(Marking.PREF_ASSIST, true)) }
    var markScanStatus by remember(note.id) { mutableStateOf<String?>(null) }
    var markScanBusy by remember(note.id) { mutableStateOf(false) }
    var scannedZones by remember(note.id) { mutableStateOf(emptyList<MarkZone>()) }
    // Allocations the marker boxed by hand because the scan missed them; kept per notebook.
    var manualZones by remember(note.id) { mutableStateOf(MarkZones.decode(appPrefs.getString("marking.zones.${note.id}", null))) }
    val markZones = scannedZones + manualZones
    fun saveManualZones(next: List<MarkZone>) {
        manualZones = next
        appPrefs.edit().putString("marking.zones.${note.id}", MarkZones.encode(next)).apply()
    }
    // Holds the selection being restyled, so the sheet always edits from the original strokes.
    var restylePicked by remember { mutableStateOf<Pair<String, CanvasSelection>?>(null) }
    var restyleSelection by remember { mutableStateOf<List<Stroke>?>(null) }
    // The picture tapped with the hand tool, so the editor can offer delete and layering.
    var selectedImage by editor.interaction.selectedImage
    LaunchedEffect(page.id) { if (selectedImage?.first != page.id) selectedImage = null }
    // The picture being cropped; separate from the selection so the panel stays put underneath.
    var cropActive by editor.interaction.cropActive
    var inkNavigating by editor.interaction.navigating
    LaunchedEffect(page.id) { cropActive = false }
    var pageMenu by remember { mutableStateOf<Triple<Float, Float, InkPoint>?>(null) }
    LaunchedEffect(page.id) { pageMenu = null }
    fun rememberTextLook(box: TextBox) {
        textSize = box.size; textColor = box.color; textBold = box.bold; textItalic = box.italic
        textAlign = box.align; textUnderline = box.underline
        textOpacity = box.opacity.coerceIn(TextBox.MIN_OPACITY, TextBox.MAX_OPACITY)
        appPrefs.edit().putFloat("text.size", box.size).putInt("text.color", box.color)
            .putBoolean("text.bold", box.bold).putBoolean("text.italic", box.italic)
            .putString("text.align", box.align.name).putBoolean("text.underline", box.underline)
            .putFloat("text.opacity", textOpacity).apply()
    }
    /** A toolbar quick colour: boxes created after this start with it. */
    fun setTextColor(value: Int) {
        textColor = value
        textEditor = textEditor?.let { it.copy(box = it.box.copy(color = value)) }
        appPrefs.edit().putInt("text.color", value).apply()
    }
    fun finishTextEditing(hideKeyboard: Boolean = true) {
        val draft = textEditor ?: return
        textEditor = null
        model.commitText(draft.pageId, draft.box)
        rememberTextLook(draft.box)
        if (hideKeyboard) textKeyboard?.hide()
    }
    fun changeTextDraft(box: TextBox) {
        textEditor = textEditor?.takeIf { it.box.id == box.id }?.copy(box = box) ?: textEditor
    }
    fun editTextBox(target: NotePage, box: TextBox) {
        if (textEditor?.box?.id == box.id) return
        finishTextEditing(hideKeyboard = false)
        textEditor = InlineTextSession(target.id, box)
        rememberTextLook(box)
    }
    /** A tap on bare page drops a fresh text box where the finger landed, clear of the right edge. */
    fun placeTextBox(target: NotePage, at: InkPoint) {
        finishTextEditing(hideKeyboard = false)
        val layer = model.activeLayerOf(target)
        if (!PageLayers.editable(target.layers, layer)) { mainInkView?.onLayerBlocked?.invoke(); return }
        val x = if (target.infinite) at.x else at.x.coerceIn(0f, (target.width - TextBox.MIN_WIDTH - 16f).coerceAtLeast(0f))
        val width = if (target.infinite) TextBox.DEFAULT_WIDTH else (target.width - x - 16f).coerceIn(TextBox.MIN_WIDTH, TextBox.DEFAULT_WIDTH)
        textEditor = InlineTextSession(target.id, TextBox(x = x, y = at.y, width = width, text = "", size = textSize,
            color = textColor, bold = textBold, italic = textItalic, align = textAlign, underline = textUnderline,
            opacity = textOpacity, layer = layer))
    }
    LaunchedEffect(page.id, tool, page.layers, music?.performance) {
        if (textEditor?.pageId != page.id || tool != Tool.TEXT || music?.performance == true ||
            textEditor?.box?.let { !PageLayers.editable(page.layers, it.layer) } == true) finishTextEditing()
    }
    val textLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(note.id, textLifecycle) {
        // Read the session holder directly so teardown uses the old notebook's latest draft.
        fun flushDraft() {
            val draft = textEditorState.value ?: return
            textEditorState.value = null
            model.commitText(draft.pageId, draft.box)
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) flushDraft()
        }
        textLifecycle.lifecycle.addObserver(observer)
        onDispose { textLifecycle.lifecycle.removeObserver(observer); flushDraft() }
    }
    BackHandler(enabled = textEditor != null) { finishTextEditing() }
    var pageEdgeLeft by remember { mutableStateOf(true) }
    var pageEdgeRight by remember { mutableStateOf(true) }
    val paged = music == null && singlePage && !page.infinite
    var documentZoom by if (paged || music != null || page.infinite) editor.viewport.pageZoom else editor.viewport.zoom
    var documentPan by if (paged || music != null || page.infinite) editor.viewport.pagePan else editor.viewport.pan
    var pageBrowser by editor.overlays.pageBrowser
    var namedPage by editor.overlays.namedPage
    var pageTitle by editor.overlays.pageTitle
    var movingPage by editor.overlays.movingPage
    var destinationPage by editor.overlays.destinationPage
    var deletingPage by editor.overlays.deletingPage
    var rename by editor.overlays.rename
    var renameTitle by editor.overlays.renameTitle
    var scrubbing by editor.interaction.scrubbing
    var clear by editor.overlays.clear
    var paperMenu by editor.paper.open
    var nextPaperMenu by editor.paper.next
    var nextPagePaper by editor.paper.nextPaper
    var chosenPaper by editor.paper.chosen
    var savePaperDefault by editor.paper.saveDefault
    fun openPaperMenu(next: Boolean) {
        nextPaperMenu = next
        chosenPaper = if (next) nextPagePaper ?: note.defaultPaper ?: page.paper else page.paper
        savePaperDefault = false
        paperMenu = true
    }
    var timerPanel by editor.overlays.timer
    var studyPanel by editor.overlays.study
    var examPanel by editor.overlays.exam
    var markDialog by editor.overlays.markDialog
    // A mark just recorded for Focal, awaiting confirmation in the save review.
    var pdfSearchOpen by editor.overlays.pdfSearch
    var pdfContentsOpen by editor.overlays.pdfContents
    var pdfOutline by remember(note.id) { mutableStateOf<List<PdfOutlineEntry>?>(null) }
    var pdfLinks by remember(note.id) { mutableStateOf(emptyList<PdfLink>()) }
    // Drives the countdown once a second; a stopped timer's tick is a no-op, so nothing recomposes.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            model.tickTimer()
        }
    }
    // The exam clock only runs while the pages are on screen: leaving the editor for the library
    // or the mistakes list parks it. Rotation tears the composition down too, so it is skipped
    // the same way as the activity stop — the timer keeps running across a rotate.
    val hostActivity = context as? android.app.Activity
    DisposableEffect(Unit) {
        onDispose { if (hostActivity?.isChangingConfigurations != true) model.autoPauseTimer() }
    }
    val pages = rememberLazyListState(initialFirstVisibleItemIndex = session?.viewport?.documentPageId?.let { id -> note.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: state.pageIndex, initialFirstVisibleItemScrollOffset = session?.viewport?.scrollOffset ?: 0)
    // Single-page view: one fitted page at a time, turned by swiping or the corner arrows. A
    // per-device preference; infinite canvases and scores keep their own layouts.
    var savedCanvas by remember(page.id) { mutableStateOf(session?.viewport ?: WorkspaceViewport()) }
    LaunchedEffect(note.id, pages) {
        snapshotFlow {
            val anchor = editor.viewport.anchor
            // Offset and page identity refer to the same document anchor, independently of
            // the most-visible page used by shared page chrome.
            val offset = anchor?.pageId?.let { id -> pages.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let { -it.offset } }
                ?: pages.firstVisibleItemScrollOffset
            WorkspaceViewport(editor.viewport.zoom.floatValue, editor.viewport.pan.floatValue, offset,
                savedCanvas.canvasX, savedCanvas.canvasY, savedCanvas.canvasZoom,
                editor.viewport.anchor?.pageId, editor.viewport.anchor?.localY) to tool
        }
            .distinctUntilChanged()
            .debounce(250)
            .collect { (viewport, selectedTool) ->
                model.updateTabViewport(note.id, viewport, selectedTool)
            }
    }
    val saveOnLeave by rememberUpdatedState<() -> Unit>({
        val anchorId = editor.viewport.anchor?.pageId
        val offset = pages.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorId }?.let { -it.offset }
            ?: pages.firstVisibleItemScrollOffset
        model.updateTabViewport(note.id, WorkspaceViewport(
            editor.viewport.zoom.floatValue, editor.viewport.pan.floatValue, offset,
            savedCanvas.canvasX, savedCanvas.canvasY, savedCanvas.canvasZoom,
            editor.viewport.anchor?.pageId, editor.viewport.anchor?.localY), editor.tools.tool.value)
    })
    DisposableEffect(note.id) { onDispose { saveOnLeave() } }
    val scope = rememberCoroutineScope()
    val motionDensity = LocalDensity.current.density
    val motion = remember(pages, note.id, motionDensity) { DocumentMotion(pages::dispatchRawDelta, scope, motionDensity) }
    DisposableEffect(motion) { onDispose { motion.reset() } }
    var pullAdding by remember { mutableStateOf(false) }
    val pullHaptics = LocalHapticFeedback.current
    var canvasReset by remember { mutableIntStateOf(0) }
    fun resetZoom() {
        activeInkView?.suspendWritingFollow()
        canvasReset++
        motion.reset()
        pages.requestScrollToItem(pages.firstVisibleItemIndex, (pages.firstVisibleItemScrollOffset / documentZoom).roundToInt())
        documentZoom = 1f
        documentPan = 0f
    }
    fun returnToWorking() {
        val previous = overviewReturn ?: return
        val view = (if (zoomPaneOpen) mainInkView else activeInkView) ?: return
        if (view.isWritingGesture) return
        view.returnToCanvasView(previous)
        overviewReturn = null
    }
    /** Inspect all working without losing the exact position and zoom being used. */
    fun fitAllContent() {
        if (!page.infinite || !page.loaded) return
        val view = (if (zoomPaneOpen) mainInkView else activeInkView) ?: return
        if (view.isWritingGesture) return
        if (overviewReturn != null) { returnToWorking(); return }
        overviewReturn = view.canvasView()
        zoomInkView?.holdWritingFollow()
        view.followEnabled = false
        view.fitCanvas(
            InkGeometry.contentBounds(page.strokes, page.texts, page.images, { InkRenderer.textHeight(it) }, page.width, page.height)
        )
    }
    val viewportCoordinator = remember(note.id, pages, motion) { EditorViewportCoordinator(pages, motion, model, note.id, editor.viewport) }
    fun jumpTo(index: Int) {
        finishTextEditing()
        activeInkView?.suspendWritingFollow()
        viewportCoordinator.jump(index)
    }
    /** Previous/next page in either view; the single-page view starts each page fitted. */
    fun turnPage(forward: Boolean) {
        val live = model.state.value.takeIf { it.activeId == note.id } ?: return
        val last = live.active?.pages?.lastIndex ?: return
        val target = (live.pageIndex + if (forward) 1 else -1).coerceIn(0, last)
        if (target != live.pageIndex) jumpTo(target)
    }
    fun jumpToEnd(last: Boolean) {
        val live = model.state.value.takeIf { it.activeId == note.id } ?: return
        val target = if (last) live.active?.pages?.lastIndex ?: return else 0
        if (target != live.pageIndex) jumpTo(target)
    }
    // Every way of changing page (thumbnails, search, outline, delete, undo) starts the next page fitted.
    LaunchedEffect(page.id, paged) { if (paged) { canvasReset++; documentZoom = 1f; documentPan = 0f } }
    fun setSinglePage(on: Boolean) {
        editor.documentInput.clearSelection()
        if (!paged) viewportCoordinator.capture()
        appPrefs.edit().putBoolean(AppPrefs.SINGLE_PAGE_VIEW, on).apply()
        activeInkView?.suspendWritingFollow()
        motion.reset()
    }
    /** Follows a tapped PDF link: another page jumps there, a web address opens in the browser. */
    fun openPdfLink(link: PdfLink) {
        when (val target = link.target) {
            is PdfLinkTarget.Page -> jumpTo(target.pageIndex.coerceIn(0, note.pages.lastIndex))
            is PdfLinkTarget.Url -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target.uri)))
                } catch (_: Exception) { model.reportError("Couldn't open this link") }
            }
        }
    }
    /** Opens the contents panel, reading the PDF's bookmarks the first time it is needed. */
    fun loadOutline() {
        if (pdfOutline != null) return
        scope.launch {
            pdfOutline = try { model.repository.pdfOutline(note.id) } catch (_: Exception) { emptyList() }
        }
    }
    fun addPage() {
        model.addPage(nextPagePaper)
        nextPagePaper = null
        jumpTo(model.state.value.pageIndex)
    }
    fun pullAddPage() {
        pullAdding = true
        scope.launch {
            // Hold the spinner briefly so the pull reads as a deliberate action, then add and reveal.
            delay(280)
            addPage()
            delay(450)
            pullAdding = false
        }
    }
    SideEffect {
        motion.pullEnabled = { !pullAdding && appPrefs.getBoolean(EditorQuickPrefs.PULL_TO_ADD_PAGE, true) && note.pages.lastOrNull()?.infinite == false && peekShown == null }
        motion.onPullCommit = { pullAddPage() }
    }
    /** A pending scroll request lands after the new page is measured, in either layout. */
    fun revealNewPage(index: Int) { jumpTo(index) }
    fun selectAllInk() {
        editor.documentInput.clearSelection()
        val view = activeInkView
        if (view != null) view.selectAll()
        else if (page.strokes.isNotEmpty() || page.texts.isNotEmpty() || page.images.isNotEmpty()) {
            // Fallback when view not yet bound (e.g. immediate toolbar tap after page switch)
            tool = Tool.LASSO
            selection = page.id to CanvasSelection(page.strokes.toList(), page.texts.toList(), page.images.toList())
        }
    }
    /**
     * Reads the lasso's handwriting and swaps it for one typed box, as one undoable step. The
     * recogniser runs off the page, so drawing and navigation stay available while it works.
     */
    fun convertSelectionToText(pageId: String, lasso: CanvasSelection) {
        if (inkConverting || lasso.strokes.isEmpty() && lasso.texts.none { it.stickyInk.isNotEmpty() }) return
        val sourcePage = note.pages.firstOrNull { it.id == pageId } ?: return
        inkConverting = true
        scope.launch {
            try {
                val after = InkToText.convert(sourcePage, lasso)
                val edit = PageJournal.diff(sourcePage.content(), after)
                if (edit == null) model.reportError("No readable text in this selection")
                else if (model.commitDocumentEdits(note.id, mapOf(pageId to edit), mapOf(pageId to sourcePage.revision))) {
                    activeInkView?.clearSelection(); selection = null
                } else model.reportError("The document changed while reading handwriting; select it again")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                model.reportError("Couldn't read this handwriting: ${e.message.orEmpty()}")
            } finally {
                inkConverting = false
            }
        }
    }
    /** Decodes a picked picture, stores it beside the notebook and places it centred on the page. */
    fun insertImage(uri: android.net.Uri) {
        val target = state.page ?: return
        scope.launch {
            try {
                val (bytes, srcWidth, srcHeight) = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val raw = input.readBytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                        var sample = 1
                        while (kotlin.math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                            ?: error("This picture could not be opened")
                        val capped = if (kotlin.math.max(decoded.width, decoded.height) > 2048) {
                            val s = 2048f / kotlin.math.max(decoded.width, decoded.height)
                            Bitmap.createScaledBitmap(decoded, (decoded.width * s).toInt().coerceAtLeast(1),
                                (decoded.height * s).toInt().coerceAtLeast(1), true).also { decoded.recycle() }
                        } else decoded
                        val out = ByteArrayOutputStream()
                        check(capped.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "This picture could not be saved" }
                        val w = capped.width; val h = capped.height
                        capped.recycle()
                        Triple(out.toByteArray(), w, h)
                    } ?: error("This picture could not be opened")
                }
                val maxWidth = if (target.infinite) 560f else (target.width - 96f).coerceIn(200f, 640f)
                val (w, h) = InkGeometry.fitImage(srcWidth.toFloat(), srcHeight.toFloat(), maxWidth)
                val x = if (target.infinite) -w / 2f else (target.width - w) / 2f
                val y = if (target.infinite) -h / 2f else (target.height - h) / 2f
                val image = PageImage(
                    x = if (target.infinite) x else x.coerceAtLeast(0f),
                    y = if (target.infinite) y else y.coerceAtLeast(0f),
                    width = w, height = h
                )
                model.addImage(image, bytes, target.id)
                selectedImage = target.id to image
                if (tool != Tool.HAND) selectTool(Tool.HAND)
            } catch (e: Exception) {
                model.reportError("Couldn't add this picture: ${e.message.orEmpty()}")
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            insertImage(it)
        }
    }
    // Link rectangles arrive once per notebook; a native notebook simply has none.
    LaunchedEffect(note.id) {
        pdfLinks = if (note.pages.any { it.pdfIndex != null }) {
            try { model.repository.pdfPageLinks(note.id, note.pages) } catch (_: Exception) { emptyList() }
        } else emptyList()
    }
    val markPageGeometry = note.pages.map { Triple(it.pdfIndex, it.width, it.height) }
    LaunchedEffect(note.id, markAssist, markPageGeometry) {
        scannedZones = emptyList()
        markScanStatus = null
        if (markAssist && note.pages.any { it.pdfIndex != null }) {
            markScanBusy = true
            markScanStatus = "Looking for printed marks…"
            try {
                scannedZones = model.repository.pdfMarkZones(note.id, note.pages) { zones, status ->
                    scannedZones = zones
                    markScanStatus = status
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                markScanStatus = "Couldn’t read printed marks. Turn the switch off and on to retry."
            } finally { markScanBusy = false }
        }
    }
    var handledNavigation by remember(note.id) { mutableIntStateOf(state.navigationRequest) }
    LaunchedEffect(note.id, page.infinite, note.pages.size, state.navigationRequest, paged) {
        if (handledNavigation != state.navigationRequest) {
            activeInkView?.suspendWritingFollow()
            motion.reset()
            if (!page.infinite) {
                val target = state.pageIndex.coerceIn(0, note.pages.lastIndex)
                viewportCoordinator.requestCurrent()
            }
            handledNavigation = state.navigationRequest
        }
        // A score has no scrolling column: the stage decides which pages are on view.
        if (page.infinite || music != null || paged) return@LaunchedEffect
        snapshotFlow {
            val layout = pages.layoutInfo
            viewportCoordinator.generation to PageNavigation.Viewport(
                PageNavigation.Position(pages.firstVisibleItemIndex, pages.firstVisibleItemScrollOffset),
                layout.visibleItemsInfo.map { it.index }.toSet(), pages.canScrollForward,
                visibleCurrentPage(pages, note.pages.size), layout)
        }.collect { (_, viewport) ->
            val live = model.state.value.active?.takeIf { it.id == note.id } ?: return@collect
            viewportCoordinator.observe(viewport)
            viewportCoordinator.capture()
        }
    }
    // The pages beside the open one are read before they are scrolled to, so previous/next and
    // the fast-scroll thumb land on ink instead of a spinner. Loading is deduplicated in the
    // ViewModel, so asking twice costs nothing.
    LaunchedEffect(note.id, state.pageIndex) {
        // Two pages each way: the binary snapshots are cheap to read, and a page that is already in
        // memory when it scrolls in never shows a loading spinner.
        for (offset in 1..2) {
            note.pages.getOrNull(state.pageIndex - offset)?.let { model.loadPage(it.id) }
            note.pages.getOrNull(state.pageIndex + offset)?.let { model.loadPage(it.id) }
        }
    }
    BackHandler(enabled = (selected.isNotEmpty() || documentSelection.isNotEmpty()) && restyleSelection == null) {
        if (documentSelection.isNotEmpty()) editor.documentInput.clearSelection() else activeInkView?.clearSelection()
        selection = null
    }
    BackHandler(enabled = cropActive || selectedImage != null) {
        if (cropActive) activeInkView?.endImageCrop(false)
        else { selectedImage = null; activeInkView?.clearImageSelection() }
    }
    var editorChromeHeightPx by remember { mutableFloatStateOf(0f) }
    var canvasWindowBounds by remember(note.id) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(tool) {
        if (zoomPaneOpen && tool in setOf(Tool.TEXT, Tool.LASSO, Tool.MARK_AREA, Tool.STICKY_NOTE, Tool.LASER)) closeZoomPane()
    }
    val sharedSurface = InkSurfaceConfig(tool = tool,
        options = options,
        finger = finger,
        snapEnabled = snapEnabled,
        shapeRecognition = shapeRecognition,
        eraserPressureEnabled = eraserPressure,
        scribbleToErase = scribbleToErase,
        scribbleSensitivity = scribbleSensitivity,
        eraserWholeStroke = eraserWholeStroke,
        shapeMeasurements = shapeMeasurements,
        multiTouchUndo = multiTouchUndo,
        graphStyle = graphStyle,
        palmRejectMs = palmRejectMs,
        panMultiplier = panMultiplier,
        inkStyle = options.style)
    Column(Modifier.fillMaxSize().onSizeChanged { editorSize = it }.onPreviewKeyEvent { event ->
        if (textEditor != null && event.type == KeyEventType.KeyDown &&
            (event.key == Key.Escape || (event.key == Key.Enter && event.isCtrlPressed))) { finishTextEditing(); true }
        else if (textEditor == null && music != null && music.onKey(event.nativeKeyEvent)) true
        else if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) when (event.key) {
            Key.Z -> { finishTextEditing(); if (event.isShiftPressed) redoEditor() else undoEditor(); true }
            Key.Y -> { finishTextEditing(); redoEditor(); true }
            Key.F -> { finishTextEditing(); if (page.pdfIndex != null) pdfSearchOpen = true else noteSearchOpen = true; true }
            Key.G -> { finishTextEditing(); pageBrowser = true; editor.browser.jumpExpanded.value = true; true }
            Key.Zero -> { resetZoom(); true }
            else -> false
        } else if (event.type == KeyEventType.KeyDown && event.isAltPressed && writingFollowEnabled && overviewReturn == null &&
            !peekOpen && textEditor == null && event.key in setOf(Key.DirectionDown, Key.DirectionUp, Key.Backspace)) {
            // Keyboard writing follow: Alt+Down next line, Alt+Up previous line, Alt+Backspace back.
            when (event.key) {
                Key.DirectionDown -> activeInkView?.nextWritingLine()
                Key.DirectionUp -> activeInkView?.previousWritingLine()
                else -> activeInkView?.backWritingView()
            }
            true
        } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) when {
            cropActive -> { activeInkView?.endImageCrop(false); true }
            selectedImage != null -> { selectedImage = null; activeInkView?.clearImageSelection(); true }
            documentSelection.isNotEmpty() -> { editor.documentInput.clearSelection(); true }
            selected.isNotEmpty() -> { activeInkView?.clearSelection(); selection = null; true }
            pageMenu != null -> { pageMenu = null; true }
            else -> false
        } else false
    }) {
        LongResponseBar(note, page, model, onAttempts = { responseAttempts = true })
        // Both rows overlay the same canvas. Measure the dock so page/scroll affordances
        // stay reachable with larger accessibility text as well as compact windows.
        var floatingToolbarTop by remember { mutableStateOf(120.dp) }
        // A score clears the whole tool strip, quick bar included. Measured in full but applied once it
        // settles, so opening or closing the bar re-fits the sheet once instead of every frame.
        var musicToolbarFull by remember { mutableIntStateOf(0) }
        var musicToolbarDp by remember { mutableStateOf(64.dp) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.localToWindow(Offset.Zero)
                canvasWindowBounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            }) {
            val density = LocalDensity.current
            if (music != null) LaunchedEffect(Unit) {
                snapshotFlow { musicToolbarFull }.collectLatest { full ->
                    if (full > 0) { delay(160); musicToolbarDp = with(density) { full.toDp() } + FolioSpacing.dp6 }
                    else musicToolbarDp = FolioSpacing.dp6
                }
            }
            val musicInsets = music?.let { stage ->
                MusicStageInsets(
                    top = if (stage.performance) MusicStageMetrics.PERFORMANCE_TOP
                        else (if (stage.showToolbar) musicToolbarDp else FolioSpacing.dp6) + FolioSpacing.dp4,
                    bottom = MusicStageMetrics.BOTTOM,
                    side = if (stage.performance) MusicStageMetrics.PERFORMANCE_GUTTER else MusicStageMetrics.RAIL_GUTTER,
                    gap = MusicStageMetrics.GAP)
            }
            val selectionViewport = canvasWindowBounds?.let { bounds ->
                bounds.copy(top = (bounds.top + with(density) { floatingToolbarTop.toPx() }).coerceAtMost(bounds.bottom))
            }
            val viewportWidth = with(density) { maxWidth.toPx() }
            val baseWidth = (maxWidth - 20.dp).coerceAtMost(900.dp)
            val baseWidthPx = with(density) { baseWidth.toPx() }
            // Sticky notes can live beside the paper, so the document is wider than its pages.
            val workspaceSide = remember(note.pages) { StickyNotes.workspaceSide(note.pages) }
            val documentScale = 1f + 2f * workspaceSide
            val stripWidth = FastScrollStripWidth
            val stripInset = FolioSpacing.dp2
            val trackTop = floatingToolbarTop + FolioSpacing.dp8
            val trackBottom = FolioSpacing.dp16
            LaunchedEffect(viewportWidth, baseWidthPx, documentScale) {
                documentPan = DocumentViewport.clampPan(documentPan, baseWidthPx * documentScale * documentZoom, viewportWidth)
            }
            fun panBy(dx: Float, dy: Float) {
                activeInkView?.suspendWritingFollow()
                documentPan = DocumentViewport.clampPan(documentPan + dx, baseWidthPx * documentScale * documentZoom, viewportWidth)
                motion.drag(dy)
            }
            /** Deselect without changing the active tool. */
            fun dismissSelection() { activeInkView?.clearSelection(); selection = null }
            /** Compact selection actions; secondary commands live in the overflow menu. */
            val selectionMenu: @Composable (Dp) -> Unit = { availableWidth ->
                SelectionContextMenu(
                    availableWidth = availableWidth,
                    canRestyle = selected.strokes.isNotEmpty() || selected.texts.any { it.stickyInk.isNotEmpty() },
                    onCopy = { model.copyToClipboard(selected) },
                    canMove = note.pages.size > 1,
                    onMove = { moveSelection = page.id to selected },
                    onPaste = { pasteInView(); dismissSelection() }, canPaste = canPaste,
                    onCut = { model.cutSelection(selected); dismissSelection() },
                    onDuplicate = {
                        model.duplicateSelection(selected)
                        activeInkView?.clearSelection()
                        selection = null
                    },
                    onStyle = { restylePicked = page.id to selected; restyleSelection = selected.strokes + selected.texts.flatMap { it.stickyInk } },
                    onDelete = { model.deleteSelection(selected); dismissSelection() },
                    onDeselect = ::dismissSelection,
                    onSelectAll = ::selectAllInk,
                    onConvertText = if (selected.strokes.isEmpty() && selected.texts.none { it.stickyInk.isNotEmpty() } || inkConverting) null
                        else ({ convertSelectionToText(page.id, selected) })
                )
            }
            val pictureMenu: (@Composable (Dp) -> Unit)? = selectedImage?.takeIf { it.first == page.id }?.let { (_, picked) ->
                val live = page.images.find { it.id == picked.id }
                if (live == null) null else { availableWidth ->
                    if (cropActive) CropContextMenu(
                        onApply = { activeInkView?.endImageCrop(true) },
                        onCancel = { activeInkView?.endImageCrop(false) }
                    ) else PictureContextMenu(
                        cropped = live.isCropped(), availableWidth = availableWidth,
                        onRotateLeft = { model.rotateImageCounterClockwise(live.id) },
                        onRotateRight = { model.rotateImageClockwise(live.id) },
                        onCrop = { activeInkView?.beginImageCrop() },
                        onFullPhoto = { model.resetImageCrop(live.id) },
                        onFront = { model.bringImageToFront(live.id) },
                        onBack = { model.sendImageToBack(live.id) },
                        onDelete = { model.removeImage(live.id); selectedImage = null; activeInkView?.clearImageSelection() }
                    )
                }
            }
            if (paged || (music != null && musicInsets != null)) {
                val sheet: @Composable (NotePage, Int, Boolean) -> Unit = { item, index, preview ->
                    EditorPage(note.id, item, model,
                        surface = sharedSurface.copy(tool = if (music?.performance == true || zoomPaneOpen) Tool.HAND else tool,
                            activeLayer = model.activeLayerOf(item),
                            followEnabled = writingFollowEnabled && !zoomPaneOpen && !preview && item.id == page.id,
                            writingHand = writingHand,
                            followZoom = documentZoom,
                            showAnswerAreas = showAnswerAreas,
                            autoDetectAnswerAreas = autoDetectAnswerAreas && music == null,
                            inputBlocked = preview || music?.performance == true || (zoomPaneOpen && zoomGesture),
                            rulerEnabled = rulerOn && !preview && item.id == page.id,
                            ruler = rulers[item.id]),
                        presentation = PagePresentation(active = !preview && item.id == page.id,
                            textDraft = textEditor?.takeIf { it.pageId == item.id }?.box,
                            textTopInset = editorChromeHeightPx,
                            fullscreen = true,
                            pageCamera = true,
                            canvasReset = canvasReset,
                            selectedImageId = selectedImage?.takeIf { it.first == item.id }?.second?.id,
                            pdfLinks = pdfLinks,
                            readOnly = preview,
                            hideFromAccessibility = preview,
                            selectionAnchor = if (item.id == page.id) selectionAnchor else null,
                            selectionMenuViewport = selectionViewport,
                            selectionMenu = if (preview || item.id != page.id || inkNavigating || restyleSelection != null || music?.performance == true) null
                            else if (selected.isNotEmpty()) selectionMenu else pictureMenu),
                        actions = EditorPageActions(mutations = remember(item.id) { model.pageMutations(item.id) }, onActive = { if (!preview && music != null) model.selectPage(index) },
                            onPan = { _, _ -> },
                            onPanEnd = {},
                            onSelection = { picked -> if (item.id == page.id) selection = item.id to picked },
                            onTextEdit = { editTextBox(item, it) },
                            onTextCreate = { placeTextBox(item, it) },
                            onTextDraft = ::changeTextDraft,
                            onTextDone = { finishTextEditing() },
                            onLoad = { model.loadPage(item.id) },
                            onPageKey = music?.onKey,
                            onStickyDraw = { selectTool(Tool.PEN) },
                            onCanvasZoom = { if (!preview) documentZoom = it },
                            onPageEdges = { left, right -> if (!preview && item.id == page.id) { pageEdgeLeft = left; pageEdgeRight = right } },
                            onImageSelected = { image -> selectedImage = image?.let { item.id to it } },
                            onCropMode = { if (item.id == page.id) cropActive = it },
                            onNavigating = { inkNavigating = it },
                            onLongPress = { x, y, at -> if (item.id == page.id) pageMenu = Triple(x, y, at) },
                            onPdfLink = ::openPdfLink,
                            onEraserFinished = ::finishSingleStrokeEraser,
                            onUndo = ::undoEditor,
                            onRedo = ::redoEditor,
                            onSelectAllView = { if (!preview && item.id == page.id) { configureOverview(it) } },
                            onRulerChanged = { line -> rulers = rulers + (item.id to line) },
                            onSelectionAnchor = { rect -> if (item.id == page.id) selectionAnchor = rect }))
                }
                if (music != null && musicInsets != null) MusicCanvas(note, music, musicInsets, zoomed = documentZoom > 1.02f, onFit = { canvasReset++; documentZoom = 1f },
                    tapTurns = music.performance || tool == Tool.HAND || (!finger && tool != Tool.TEXT && tool != Tool.LASSO)) { item, index -> sheet(item, index, false) }
                else SinglePageCanvas(note.pages, state.pageIndex.coerceIn(0, note.pages.lastIndex),
                    top = floatingToolbarTop + FolioSpacing.dp4,
                    swipeTurns = (tool == Tool.HAND || !finger) && textEditor == null && !cropActive && selected.isEmpty() && selectedImage == null && !peekOpen,
                    zoomed = documentZoom > 1.02f, atLeftEdge = pageEdgeLeft, atRightEdge = pageEdgeRight, haptics = haptics,
                    onNote = { x, y -> mainInkView?.touchesNote(x, y, bodies = tool == Tool.STICKY_NOTE || tool == Tool.TEXT) == true },
                    onSelect = { origin, destination ->
                        val live = model.state.value
                        val target = live.active?.pages?.indexOfFirst { it.id == destination } ?: -1
                        if (live.page?.id == origin && target >= 0) jumpTo(target)
                    }, sheet = sheet)
            } else if (page.infinite) {
                InfiniteCanvas(note.id, page, model,
                    surface = sharedSurface.copy(tool = if (zoomPaneOpen) Tool.HAND else tool,
                        activeLayer = model.activeLayerOf(page),
                        followEnabled = writingFollowEnabled && !zoomPaneOpen && overviewReturn == null,
                        writingHand = writingHand,
                        followZoom = documentZoom,
                        showAnswerAreas = showAnswerAreas,
                        inputBlocked = peekOpen || (zoomPaneOpen && zoomGesture)),
                    presentation = PagePresentation(active = true,
                        textDraft = textEditor?.takeIf { it.pageId == page.id }?.box,
                        textTopInset = editorChromeHeightPx,
                        fullscreen = true,
                        canvasReset = canvasReset,
                        initialViewport = session?.viewport,
                        selectedImageId = selectedImage?.takeIf { it.first == page.id }?.second?.id,
                        pdfLinks = pdfLinks,
                        selectionAnchor = selectionAnchor,
                        selectionMenuViewport = selectionViewport,
                        selectionMenu = if (inkNavigating || pages.isScrollInProgress || restyleSelection != null || peekOpen) null else if (selected.isNotEmpty()) selectionMenu else pictureMenu),
                    actions = EditorPageActions(mutations = remember(page.id) { model.pageMutations(page.id) }, onActive = {},
                        onPan = { _, _ -> },
                        onPanEnd = {},
                        onSelection = { selection = page.id to it },
                        onTextEdit = { editTextBox(page, it) },
                        onTextCreate = { placeTextBox(page, it) },
                        onTextDraft = ::changeTextDraft,
                        onTextDone = { finishTextEditing() },
                        onLoad = { model.loadPage(page.id) },
                        onCanvasZoom = { documentZoom = it },
                        onCameraChanged = { savedCanvas = it },
                        onImageSelected = { image -> selectedImage = image?.let { page.id to it } },
                        onCropMode = { cropActive = it },
                        onNavigating = { inkNavigating = it },
                        onLongPress = { x, y, at -> pageMenu = Triple(x, y, at) },
                        onPdfLink = ::openPdfLink,
                        onEraserFinished = ::finishSingleStrokeEraser,
                        onUndo = ::undoEditor,
                        onRedo = ::redoEditor,
                        onSelectAllView = { configureOverview(it) },
                        onSelectionAnchor = { selectionAnchor = it }))
            } else ContinuousDocumentHost(note.id, note.pages.size, note.pages.any { it.pdfIndex != null }, editor,
                pages, motion, viewportCoordinator,
                DocumentHostLayout(baseWidth, viewportWidth, documentScale, floatingToolbarTop, canvasWindowBounds,
                    if (tool == Tool.ERASER && !zoomPaneOpen) EraserPreview(options.width / 2f, page.width, eraserWholeStroke) else null),
                activeInkView, haptics, pullAdding, showPageBoundaries, ::panBy) {

                    itemsIndexed(note.pages, key = { _, item -> item.id }) { index, item ->
                        Box(Modifier.fillMaxWidth().semantics {
                            // The long-press popover, for people who cannot long-press: opened at the middle of the pane.
                            if (item.id == page.id) customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("Page ${index + 1} options") {
                                val bounds = canvasWindowBounds
                                pageMenu = Triple(bounds?.center?.x ?: 0f, bounds?.center?.y ?: 0f, InkPoint(page.width / 2f, page.height / 2f))
                                true
                            })
                        }) {
                            EditorPage(note.id, item, model,
                                surface = sharedSurface.copy(tool = if (zoomPaneOpen) Tool.HAND else tool,
                                    activeLayer = model.activeLayerOf(item),
                                    followEnabled = writingFollowEnabled && !zoomPaneOpen && item.id == page.id && overviewReturn == null,
                                    writingHand = writingHand,
                                    followZoom = documentZoom,
                                    showAnswerAreas = showAnswerAreas,
                                    autoDetectAnswerAreas = autoDetectAnswerAreas,
                                    inputBlocked = peekOpen || (zoomPaneOpen && zoomGesture)),
                                presentation = PagePresentation(active = item.id == page.id,
                                    pageLabel = "Page ${index + 1} of ${note.pages.size}" + item.title.takeIf { it.isNotBlank() }?.let { ". $it" }.orEmpty(),
                                    textDraft = textEditor?.takeIf { it.pageId == item.id }?.box,
                                    textTopInset = editorChromeHeightPx,
                                    paperWidth = baseWidth * documentZoom,
                                    selectedImageId = selectedImage?.takeIf { it.first == item.id }?.second?.id,
                                    pdfLinks = pdfLinks,
                                    markZones = markZones,
                                    markAssist = markAssist,
                                    markColor = markingColor,
                                    selectionAnchor = if (item.id == page.id) selectionAnchor else null,
                                    selectionMenuViewport = selectionViewport,
                                    selectionMenu = if (documentSelection.isNotEmpty() || item.id != page.id || inkNavigating || pages.isScrollInProgress || restyleSelection != null || peekOpen) null else if (selected.isNotEmpty()) selectionMenu else pictureMenu),
                                actions = EditorPageActions(mutations = remember(item.id) { model.pageMutations(item.id) }, onActive = { model.selectPage(index) },
                                    onPan = ::panBy,
                                    onPanEnd = motion::release,
                                    onSelection = { picked -> if (item.id == page.id) selection = item.id to picked },
                                    onTextEdit = { editTextBox(item, it) },
                                    onTextCreate = { placeTextBox(item, it) },
                                    onTextDraft = ::changeTextDraft,
                                    onTextDone = { finishTextEditing() },
                                    onLoad = { model.loadPage(item.id) },
                                    onStickyDraw = { selectTool(Tool.PEN) },
                                    onCropMode = { if (item.id == page.id) cropActive = it },
                                    onNavigating = { inkNavigating = it },
                                    onLongPress = { x, y, at -> if (item.id == page.id) pageMenu = Triple(x, y, at) },
                                    onImageSelected = { image ->
                                    selectedImage = image?.let { item.id to it }
                                },
                                    onPdfLink = ::openPdfLink,
                                    onReplaceZone = { old, new -> saveManualZones(manualZones.filterNot { it == old } + listOfNotNull(new)) },
                                    onPageFrame = transfers::paper,
                                    onPageRegion = transfers::region,
                                    onObjectLift = { objectLift = it },
                                    onObjectDrop = { source, lift -> transfers.dropObject(model, source, lift) },
                                    onStickyLift = { stickyLift = it },
                                    onEraserBrush = { editor.interaction.eraserBrush.value = it },
                                    onStickyDrop = { source, lift -> transfers.dropSticky(model, source, lift) },
                                    onSelectionDrop = { source, picked, x, y -> transfers.dropSelection(model, source, picked, x, y) },
                                    onEraserFinished = ::finishSingleStrokeEraser,
                                    onUndo = ::undoEditor,
                                    onRedo = ::redoEditor,
                                    onSelectAllView = { if (item.id == page.id) { configureOverview(it) } },
                                    onFollowPan = { dx, dy ->
                                    val oldPan = documentPan
                                    documentPan = DocumentViewport.clampPan(documentPan + dx, baseWidthPx * documentScale * documentZoom, viewportWidth)
                                    val movedY = -pages.dispatchRawDelta(-dy)
                                    (documentPan - oldPan) to movedY
                                },
                                    onSelectionAnchor = { rect -> if (item.id == page.id) selectionAnchor = rect }))
                        }
                    }
                    item {
                        AddPageButton(
                            label = "Add page · ${editorPaperLabel(nextPagePaper ?: note.defaultPaper ?: page.paper)}",
                            onClick = ::addPage,
                            onLongClick = { openPaperMenu(true) },
                            modifier = Modifier.padding(top = FolioSpacing.dp4)
                        )
                    }
                            }
            val peekContext = LocalContext.current
            // Keep the original composition and cameras alive underneath. Closing the peek is an
            // exact return, including scroll offset, tool and the follow engine's line.
            if (peekOpen) {
                val anchor = (peekShown ?: peekAnchor)!!
                val target = anchor.page(note.pages)
                if (target != null) Box(Modifier.fillMaxSize().zIndex(10f).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    // A held peek is a glance; one kept open can be panned and zoomed to read.
                    EditorPage(note.id, target, model,
                        surface = InkSurfaceConfig(tool = Tool.HAND,
                            options = options,
                            finger = false,
                            snapEnabled = false,
                            shapeRecognition = false,
                            inputBlocked = peekMode == PeekMode.HELD),
                        presentation = PagePresentation(active = false,
                            fullscreen = true,
                            readOnly = true,
                            peekRegion = anchor),
                        actions = EditorPageActions(mutations = remember(target.id) { model.pageMutations(target.id) }, onActive = {},
                            onPan = { _, _ -> },
                            onPanEnd = {},
                            onSelection = {},
                            onTextEdit = {},
                            onTextCreate = {},
                            onLoad = { model.loadPage(target.id) },
                            onSelectAllView = {}))
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(top = FolioSpacing.dp12).guardUiTouches(),
                        shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 4.dp, tonalElevation = 1.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp4, bottom = FolioSpacing.dp4).heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Icon(Icons.Rounded.Visibility, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            val pageIndex = note.pages.indexOfFirst { it.id == target.id }
                            val pageName = target.title.takeIf { it.isNotBlank() }?.let { "page ${pageIndex + 1} · $it" } ?: "page ${pageIndex + 1}"
                            Text(if (peekMode == PeekMode.HELD) "Peeking at $pageName · release to return" else "Peek · $pageName",
                                Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (peekMode == PeekMode.LATCHED) {
                                // A kept-open peek can be panned; these bring it back, keep the new framing, or go there to write.
                                IconButton({ peekInkView?.fitPeekAnchor(anchor) }, Modifier.size(40.dp)) {
                                    Icon(Icons.Rounded.CenterFocusStrong, "Re-centre on the pinned view", Modifier.size(20.dp))
                                }
                                // Auto peek always shows the whole current page, so a pin made here would not show.
                                if (!autoPeek || page.infinite) IconButton({
                                    val shown = peekInkView?.currentPeekAnchor() ?: return@IconButton
                                    model.setPeekAnchor(shown); peekShown = shown.fittedTo(target)
                                    android.widget.Toast.makeText(peekContext, "Peek view updated", android.widget.Toast.LENGTH_SHORT).show()
                                }, Modifier.size(40.dp)) {
                                    Icon(Icons.Rounded.PushPin, "Pin what the peek shows now", Modifier.size(20.dp))
                                }
                                TextButton({
                                    val shown = peekInkView?.currentPeekAnchor() ?: anchor
                                    peekMode = null
                                    if (pageIndex >= 0 && pageIndex != model.state.value.pageIndex) jumpTo(pageIndex)
                                    // Only a canvas has a camera to aim; a fixed page is shown from its top in the column.
                                    if (target.infinite) peekGoTo = shown
                                }, shapes = ButtonDefaults.shapes()) { Text("Go to") }
                                TextButton({ peekMode = null }, shapes = ButtonDefaults.shapes()) { Text("Close") }
                            }
                        }
                    }
                }
            }
            BackHandler(enabled = peekMode == PeekMode.LATCHED) { peekMode = null }
            LaunchedEffect(peekGoTo, mainInkView, page.id) {
                val anchor = peekGoTo ?: return@LaunchedEffect
                // Navigating elsewhere first drops the request instead of aiming a later visit.
                if (page.id != anchor.pageId) { peekGoTo = null; return@LaunchedEffect }
                val view = mainInkView?.takeIf { it.page.id == anchor.pageId } ?: return@LaunchedEffect
                view.showRegion(anchor); peekGoTo = null
            }
            fun pinPeekView(wholePage: Boolean) {
                val anchor = if (wholePage) PeekAnchor.wholePage(page) else activeInkView?.currentPeekAnchor() ?: PeekAnchor.wholePage(page)
                val replacing = pinnedPeek != null
                model.setPeekAnchor(anchor)
                val number = note.pages.indexOfFirst { it.id == anchor.pageId } + 1
                val what = if (wholePage || anchor == PeekAnchor.wholePage(page).fittedTo(page)) "Page $number" else "View on page $number"
                android.widget.Toast.makeText(peekContext, "$what ${if (replacing) "replaces the peek view" else "pinned"} · hold the eye to look",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            // Keep the default Material shape, colors and elevation, with a shorter container.
            // Secondary actions overflow in narrow companion panes instead of shrinking targets.
            // Peek has a 48dp hold target, wider than the other 40dp controls. Include it
            // and the actual outer inset when deciding whether the full group fits.
            val responseTextButton = page.infinite && maxWidth >= 420.dp
            val showOverviewControl = page.infinite && maxWidth >= 220.dp
            val followToolbarWidth = 40.dp * 5 + 48.dp + 8.dp + (if (responseTextButton) 120.dp else 0.dp) + (if (page.infinite) 40.dp else 0.dp)
            val followInset = if (maxWidth < 180.dp) FolioSpacing.dp4 else FloatingToolbarDefaults.ScreenOffset
            val overflowFollowActions = maxWidth - followInset * 2 < followToolbarWidth
            fun toggleFollowPause() {
                writingFollowPaused = !followStatus.paused
                if (writingFollowPaused) followView?.pauseWritingFollow() else followView?.resumeWritingFollow()
            }
            // Held, blocked and end-of-area messages show briefly above the follow controls, because
            // otherwise a still view looks broken; the full status stays in the options menu.
            var followHint by remember(page.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(followStatus.message, followStatus.attention, writingFollowEnabled) {
                val message = followStatus.message
                if (!followStatus.attention || !writingFollowEnabled) return@LaunchedEffect
                followHint = message
                try { delay(2800) } finally { if (followHint == message) followHint = null }
            }
            if (music == null) followHint?.let { hint ->
                Surface(
                    Modifier.align(if (writingHand == WritingHand.RIGHT) Alignment.BottomStart else Alignment.BottomEnd)
                        .padding(followInset).padding(bottom = 44.dp + FolioSpacing.dp8).widthIn(max = 320.dp).zIndex(11f),
                    shape = FolioShapes.large,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(hint, Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8)
                        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (music == null) HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.align(if (writingHand == WritingHand.RIGHT) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(followInset)
                    .height(44.dp)
                    .guardUiTouches()
                    .zIndex(11f),
                contentPadding = PaddingValues(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp2),
            ) {
                val responseLabel = if (writingFollowEnabled) "Finish response" else "Write a response"
                if (responseTextButton) TextButton(
                    onClick = { setWritingFollow(!writingFollowEnabled) }, enabled = !peekOpen && overviewReturn == null,
                ) { Text(responseLabel) }
                else WritingFollowControl(
                    if (page.infinite) Icons.Rounded.TextFields else Icons.Rounded.SwipeRight,
                    if (page.infinite) responseLabel else if (writingFollowEnabled) "Writing follow on — tap to turn off" else "Turn on writing follow",
                    enabled = !peekOpen && overviewReturn == null, active = writingFollowEnabled,
                    onClick = { setWritingFollow(!writingFollowEnabled) }
                )
                if (showOverviewControl) WritingFollowControl(
                    if (overviewReturn == null) Icons.Rounded.FitScreen else Icons.AutoMirrored.Rounded.ArrowBack,
                    if (overviewReturn == null) "See all working" else "Return to working",
                    enabled = !peekOpen, active = overviewReturn != null, onClick = ::fitAllContent,
                )
                if (writingFollowEnabled && !overflowFollowActions) {
                    WritingFollowControl(Icons.AutoMirrored.Rounded.KeyboardReturn, "Next writing line",
                        enabled = !peekOpen && overviewReturn == null, onClick = { followView?.nextWritingLine() })
                    WritingFollowControl(Icons.AutoMirrored.Rounded.Undo, "Undo the last follow move",
                        enabled = !peekOpen && followStatus.canGoBack, onClick = { followView?.backWritingView() })
                    WritingFollowControl(
                        if (followStatus.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (followStatus.paused) "Resume writing follow" else "Pause writing follow",
                        enabled = !peekOpen, active = followStatus.paused,
                        onClick = ::toggleFollowPause
                    )
                }
                Box {
                    var followSub by remember { mutableStateOf<FollowSub?>(null) }
                    val openSub: (FollowSub) -> Unit = { followSub = if (followSub == it) null else it }
                    WritingFollowControl(Icons.Rounded.Tune, "Writing follow and peek options",
                        enabled = !peekOpen, onClick = { followSub = null; followMenu = true })
                    FolioMenuPopover(followMenu, { followMenu = false; followSub = null }, modifier = Modifier.guardUiTouches(), title = "Writing & peek") {
                        FolioMenuItem({ Text(if (zoomPaneOpen) "Close zoom pane" else "Open zoom pane") },
                            { if (zoomPaneOpen) closeZoomPane() else { finishTextEditing(); selectTool(Tool.PEN); openZoomPane() }; followMenu = false },
                            enabled = !peekOpen && overviewReturn == null,
                            leadingIcon = { Icon(Icons.Rounded.ZoomIn, null) })
                        if (page.infinite) {
                            FolioMenuItem({ Text(responseLabel) },
                                { setWritingFollow(!writingFollowEnabled); followMenu = false },
                                enabled = overviewReturn == null,
                                leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                            Text("Use a response column for paragraphs. Leave it off for maths, short answers and diagrams.",
                                Modifier.widthIn(max = 280.dp).padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                                style = MaterialTheme.typography.bodySmall)
                            if (canvasResponse != null) FolioMenuItem({ Text("Start a new column here") },
                                { setWritingFollow(true); followMenu = false }, enabled = overviewReturn == null)
                            FolioMenuItem({ Text(if (overviewReturn == null) "See all working" else "Return to working") },
                                { fitAllContent(); followMenu = false })
                        }
                        if (writingFollowEnabled) {
                            Text(followStatus.message,
                                Modifier.widthIn(max = 280.dp).padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SubmenuItem("Lines & indentation…", Icons.AutoMirrored.Rounded.KeyboardReturn, followSub == FollowSub.LINES, { openSub(FollowSub.LINES) }) {
                            FolioMenuItem({ Text("Previous writing line") },
                                { followView?.previousWritingLine(); followMenu = false },
                                enabled = overviewReturn == null,
                                leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null) },
                                trailingIcon = { Text("Alt+↑", style = MaterialTheme.typography.labelSmall) })
                            FolioMenuItem({ Text("New paragraph · leave a blank line") },
                                { followView?.nextWritingLine(paragraph = true); followMenu = false }, enabled = overviewReturn == null)
                            FolioMenuItem({ Text("Indent next lines") },
                                { followView?.indentWritingLine(); followMenu = false }, enabled = overviewReturn == null)
                            FolioMenuItem({ Text("Reset indent") },
                                { followView?.indentWritingLine(reset = true); followMenu = false }, enabled = overviewReturn == null)
                            }
                            if (overflowFollowActions) {
                                FolioMenuItem({ Text("Next writing line") },
                                    { followView?.nextWritingLine(); followMenu = false },
                                    enabled = overviewReturn == null,
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null) })
                                FolioMenuItem({ Text(if (followStatus.paused) "Resume writing follow" else "Pause writing follow") },
                                    { toggleFollowPause(); followMenu = false },
                                    leadingIcon = { Icon(if (followStatus.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null) })
                                FolioMenuItem({ Text("Undo the last follow move") },
                                    { followView?.backWritingView(); followMenu = false },
                                    enabled = followStatus.canGoBack,
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) })
                            }
                            HorizontalDivider()
                        }
                        if (!page.infinite) SubmenuItem("Answer areas…", Icons.Rounded.CropFree, followSub == FollowSub.AREAS, { openSub(FollowSub.AREAS) }) {
                            FolioMenuItem({ Text("Select answer area") }, { appPrefs.edit().putBoolean("writingFollow", true).apply(); followView?.selectWritingRegion(); followMenu = false })
                            FolioMenuItem({ Text("Detect answer areas") }, { appPrefs.edit().putBoolean("writingFollow", true).apply(); followView?.suggestWritingRegion(); followMenu = false })
                            if (writingRegion != null) FolioMenuItem({ Text("Clear answer areas") }, { followView?.clearWritingRegion(); followMenu = false })
                        }
                        if (page.infinite && canvasResponse != null) FolioMenuItem(
                            { Text("Auto-return for this response") },
                            { canvasResponse = canvasResponse?.copy(automaticReturn = canvasResponse?.automaticReturn != true); followMenu = false },
                            trailingIcon = { Checkbox(checked = canvasResponse?.automaticReturn == true, onCheckedChange = null) })
                        FolioMenuItem({ Text("Writing follow settings…") }, { openFollowSettings(); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                        HorizontalDivider()
                        SubmenuItem("Peek view…", Icons.Rounded.PushPin, followSub == FollowSub.PEEK, { openSub(FollowSub.PEEK) }) {
                        FolioMenuItem(
                            { Text(if (pinnedPeek == null) "Pin this view" else "Replace with this view") },
                            { pinPeekView(wholePage = false); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, null) }
                        )
                        FolioMenuItem(
                            { Text("Pin this whole page") },
                            { pinPeekView(wholePage = true); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.FitScreen, null) }
                        )
                        if (pinnedPeek != null) FolioMenuItem(
                            { Text("Remove peek view") },
                            { model.setPeekAnchor(null); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Close, null) }
                        , destructive = true)
                        }
                    }
                }
                if (peekAnchor == null) {
                    WritingFollowControl(Icons.Rounded.PushPin, "Pin this view to peek at later",
                        onClick = { pinPeekView(wholePage = false) })
                } else {
                    PeekControl(peekMode) { mode ->
                        when {
                            mode == null -> { peekMode = null; true }
                            // Opening mid-stroke would cut the stroke off under the lens.
                            activeInkView?.isWritingGesture == true -> false
                            else -> { finishTextEditing(); motion.reset(); peekShown = peekAnchor; peekMode = mode; true }
                        }
                    }
                }
            }
            if (tool == Tool.MARK_AREA && page.pdfIndex != null) MarkAreaHint(
                Modifier.align(Alignment.BottomCenter).zIndex(11f).padding(bottom = FolioSpacing.dp16))
            if (!page.infinite && music == null && !paged) Box(Modifier.align(Alignment.CenterEnd).padding(end = stripInset).padding(top = trackTop, bottom = trackBottom).width(110.dp).fillMaxHeight()) {
                FastScrollTrack(pages, note.pages.size, scrubbing, Modifier.fillMaxSize())
            }
            if (!paged && !page.infinite && music == null) EditorDocumentSelection(note, model, editor.interaction, editor.documentInput, quick.colors(InkColors.INK_GROUP),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 8.dp, end = 8.dp).guardUiTouches().zIndex(12f))
            if (state.documentHistoryBusy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = floatingToolbarTop).semanticsLabel("Restoring document action"))
            if (music != null && musicInsets != null) music.chrome(this, musicInsets)
            if (music == null && note.pages.isNotEmpty() && (!page.infinite || note.pages.size > 1)) PageNavigator(
                index = state.pageIndex.coerceIn(0, note.pages.lastIndex), count = note.pages.size, singlePage = singlePage,
                onTurn = ::turnPage, onJumpToEnd = ::jumpToEnd, onToggleView = { setSinglePage(!singlePage) },
                onBrowse = { pageBrowser = true },
                modifier = Modifier.align(if (writingHand == WritingHand.RIGHT) Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(followInset).guardUiTouches().zIndex(11f))
            EditorToolbarHost(toolbarStyle, music != null,
                onHeight = { editorChromeHeightPx = it.toFloat() + with(density) { 6.dp.toPx() } },
                tools = {

                if (music == null || (music.showToolbar && !music.performance)) FloatingInkToolbar(
                    modifier = Modifier, markAreaAvailable = music == null && note.pages.any { it.pdfIndex != null },
                    onMainHeight = { floatingToolbarTop = with(density) { it.toDp() } + 8.dp },
                    onFullHeight = { if (music != null) musicToolbarFull = it },
                    hiddenSlots = if (music != null) setOf(ToolbarSlot.STICKY_NOTE, ToolbarSlot.MARK_AREA) else emptySet(),
                    shapeTools = if (music != null) ShapeTools.toList() else ShapePickerTools.toList(),
                    tool = tool,
                    onTool = { selectTool(it) },
                    options = options,
                    onOptions = ::changeOptions,
                    quick = quick,
                    canUndo = state.canUndo || textEditor?.box?.text?.isNotBlank() == true,
                    canRedo = state.canRedo,
                    undo = { finishTextEditing(); undoEditor() },
                    redo = { finishTextEditing(); redoEditor() },
                    palette = palette,
                    snapEnabled = snapEnabled,
                    onSnap = ::setSnap,
                    onPalette = { palette = it },
                    eraserSingleStroke = eraserSingleStroke, onEraserSingleStroke = ::setEraserSingleStroke,
                    scribbleToErase = scribbleToErase, onScribbleToErase = ::setScribbleToErase,
                    eraserPressureEnabled = eraserPressure, onEraserPressure = ::setEraserPressure,
                    eraserWholeStroke = eraserWholeStroke, onEraserWholeStroke = ::setEraserWholeStroke,
                    shapeMeasurements = shapeMeasurements, onShapeMeasurements = ::setShapeMeasurements,
                    multiTouchUndo = multiTouchUndo, onMultiTouchUndo = ::setMultiTouchUndo,
                    onSelectAll = ::selectAllInk,
                    textColor = textColor, onTextColor = ::setTextColor,
                    presets = toolPresets.presets, onApplyPreset = ::applyPreset,
                    toolPresetsState = toolPresets,
                    toolbarLayoutState = toolbarLayouts,
                    rulerOn = rulerOn, onRuler = { rulerOn = !rulerOn },
                    style = if (music == null) toolbarStyle else ToolbarStyle.FOLIO,
                    toolbarOptions = toolbarOptions,
                    header = { mainTools ->
                        if (music != null) FolioExpand(true) { mainTools() } else EditorTopBar(
                            title = note.title,
                            mainTools = mainTools,
                            notebookActions = notebookActions,
                            saveFailed = state.saveFailed,
                            retryingSave = state.retryingSave,
                            saveFailureReason = state.saveFailureReason,
                            lastSaveProgressAt = state.lastSaveProgressAt,
                            saving = state.saving,
                            starred = note.starred,
                            onStar = { model.star(note) },
                            onRename = { renameTitle = note.title; rename = true },
                            onRetrySave = model::retrySave,
                            onClose = { finishTextEditing(); model.close() },
                            showBack = showBack,
                            style = toolbarStyle,
                            toolbarOptions = toolbarOptions,
                            // Practice notebooks stay in Mistakes; a regular tab uses its current title.
                            tabs = if (focusMode) emptyList() else state.tabs.mapNotNull { tab ->
                                val notebook = state.notes.find { it.id == tab.notebookId }
                                if (notebook?.mistakePractice == true) null
                                else EditorTabChip(tab.notebookId, notebook?.title ?: tab.title)
                            },
                            activeTabId = note.id,
                            onSelectTab = { id -> if (id != note.id) { finishTextEditing(); model.open(id) } },
                            onCloseTab = { id -> finishTextEditing(); model.closeTab(id) },
                            onNewTab = onOpenDocument?.let { open -> { finishTextEditing(); open() } },
                            timer = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                                    TimingChip(state.timer, onLongClick = {
                                        if (state.timer.phase != ExamTimerPhase.IDLE) model.toggleTimerPause()
                                    }) { timerPanel = true }
                                    FocalStudyChip(state.timer, note.id, onLongClick = {
                                        (context.applicationContext as FolioApplication).focalStudy.toggleFocus()
                                    }) { studyPanel = true }
                                }
                            },
                            pageIndex = state.pageIndex,
                            pageCount = note.pages.size,
                            onPrevious = { turnPage(false) },
                            onNext = { turnPage(true) },
                            onPages = { pageBrowser = true },
                            onFirstPage = { jumpToEnd(false) },
                            onLastPage = { jumpToEnd(true) },
                            zoomPercent = (documentZoom * 100).roundToInt(),
                            onFit = ::resetZoom,
                            onFitAll = if (page.infinite) ::fitAllContent else null,
                            onAdd = ::addPage,
                            onSearch = {
                                if (page.pdfIndex != null) { pdfSearchOpen = true }
                                else { noteSearchOpen = true }
                            },
                            onLayers = { finishTextEditing(); layersPopover = true },
                            layerStatus = PageLayers.effective(page.layers).firstOrNull { it.id == model.activeLayerOf(page) }?.let { layer ->
                                when { !layer.visible -> "Hidden layer: ${layer.name}"; layer.locked -> "Locked layer: ${layer.name}"; else -> null }
                            },
                            onContents = if (page.pdfIndex != null) ({ pdfContentsOpen = true; loadOutline() }) else null,
                            onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                            paperTitle = editorPaperLabel(page.paper),
                            onPaper = if (page.pdfIndex == null) ({ openPaperMenu(false) }) else null,
                            bookmarked = page.bookmarked,
                            onBookmark = { model.togglePageBookmark(page.id) },
                            layersPopover = {
                                if (layersPopover) LayersPopover(page, model.activeLayerOf(page), selected.size, model,
                                    onMoveSelection = { layer -> if (documentSelection.isNotEmpty()) editor.documentInput.moveSelectionToLayer(layer) else model.moveSelectionToLayer(selected, layer) },
                                    onDismiss = { layersPopover = false })
                            },
                            onInsertPage = { revealNewPage(model.insertPage(state.pageIndex + 1)) },
                            onDuplicatePage = { model.duplicatePage()?.let { revealNewPage(it) } },
                            onExport = { finishTextEditing(); onExport() },
                            onShareLongPress = onShareLongPress?.let { shortcut -> { finishTextEditing(); shortcut() } },
                            onChatGpt = onChatGpt?.takeUnless { note.hiddenFromLibrary }?.let { open -> { finishTextEditing(); open() } },
                            onSettings = { finishTextEditing(); onSettings() },
                            onKeyboardShortcuts = { keyboardShortcuts = true },
                            pageActions = { dismiss ->
                                PageOptionsContent(dismiss, page, state.saveFailed,
                                    focusMode = focusMode, onFocusMode = { appPrefs.edit().putBoolean(AppPrefs.EDITOR_FOCUS_MODE, !focusMode).apply() },
                                    onPaper = { openPaperMenu(false) }, onClear = { clear = true }, onRetry = model::retrySave,
                                    onRedo = model::toggleRedoFlag, onExam = { examPanel = true }, onRecordMark = { markDialog = true }, onTimer = { timerPanel = true },
                                    onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                                    onSearchPdf = { pdfSearchOpen = true },
                                    onContents = { pdfContentsOpen = true; loadOutline() },
                                    onSearchNotes = { noteSearchOpen = true },
                                    onOrganize = { pageBrowser = true },
                                    onBookmark = { model.togglePageBookmark(page.id) },
                                    onNamePage = { namedPage = page; pageTitle = page.title })
                            }
                        )
                    }
                )
                }, text = {
                textEditor?.let { draft ->
                    InlineTextToolbar(draft.box, quick.colors(InkColors.INK_GROUP),
                        onChange = { box -> textEditor = draft.copy(box = box); rememberTextLook(box) },
                        onDone = { finishTextEditing() },
                        onDelete = { textEditor = draft.copy(box = draft.box.copy(text = "")); finishTextEditing() },
                        onDuplicate = {
                            finishTextEditing(hideKeyboard = false)
                            textEditor = draft.copy(box = draft.box.copy(id = java.util.UUID.randomUUID().toString()).moved(18f, 18f))
                        })
                }
                            })
        }
        if (zoomPaneOpen && music == null && zoomTarget != null) {
            val available = with(editorDensity) { editorSize.height.toDp().value }
            val paneHeight = zoomHeight.coerceAtMost(maxOf(160f, available * .55f)).dp
            ZoomWritingPane(paneHeight, followStatus, writingHand,
                onResize = { delta ->
                    if (zoomInkView?.isWritingGesture != true) {
                        appPrefs.edit().putFloat(AppPrefs.ZOOM_PANE_HEIGHT, AppPrefs.zoomPaneHeight(zoomHeight + delta)).apply()
                    }
                },
                onAhead = { zoomInkView?.revealWritingAhead() }, onNext = { zoomInkView?.nextWritingLine() },
                onBack = { zoomInkView?.backWritingView() },
                onPause = {
                    writingFollowPaused = !followStatus.paused
                    if (writingFollowPaused) zoomInkView?.pauseWritingFollow() else zoomInkView?.resumeWritingFollow()
                }, onOptions = openFollowSettings, onClose = ::closeZoomPane) {
                EditorPage(note.id, page, model,
                    surface = sharedSurface.copy(zoomWritingPane = true,
                        activeLayer = model.activeLayerOf(page),
                        followEnabled = writingFollowEnabled && overviewReturn == null,
                        writingHand = writingHand,
                        followZoom = 2f,
                        showAnswerAreas = showAnswerAreas,
                        inputBlocked = peekOpen || overviewReturn != null,
                        autoDetectAnswerAreas = autoDetectAnswerAreas,
                        rulerEnabled = rulerOn,
                        ruler = rulers[page.id]),
                    presentation = PagePresentation(active = true,
                        fullscreen = true,
                        pageCamera = true,
                        writingWindowTarget = zoomTarget),
                    actions = EditorPageActions(mutations = remember(page.id) { model.pageMutations(page.id) }, onActive = {},
                        onPan = { _, _ -> },
                        onPanEnd = {},
                        onSelection = {},
                        onTextEdit = {},
                        onTextCreate = {},
                        onLoad = { model.loadPage(page.id) },
                        onSelectAllView = { view ->
                        val first = zoomInkView !== view
                        configureFollow(view)
                        if (first) zoomInitialState?.let { view.writingFollow.state = it }
                        if (first) view.writingIndent = mainInkView?.writingIndent
                        view.onWritingWindowChanged = { anchor ->
                            zoomWindow = anchor
                            if (overviewReturn == null) mainInkView?.revealWritingWindow(anchor, editorChromeHeightPx)
                        }
                        view.onFollowLanding = { guide ->
                            mainInkView?.followLandingPreview = guide?.takeIf { view.followPreferences.showLandingGuide }
                        }
                        view.onLiveInk = { stroke, busy ->
                            mainInkView?.liveInkPreview = stroke
                            if (zoomGesture != busy) zoomGesture = busy
                            mainInkView?.inputBlocked = busy || peekOpen
                        }
                    },
                        onEraserFinished = ::finishSingleStrokeEraser,
                        onUndo = ::undoEditor,
                        onRedo = ::redoEditor,
                        onRulerChanged = { rulers = rulers + (page.id to it) }))
            }
        }
    }
    // Popups must belong to the browser window while it is open, or they appear behind it.

    EditorPageBrowser(state, model, editor.overlays, editor.browser, ::jumpTo, ::addPage, ::revealNewPage, ::openPaperMenu)

    if (!pageBrowser) EditorPageDialogs(note, model, editor.overlays)
    EditorRenameDialog(note, model, editor.overlays)

    EditorPaperDialog(note, page, model, editor.paper)
    EditorClearDialog(model, editor.overlays, editor.interaction)
    EditorStudyDialogs(state, model, editor.overlays,
        EditorMarkingPresentation(markAssist, markScanBusy, markScanStatus, markZones, markingColor),
        onAssist = { markAssist = it; appPrefs.edit().putBoolean(Marking.PREF_ASSIST, it).apply() },
        onColor = { markingColor = it; appPrefs.edit().putInt(Marking.PREF_COLOR, it).apply() })
    restyleSelection?.let { originals ->
        RestyleSelectionPanel(
            originals = originals, quickColors = quick.colors(InkColors.INK_GROUP),
            onDismiss = { restyleSelection = null },
            onApply = { color, scale, opacity, style ->
                val picked = restylePicked
                val source = note.pages.firstOrNull { it.id == picked?.first }
                if (source != null && picked != null && source.strokes.containsAll(picked.second.strokes) && source.texts.containsAll(picked.second.texts)) {
                    val after = DocumentToolEdits.restyle(source, picked.second, color, scale, opacity, style)
                    PageJournal.diff(source.content(), after)?.let { edit -> model.commitDocumentEdits(note.id, mapOf(source.id to edit), mapOf(source.id to source.revision)) }
                }
                activeInkView?.clearSelection()
                restylePicked = null; restyleSelection = null; selection = null
            }
        )
    }
    moveSelection?.let { (sourceId, picked) ->
        var destinationQuery by rememberSaveable(sourceId) { mutableStateOf("") }
        val destinations = remember(note.pages, sourceId, destinationQuery) {
            organizePages(note.pages, destinationQuery, PageFilter.ALL).filter { it.value.id != sourceId }
        }
        FolioPanel(title = "Move ${picked.size} item${if (picked.size == 1) "" else "s"} to page", onDismissRequest = { moveSelection = null }) {
            OutlinedTextField(destinationQuery, { destinationQuery = it }, Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24),
                singleLine = true, label = { Text("Find destination page") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { textKeyboard?.hide() }),
                trailingIcon = { if (destinationQuery.isNotEmpty()) IconButton({ destinationQuery = ""; textKeyboard?.hide() }) { Icon(Icons.Rounded.Close, "Clear destination search") } })
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(FolioSpacing.dp24),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                if (destinations.isEmpty()) item {
                    EmptyHint("No destination pages match.")
                    TextButton({ destinationQuery = "" }) { Text("Show all destinations") }
                }
                items(destinations, key = { it.value.id }) { (index, destination) ->
                    Surface(onClick = {
                        model.moveSelectionToPage(sourceId, destination.id, picked)
                        moveSelection = null; activeInkView?.clearSelection(); selection = null
                    }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                            PageThumbnail(note.id, destination, model.thumbnails, Modifier.width(40.dp).height(56.dp))
                            Column(Modifier.weight(1f)) {
                                Text(destination.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Page ${index + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Move to page ${index + 1}")
                        }
                    }
                }
            }
        }
    }
    pageMenu?.takeIf { !inkNavigating }?.let { (wx, wy, at) ->
        val pressedText = page.texts.lastOrNull {
            !it.isSticky && PageLayers.editable(page.layers, it.layer) &&
                at.x >= it.x && at.x <= it.x + it.width && at.y >= it.y && at.y <= it.y + InkRenderer.textHeight(it)
        }
        if (pressedText != null) {
            fun copyText() {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Folio text", pressedText.text))
            }
            TextBoxContextMenu(wx, wy,
                onEdit = { selectTool(Tool.TEXT); editTextBox(page, pressedText) },
                onCopy = ::copyText,
                onCut = { copyText(); finishTextEditing(); model.removeText(pressedText.id) },
                onDuplicate = { finishTextEditing(); model.duplicateText(pressedText.id) },
                onDelete = { finishTextEditing(); model.removeText(pressedText.id) },
                onDismiss = { pageMenu = null })
            return@let
        }
        val pageIndex = state.pageIndex.coerceIn(0, note.pages.lastIndex)
        PageContextMenu(wx, wy,
            pageActions = PageMenuActions(page,
                canMoveUp = pageIndex > 0, canMoveDown = pageIndex < note.pages.lastIndex, canDelete = note.pages.size > 1,
                onName = { namedPage = page; pageTitle = page.title },
                onBookmark = { model.togglePageBookmark(page.id) },
                onRedoFlag = { model.setPageRedoFlag(note.id, page.id, !page.redoFlag) },
                onMoveTo = { movingPage = page.id; destinationPage = (pageIndex + 1).toString() },
                onMoveUp = { model.movePage(pageIndex, pageIndex - 1) }, onMoveDown = { model.movePage(pageIndex, pageIndex + 1) },
                onInsert = { revealNewPage(model.insertPage(pageIndex + 1)) },
                onDuplicate = { model.duplicatePage(pageIndex)?.let { revealNewPage(it) } },
                onDelete = { deletingPage = page.id }),
            onPaste = { model.pasteClipboard(at) }, canPaste = canPaste,
            onSelectAll = ::selectAllInk,
            onText = { selectTool(Tool.TEXT); placeTextBox(page, at) },
            onImage = { imagePicker.launch(arrayOf("image/*")) },
            canUndo = state.canUndo || textEditor?.box?.text?.isNotBlank() == true, canRedo = state.canRedo,
            onUndo = { finishTextEditing(); undoEditor() }, onRedo = { finishTextEditing(); redoEditor() },
            onDismiss = { pageMenu = null })
    }
    EditorSearchPanels(state, model, editor.overlays, searchQuery, pdfOutline, ::jumpTo)
    if (responseAttempts) LongResponseAttemptsPanel(note, page.id, model) { responseAttempts = false }

}

/**
 * One timing action while idle, with live countdown and elapsed clocks when active.
 */
@Composable private fun TimingChip(timer: ExamTimerState, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // The hold claims the gesture so the release after it never also opens the timer panel.
    val hold = rememberLongPressGuard()
    val active = timer.phase == ExamTimerPhase.READING || timer.phase == ExamTimerPhase.WRITING || timer.phase == ExamTimerPhase.DONE
    Crossfade(targetState = active, label = "timingChip") { isActive ->
        if (!isActive) {
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Timer & stopwatch") } }, state = rememberTooltipState()) {
                IconButton(onClick, modifier = Modifier.size(DockButtonWidth, DockButtonHeight), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Timer, "Timer & stopwatch", Modifier.size(20.dp)) }
            }
        } else {
            val done = timer.phase == ExamTimerPhase.DONE
            val reading = timer.phase == ExamTimerPhase.READING
            val caption = when {
                timer.paused -> if (timer.autoParked) "Stopped" else "Paused"
                done -> "Time"
                reading -> "Reading"
                else -> "Writing"
            }
            val phaseSeconds = if (reading) timer.preset.readingSeconds else timer.preset.writingSeconds
            val clock = if (done) "Pens down" else timer.clockText()
            ToolbarSessionPill(
                icon = when {
                    done -> Icons.Rounded.Flag
                    timer.paused -> Icons.Rounded.Pause
                    reading -> Icons.Rounded.Visibility
                    else -> Icons.Rounded.Timer
                },
                value = clock,
                tone = when {
                    done -> SessionTone.ALERT
                    timer.paused -> SessionTone.PAUSED
                    else -> SessionTone.RUNNING
                },
                reserve = if (done) null else if (phaseSeconds >= 3600) "0:00:00" else "00:00",
                description = "Exam timer, $caption, ${if (done) clock else "$clock left"}. Open timer" +
                    if (onLongClick != null && !done) ". Hold to ${if (timer.paused) "resume" else "pause"}" else "",
                onClick = hold.click(onClick),
                modifier = if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier
            )
        }
    }
}

/** One visibility rule for active-page actions (including sharing) and the scroll label. */
@Composable internal fun RestyleSelectionPanel(
    originals: List<Stroke>, quickColors: List<Int>,
    onDismiss: () -> Unit, onApply: (Int?, Float?, Float?, StrokeStyle?) -> Unit
) {
    val style = remember(originals) { InkGeometry.styleOf(originals) }
    var color by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var fade by remember { mutableStateOf(false) }
    var opacity by remember { mutableFloatStateOf(style?.opacity ?: 1f) }
    var lineStyle by remember { mutableStateOf<StrokeStyle?>(null) }
    val hasShape = remember(originals) { originals.any { it.tool in ShapeTools } }
    FolioPanel(title = "Restyle selection", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp32), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {

            Text("${originals.size} ${if (originals.size == 1) "stroke" else "strokes"}. Leave a control alone to keep it as it is.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(color == null, { color = null }, { Text("Keep") })
                Spacer(Modifier.width(FolioSpacing.dp6))
                quickColors.forEach { option -> InkColorDot(option, color == option, { color = option }, touch = 38.dp, dot = 26.dp, label = "Selection colour") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Thickness", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
                Slider(scale, { scale = it }, valueRange = 0.5f..3f, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.ROOT, "%.1fx", scale), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            if (hasShape) {
                Text("Line style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    FilterChip(lineStyle == null, { lineStyle = null }, { Text("Keep") })
                    FilterChip(lineStyle == StrokeStyle.SOLID, { lineStyle = StrokeStyle.SOLID }, { Text("Solid") })
                    FilterChip(lineStyle == StrokeStyle.DASHED, { lineStyle = StrokeStyle.DASHED }, { Text("Dashed") })
                    FilterChip(lineStyle == StrokeStyle.DOTTED, { lineStyle = StrokeStyle.DOTTED }, { Text("Dotted") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    listOf(StrokeStyle.LONG_DASH, StrokeStyle.DASH_DOT, StrokeStyle.DENSE_DOTS).forEach { style ->
                        FilterChip(lineStyle == style, { lineStyle = style }, { Text(style.label) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Opacity", style = MaterialTheme.typography.labelMedium)
                    Text("Off keeps each stroke's own fade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(fade, { fade = it })
            }
            if (fade) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Slider(opacity, { opacity = it }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ onApply(color, scale.takeIf { it != 1f }, opacity.takeIf { fade }, lineStyle) }, shapes = ButtonDefaults.shapes()) { Text("Apply") }
            }
        }
    }
}

/** One gesture recognizer owns both actions, so holding never also adds a page. */
@Composable
private fun AddPageButton(label: String, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = FolioShapes.large,
        modifier = modifier.guardUiTouches().clip(FolioShapes.large).combinedClickable(
            role = androidx.compose.ui.semantics.Role.Button,
            onClick = onClick,
            onLongClickLabel = "Choose next page paper",
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        )
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 40.dp).padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(FolioSpacing.dp8))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private enum class FollowSub { AREAS, LINES, PEEK }

/**
 * A small chip beside a printed "[4 marks]" label: tick awards every mark at once, cross opens a − / +
 * stepper for part marks. It floats over the page without taking focus, never blocks writing, and goes
 * away by itself — a pen touching the page dismisses it, and so does a few seconds of nothing.
 */
