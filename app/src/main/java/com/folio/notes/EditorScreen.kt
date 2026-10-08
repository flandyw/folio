@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compact icons retain full touch targets and spoken labels inside the floating toolbar.
 *
 * The active (highlighted) container is a circle: the toolbar's own corner is nearly square, so a
 * rounded square inside it read as a mismatched chip. Only the highlight has a visible shape, so
 * the circle keeps every state the same size and the row visually even.
 */
@Composable private fun WritingFollowControl(
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

private fun paperLabel(p: Paper): String = when (p) {
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

@Composable internal fun EditorScreen(state: FolioState, model: FolioViewModel, finger: Boolean, haptics: Boolean, shapeRecognitionSetting: Boolean, onSettings: () -> Unit, onExport: () -> Unit, notebookActions: @Composable (() -> Unit) -> Unit = {}, music: MusicStage? = null, showBack: Boolean = true) {
    val note = state.active ?: return
    val page = state.page ?: return
    val context = LocalContext.current
    val session = state.tabs.find { it.notebookId == note.id }
    val prefs = context.getSharedPreferences("ink-tools", 0)
    val appPrefs = context.getSharedPreferences("preferences", 0)
    var tool by rememberSaveable { mutableStateOf(session?.tool ?: AppPrefs.defaultTool(appPrefs.getString(AppPrefs.DEFAULT_TOOL, null))) }
    var previousTool by rememberSaveable { mutableStateOf(Tool.PEN) }
    var palette by rememberSaveable { mutableStateOf(false) }
    var palmRejectMs by remember { mutableLongStateOf(AppPrefs.palmMs(appPrefs.getLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS).takeIf { appPrefs.contains(AppPrefs.PALM_MS) })) }
    var panMultiplier by remember { mutableFloatStateOf(readPanFactor(appPrefs)) }
    DisposableEffect(appPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefsChanged, key ->
            if (key == AppPrefs.PALM_MS) {
                palmRejectMs = AppPrefs.palmMs(prefsChanged.getLong(key, AppPrefs.DEFAULT_PALM_MS))
            }
            if (key == AppPrefs.FAST_PAN || key == AppPrefs.FAST_PAN_MULTIPLIER) panMultiplier = readPanFactor(prefsChanged)
        }
        appPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var pageFollowEnabled by remember { mutableStateOf(appPrefs.getBoolean("writingFollow", false)) }
    // Starting prose is deliberate and local to this canvas visit, never a global preference.
    var canvasResponse by remember(note.id, page.id) { mutableStateOf<CanvasWritingSession?>(null) }
    var overviewReturn by remember(note.id, page.id) { mutableStateOf<WorkspaceViewport?>(null) }
    val writingFollowEnabled = if (page.infinite) canvasResponse != null else pageFollowEnabled
    var writingFollowPaused by rememberSaveable(note.id) { mutableStateOf(false) }
    var autoDetectAnswerAreas by remember { mutableStateOf(appPrefs.getBoolean("follow.autoDetectAnswerAreas", false)) }
    var showAnswerAreas by remember { mutableStateOf(appPrefs.getBoolean("follow.showAnswerAreas", true)) }
    var writingHand by remember { mutableStateOf(runCatching { WritingHand.valueOf(appPrefs.getString("writingHand", "RIGHT")!!) }.getOrDefault(WritingHand.RIGHT)) }
    var followSettingsOpen by remember { mutableStateOf(false) }
    var followPreferences by remember { mutableStateOf(FollowPreferences(
        direction = runCatching { WritingDirection.valueOf(appPrefs.getString("follow.direction", "LTR")!!) }.getOrDefault(WritingDirection.LTR),
        mode = runCatching { FollowMode.valueOf(appPrefs.getString("follow.mode", "TEXT")!!) }.getOrDefault(FollowMode.TEXT),
        automaticReturn = appPrefs.getBoolean("follow.autoReturn", false),
        position = appPrefs.getFloat("follow.position", .55f).coerceIn(.35f, .7f),
        horizontalPosition = appPrefs.getFloat("follow.horizontal", .5f).coerceIn(.35f, .65f),
        spacing = appPrefs.getFloat("follow.spacing", 32f).coerceIn(FollowPreferences.MIN_SPACING, FollowPreferences.MAX_SPACING),
        returnDelayMs = appPrefs.getInt("follow.returnDelayMs", WritingFollow.DEFAULT_RETURN_MS).coerceIn(300, 2000),
        adaptiveSpacing = appPrefs.getBoolean("follow.adaptiveSpacing", true),
        horizontalFollow = appPrefs.getBoolean("follow.horizontalFollow", true),
        verticalFollow = appPrefs.getBoolean("follow.verticalFollow", true),
        autoSwitchAreas = appPrefs.getBoolean("follow.autoSwitchAreas", true),
        minimumZoom = appPrefs.getFloat("follow.minimumZoom", 1.4f).coerceIn(1f, 3f),
        edgeThreshold = appPrefs.getFloat("follow.edgeThreshold", .72f).coerceIn(.55f, .95f),
        verticalDeadBand = appPrefs.getFloat("follow.verticalDeadBand", .15f).coerceIn(.05f, .3f),
        endMargin = appPrefs.getFloat("follow.endMargin", .08f).coerceIn(.02f, .2f),
        glideDurationMs = appPrefs.getInt("follow.glideMs", WritingFollow.DEFAULT_GLIDE_MS).coerceIn(120, 800),
        lineSpeedMs = appPrefs.getInt("follow.lineSpeedMs", FollowGlide.MS_PER_VIEWPORT.toInt()).coerceIn(250, 1500))) }
    LaunchedEffect(followPreferences) {
        appPrefs.edit()
            .putBoolean("follow.adaptiveSpacing", followPreferences.adaptiveSpacing)
            .putBoolean("follow.horizontalFollow", followPreferences.horizontalFollow)
            .putBoolean("follow.verticalFollow", followPreferences.verticalFollow)
            .putBoolean("follow.autoSwitchAreas", followPreferences.autoSwitchAreas)
            .putFloat("follow.minimumZoom", followPreferences.minimumZoom)
            .putFloat("follow.edgeThreshold", followPreferences.edgeThreshold)
            .putFloat("follow.verticalDeadBand", followPreferences.verticalDeadBand)
            .putFloat("follow.endMargin", followPreferences.endMargin)
            .putString("follow.direction", followPreferences.direction.name)
            .putString("follow.mode", followPreferences.mode.name).putBoolean("follow.autoReturn", followPreferences.automaticReturn)
            .putFloat("follow.horizontal", followPreferences.horizontalPosition).putFloat("follow.position", followPreferences.position).putFloat("follow.spacing", followPreferences.spacing)
            .putInt("follow.returnDelayMs", followPreferences.returnDelayMs).putInt("follow.glideMs", followPreferences.glideDurationMs)
            .putInt("follow.lineSpeedMs", followPreferences.lineSpeedMs).apply()
    }
    fun setWritingHand(value: WritingHand) {
        writingHand = value
        appPrefs.edit().putString("writingHand", value.name).apply()
    }
    var followStatus by remember(page.id) { mutableStateOf(WritingFollowStatus()) }
    val regionKey = "follow.region.${note.id}.${page.id}"
    var writingRegion by remember(regionKey) { mutableStateOf(runCatching {
        val values = appPrefs.getString(regionKey, null)?.split(",")?.map { it.toFloat() } ?: return@runCatching null
        WritingLane(values[0], values[1], values[2], values[3]).takeIf { values.all(Float::isFinite) && it.right > it.left && it.bottom > it.top }
    }.getOrNull()) }
    var writingRegions by remember(regionKey) { mutableStateOf(
        appPrefs.getString("$regionKey.areas", null)?.split(";")?.mapNotNull { entry ->
            runCatching {
                val v = entry.split(",").map { it.toFloat() }
                WritingLane(v[0], v[1], v[2], v[3]).takeIf {
                    v.size == 4 && v.all(Float::isFinite) && it.right > it.left && it.bottom > it.top
                }
            }.getOrNull()
        } ?: listOfNotNull(writingRegion)
    ) }
    val effectiveFollowPreferences = if (page.infinite)
        canvasResponse?.preferences(followPreferences) ?: followPreferences.copy(mode = FollowMode.TEXT, automaticReturn = false)
        else followPreferences
    if (followSettingsOpen) FollowSettingsDialog(
        preferences = effectiveFollowPreferences,
        proseOnly = page.infinite,
        writingHand = writingHand,
        onPreferences = {
            if (page.infinite) {
                canvasResponse = canvasResponse?.copy(automaticReturn = it.automaticReturn, direction = it.direction)
                followPreferences = it.copy(mode = followPreferences.mode, automaticReturn = followPreferences.automaticReturn,
                    autoSwitchAreas = followPreferences.autoSwitchAreas)
            } else followPreferences = it
        },
        onHand = ::setWritingHand,
        onDismiss = { followSettingsOpen = false },
    )
    var followMenu by remember { mutableStateOf(false) }
    // One peek view per notebook. A held peek closes on release; a tapped one stays until closed.
    val pinnedPeek = note.livePeekAnchor
    // Auto peek needs no pin: it is always the whole of the page being written on (a canvas has no page edge).
    var autoPeek by remember { mutableStateOf(appPrefs.getBoolean(AppPrefs.AUTO_PEEK, false)) }
    val peekAnchor = if (autoPeek && !page.infinite) PeekAnchor.wholePage(page) else pinnedPeek
    var peekMode by remember(note.id) { mutableStateOf<PeekMode?>(null) }
    // The view is fixed when the peek opens, so the page behind it changing can never re-frame it.
    var peekShown by remember(note.id) { mutableStateOf<PeekAnchor?>(null) }
    LaunchedEffect(peekMode) { if (peekMode == null) peekShown = null }
    LaunchedEffect(peekAnchor == null) { if (peekAnchor == null) peekMode = null }
    val peekOpen = peekMode != null && (peekShown ?: peekAnchor) != null
    val quick = remember(prefs) { QuickColorsState(prefs) }
    val toolPresets = remember(prefs) { ToolPresetState(prefs) }
    val toolbarLayouts = remember(appPrefs) { ToolbarLayoutState(appPrefs) }
    var options by remember(tool) { mutableStateOf(ToolOptions.load(prefs, tool)) }
    fun changeOptions(value: ToolOptions) { options = value; value.save(prefs, tool) }
    /** Applies a saved favorite tool setup: switches tool and restores its colour/width/opacity/style. */
    fun applyPreset(preset: ToolPreset) {
        tool = preset.tool
        val next = ToolOptions.load(prefs, preset.tool).copy(
            color = preset.color, width = preset.width, opacity = preset.opacity, style = preset.style)
        options = next
        next.save(prefs, preset.tool)
    }
    var snapEnabled by rememberSaveable { mutableStateOf(appPrefs.getBoolean("mathSnap", true)) }
    var shapeRecognition by remember(shapeRecognitionSetting) { mutableStateOf(shapeRecognitionSetting) }
    fun setSnap(v: Boolean) { snapEnabled = v; appPrefs.edit().putBoolean("mathSnap", v).apply() }
    // Eraser single-stroke + pressure + scribble-to-erase + whole-stroke + measurements + multitouch undo
    var eraserSingleStroke by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, false)) }
    fun setEraserSingleStroke(v: Boolean) {
        eraserSingleStroke = v
        appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, v).apply()
    }
    var eraserPressure by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_PRESSURE, true)) }
    fun setEraserPressure(v: Boolean) { eraserPressure = v; appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_PRESSURE, v).apply() }
    var scribbleToErase by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, true)) }
    var scribbleSensitivity by remember { mutableFloatStateOf(appPrefs.getFloat(EditorQuickPrefs.SCRIBBLE_SENSITIVITY, ScribbleSensitivity.DEFAULT)) }
    fun setScribbleToErase(v: Boolean) { scribbleToErase = v; appPrefs.edit().putBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, v).apply() }
    var eraserWholeStroke by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, false)) }
    fun setEraserWholeStroke(v: Boolean) { eraserWholeStroke = v; appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, v).apply() }
    var shapeMeasurements by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, true)) }
    fun setShapeMeasurements(v: Boolean) { shapeMeasurements = v; appPrefs.edit().putBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, v).apply() }
    var multiTouchUndo by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, true)) }
    fun setMultiTouchUndo(v: Boolean) { multiTouchUndo = v; appPrefs.edit().putBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, v).apply() }
    // One encoded string, so the graph sheet and the page never disagree about the dressing.
    var graphStyle by remember { mutableStateOf(GraphStyle.load(appPrefs)) }
    // Observe external pref changes (e.g. from ToolOptionsPanel): re-read when screen re-enters foreground
    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                EditorQuickPrefs.ERASER_SINGLE_STROKE -> { eraserSingleStroke = appPrefs.getBoolean(key, false) }
                EditorQuickPrefs.ERASER_PRESSURE -> eraserPressure = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.SCRIBBLE_SENSITIVITY -> scribbleSensitivity = appPrefs.getFloat(key, ScribbleSensitivity.DEFAULT)
                EditorQuickPrefs.SCRIBBLE_TO_ERASE -> scribbleToErase = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.ERASER_WHOLE_STROKE -> eraserWholeStroke = appPrefs.getBoolean(key, false)
                EditorQuickPrefs.SHAPE_MEASUREMENTS -> shapeMeasurements = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.MULTI_TOUCH_UNDO -> multiTouchUndo = appPrefs.getBoolean(key, true)
                "mathSnap" -> snapEnabled = appPrefs.getBoolean(key, true)
                "shapeRecognition" -> shapeRecognition = appPrefs.getBoolean(key, false)
                GraphStyle.PREF_KEY -> graphStyle = GraphStyle.load(appPrefs)
            }
        }
        appPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val penHaptics = remember(context) { (context.applicationContext as? FolioApplication)?.penHaptics }
    // For single-stroke eraser: remembers the tool before eraser was entered
    var eraserReturnTool by remember { mutableStateOf<Tool?>(null) }
    fun selectTool(value: Tool): Boolean {
        if (value == tool) return false
        if (value == Tool.ERASER && eraserSingleStroke) {
            // Only remember a real return target; entering eraser repeatedly should not overwrite it
            if (tool != Tool.ERASER) eraserReturnTool = tool
        } else if (tool == Tool.ERASER && value != Tool.ERASER) {
            eraserReturnTool = null
        }
        tool = value
        // Keep options in sync with the new tool's stored values immediately (remember(tool) updates next compose, but options var lags one frame)
        options = ToolOptions.load(prefs, value)
        return true
    }
    // Called by InkView after exactly one eraser stroke when single-stroke is on
    fun finishSingleStrokeEraser() {
        if (!eraserSingleStroke) return
        val ret = eraserReturnTool ?: Tool.PEN
        eraserReturnTool = null
        tool = ret
        options = ToolOptions.load(prefs, ret)
    }
    DisposableEffect(penHaptics, haptics) {
        if (haptics) penHaptics?.start() else penHaptics?.stop()
        onDispose { penHaptics?.stop() }
    }
    var mainInkView by remember { mutableStateOf<InkView?>(null) }
    val activeInkView = mainInkView
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
            pageFollowEnabled = enabled
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
    fun applyStylusShortcut(action: StylusShortcut) {
        val effect = StylusShortcuts.effect(action, tool, previousTool)
        if (effect != StylusShortcutEffect.None) penHaptics?.pulse()
        when (effect) {
            is StylusShortcutEffect.SwitchTool -> selectTool(effect.tool)
            StylusShortcutEffect.OpenPalette -> palette = true
            StylusShortcutEffect.Undo -> model.undo()
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
    var selection by remember { mutableStateOf<Pair<String, CanvasSelection>?>(null) }
    val pageFrames = remember { mutableMapOf<String, Rect>() }
    var moveSelection by remember { mutableStateOf<Pair<String, CanvasSelection>?>(null) }
    val canPaste = context.getSystemService(android.content.ClipboardManager::class.java)?.hasPrimaryClip() == true
    val selected = selection?.takeIf { it.first == page.id }?.second ?: CanvasSelection()
    /** Selection frame in view fractions for the context menu; cleared with the page. */
    var selectionAnchor by remember(page.id) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(tool, page.id) { selection = null }
    // The bound canvas, so toolbar actions can drive it directly (select-all fallback, deselect).
    LaunchedEffect(effectiveFollowPreferences, writingHand, tool) { followView?.suspendWritingFollow(clearBack = false) }
    fun configureFollow(view: InkView) {
        view.canvasWritingSession = if (page.infinite) canvasResponse else null
        view.followPreferences = effectiveFollowPreferences
        view.writingRegions = if (page.infinite) emptyList() else writingRegions
        view.onWritingRegions = { regions ->
            writingRegions = regions
            appPrefs.edit().putString("$regionKey.areas", regions.joinToString(";") {
                "${it.left},${it.top},${it.right},${it.bottom}"
            }).apply()
        }
        view.writingRegion = if (page.infinite) null else writingRegion
        view.onWritingRegion = { region ->
            writingRegion = region
            val edit = appPrefs.edit()
            if (region == null) edit.remove(regionKey) else edit.putString(regionKey, "${region.left},${region.top},${region.right},${region.bottom}")
            edit.apply()
        }
        if (view.isWritingFollowManuallyPaused != writingFollowPaused) {
            if (writingFollowPaused) view.pauseWritingFollow() else view.resumeWritingFollow()
        }
        view.onFollowStatus = { followStatus = it }
    }
    // Text defaults live with the app, not the notebook, so a new label keeps the last look.
    var textSize by rememberSaveable { mutableFloatStateOf(appPrefs.getFloat("text.size", 26f)) }
    var textColor by rememberSaveable { mutableIntStateOf(appPrefs.getInt("text.color", 0xFF303431.toInt())) }
    var textBold by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.bold", false)) }
    var textItalic by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.italic", false)) }
    var textAlign by rememberSaveable { mutableStateOf(try { TextAlignMode.valueOf(appPrefs.getString("text.align", "LEFT") ?: "LEFT") } catch (_: Exception) { TextAlignMode.LEFT }) }
    var textUnderline by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.underline", false)) }
    var textOpacity by rememberSaveable { mutableFloatStateOf(appPrefs.getFloat("text.opacity", TextBox.DEFAULT_OPACITY)) }
    val textDraftSaver = remember { Saver<TextBox?, String>(
        save = { draft -> draft?.let { InkCodec.encodeTexts(listOf(it)).toString() } ?: "" },
        restore = { raw -> runCatching { InkCodec.decodeTexts(org.json.JSONArray(raw)).firstOrNull() }.getOrNull() }
    ) }
    var textEditor by rememberSaveable(note.id, page.id, stateSaver = textDraftSaver) { mutableStateOf<TextBox?>(null) }
    var textEditorNew by rememberSaveable(note.id, page.id) { mutableStateOf(false) }
    // Notebook-wide typed-text search and reusable diagram elements.
    var noteSearchOpen by remember { mutableStateOf(false) }
    var noteQuery by rememberSaveable(note.id) { mutableStateOf("") }
    var layersPopover by remember { mutableStateOf(false) }
    var keyboardShortcuts by remember { mutableStateOf(false) }
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
    var restyleSelection by remember { mutableStateOf<List<Stroke>?>(null) }
    // The picture tapped with the hand tool, so the editor can offer delete and layering.
    var selectedImage by remember { mutableStateOf<Pair<String, PageImage>?>(null) }
    LaunchedEffect(page.id) { if (selectedImage?.first != page.id) selectedImage = null }
    // The picture being cropped; separate from the selection so the panel stays put underneath.
    var cropActive by remember { mutableStateOf(false) }
    var inkNavigating by remember { mutableStateOf(false) }
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
        appPrefs.edit().putInt("text.color", value).apply()
    }
    /** A tap on bare page drops a fresh text box where the finger landed, clear of the right edge. */
    fun placeTextBox(at: InkPoint) {
        val width = if (page.infinite) TextBox.DEFAULT_WIDTH else (page.width - at.x - 16f).coerceIn(TextBox.MIN_WIDTH, TextBox.DEFAULT_WIDTH)
        textEditor = TextBox(x = at.x, y = at.y, width = width, text = "", size = textSize, color = textColor, bold = textBold, italic = textItalic, align = textAlign, underline = textUnderline, opacity = textOpacity)
        textEditorNew = true
    }
    var documentZoom by rememberSaveable(note.id) { mutableFloatStateOf(session?.viewport?.zoom ?: 1f) }
    var documentPan by rememberSaveable(note.id) { mutableFloatStateOf(session?.viewport?.pan ?: 0f) }
    var pageBrowser by remember { mutableStateOf(false) }
    var pageQuery by rememberSaveable(note.id) { mutableStateOf("") }
    var pageFilter by rememberSaveable(note.id) { mutableStateOf(PageFilter.ALL) }
    var namedPage by remember { mutableStateOf<NotePage?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var pageMenuFor by remember { mutableStateOf<String?>(null) }
    var movingPage by remember { mutableStateOf<String?>(null) }
    var destinationPage by remember { mutableStateOf("") }
    var deletingPage by remember { mutableStateOf<String?>(null) }
    var pageNumber by rememberSaveable(note.id) { mutableStateOf("") }
    var pageBrowserGrid by rememberSaveable(note.id) { mutableStateOf(false) }
    var pageJumpExpanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf(note.title) }
    var scrubbing by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var paperMenu by remember { mutableStateOf(false) }
    var nextPaperMenu by remember { mutableStateOf(false) }
    var nextPagePaper by remember(note.id) { mutableStateOf<Paper?>(null) }
    var chosenPaper by remember { mutableStateOf(Paper.MATH_GRID) }
    var savePaperDefault by remember { mutableStateOf(false) }
    fun openPaperMenu(next: Boolean) {
        nextPaperMenu = next
        chosenPaper = if (next) nextPagePaper ?: note.defaultPaper ?: page.paper else page.paper
        savePaperDefault = false
        paperMenu = true
    }
    var timerPanel by remember { mutableStateOf(false) }
    var studyPanel by remember { mutableStateOf(false) }
    var examPanel by remember { mutableStateOf(false) }
    var markDialog by remember { mutableStateOf(false) }
    var pdfSearchOpen by remember { mutableStateOf(false) }
    var pdfQuery by remember { mutableStateOf("") }
    var pdfContentsOpen by remember { mutableStateOf(false) }
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
    val pages = rememberLazyListState(initialFirstVisibleItemIndex = state.pageIndex, initialFirstVisibleItemScrollOffset = session?.viewport?.scrollOffset ?: 0)
    var savedCanvas by remember(page.id) { mutableStateOf(session?.viewport ?: WorkspaceViewport()) }
    LaunchedEffect(note.id, pages) {
        snapshotFlow {
            val current = visibleCurrentPage(pages, note.pages.size)
            // Tab restoration starts at the current page, which may no longer be the first
            // visible one. Keep its actual position rather than the previous page's offset.
            val offset = pages.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }?.let { -it.offset }
                ?: pages.firstVisibleItemScrollOffset
            WorkspaceViewport(documentZoom, documentPan, offset,
                savedCanvas.canvasX, savedCanvas.canvasY, savedCanvas.canvasZoom) to tool
        }
            .distinctUntilChanged()
            .debounce(250)
            .collect { (viewport, selectedTool) ->
                model.updateTabViewport(note.id, viewport, selectedTool)
            }
    }
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
        val view = activeInkView ?: return
        if (view.isWritingGesture) return
        view.returnToCanvasView(previous)
        overviewReturn = null
    }
    /** Inspect all working without losing the exact position and zoom being used. */
    fun fitAllContent() {
        if (!page.infinite || !page.loaded) return
        val view = activeInkView ?: return
        if (view.isWritingGesture) return
        if (overviewReturn != null) { returnToWorking(); return }
        overviewReturn = view.canvasView()
        view.followEnabled = false
        view.fitCanvas(
            InkGeometry.contentBounds(page.strokes, page.texts, page.images, { InkRenderer.textHeight(it) }, page.width, page.height)
        )
    }
    fun jumpTo(index: Int) { activeInkView?.suspendWritingFollow(); motion.reset(); model.selectPage(index); scope.launch { pages.scrollToItem(index) } }
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
        motion.reset()
        val index = note.pages.size
        model.addPage(nextPagePaper)
        nextPagePaper = null
        if (page.infinite) return
        scope.launch {
            snapshotFlow { pages.layoutInfo.totalItemsCount }.first { it > index + 1 }
            pages.scrollToItem(index)
            model.selectPage(index)
        }
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
    /** Waits for the lazy list to grow, then lands on a page created mid-notebook. */
    fun revealNewPage(index: Int) {
        motion.reset()
        if (page.infinite) return
        val expected = note.pages.size + 2 // the fresh page plus the trailing Add button
        scope.launch {
            snapshotFlow { pages.layoutInfo.totalItemsCount }.first { it >= expected }
            pages.scrollToItem(index)
            model.selectPage(index)
        }
    }
    fun selectAllInk() {
        val view = activeInkView
        if (view != null) view.selectAll()
        else if (page.strokes.isNotEmpty() || page.texts.isNotEmpty() || page.images.isNotEmpty()) {
            // Fallback when view not yet bound (e.g. immediate toolbar tap after page switch)
            tool = Tool.LASSO
            selection = page.id to CanvasSelection(page.strokes.toList(), page.texts.toList(), page.images.toList())
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
    LaunchedEffect(note.id, page.infinite, note.pages.size, state.navigationRequest) {
        if (handledNavigation != state.navigationRequest) {
            activeInkView?.suspendWritingFollow()
            motion.reset()
            if (!page.infinite) pages.scrollToItem(state.pageIndex.coerceIn(0, note.pages.lastIndex))
            handledNavigation = state.navigationRequest
        }
        // A score has no scrolling column: the stage decides which pages are on view.
        if (page.infinite || music != null) return@LaunchedEffect
        snapshotFlow { visibleCurrentPage(pages, note.pages.size) }
            .distinctUntilChanged().collect { index -> index?.let(model::selectPage) }
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
    BackHandler(enabled = selected.isNotEmpty() && restyleSelection == null) {
        activeInkView?.clearSelection()
        selection = null
    }
    BackHandler(enabled = cropActive || selectedImage != null) {
        if (cropActive) activeInkView?.endImageCrop(false)
        else { selectedImage = null; activeInkView?.clearImageSelection() }
    }
    var canvasWindowBounds by remember(note.id) { mutableStateOf<Rect?>(null) }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (music != null && music.onKey(event.nativeKeyEvent)) true
        else if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) when (event.key) {
            Key.Z -> { if (event.isShiftPressed) model.redo() else model.undo(); true }
            Key.Y -> { model.redo(); true }
            Key.F -> { if (page.pdfIndex != null) pdfSearchOpen = true else noteSearchOpen = true; true }
            Key.G -> { pageBrowser = true; pageJumpExpanded = true; true }
            Key.Zero -> { resetZoom(); true }
            else -> false
        } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) when {
            cropActive -> { activeInkView?.endImageCrop(false); true }
            selectedImage != null -> { selectedImage = null; activeInkView?.clearImageSelection(); true }
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
            val stripWidth = 26.dp
            val stripInset = 2.dp
            val trackTop = floatingToolbarTop + FolioSpacing.dp8
            val trackBottom = 20.dp
            val stripWidthPx = with(density) { stripWidth.toPx() }
            val stripInsetPx = with(density) { stripInset.toPx() }
            val trackTopPx = with(density) { trackTop.toPx() }
            val trackBottomPx = with(density) { trackBottom.toPx() }
            val minimumThumbPx = with(density) { 24.dp.toPx() }
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
                    canRestyle = selected.strokes.isNotEmpty(),
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
                    onStyle = { restyleSelection = selected.strokes },
                    onDelete = { model.deleteSelection(selected); dismissSelection() },
                    onDeselect = ::dismissSelection,
                    onSelectAll = ::selectAllInk
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
            if (music != null && musicInsets != null) {
                val sheet: @Composable (NotePage, Int) -> Unit = { item, index ->
                    EditorPage(note.id, item, model, if (music.performance) Tool.HAND else tool, options, finger, snapEnabled, shapeRecognition, item.id == page.id,
                        onActive = { model.selectPage(index) }, onPan = { _, _ -> }, onPanEnd = {},
                        onSelection = { picked -> if (item.id == page.id) selection = item.id to picked },
                        onTextEdit = { textEditor = it; textEditorNew = false }, onTextCreate = ::placeTextBox,
                        onLoad = { model.loadPage(item.id) }, fullscreen = true, pageCamera = true, onPageKey = music.onKey,
                        canvasReset = canvasReset, onCanvasZoom = { documentZoom = it }, activeLayer = model.activeLayerOf(item),
                        selectedImageId = selectedImage?.takeIf { it.first == item.id }?.second?.id,
                        onImageSelected = { image -> selectedImage = image?.let { item.id to it } },
                        onCropMode = { if (item.id == page.id) cropActive = it }, onNavigating = { inkNavigating = it },
                        onLongPress = { x, y, at -> if (item.id == page.id) pageMenu = Triple(x, y, at) },
                        pdfLinks = pdfLinks, onPdfLink = ::openPdfLink,
                        eraserPressureEnabled = eraserPressure, scribbleToErase = scribbleToErase, scribbleSensitivity = scribbleSensitivity,
                        eraserWholeStroke = eraserWholeStroke, shapeMeasurements = shapeMeasurements, multiTouchUndo = multiTouchUndo, graphStyle = graphStyle,
                        palmRejectMs = palmRejectMs, panMultiplier = panMultiplier,
                        onEraserFinished = ::finishSingleStrokeEraser, onUndo = model::undo, onRedo = model::redo,
                        onSelectAllView = { if (item.id == page.id) { mainInkView = it; configureFollow(it) } }, inkStyle = options.style,
                        inputBlocked = music.performance,
                        onSelectionAnchor = { rect -> if (item.id == page.id) selectionAnchor = rect },
                        selectionAnchor = if (item.id == page.id) selectionAnchor else null,
                        selectionMenuViewport = selectionViewport,
                        selectionMenu = if (item.id != page.id || inkNavigating || restyleSelection != null || music.performance) null
                            else if (selected.isNotEmpty()) selectionMenu else pictureMenu)
                }
                MusicSheets(note, music, musicInsets, zoomed = documentZoom > 1.02f, onFit = { canvasReset++; documentZoom = 1f },
                    tapTurns = music.performance || tool == Tool.HAND || (!finger && tool != Tool.TEXT && tool != Tool.LASSO), sheet)
            } else if (page.infinite) {
                EditorPage(note.id, page, model, tool, options, finger, snapEnabled, shapeRecognition, true,
                    onActive = {}, onPan = { _, _ -> }, onPanEnd = {},
                    onSelection = { selection = page.id to it },                    onTextEdit = { textEditor = it; textEditorNew = false }, onTextCreate = ::placeTextBox,
                    onLoad = { model.loadPage(page.id) }, fullscreen = true, canvasReset = canvasReset,
                    onCanvasZoom = { documentZoom = it }, activeLayer = model.activeLayerOf(page),
                    initialViewport = session?.viewport, onCameraChanged = { savedCanvas = it },
                    selectedImageId = selectedImage?.takeIf { it.first == page.id }?.second?.id,
                    onImageSelected = { image -> selectedImage = image?.let { page.id to it } },
                    onCropMode = { cropActive = it }, onNavigating = { inkNavigating = it }, onLongPress = { x, y, at -> pageMenu = Triple(x, y, at) },
                    pdfLinks = pdfLinks, onPdfLink = ::openPdfLink,
                    eraserPressureEnabled = eraserPressure, scribbleToErase = scribbleToErase, scribbleSensitivity = scribbleSensitivity,
                    eraserWholeStroke = eraserWholeStroke, shapeMeasurements = shapeMeasurements, multiTouchUndo = multiTouchUndo, graphStyle = graphStyle,
                    palmRejectMs = palmRejectMs, panMultiplier = panMultiplier,
                    onEraserFinished = ::finishSingleStrokeEraser, onUndo = model::undo, onRedo = model::redo,
                    onSelectAllView = { mainInkView = it; configureFollow(it) }, inkStyle = options.style,
                    followEnabled = writingFollowEnabled && overviewReturn == null, writingHand = writingHand, followZoom = documentZoom, showAnswerAreas = showAnswerAreas, inputBlocked = peekOpen,
                    onSelectionAnchor = { selectionAnchor = it },
                    selectionAnchor = selectionAnchor,
                    selectionMenuViewport = selectionViewport,
                    selectionMenu = if (inkNavigating || pages.isScrollInProgress || restyleSelection != null || peekOpen) null else if (selected.isNotEmpty()) selectionMenu else pictureMenu)
            } else Box(Modifier.fillMaxSize().pointerInput(motion, viewportWidth, baseWidthPx, stripWidthPx, stripInsetPx, trackTopPx, trackBottomPx, minimumThumbPx, note.pages.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    motion.stop()
                    scope.launch { pages.stopScroll() }
                    if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                        motion.reset()
                        return@awaitEachGesture
                    }
                    val span = (size.height - trackTopPx - trackBottomPx).coerceAtLeast(0f)
                    val geometry = fastScrollGeometry(pages, note.pages.size, span, minimumThumbPx)
                    val onThumb = geometry != null &&
                        down.position.x in (size.width - stripInsetPx - stripWidthPx)..(size.width - stripInsetPx) &&
                        down.position.y in (trackTopPx + geometry.top)..(trackTopPx + geometry.top + geometry.height)
                    if (onThumb) {
                        activeInkView?.suspendWritingFollow()
                        motion.reset()
                        down.consume()
                        val travelSpan = (span - geometry.height).coerceAtLeast(1f)
                        val firstPageIndex = pages.firstVisibleItemIndex.coerceIn(0, (note.pages.size - 1).coerceAtLeast(0))
                        val firstPageSize = pages.layoutInfo.visibleItemsInfo.firstOrNull { it.index < note.pages.size }?.size
                            ?: pages.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
                        val startProgress = if (!pages.canScrollForward) 1f else DocumentViewport.scrollProgress(firstPageIndex,
                            pages.firstVisibleItemScrollOffset, firstPageSize,
                            note.pages.size)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                // Additional fingers or a pen cancel the scrub before it moves the page.
                                if (event.changes.any { it.id != down.id && it.pressed }) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                val delta = change.position - down.position
                                if (!scrubbing) {
                                    if (kotlin.math.abs(delta.x) > viewConfiguration.touchSlop &&
                                        kotlin.math.abs(delta.x) >= kotlin.math.abs(delta.y)) break
                                    if (kotlin.math.abs(delta.y) <= viewConfiguration.touchSlop) continue
                                    scrubbing = true
                                }
                                pages.requestScrollToItem(DocumentViewport.pageAt(startProgress + delta.y / travelSpan, note.pages.size))
                            }
                        } finally {
                            scrubbing = false
                        }
                    } else {
                        // Let ink and controls claim their down first. Unclaimed touches belong
                        // to the surrounding workspace, including page gaps and outer margins.
                        val backgroundPan = !awaitPointerEvent(PointerEventPass.Main)
                            .changes.first { it.id == down.id }.isConsumed
                        var transforming = false
                        val velocity = VelocityTracker()
                        var travel = Offset.Zero
                        velocity.addPosition(down.uptimeMillis, travel)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser) }) {
                                transforming = false
                                motion.reset()
                                break
                            }
                            val fingers = event.changes.count { it.pressed }
                            if (backgroundPan && !transforming && fingers == 1) {
                                val change = event.changes.first { it.pressed }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    transforming = true
                                }
                            }
                            if (fingers >= 2) {
                                activeInkView?.suspendWritingFollow()
                                if (!transforming) velocity.addPosition(event.changes.first().previousUptimeMillis, travel)
                                transforming = true
                                val factor = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val delta = event.calculatePan()
                                // Pan deltas exclude newly added/lifted pointers, so cumulative travel
                                // keeps release velocity intact when the fingers lift one at a time.
                                travel += delta
                                velocity.addPosition(event.changes.first().uptimeMillis, travel)
                                val oldZoom = documentZoom
                                val newZoom = (oldZoom * factor).coerceIn(0.5f, 4f)
                                val ratio = newZoom / oldZoom
                                documentPan = DocumentViewport.zoomPan(documentPan, centroid.x, viewportWidth, baseWidthPx * documentScale * newZoom, ratio)
                                documentZoom = newZoom
                                val offset = DocumentViewport.zoomScroll(pages.firstVisibleItemScrollOffset, centroid.y - pages.layoutInfo.beforeContentPadding, ratio)
                                if (kotlin.math.abs(factor - 1f) > .001f) {
                                    motion.reset()
                                    pages.requestScrollToItem(pages.firstVisibleItemIndex, offset - delta.y.roundToInt())
                                } else motion.drag(delta.y)
                                documentPan = DocumentViewport.clampPan(documentPan + delta.x, baseWidthPx * documentScale * newZoom, viewportWidth)
                            }
                            if (transforming && fingers < 2) {
                                val delta = event.calculatePan()
                                if (fingers == 1) {
                                    travel += delta
                                    panBy(delta.x, delta.y)
                                }
                                velocity.addPosition(event.changes.first().uptimeMillis, travel)
                            }
                            if (transforming) event.changes.forEach { it.consume() }
                            inkNavigating = transforming
                        } while (event.changes.any { it.pressed })
                        inkNavigating = false
                        if (transforming) motion.release(velocity.calculateVelocity().y)
                        else if (motion.stretch != 0f) motion.release(0f)
                    }
                }
            }, contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = pages,
                    modifier = Modifier.requiredWidth(baseWidth * documentScale * documentZoom).fillMaxHeight().offset { IntOffset(documentPan.roundToInt(), 0) }.graphicsLayer { translationY = motion.stretch }.holdPenFromScrolling(),
                    contentPadding = PaddingValues(top = floatingToolbarTop + FolioSpacing.dp8, bottom = FolioSpacing.dp16),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(note.pages, key = { _, item -> item.id }) { index, item ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4), modifier = Modifier.fillMaxWidth()) {
                            EditorPage(note.id, item, model, tool, options, finger, snapEnabled, shapeRecognition, active = item.id == page.id,
                                onActive = { model.selectPage(index) }, onPan = ::panBy, onPanEnd = motion::release,
                                onSelection = { picked -> if (item.id == page.id) selection = item.id to picked },
                                onTextEdit = { box -> textEditor = box; textEditorNew = false },
                                onTextCreate = ::placeTextBox,
                                onLoad = { model.loadPage(item.id) }, activeLayer = model.activeLayerOf(item),
                                paperWidth = baseWidth * documentZoom, onStickyDraw = { selectTool(Tool.PEN) },
                                selectedImageId = selectedImage?.takeIf { it.first == item.id }?.second?.id,
                                onCropMode = { if (item.id == page.id) cropActive = it }, onNavigating = { inkNavigating = it },
                                onLongPress = { x, y, at -> if (item.id == page.id) pageMenu = Triple(x, y, at) },
                                onImageSelected = { image ->
                                    selectedImage = image?.let { item.id to it }
                                },
                                pdfLinks = pdfLinks, onPdfLink = ::openPdfLink,
                                markZones = markZones, markAssist = markAssist, markColor = markingColor,
                                                onReplaceZone = { old, new -> saveManualZones(manualZones.filterNot { it == old } + listOfNotNull(new)) },
                                onPageFrame = { id, frame -> if (frame == null) pageFrames.remove(id) else pageFrames[id] = frame },
                                onSelectionDrop = { sourceId, picked, x, y ->
                                    // A selection let go over another page lands there, centred on the finger.
                                    val hit = pageFrames.entries.firstOrNull { (id, f) -> id != sourceId && f.contains(Offset(x, y)) }
                                    val target = hit?.let { e -> note.pages.find { it.id == e.key } }
                                    if (hit == null || target == null || target.infinite || hit.value.width <= 0f || hit.value.height <= 0f) false
                                    else {
                                        val f = hit.value
                                        model.moveSelectionToPage(sourceId, target.id, picked,
                                            InkPoint((x - f.left) / f.width * target.width, (y - f.top) / f.height * target.height))
                                        true
                                    }
                                },
                                eraserPressureEnabled = eraserPressure, scribbleToErase = scribbleToErase, scribbleSensitivity = scribbleSensitivity,
                                eraserWholeStroke = eraserWholeStroke, shapeMeasurements = shapeMeasurements, multiTouchUndo = multiTouchUndo, graphStyle = graphStyle,
                                palmRejectMs = palmRejectMs, panMultiplier = panMultiplier,
                                onEraserFinished = ::finishSingleStrokeEraser, onUndo = model::undo, onRedo = model::redo,
                                onSelectAllView = { if (item.id == page.id) { mainInkView = it; configureFollow(it) } }, inkStyle = options.style,
                                followEnabled = writingFollowEnabled && item.id == page.id && overviewReturn == null, writingHand = writingHand, followZoom = documentZoom, showAnswerAreas = showAnswerAreas,
                                autoDetectAnswerAreas = autoDetectAnswerAreas,
                                inputBlocked = peekOpen, onFollowPan = { dx, dy ->
                                    val oldPan = documentPan
                                    documentPan = DocumentViewport.clampPan(documentPan + dx, baseWidthPx * documentScale * documentZoom, viewportWidth)
                                    val movedY = -pages.dispatchRawDelta(-dy)
                                    (documentPan - oldPan) to movedY
                                },
                                onSelectionAnchor = { rect -> if (item.id == page.id) selectionAnchor = rect },
                                selectionAnchor = if (item.id == page.id) selectionAnchor else null,
                                selectionMenuViewport = selectionViewport,
                                selectionMenu = if (item.id != page.id || inkNavigating || pages.isScrollInProgress || restyleSelection != null || peekOpen) null else if (selected.isNotEmpty()) selectionMenu else pictureMenu)
                            // Quiet caption keeps the eye oriented in long notebooks without chrome noise.
                            // Long-pressing it opens the page's own menu — name, bookmark, redo, move, delete.
                            Box {
                                val captionHold = rememberLongPressGuard()
                                Row(Modifier.longPressAction(captionHold) { pageMenuFor = item.id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                    if (item.bookmarked) Icon(Icons.Rounded.Bookmark, "Bookmarked", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    if (item.redoFlag) Icon(Icons.Rounded.OutlinedFlag, "Flagged to redo", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                    Text(
                                        item.title.ifBlank { "Page ${index + 1}" } + " · ${index + 1} / ${note.pages.size}",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                PageRowMenu(item, pageMenuFor == item.id, { pageMenuFor = null },
                                    canMoveUp = index > 0, canMoveDown = index < note.pages.lastIndex, canDelete = note.pages.size > 1,
                                    onName = { namedPage = item; pageTitle = item.title },
                                    onBookmark = { model.togglePageBookmark(item.id) },
                                    onRedoFlag = { model.setPageRedoFlag(note.id, item.id, !item.redoFlag) },
                                    onMoveTo = { movingPage = item.id; destinationPage = (index + 1).toString() },
                                    onMoveUp = { model.movePage(index, index - 1) }, onMoveDown = { model.movePage(index, index + 1) },
                                    onInsert = { revealNewPage(model.insertPage(index + 1)) },
                                    onDuplicate = { model.duplicatePage(index)?.let { revealNewPage(it) } },
                                    onDelete = { deletingPage = item.id })
                            }
                        }
                    }
                    item {
                        AddPageButton(
                            label = "Add page · ${paperLabel(nextPagePaper ?: note.defaultPaper ?: page.paper)}",
                            onClick = ::addPage,
                            onLongClick = { openPaperMenu(true) },
                            modifier = Modifier.padding(top = FolioSpacing.dp4)
                        )
                    }
                }
                // Pull past the last page: the squiggly loading shape fills as you pull, spins while the page is added.
                val pullProgress = if (pullAdding) 1f else motion.pullProgress
                LaunchedEffect(pullProgress >= 1f) { if (pullProgress >= 1f && !pullAdding) pullHaptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                if (pullProgress > 0.04f) Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = FolioSpacing.dp24).graphicsLayer {
                        alpha = if (pullAdding) 1f else (pullProgress * 1.6f).coerceAtMost(1f)
                        val grow = .7f + .3f * pullProgress
                        scaleX = grow; scaleY = grow
                    },
                    shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(start = FolioSpacing.dp8, end = FolioSpacing.dp16, top = FolioSpacing.dp4, bottom = FolioSpacing.dp4),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        if (pullAdding) LoadingIndicator(Modifier.size(40.dp).semanticsLabel("Adding page"))
                        else LoadingIndicator(progress = { pullProgress }, modifier = Modifier.size(40.dp))
                        Text(if (pullAdding) "Adding page…" else if (pullProgress >= 1f) "Release to add page" else "Pull to add page",
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            // Keep the original composition and cameras alive underneath. Closing the peek is an
            // exact return, including scroll offset, tool and the follow engine's line.
            if (peekOpen) {
                val anchor = (peekShown ?: peekAnchor)!!
                val target = anchor.page(note.pages)
                if (target != null) Box(Modifier.fillMaxSize().zIndex(10f).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    // A held peek is a glance; one kept open can be panned and zoomed to read.
                    EditorPage(note.id, target, model, Tool.HAND, options, false, false, false, false,
                        onActive = {}, onPan = { _, _ -> }, onPanEnd = {}, onSelection = {}, onTextEdit = {}, onTextCreate = {},
                        onLoad = { model.loadPage(target.id) }, fullscreen = true, readOnly = true,
                        inputBlocked = peekMode == PeekMode.HELD, peekRegion = anchor)
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(top = FolioSpacing.dp12).guardUiTouches(),
                        shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 4.dp, tonalElevation = 1.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp4, bottom = FolioSpacing.dp4).heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Icon(Icons.Rounded.Visibility, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            val pageNumber = note.pages.indexOfFirst { it.id == target.id } + 1
                            Text(if (peekMode == PeekMode.HELD) "Peeking at page $pageNumber · release to return" else "Peek · page $pageNumber",
                                style = MaterialTheme.typography.labelLarge)
                            if (peekMode == PeekMode.LATCHED) TextButton({ peekMode = null }, shapes = ButtonDefaults.shapes()) { Text("Close") }
                        }
                    }
                }
            }
            BackHandler(enabled = peekMode == PeekMode.LATCHED) { peekMode = null }
            fun pinPeekView(wholePage: Boolean) {
                val anchor = if (wholePage) PeekAnchor.wholePage(page) else activeInkView?.currentPeekAnchor() ?: PeekAnchor.wholePage(page)
                model.setPeekAnchor(anchor)
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
            if (music == null) HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.align(if (writingHand == WritingHand.RIGHT) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(followInset)
                    .height(44.dp)
                    .guardUiTouches()
                    .zIndex(11f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
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
                        enabled = !peekOpen, onClick = { followMenu = true })
                    DropdownMenu(followMenu, { followMenu = false; followSub = null }, modifier = Modifier.guardUiTouches()) {
                        if (page.infinite) {
                            DropdownMenuItem({ Text(responseLabel) },
                                { setWritingFollow(!writingFollowEnabled); followMenu = false },
                                enabled = overviewReturn == null,
                                leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                            Text("Use a response column for paragraphs. Leave it off for maths, short answers and diagrams.",
                                Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall)
                            if (canvasResponse != null) DropdownMenuItem({ Text("Start a new column here") },
                                { setWritingFollow(true); followMenu = false }, enabled = overviewReturn == null)
                            DropdownMenuItem({ Text(if (overviewReturn == null) "See all working" else "Return to working") },
                                { fitAllContent(); followMenu = false })
                        }
                        if (writingFollowEnabled) {
                            Text(followStatus.message,
                                Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (overflowFollowActions) {
                                DropdownMenuItem({ Text("Next writing line") },
                                    { followView?.nextWritingLine(); followMenu = false },
                                    enabled = overviewReturn == null,
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null) })
                                DropdownMenuItem({ Text(if (followStatus.paused) "Resume writing follow" else "Pause writing follow") },
                                    { toggleFollowPause(); followMenu = false },
                                    leadingIcon = { Icon(if (followStatus.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null) })
                                DropdownMenuItem({ Text("Undo the last follow move") },
                                    { followView?.backWritingView(); followMenu = false },
                                    enabled = followStatus.canGoBack,
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) })
                            }
                            HorizontalDivider()
                        }
                        if (!page.infinite) DropdownMenuItem(
                            { Text("Writing: " + if (followPreferences.mode == FollowMode.TEXT) "Text" else "Maths") },
                            {
                                followPreferences = followPreferences.copy(
                                    mode = if (followPreferences.mode == FollowMode.TEXT) FollowMode.MATH else FollowMode.TEXT)
                                followMenu = false
                            },
                            leadingIcon = { Icon(if (followPreferences.mode == FollowMode.TEXT) Icons.Rounded.TextFields else Icons.Rounded.Functions, null) }
                        )
                        DropdownMenuItem(
                            { Text("Writing hand: " + writingHand.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            {
                                setWritingHand(if (writingHand == WritingHand.RIGHT) WritingHand.LEFT else WritingHand.RIGHT)
                                followView?.suspendWritingFollow()
                            },
                            leadingIcon = { Icon(Icons.Rounded.PanTool, null) }
                        )
                        if (!page.infinite) SubmenuItem("Answer areas…", Icons.Rounded.CropFree, followSub == FollowSub.AREAS, { openSub(FollowSub.AREAS) }) {
                            DropdownMenuItem({ Text("Select answer area") }, { pageFollowEnabled = true; appPrefs.edit().putBoolean("writingFollow", true).apply(); followView?.selectWritingRegion(); followMenu = false })
                            DropdownMenuItem({ Text("Detect answer areas") }, { pageFollowEnabled = true; appPrefs.edit().putBoolean("writingFollow", true).apply(); followView?.suggestWritingRegion(); followMenu = false })
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Auto-detect answer areas")
                                        Text("Current page only, as you scroll", style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                trailingIcon = { Checkbox(checked = autoDetectAnswerAreas, onCheckedChange = null) },
                                onClick = {
                                    autoDetectAnswerAreas = !autoDetectAnswerAreas
                                    val edit = appPrefs.edit().putBoolean("follow.autoDetectAnswerAreas", autoDetectAnswerAreas)
                                    if (autoDetectAnswerAreas) {
                                        pageFollowEnabled = true
                                        edit.putBoolean("writingFollow", true)
                                    }
                                    edit.apply()
                                    followMenu = false
                                })
                            DropdownMenuItem(
                                text = { Text("Show answer area box") },
                                trailingIcon = { Checkbox(checked = showAnswerAreas, onCheckedChange = null) },
                                onClick = {
                                    showAnswerAreas = !showAnswerAreas
                                    appPrefs.edit().putBoolean("follow.showAnswerAreas", showAnswerAreas).apply()
                                    followMenu = false
                                })
                            if (writingRegion != null) DropdownMenuItem({ Text("Clear answer areas") }, { followView?.clearWritingRegion(); followMenu = false })
                        }
                        if (!page.infinite || canvasResponse != null) DropdownMenuItem({ Text(if (page.infinite) "Response settings…" else "Writing follow settings…") }, { followSettingsOpen = true; followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                        HorizontalDivider()
                        Text("Peek view", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownMenuItem(
                            { Text(if (pinnedPeek == null) "Pin this view" else "Replace with this view") },
                            { pinPeekView(wholePage = false); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, null) }
                        )
                        DropdownMenuItem(
                            { Text("Pin this whole page") },
                            { pinPeekView(wholePage = true); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.FitScreen, null) }
                        )
                        DropdownMenuItem(
                            { Text(if (autoPeek) "Auto peek: whole page (on)" else "Auto peek: whole page (off)") },
                            { autoPeek = !autoPeek; appPrefs.edit().putBoolean(AppPrefs.AUTO_PEEK, autoPeek).apply(); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Visibility, null) }
                        )
                        if (pinnedPeek != null) DropdownMenuItem(
                            { Text("Remove peek view") },
                            { model.setPeekAnchor(null); followMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Close, null) }
                        )
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
                            else -> { motion.reset(); peekShown = peekAnchor; peekMode = mode; true }
                        }
                    }
                }
            }
            if (tool == Tool.MARK_AREA && page.pdfIndex != null) MarkAreaHint(
                Modifier.align(Alignment.BottomCenter).zIndex(11f).padding(bottom = FolioSpacing.dp16))
            if (!page.infinite && music == null) Box(Modifier.align(Alignment.CenterEnd).padding(end = stripInset).padding(top = trackTop, bottom = trackBottom).width(110.dp).fillMaxHeight()) {
                FastScrollTrack(pages, note.pages.size, scrubbing, Modifier.fillMaxSize())
            }
            if (music != null && musicInsets != null) music.chrome(this, musicInsets)
            Column(
                Modifier.align(Alignment.TopCenter).zIndex(11f)
                    .fillMaxWidth()
                    .padding(top = FolioSpacing.dp6, start = FolioSpacing.dp6, end = FolioSpacing.dp6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)
            ) {
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
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    undo = model::undo,
                    redo = model::redo,
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
                    actions = listOf(
                        ToolbarAction(Icons.Rounded.Settings, "Settings", onSettings)
                    ),
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
                            onClose = model::close,
                            showBack = showBack,
                            timer = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                                    TimingChip(state.timer, onLongClick = {
                                        if (state.timer.phase != ExamTimerPhase.IDLE) model.toggleTimerPause()
                                    }) { timerPanel = true }
                                    FocalStudyChip(state.timer, onLongClick = {
                                        (context.applicationContext as FolioApplication).focalStudy.toggleFocus()
                                    }) { studyPanel = true }
                                }
                            },
                            pageIndex = state.pageIndex,
                            pageCount = note.pages.size,
                            onPrevious = { jumpTo(state.pageIndex - 1) },
                            onNext = { jumpTo(state.pageIndex + 1) },
                            onPages = { pageBrowser = true },
                            onFirstPage = { jumpTo(0) },
                            onLastPage = { jumpTo(note.pages.size - 1) },
                            zoomPercent = (documentZoom * 100).roundToInt(),
                            onFit = ::resetZoom,
                            onFitAll = if (page.infinite) ::fitAllContent else null,
                            onAdd = ::addPage,
                            onSearch = {
                                if (page.pdfIndex != null) { pdfQuery = state.pdfSearch.query; pdfSearchOpen = true }
                                else { noteSearchOpen = true }
                            },
                            onLayers = { layersPopover = true },
                            layerStatus = PageLayers.effective(page.layers).firstOrNull { it.id == model.activeLayerOf(page) }?.let { layer ->
                                when { !layer.visible -> "Hidden layer: ${layer.name}"; layer.locked -> "Locked layer: ${layer.name}"; else -> null }
                            },
                            onContents = if (page.pdfIndex != null) ({ pdfContentsOpen = true; loadOutline() }) else null,
                            onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                            paperTitle = paperLabel(page.paper),
                            onPaper = if (page.pdfIndex == null) ({ openPaperMenu(false) }) else null,
                            bookmarked = page.bookmarked,
                            onBookmark = { model.togglePageBookmark(page.id) },
                            layersPopover = {
                                if (layersPopover) LayersPopover(page, model.activeLayerOf(page), selected.size, model,
                                    onMoveSelection = { layer -> model.moveSelectionToLayer(selected, layer) },
                                    onDismiss = { layersPopover = false })
                            },
                            onInsertPage = { revealNewPage(model.insertPage(state.pageIndex + 1)) },
                            onDuplicatePage = { model.duplicatePage()?.let { revealNewPage(it) } },
                            onExport = onExport,
                            onSettings = onSettings,
                            onKeyboardShortcuts = { keyboardShortcuts = true },
                            pageActions = { dismiss ->
                                PageOptionsContent(dismiss, page, state.saveFailed,
                                    onPaper = { openPaperMenu(false) }, onClear = { clear = true }, onRetry = model::retrySave,
                                    onRedo = model::toggleRedoFlag, onExam = { examPanel = true }, onRecordMark = { markDialog = true }, onTimer = { timerPanel = true },
                                    onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                                    onSearchPdf = { pdfQuery = state.pdfSearch.query; pdfSearchOpen = true },
                                    onContents = { pdfContentsOpen = true; loadOutline() },
                                    onSearchNotes = { noteSearchOpen = true },
                                    onOrganize = { pageBrowser = true },
                                    onBookmark = { model.togglePageBookmark(page.id) },
                                    onNamePage = { namedPage = page; pageTitle = page.title })
                            }
                        )
                    }
                )
            }
        }
    }
    if (pageBrowser) FolioPanel(title = "Notebook pages", onDismissRequest = { pageBrowser = false }) {
        val visiblePages = remember(note.pages, pageQuery, pageFilter) { organizePages(note.pages, pageQuery, pageFilter) }
        val canDrag = !pageBrowserGrid && pageQuery.isBlank() && pageFilter == PageFilter.ALL
        val browserPages = rememberLazyListState()
        val browserGrid = rememberLazyGridState()
        LaunchedEffect(pageQuery, pageFilter, pageBrowserGrid) {
            val current = visiblePages.indexOfFirst { it.value.id == page.id }
            if (pageBrowserGrid) browserGrid.scrollToItem(current.coerceAtLeast(0))
            else browserPages.scrollToItem(current.coerceAtLeast(0))
        }
        fun goToPage() {
            val target = pageNumber.toIntOrNull()?.takeIf { it in 1..note.pages.size } ?: return
            jumpTo(target - 1); pageBrowser = false; pageNumber = ""
        }
        OutlinedTextField(pageQuery, { pageQuery = it }, Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24),
            label = { Text("Find a page by name or number") }, placeholder = { Text("e.g. Quadratics or 12") }, singleLine = true,
            shape = FolioShapes.large,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (pageQuery.isNotEmpty()) IconButton({ pageQuery = "" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear page search") } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            PageFilter.entries.forEach { option ->
                FilterChip(pageFilter == option, { pageFilter = option }, { Text(option.label) })
            }
        }
        TextButton({ pageJumpExpanded = !pageJumpExpanded }, modifier = Modifier.padding(horizontal = FolioSpacing.dp24)) {
            Icon(Icons.Rounded.Numbers, null); Spacer(Modifier.width(FolioSpacing.dp8))
            Text(if (pageJumpExpanded) "Hide page jump" else "Go to a page number")
        }
        if (pageJumpExpanded) Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(pageNumber, { pageNumber = it.filter(Char::isDigit).take(9) },
                label = { Text("Go to page (1–${note.pages.size})") }, singleLine = true,
                shape = FolioShapes.large,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { goToPage() }),
                modifier = Modifier.weight(1f))
            FilledTonalButton(::goToPage,
                enabled = pageNumber.toIntOrNull()?.let { it in 1..note.pages.size } == true,
                shapes = ButtonDefaults.shapes()) { Text("Go") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically) {
            Text(if (canDrag) "Long-press a page and drag to reorder it." else "${visiblePages.size} pages · tap to open", Modifier.weight(1f).padding(start = FolioSpacing.dp8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ pageBrowserGrid = !pageBrowserGrid }) {
                Icon(if (pageBrowserGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    if (pageBrowserGrid) "Show page list" else "Show page thumbnails")
            }
        }
        val rowHeight = 112.dp
        val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragDelta by remember { mutableFloatStateOf(0f) }
        if (pageBrowserGrid && visiblePages.isNotEmpty()) LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp), state = browserGrid,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
        ) {
            gridItems(visiblePages, key = { it.value.id }) { (index, item) ->
                var menu by remember(item.id) { mutableStateOf(false) }
                Surface(onClick = { jumpTo(index); pageBrowser = false }, shape = FolioShapes.large,
                    color = if (index == state.pageIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        PageThumbnail(note.id, item, model.thumbnails, Modifier.fillMaxWidth().height(152.dp), previewWidth = 144.dp)
                        Text(item.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            IconButton({ model.togglePageBookmark(item.id) }) {
                                Icon(if (item.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark page ${index + 1}")
                            }
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Page ${index + 1} options") }
                                PageRowMenu(item, menu, { menu = false },
                                    canMoveUp = index > 0, canMoveDown = index < note.pages.lastIndex, canDelete = note.pages.size > 1,
                                    onName = { namedPage = item; pageTitle = item.title }, onBookmark = { model.togglePageBookmark(item.id) },
                                    onRedoFlag = { model.setPageRedoFlag(note.id, item.id, !item.redoFlag) },
                                    onMoveTo = { movingPage = item.id; destinationPage = (index + 1).toString() },
                                    onMoveUp = { model.movePage(index, index - 1) }, onMoveDown = { model.movePage(index, index + 1) },
                                    onInsert = { model.insertPage(index + 1) }, onDuplicate = { model.duplicatePage(index) },
                                    onDelete = { deletingPage = item.id })
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = browserPages, contentPadding = PaddingValues(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (visiblePages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No pages match", style = MaterialTheme.typography.titleSmall)
                    Text("Try another name or filter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ pageQuery = ""; pageFilter = PageFilter.ALL }) { Text("Show all pages") }
                }
            }
            items(visiblePages, key = { it.value.id }) { (index, item) ->
                val dragging = dragFrom == index
                PageRow(item, index, state.pageIndex == index, dragging,
                    Modifier.height(rowHeight)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragDelta else 0f }
                        .pointerInput(item.id, index, canDrag, note.pages.size) {
                            if (canDrag) detectDragGesturesAfterLongPress(
                                onDragStart = { dragFrom = index; dragDelta = 0f },
                                onDrag = { change, amount -> change.consume(); dragDelta += amount.y },
                                onDragEnd = {
                                    val from = dragFrom
                                    if (from != null) {
                                        val to = (from + (dragDelta / rowHeightPx).roundToInt()).coerceIn(0, note.pages.lastIndex)
                                        if (to != from) model.movePage(from, to)
                                    }
                                    dragFrom = null; dragDelta = 0f
                                },
                                onDragCancel = { dragFrom = null; dragDelta = 0f }
                            )
                        },
                    onOpen = { jumpTo(index); pageBrowser = false },
                    onMoveUp = { model.movePage(index, index - 1) }, onMoveDown = { model.movePage(index, index + 1) },
                    onDuplicate = { model.duplicatePage(index) }, onInsert = { model.insertPage(index + 1) },
                    onDelete = { deletingPage = item.id },
                    onName = { namedPage = item; pageTitle = item.title },
                    onBookmark = { model.togglePageBookmark(item.id) },
                    onRedoFlag = { model.setPageRedoFlag(note.id, item.id, !item.redoFlag) },
                    onMoveTo = { movingPage = item.id; destinationPage = (index + 1).toString() },
                    canMoveUp = index > 0, canMoveDown = index < note.pages.lastIndex, canDelete = note.pages.size > 1,
                    noteId = note.id, thumbnails = model.thumbnails)
            }
        }
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            FilledTonalButton({ addPage(); pageBrowser = false }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add page")
            }
            OutlinedButton({ revealNewPage(model.insertPage(state.pageIndex + 1)); pageBrowser = false }, shapes = ButtonDefaults.shapes()) {
                Text("Insert after ${state.pageIndex + 1}")
            }
            TextButton({ model.duplicatePage()?.let { revealNewPage(it) }; pageBrowser = false }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Duplicate")
            }
            TextButton({ pageBrowser = false; openPaperMenu(true) }, shapes = ButtonDefaults.shapes()) { Text("Choose paper") }
        }
    }
    namedPage?.let { target ->
        FolioPopover("Name page", Icons.Rounded.Edit, onDismiss = { namedPage = null }, actions = {
            TextButton({ namedPage = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Button({ model.renamePage(target.id, pageTitle); namedPage = null }, shapes = ButtonDefaults.shapes()) { Text("Save") }
        }) {
            OutlinedTextField(pageTitle, { pageTitle = it.take(120) }, label = { Text("Page name") },
                placeholder = { Text("e.g. Quadratics homework") },
                supportingText = { Text("${pageTitle.length}/120 · Leave blank to use the page number.") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.renamePage(target.id, pageTitle); namedPage = null }),
                shape = FolioShapes.large, modifier = Modifier.fillMaxWidth())
        }
    }
    movingPage?.let { pageId ->
        val destination = destinationPage.toIntOrNull()
        fun commit() {
            val from = note.pages.indexOfFirst { it.id == pageId }
            if (from >= 0 && destination != null && destination in 1..note.pages.size) model.movePage(from, destination - 1)
            movingPage = null
        }
        FolioPopover("Move page", Icons.Rounded.LowPriority, onDismiss = { movingPage = null }, actions = {
            TextButton({ movingPage = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Button(::commit, enabled = destination != null && destination in 1..note.pages.size, shapes = ButtonDefaults.shapes()) { Text("Move") }
        }) {
            OutlinedTextField(destinationPage, { destinationPage = it.filter(Char::isDigit).take(9) },
                label = { Text("New position (1–${note.pages.size})") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                shape = FolioShapes.large, modifier = Modifier.fillMaxWidth())
        }
    }
    deletingPage?.let { pageId ->
        val index = note.pages.indexOfFirst { it.id == pageId }
        FolioPopover("Delete ${note.pages.getOrNull(index)?.displayTitle(index) ?: "page"}?", Icons.Rounded.DeleteOutline,
            onDismiss = { deletingPage = null }, actions = {
                TextButton({ deletingPage = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ if (index >= 0 && note.pages.size > 1) model.deletePage(index); deletingPage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shapes = ButtonDefaults.shapes()) { Text("Delete") }
            }) { Text("This removes the page and its content. Use Undo to restore it during this session.", style = MaterialTheme.typography.bodyMedium) }
    }
    if (rename) FolioPopover("Rename notebook", Icons.Rounded.Edit, onDismiss = { rename = false }, actions = {
        TextButton({ rename = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        Button({ model.rename(note, renameTitle); rename = false }, enabled = renameTitle.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Save") }
    }) {
        OutlinedTextField(renameTitle, { renameTitle = it }, label = { Text("Notebook title") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (renameTitle.isNotBlank()) { model.rename(note, renameTitle); rename = false } }),
            shape = FolioShapes.large, modifier = Modifier.fillMaxWidth())
    }
    if (paperMenu) FolioPopover(if (nextPaperMenu) "Next page paper" else "Change paper", Icons.Rounded.GridOn, onDismiss = { paperMenu = false }, actions = {
        TextButton({ paperMenu = false }) { Text("Cancel") }
        TextButton({
            if (nextPaperMenu) nextPagePaper = chosenPaper else model.setPaper(chosenPaper)
            if (savePaperDefault) model.setDefaultPaper(chosenPaper)
            paperMenu = false
        }, shapes = ButtonDefaults.shapes()) { Text("Apply") }
    }) {
        Column {
            Text(if (nextPaperMenu) "Choose the style for the next blank page." else "Choose the style for this page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = FolioSpacing.dp8))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use as notebook default", style = MaterialTheme.typography.bodyMedium)
                    Text("For new blank pages", style = MaterialTheme.typography.bodySmall)
                }
                Switch(savePaperDefault, { savePaperDefault = it })
            }
            listOf(Paper.MATH_GRID, Paper.GRAPH, Paper.GRID, Paper.DOTS, Paper.PLAIN, Paper.RULED, Paper.SPLIT_RULED, Paper.MC_SHEET, Paper.TIAN_GRID, Paper.MI_GRID).forEach { p ->
                val selectedPaper = chosenPaper == p
                Surface(
                    onClick = { chosenPaper = p },
                    shape = FolioShapes.large,
                    color = if (selectedPaper) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp2)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selectedPaper, { chosenPaper = p })
                        Column(Modifier.padding(start = FolioSpacing.dp8).weight(1f)) {
                            Text(paperLabel(p), style = MaterialTheme.typography.bodyMedium)
                            val hint = when (p) {
                                Paper.SPLIT_RULED -> "Ruled lines with a centre divider"
                                Paper.MATH_GRID -> "Fine 20 px grid, bold every 5"
                                Paper.GRAPH -> "Same grid + centred axes"
                                Paper.MC_SHEET -> "Exam Section A answer sheet, 25 questions A–E"
                                Paper.TIAN_GRID -> "田字格 — one character per square, dashed cross"
                                Paper.MI_GRID -> "米字格 — cross plus diagonals per square"
                                else -> null
                            }
                            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selectedPaper) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (clear) FolioPopover("Clear this page?", Icons.Rounded.LayersClear, onDismiss = { clear = false }, actions = {
        TextButton({ clear = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        Button({ model.clearPage(); selectedImage = null; clear = false }, shapes = ButtonDefaults.shapes()) { Text("Clear page") }
    }) { Text("Your paper or PDF stays in place. Ink, text and pictures are removed. You can undo this change.", style = MaterialTheme.typography.bodyMedium) }
    if (timerPanel) TimingPanel(
        timer = state.timer,
        note = note,
        onOpenStudy = { studyPanel = true },
        onDismiss = { timerPanel = false },
        onStartTimer = model::startTimer,
        onStopTimer = { model.stopTimer() },
        onAdjustTimer = model::adjustTimer,
        onSkipTimer = model::skipTimerPhase,
        onPauseTimer = model::toggleTimerPause
    )
    if (studyPanel) FocalStudyPanel(note, state.timer, onDismiss = { studyPanel = false })
    if (examPanel) ExamDetailsPanel(
        note = note,
        onDismiss = { examPanel = false },
        onSave = { tags -> model.updateExamTags(note.id, tags); examPanel = false },
        onRecordMark = { attempt -> model.recordAttempt(note.id, attempt, note.longResponse?.attemptFor(page.id)?.id) },
        onDeleteAttempt = { attempt -> model.deleteAttempt(note.id, attempt.id) },
        suggestedSeconds = state.lastTimedSeconds
    )
    if (markDialog) ScoreDialog(
        total = note.exam.marksTotal,
        defaultSeconds = state.lastTimedSeconds,
        showDetection = note.pages.any { it.pdfIndex != null },
        loadPages = model::pagesForMarking,
        markAssist = markAssist,
        markScanBusy = markScanBusy,
        markScanStatus = markScanStatus,
        markZoneCount = markZones.size,
        markZoneTotal = MarkZones.total(markZones),
        onMarkAssist = { markAssist = it; appPrefs.edit().putBoolean(Marking.PREF_ASSIST, it).apply() },
        markColor = markingColor,
        onMarkColor = { markingColor = it; appPrefs.edit().putInt(Marking.PREF_COLOR, it).apply() },
        onDismiss = { markDialog = false },
        onRecord = { score, total, seconds, timed ->
            model.recordAttempt(note.id, ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed))
            markDialog = false
        }
    )
    restyleSelection?.let { originals ->
        RestyleSelectionPanel(
            originals = originals, quickColors = quick.colors(InkColors.INK_GROUP),
            onDismiss = { restyleSelection = null },
            onApply = { color, scale, opacity, style ->
                model.restyleSelection(originals, color, scale, opacity, style)
                restyleSelection = null; selection = null
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
                trailingIcon = { if (destinationQuery.isNotEmpty()) IconButton({ destinationQuery = "" }) { Icon(Icons.Rounded.Close, "Clear destination search") } })
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
    textEditor?.let { box ->
        TextBoxDialog(
            box = box, isNew = textEditorNew, colors = quick.colors(InkColors.INK_GROUP),
            onDismiss = { textEditor = null },
            onCreate = { created -> model.addText(created); rememberTextLook(created); textEditor = null },
            onUpdate = { updated -> model.updateText(updated); rememberTextLook(updated); textEditor = null },
            onDelete = { model.removeText(box.id); textEditor = null },
            onDuplicate = { source -> model.duplicateText(source.id, source); rememberTextLook(source); textEditor = null },
            canMove = note.pages.size > 1,
            onMove = { source ->
                model.updateText(source)
                moveSelection = page.id to CanvasSelection(texts = listOf(source))
                textEditor = null
            }
        )
    }
    pageMenu?.takeIf { !inkNavigating }?.let { (wx, wy, at) ->
        PageContextMenu(wx, wy,
            onPaste = { model.pasteClipboard(at) }, canPaste = canPaste,
            onSelectAll = ::selectAllInk,
            onText = { placeTextBox(at) },
            onImage = { imagePicker.launch(arrayOf("image/*")) },
            canUndo = state.canUndo, canRedo = state.canRedo, onUndo = model::undo, onRedo = model::redo,
            onDismiss = { pageMenu = null })
    }
    if (noteSearchOpen) FolioPanel(title = "Find in notes", onDismissRequest = { noteSearchOpen = false }) {
        val searchFocus = remember { FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        // Search runs off the main thread with a debounce so typing never janks composition.
        var debouncedQuery by remember { mutableStateOf(noteQuery) }
        LaunchedEffect(noteQuery) {
            kotlinx.coroutines.delay(200)
            debouncedQuery = noteQuery
        }
        var hits by remember { mutableStateOf<List<NotebookTextSearch.Hit>>(emptyList()) }
        val searchPages by produceState<List<NotePage>?>(null, note.id, note.pages, noteQuery.isNotBlank()) {
            value = null
            value = if (noteQuery.isBlank()) emptyList() else model.pagesForSearch(note.id)
        }
        var searchingText by remember { mutableStateOf(false) }
        LaunchedEffect(debouncedQuery, searchPages) {
            hits = emptyList()
            val snapshot = searchPages ?: return@LaunchedEffect
            if (debouncedQuery.isBlank()) return@LaunchedEffect
            searchingText = true
            try { hits = withContext(Dispatchers.Default) { NotebookTextSearch.search(snapshot, debouncedQuery) } }
            finally { searchingText = false }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(
                noteQuery, { noteQuery = it },
                Modifier.fillMaxWidth().focusRequester(searchFocus),
                label = { Text("Find typed text") },
                placeholder = { Text("e.g. quadratic formula") },
                singleLine = true,
                shape = FolioShapes.large,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { debouncedQuery = noteQuery; keyboard?.hide() }),
                trailingIcon = {
                    if (noteQuery.isNotEmpty()) IconButton({ noteQuery = "" }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Clear, "Clear search")
                    }
                }
            )
            if (note.pages.any { it.pdfIndex != null }) TextButton({ noteSearchOpen = false; pdfSearchOpen = true }) {
                Text("Search PDF text instead")
            }
            if (searchPages?.any { !it.loaded } == true) Text("Some pages could not be read. Results may be incomplete.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (noteQuery.isBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                    Icon(Icons.Rounded.FindInPage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Searches every typed text box in this notebook. Handwriting is not searched.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (searchPages == null || noteQuery != debouncedQuery || searchingText) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    LoadingIndicator(Modifier.size(24.dp).semanticsLabel("Searching notebook text"))
                    Text("Searching all pages…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (hits.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp12), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No typed text matches “${noteQuery.trim().take(80)}”.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("${hits.size} ${if (hits.size == 1) "page matches" else "pages match"} — most matches first.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    items(hits, key = { it.pageIndex }) { hit ->
                        Surface(onClick = { jumpTo(hit.pageIndex); noteSearchOpen = false }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Page ${hit.pageIndex + 1} · ${hit.matchCount}×", style = MaterialTheme.typography.titleSmall)
                                    if (hit.snippet.isNotBlank()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open page ${hit.pageIndex + 1}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (keyboardShortcuts) FolioPanel("Keyboard shortcuts", { keyboardShortcuts = false }) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            listOf("Ctrl + F" to "Find in the current document", "Ctrl + G" to "Go to a page", "Ctrl + 0" to "Reset zoom",
                "Ctrl + Z" to "Undo", "Ctrl + Shift + Z / Ctrl + Y" to "Redo", "Ctrl + Enter" to "Apply a text-box draft").forEach { (keys, action) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    Text(action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(keys, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (responseAttempts) LongResponseAttemptsPanel(note, page.id, model) { responseAttempts = false }
    if (pdfSearchOpen) ReferenceSearchPanel(
        search = state.pdfSearch, currentIndex = state.pageIndex,
        onQuery = model::searchPdf,
        onJump = { jumpTo(it); pdfSearchOpen = false },
        onDismiss = { pdfSearchOpen = false },
        onFindNotes = { pdfSearchOpen = false; noteSearchOpen = true }
    )
    if (pdfContentsOpen) FolioPanel(title = "Contents", onDismissRequest = { pdfContentsOpen = false }) {
        val outline = pdfOutline
        if (outline == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                LoadingIndicator(Modifier.size(24.dp).semanticsLabel("Loading contents"))
                Text("Reading bookmarks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (outline.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This PDF has no bookmarks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = FolioSpacing.dp16), contentPadding = PaddingValues(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                itemsIndexed(outline) { _, entry ->
                    Surface(onClick = { jumpTo(entry.pageIndex); pdfContentsOpen = false }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                        Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16 + FolioSpacing.dp16 * entry.depth.coerceIn(0, 3), end = FolioSpacing.dp16, top = FolioSpacing.dp10, bottom = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Page ${entry.pageIndex + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open ${entry.title}")
                        }
                    }
                }
            }
        }
    }
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
                IconButton(onClick, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Timer, "Timer & stopwatch") }
            }
        } else {
            val done = timer.phase == ExamTimerPhase.DONE
            Surface(
                onClick = hold.click(onClick),
                shape = FolioShapes.extraLarge,
                color = if (done) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (done) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.height(36.dp)
                    .then(if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier)
            ) {
                Row(Modifier.padding(horizontal = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Icon(
                        if (done) Icons.Rounded.Flag
                        else if (timer.paused) Icons.Rounded.Pause
                        else Icons.Rounded.Timer,
                        null, Modifier.size(15.dp)
                    )
                    Text(
                        if (timer.paused) "${if (timer.autoParked) "Stopped" else "Paused"} · ${timer.clockText()}"
                        else if (timer.phase == ExamTimerPhase.WRITING) timer.clockText()
                        else if (timer.phase == ExamTimerPhase.READING) "R · ${timer.clockText()}"
                        else "Pens down",
                        style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }
}

/** One visibility rule for active-page actions (including sharing) and the scroll label. */
private fun visibleCurrentPage(pages: LazyListState, pageCount: Int): Int? {
    val info = pages.layoutInfo
    return DocumentViewport.currentPage(
        info.visibleItemsInfo.map { DocumentViewport.VisiblePage(it.index, it.offset, it.size) },
        info.viewportStartOffset, info.viewportEndOffset, pageCount
    )
}

private const val FastScrollChipHoldMs = 900L

private data class FastScrollGeometry(val top: Float, val height: Float)

/** Shared geometry keeps the touch target aligned with the visible thumb. */
private fun fastScrollGeometry(pages: LazyListState, pageCount: Int, height: Float, minimumThumb: Float): FastScrollGeometry? {
    val info = pages.layoutInfo
    if (pageCount <= 0 || height <= 0f || (!pages.canScrollBackward && !pages.canScrollForward)) return null
    // The LazyColumn holds one extra trailing item (the Add page button), so clamp to real
    // pages and count only pages: otherwise progress and thumb size drift off by one.
    val firstPageIndex = pages.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
    val firstPageSize = info.visibleItemsInfo.firstOrNull { it.index < pageCount }?.size
        ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0
    val progress = if (!pages.canScrollForward) 1f else DocumentViewport.scrollProgress(firstPageIndex, pages.firstVisibleItemScrollOffset, firstPageSize, pageCount)
    val visiblePages = info.visibleItemsInfo.count { it.index < pageCount }.coerceAtLeast(1)
    val share = DocumentViewport.thumbFraction(visiblePages, pageCount)
    val thumb = (height * share).coerceAtLeast(minimumThumb).coerceAtMost(height)
    return FastScrollGeometry((height - thumb) * progress, thumb)
}

/** The page count follows the thumb without making the label a touch target. */
@Composable private fun FastScrollTrack(pages: LazyListState, pageCount: Int, scrubbing: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // The chip is a transient affordance: it fades in for any scroll or thumb drag and
    // fades out again once the document settles, so it never clutters a still page.
    var chipActive by remember { mutableStateOf(false) }
    LaunchedEffect(pages, scrubbing) {
        snapshotFlow { pages.isScrollInProgress || scrubbing }.collectLatest { active ->
            if (active) chipActive = true else {
                delay(FastScrollChipHoldMs)
                chipActive = false
            }
        }
    }
    val chipAlpha by animateFloatAsState(if (chipActive) 1f else 0f,
        animationSpec = tween(if (chipActive) 140 else 220), label = "fastScrollChipAlpha")
    // The thumb brightens and thickens smoothly when grabbed instead of snapping.
    val thumbAlpha by animateFloatAsState(if (scrubbing) 1f else .55f, label = "fastScrollAlpha")
    val thumbWidth by animateDpAsState(if (scrubbing) 7.dp else 5.dp, animationSpec = folioSpring(), label = "fastScrollWidth")
    BoxWithConstraints(modifier) {
        val geometry = fastScrollGeometry(pages, pageCount, constraints.maxHeight.toFloat(), with(density) { 28.dp.toPx() })
            ?: return@BoxWithConstraints
        Box(Modifier.align(Alignment.CenterEnd).width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().width(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f)))
            Box(Modifier.offset { IntOffset(0, geometry.top.roundToInt()) }.width(thumbWidth).height(with(density) { geometry.height.toDp() }).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha)))
        }
        if (chipAlpha < 0.01f) return@BoxWithConstraints
        val labelHeightPx = with(density) { 32.dp.toPx() }
        val labelTop = (geometry.top + geometry.height / 2 - labelHeightPx / 2)
            .coerceIn(0f, (constraints.maxHeight - labelHeightPx).coerceAtLeast(0f))
        Surface(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, labelTop.roundToInt()) }.padding(end = FolioSpacing.dp32)
            .graphicsLayer {
                alpha = chipAlpha
                translationX = (1f - chipAlpha) * 8.dp.toPx()
                scaleX = .94f + .06f * chipAlpha
                scaleY = scaleX
            },
            shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = if (scrubbing) 6.dp else 2.dp, tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
            val current = visibleCurrentPage(pages, pageCount) ?: pages.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
            Text("${current + 1} / $pageCount",
                Modifier.padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp8), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        }
    }
}

@Composable internal fun EditorPage(noteId: String, page: NotePage, model: FolioViewModel, tool: Tool, options: ToolOptions, finger: Boolean, snapEnabled: Boolean, shapeRecognition: Boolean, active: Boolean, onActive: () -> Unit, onPan: (Float, Float) -> Unit, onPanEnd: (Float) -> Unit, onSelection: (CanvasSelection) -> Unit, onTextEdit: (TextBox) -> Unit, onTextCreate: (InkPoint) -> Unit, onLoad: () -> Unit, fullscreen: Boolean = false, pageCamera: Boolean = false, onPageKey: ((android.view.KeyEvent) -> Boolean)? = null, canvasReset: Int = 0, onCanvasZoom: (Float) -> Unit = {}, onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {}, selectedImageId: String? = null, onImageSelected: (PageImage?) -> Unit = {}, pdfLinks: List<PdfLink> = emptyList(), onPdfLink: (PdfLink) -> Unit = {}, eraserPressureEnabled: Boolean = true, scribbleToErase: Boolean = true, scribbleSensitivity: Float = ScribbleSensitivity.DEFAULT, eraserWholeStroke: Boolean = false, shapeMeasurements: Boolean = true, multiTouchUndo: Boolean = true, graphStyle: GraphStyle = GraphStyle.DEFAULT, palmRejectMs: Long = AppPrefs.DEFAULT_PALM_MS, panMultiplier: Float = 1f, onEraserFinished: (() -> Unit)? = null, onUndo: (() -> Unit)? = null, onRedo: (() -> Unit)? = null, onSelectAllView: ((InkView) -> Unit)? = null, inkStyle: StrokeStyle = StrokeStyle.SOLID, readOnly: Boolean = false, initialViewport: WorkspaceViewport? = null, onCameraChanged: (WorkspaceViewport) -> Unit = {}, activeLayer: Int = 0, followEnabled: Boolean = false,
    writingHand: WritingHand = WritingHand.RIGHT, followZoom: Float = 1f,
    autoDetectAnswerAreas: Boolean = false, showAnswerAreas: Boolean = true,
    onFollowPan: (Float, Float) -> Pair<Float, Float> = { _, _ -> 0f to 0f }, inputBlocked: Boolean = false, peekRegion: PeekAnchor? = null,
    /** Selection frame in view fractions (0..1); null while the selection is manipulated. */
    selectionAnchor: Rect? = null,
    /** The paper's width in a document; the page view is wider by the workspace beside it. Null fits the paper to the view. */
    paperWidth: Dp? = null, onStickyDraw: () -> Unit = {},
    /** Context menu content, given the available pane width; null on pages without a selection. */
    selectionMenu: (@Composable (Dp) -> Unit)? = null,
    selectionMenuViewport: Rect? = null,
    onSelectionAnchor: (Rect?) -> Unit = {},
    markZones: List<MarkZone> = emptyList(), markAssist: Boolean = false, markColor: Int = Marking.DEFAULT_COLOR,
    onReplaceZone: (old: MarkZone?, new: MarkZone?) -> Unit = { _, _ -> },
    onPageFrame: (String, Rect?) -> Unit = { _, _ -> },
    onCropMode: (Boolean) -> Unit = {}, onNavigating: (Boolean) -> Unit = {}, onLongPress: (Float, Float, InkPoint) -> Unit = { _, _, _ -> },
    onSelectionDrop: (String, CanvasSelection, Float, Float) -> Boolean = { _, _, _, _ -> false }) {
    DisposableEffect(page.id) { onDispose { onPageFrame(page.id, null) } }
    // The printed allocation being offered a tick/cross, with its rectangle in this page's view pixels.
    var offeredZone by remember(page.id) { mutableStateOf<Pair<MarkZone, android.graphics.RectF>?>(null) }
    var offerStamp by remember(page.id) { mutableLongStateOf(0L) }
    // A rectangle just drawn (or an existing hand-drawn one being edited) waiting for its marks to be entered.
    var areaEntry by remember(page.id) { mutableStateOf<AreaEntry?>(null) }
    val pageZones = remember(markZones, page.pdfIndex, markAssist) {
        val target = page.pdfIndex
        if (target == null) emptyList() else markZones.filter { it.pageIndex == target && (markAssist || it.manual) }
    }
    var sticky by remember(page.id) { mutableStateOf<StickyFocus?>(null) }
    var stickyDraft by remember(page.id) { mutableStateOf<StickyDraft?>(null) }
    var pageWindowFrame by remember(page.id) { mutableStateOf<Rect?>(null) }
    val canvasBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val areaColor = MaterialTheme.colorScheme.primary.toArgb()
    val shapeMeasurement = remember(page.id) { mutableStateOf<ShapeMeasurement?>(null) }
    var background by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    var writingGuides by remember(page.id) { mutableStateOf<List<WritingGuide>>(emptyList()) }
    var boundInkView by remember(page.id) { mutableStateOf<InkView?>(null) }
    // Only the current page participates: lazy-list prefetch must not select areas on neighbours.
    // Wait for asynchronous guide detection and the configured native view. No rules means no
    // automatic selection (in particular, never enter the manual drag tool on blank pages).
    LaunchedEffect(page.id, active, followEnabled, autoDetectAnswerAreas, writingGuides, boundInkView) {
        if (active && followEnabled && autoDetectAnswerAreas && writingGuides.isNotEmpty()) {
            boundInkView?.suggestWritingRegion()
        }
    }
    var ready by remember(page.id) { mutableStateOf(page.pdfIndex == null) }
    var error by remember(page.id) { mutableStateOf(false) }
    var retry by remember(page.id) { mutableIntStateOf(0) }
    var pictures by remember(page.id) { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    // A page whose ink is still on disk is fetched as soon as it is about to be shown.
    LaunchedEffect(page.id, page.loaded) { if (!page.loaded) onLoad() }
    // PDF backgrounds belong to the repository's bounded cache. Let bitmap references expire
    // naturally: a native View or retained display list may still use them after composition ends.
    LaunchedEffect(noteId, page.id, retry) {
        if (page.pdfIndex != null) {
            ready = false; error = false
            try {
                val rendered = model.repository.cachedPdfBackground(noteId, page)
                background = rendered
                ready = true
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
        }
    }
    // Detect only the immutable paper/PDF background, never the user's ink. Pixel scanning
    // runs off the input thread and reruns only when the source or follow setting changes.
    LaunchedEffect(page.id, page.paper, page.width, page.height, background, active, followEnabled) {
        writingGuides = if (!active || !followEnabled || page.infinite) emptyList() else withContext(Dispatchers.Default) {
            val bitmap = background
            when {
                page.pdfIndex != null && bitmap != null ->
                    // One row at a time: no copy of the whole page's pixels.
                    WritingGuides.analyze(bitmap.width, bitmap.height, page.width, page.height) { y, row ->
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    }.guides
                page.pdfIndex == null && page.paper == Paper.RULED -> WritingGuides.ruled(page.width, page.height)
                page.pdfIndex == null && page.paper == Paper.SPLIT_RULED -> WritingGuides.ruled(page.width, page.height, split = true)
                page.pdfIndex == null && page.paper.isHanzi -> WritingGuides.hanzi(page.width, page.height, Paper.HANZI_CELL)
                else -> emptyList()
            }
        }
    }
    // Pictures arrive with the page content; a missing file simply leaves no bitmap to draw.
    // Keyed by image ids only: ink edits bump the page revision but never change picture bytes,
    // so redrawing a stroke must not re-decode every photo on the page.
    // Keyed by image count + content hash instead of a fresh id list allocated per composition.
    val imageKey = remember(page.id, page.loaded, page.images.size) {
        page.images.fold(0) { acc, img -> 31 * acc + img.id.hashCode() }
    }
    val imageFiles by model.imageFiles.collectAsState()
    LaunchedEffect(noteId, page.id, page.loaded, imageKey, imageFiles) {
        if (!page.loaded) return@LaunchedEffect
        if (page.images.isEmpty()) {
            pictures = emptyMap()
            return@LaunchedEffect
        }
        val decoded = model.repository.loadImages(noteId, page)
        pictures = decoded
    }
    // Filtered links memoized: per-page recomposition must not re-allocate the list.
    val pageLinks = remember(pdfLinks, page.pdfIndex) {
        val target = page.pdfIndex ?: return@remember emptyList<PdfLink>()
        pdfLinks.filter { it.pageIndex == target }
    }
    /** Puts typed words into the note; the field closes whichever way typing ends. */
    fun commitStickyDraft() {
        val draft = stickyDraft ?: return
        stickyDraft = null
        boundInkView?.setStickyText(draft.id, draft.value.text)
    }
    // A document page spans the workspace beside its paper, and grows above or below the paper
    // only as far as a note already hangs past it.
    val pageDensity = LocalDensity.current
    val documentPage = paperWidth != null && !readOnly && !fullscreen && !page.infinite
    val notes = if (documentPage) page.texts.filter { it.isSticky } else emptyList()
    val workspaceTop = minOf(0f, notes.minOfOrNull { it.y } ?: 0f)
    val workspaceBottom = maxOf(page.height, notes.maxOfOrNull { it.y + it.stickyHeight } ?: 0f)
    val paperWidthPx = if (documentPage) with(pageDensity) { paperWidth!!.toPx() } else 0f
    val pageModifier = when {
        fullscreen -> Modifier.fillMaxSize()
        documentPage -> Modifier.fillMaxWidth().height(paperWidth!! * ((workspaceBottom - workspaceTop) / page.width))
        else -> Modifier.fillMaxWidth().aspectRatio(page.width / page.height)
    }
    // Keep the full page frame in window coordinates, including scrolled-off portions.
    // The context menu converts the local selection against this frame and clamps to the pane;
    // selection drops between pages land against the paper alone.
    Box(pageModifier.onGloballyPositioned { coordinates ->
            val origin = coordinates.localToWindow(Offset.Zero)
            pageWindowFrame = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            if (documentPage) {
                val left = origin.x + (coordinates.size.width - paperWidthPx) / 2
                val top = origin.y - workspaceTop * paperWidthPx / page.width
                onPageFrame(page.id, Rect(left, top, left + paperWidthPx, top + page.height * paperWidthPx / page.width))
            } else onPageFrame(page.id, pageWindowFrame)
        }) {
        Surface(
            Modifier.fillMaxSize(),
            shape = if (fullscreen && !pageCamera) RectangleShape else FolioShapes.medium,
            color = canvasBackground,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = null
        ) {
            // Placeholders cover the paper only, not the workspace beside it.
            val placeholderColor = MaterialTheme.colorScheme.surfaceContainerLowest
            val placeholderPaper = if (!documentPage) Modifier.background(placeholderColor) else Modifier.drawBehind {
                val unit = paperWidthPx / page.width
                drawRect(placeholderColor, Offset((size.width - paperWidthPx) / 2, -workspaceTop * unit),
                    androidx.compose.ui.geometry.Size(paperWidthPx, page.height * unit))
            }
            // Nothing is drawn on a page until its own ink has arrived, so a stroke can never land on top
            // of a blank stand-in and replace the content that is still on disk.
            if (!page.loaded) Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().then(placeholderPaper)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    LoadingIndicator(Modifier.semanticsLabel("Loading page"))
                    Text("Loading page…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else if (ready) AndroidView(factory = { context -> InkView(context).also { boundInkView = it } }, modifier = Modifier.fillMaxSize(), update = { view ->
                view.documentPaperWidth = paperWidthPx
                view.documentTop = workspaceTop
                view.onStickyFocus = { box, frame, typing ->
                    val draft = stickyDraft
                    if (draft != null && (!typing || box?.id != draft.id)) commitStickyDraft()
                    if (typing && box != null && stickyDraft == null)
                        stickyDraft = StickyDraft(box.id, TextFieldValue(box.text, TextRange(box.text.length)))
                    sticky = if (box != null && frame != null) StickyFocus(box, frame, typing) else null
                }
                view.canvasBackgroundColor = canvasBackground.toArgb()
                if (readOnly) view.contentDescription = "Reference page. Use the hand or two fingers to pan and zoom. Read only."
                view.onShapeMeasurement = { shapeMeasurement.value = it }
                view.onCanvasViewport = onCanvasViewport; view.onCanvasZoom = onCanvasZoom; if (view.page !== page || view.background !== background || view.imageBitmaps !== pictures) view.bind(page, background, pictures); view.resetCanvas(canvasReset); view.restoreWorkspaceCamera(initialViewport); view.onWorkspaceCamera = onCameraChanged; view.readOnly = readOnly; view.pageCamera = pageCamera; view.onPageKey = onPageKey; view.tool = tool; view.inkColor = options.color
                view.answerAreaColor = areaColor; view.showAnswerAreas = showAnswerAreas; view.writingGuides = writingGuides; view.followEnabled = followEnabled; view.writingHand = writingHand; view.documentFollowZoom = followZoom
                view.onFollowPan = onFollowPan; view.inputBlocked = inputBlocked
                view.peekRegion = peekRegion
                view.activeLayer = activeLayer
                view.onLayerBlocked = { android.widget.Toast.makeText(view.context, "That layer is hidden or locked. Pick another layer to draw.", android.widget.Toast.LENGTH_SHORT).show() }
                view.inkWidth = options.width; view.inkOpacity = options.opacity; view.inkStyle = inkStyle; view.pressureEnabled = options.pressure; view.fingerDrawing = finger
                view.pressureSensitivity = options.pressureSensitivity; view.pressureVariation = options.pressureVariation
                view.eraserPressureEnabled = eraserPressureEnabled; view.scribbleToErase = scribbleToErase; view.scribbleSensitivity = scribbleSensitivity; view.eraserWholeStroke = eraserWholeStroke; view.shapeMeasurements = shapeMeasurements; view.multiTouchUndo = multiTouchUndo; view.palmRejectMs = palmRejectMs; view.panMultiplier = panMultiplier; view.onEraserFinished = onEraserFinished
                view.onUndoRequest = onUndo; view.onRedoRequest = onRedo
                onSelectAllView?.invoke(view)
                view.snapEnabled = snapEnabled; view.graphStyle = graphStyle
                view.shapeRecognition = shapeRecognition
                view.onActive = onActive; view.onDocumentPan = onPan; view.onDocumentPanEnd = onPanEnd
                view.onStrokesChanged = { if (!readOnly) model.strokes(page.id, it) }
                view.onStrokeAppended = { before, stroke, after ->
                    if (!readOnly) model.appendStroke(page.id, before, stroke, after)
                }
                view.onPenInput = { beginsStroke -> if (!readOnly) model.onPenActivity(beginsStroke) }
                view.onSelectionChanged = onSelection
                view.onSelectionViewBounds = onSelectionAnchor
                view.onContentChanged = { strokes, texts, images -> if (!readOnly) model.updateContent(page.id, strokes, texts, images) }
                view.onTextEdit = onTextEdit; view.onTextCreate = onTextCreate
                view.onTextsChanged = { if (!readOnly) model.texts(page.id, it) }
                view.selectedImageId = selectedImageId?.takeIf { id -> page.images.any { it.id == id } }
                view.onImagesChanged = { if (!readOnly) model.images(page.id, it) }
                view.onImageSelected = onImageSelected
                view.onCropMode = onCropMode; view.onNavigating = onNavigating; view.onLongPress = onLongPress
                view.pdfLinks = pageLinks
                view.onPdfLink = onPdfLink
                view.markZones = pageZones
                view.onSelectionDrop = { picked, x, y -> !readOnly && onSelectionDrop(page.id, picked, x, y) }
                view.onMarkRegion = { lane, rect ->
                    areaEntry = if (lane == null || rect == null) null
                    else AreaEntry(null, lane.left, lane.top, lane.right - lane.left, lane.bottom - lane.top, rect)
                }
                view.onMarkZone = { zone, rect ->
                    offeredZone = if (zone != null && rect != null) zone to rect else null
                    offerStamp++
                }
                if (!active) view.clearSelection()
            }) else Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().then(placeholderPaper)) {
                if (error) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), modifier = Modifier.padding(FolioSpacing.dp24)) {
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Couldn't open this PDF page", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Check the file still exists, then try again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ retry++ }, shapes = ButtonDefaults.shapes()) { Text("Try again") }
                } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    LoadingIndicator(Modifier.semanticsLabel("Loading page"))
                    Text("Rendering PDF…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (page.loaded && ready && !readOnly && shapeMeasurements) ShapeMeasurementTooltip(shapeMeasurement)
        offeredZone?.takeIf { page.loaded && !readOnly }?.let { (zone, rect) ->
            MarkChip(zone, rect, page, offerStamp,
                onAward = { value -> model.awardMark(page.id, zone, value, markColor); offeredZone = null },
                onClear = { model.clearMark(page.id, zone); offeredZone = null },
                onDismiss = { offeredZone = null },
                onEdit = if (zone.manual) ({
                    areaEntry = AreaEntry(zone, zone.x, zone.y, zone.width, zone.height, rect); offeredZone = null
                }) else null)
        }
        areaEntry?.takeIf { page.loaded && !readOnly }?.let { entry ->
            // A fresh box is read in the background so the stepper opens on the number printed inside it.
            var guess by remember(entry) { mutableStateOf<Int?>(null) }
            LaunchedEffect(entry) {
                if (entry.existing == null) guess = try {
                    model.repository.readMarkRegion(noteId, page, WritingLane(entry.x, entry.y, entry.x + entry.width, entry.y + entry.height))
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            MarkAreaPopover(entry.rect, entry.existing?.marks, guess,
                onConfirm = { marks ->
                    val zone = MarkZone(page.pdfIndex ?: 0, entry.x, entry.y, entry.width, entry.height, marks, manual = true)
                    onReplaceZone(entry.existing, zone)
                    boundInkView?.clearMarkRegion()
                    areaEntry = null
                    // Straight on to awarding it, as if the scan had found it.
                    offeredZone = zone to entry.rect; offerStamp++
                },
                onRemove = entry.existing?.let { old -> { model.clearMark(page.id, old); onReplaceZone(old, null); areaEntry = null } },
                onDismiss = { boundInkView?.clearMarkRegion(); areaEntry = null })
        }
        val focusedSticky = sticky?.takeIf { active && !readOnly && !inputBlocked && page.loaded && ready }
        val draft = stickyDraft
        val frame = pageWindowFrame
        if (focusedSticky != null && draft != null && draft.id == focusedSticky.box.id && frame != null) {
            StickyNoteField(focusedSticky.box, focusedSticky.frame, frame.width, frame.height, draft.value,
                onValue = { stickyDraft = draft.copy(value = it) }, onDone = { boundInkView?.finishStickyTyping() })
        }
        if (focusedSticky != null) {
            SelectionContextPopup(focusedSticky.frame, pageWindowFrame, selectionMenuViewport, tightGap = true) {
                StickyNoteContextMenu(focusedSticky.typing,
                    onType = { boundInkView?.typeInSticky() }, onDone = { boundInkView?.finishStickyTyping() },
                    onDraw = onStickyDraw,
                    onDelete = { stickyDraft = null; boundInkView?.deleteSticky(focusedSticky.box.id) })
            }
        }
        if (selectionMenu != null && page.loaded && ready) {
            SelectionContextPopup(selectionAnchor, pageWindowFrame, selectionMenuViewport, tightGap = selectedImageId != null, content = selectionMenu)
        }
    }
}


/**
 * The pages of a score on the music stand: a window of one or two sheets, each fitted to the room
 * the chrome leaves and each an ordinary [EditorPage]. Tapping the left or right half turns the page
 * where a tap would not otherwise draw; the tap is only watched, never consumed.
 */
@Composable private fun MusicSheets(note: Notebook, stage: MusicStage, insets: MusicStageInsets, zoomed: Boolean, onFit: () -> Unit, tapTurns: Boolean,
    sheet: @Composable (NotePage, Int) -> Unit) {
    val end = minOf(stage.start + stage.step, note.pages.size)
    val turn by rememberUpdatedState(stage.onTurn)
    Row(Modifier.fillMaxSize().padding(start = insets.side, end = insets.side, top = insets.top, bottom = insets.bottom)
        .pointerInput(tapTurns, zoomed) {
            if (!tapTurns && !zoomed) return@pointerInput
            var lastTapAt = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Touch) return@awaitEachGesture
                var crowded = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) crowded = true
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.uptimeMillis - down.uptimeMillis > 300L || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    if (!change.pressed) {
                        if (crowded) break
                        // Zoomed in, a tap never turns the page; a quick second tap fits the sheet again.
                        if (zoomed && !stage.performance) {
                            if (change.uptimeMillis - lastTapAt < 320L) { lastTapAt = 0L; onFit() } else lastTapAt = change.uptimeMillis
                        } else turn(down.position.x >= size.width / 2f)
                        break
                    }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(insets.gap)) {
        for (index in stage.start until end) key(note.pages[index].id) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(FolioShapes.medium)) { sheet(note.pages[index], index) }
        }
    }
}

/** The editor's page menu. Shared by the floating and stacked chrome so both stay in step. */
@Composable private fun PageOptionsContent(
    dismiss: () -> Unit, page: NotePage, saveFailed: Boolean,
    onPaper: () -> Unit, onClear: () -> Unit, onRetry: () -> Unit,
    onRedo: () -> Unit, onExam: () -> Unit, onRecordMark: () -> Unit, onTimer: () -> Unit, onInsertImage: () -> Unit, onSearchPdf: () -> Unit,
    onContents: () -> Unit, onSearchNotes: () -> Unit,
    onOrganize: () -> Unit, onBookmark: () -> Unit, onNamePage: () -> Unit
) {
    val run: (() -> Unit) -> Unit = { dismiss(); it() }
    PopoverGroup("This page") {
        PopoverRow(Icons.Rounded.AutoStories, "Organise pages") { run(onOrganize) }
        PopoverRow(Icons.Rounded.Edit, "Name page") { run(onNamePage) }
        PopoverRow(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            if (page.bookmarked) "Remove bookmark" else "Bookmark page") { run(onBookmark) }
        PopoverRow(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag,
            if (page.redoFlag) "Remove redo flag" else "Flag this page to redo") { run(onRedo) }
        PopoverRow(Icons.Rounded.GridOn, "Paper style: ${paperLabel(page.paper)}", enabled = page.pdfIndex == null) { run(onPaper) }
        PopoverRow(Icons.Rounded.LayersClear, "Clear page",
            enabled = page.strokes.isNotEmpty() || page.texts.isNotEmpty() || page.images.isNotEmpty()) { run(onClear) }
    }
    PopoverGroup("Study") {
        PopoverRow(Icons.AutoMirrored.Rounded.FactCheck, "Exam details") { run(onExam) }
        PopoverRow(Icons.AutoMirrored.Rounded.Grading, "Record a mark") { run(onRecordMark) }
        PopoverRow(Icons.Rounded.Timer, "Timer & stopwatch") { run(onTimer) }
    }
    PopoverGroup("Insert & find") {
        PopoverRow(Icons.Rounded.AddPhotoAlternate, "Insert picture") { run(onInsertImage) }
        PopoverRow(Icons.Rounded.FindInPage, "Find in notes") { run(onSearchNotes) }
        PopoverRow(Icons.Rounded.Search, "Search PDF text", enabled = page.pdfIndex != null) { run(onSearchPdf) }
        PopoverRow(Icons.AutoMirrored.Rounded.FormatListBulleted, "Contents", enabled = page.pdfIndex != null) { run(onContents) }
    }
    if (saveFailed) PopoverRow(Icons.Rounded.Save, "Retry save") { run(onRetry) }
}


/**
 * The page commands shared by the page drawer and the page browser row, so one list of moves
 * governs a page wherever it is shown. Callers close their own menu via [onDismiss].
 */
@Composable private fun PageRowMenu(
    page: NotePage, expanded: Boolean, onDismiss: () -> Unit,
    canMoveUp: Boolean, canMoveDown: Boolean, canDelete: Boolean,
    onName: () -> Unit, onBookmark: () -> Unit, onRedoFlag: () -> Unit, onMoveTo: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit, onInsert: () -> Unit, onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    if (!expanded) return
    val run: (() -> Unit) -> Unit = { onDismiss(); it() }
    FolioPopover(onDismiss, width = 280.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            PopoverTile(Icons.Rounded.Edit, "Rename", Modifier.weight(1f)) { run(onName) }
            PopoverTile(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (page.bookmarked) "Bookmarked" else "Bookmark", Modifier.weight(1f), active = page.bookmarked) { run(onBookmark) }
            PopoverTile(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag,
                if (page.redoFlag) "Clear redo" else "Redo", Modifier.weight(1f), active = page.redoFlag) { run(onRedoFlag) }
        }
        Column {
            PopoverRow(Icons.Rounded.LowPriority, "Move to position…") { run(onMoveTo) }
            PopoverRow(Icons.Rounded.KeyboardArrowUp, "Move up", enabled = canMoveUp) { run(onMoveUp) }
            PopoverRow(Icons.Rounded.KeyboardArrowDown, "Move down", enabled = canMoveDown) { run(onMoveDown) }
            PopoverRow(Icons.Rounded.Add, "Insert blank page after") { run(onInsert) }
            PopoverRow(Icons.Rounded.ContentCopy, "Duplicate page") { run(onDuplicate) }
        }
        HorizontalDivider()
        PopoverRow(Icons.Rounded.DeleteOutline, "Delete page", enabled = canDelete, destructive = true) { run(onDelete) }
    }
}

/** One row of the page browser: a rendered thumbnail, its details, and reorder/duplicate/delete. */
@Composable private fun PageRow(
    page: NotePage, index: Int, current: Boolean, dragging: Boolean, modifier: Modifier = Modifier,
    onOpen: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onDuplicate: () -> Unit, onInsert: () -> Unit, onDelete: () -> Unit,
    onName: () -> Unit, onBookmark: () -> Unit, onRedoFlag: () -> Unit, onMoveTo: () -> Unit,
    canMoveUp: Boolean, canMoveDown: Boolean, canDelete: Boolean,
    noteId: String, thumbnails: PageThumbnailCache
) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = FolioShapes.extraLarge, modifier = modifier.fillMaxWidth(),
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (dragging) 6.dp else 1.dp, tonalElevation = if (current) 1.dp else 0.dp,
        border = BorderStroke(
            if (current) 1.5.dp else 1.dp,
            if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )) {
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            PageThumbnail(noteId, page, thumbnails, Modifier.width(64.dp).aspectRatio(page.width / page.height).clip(FolioShapes.small))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Text(page.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (current) Surface(shape = FolioShapes.small, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text("Open", Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp2), style = MaterialTheme.typography.labelSmall)
                    }
                }
                // A page that has not been read yet cannot say how much ink it holds.
                val detail = "Page ${index + 1} · " + (if (page.pdfIndex != null) "Imported PDF" else paperLabel(page.paper))
                val content = if (page.loaded) {
                    val bits = mutableListOf<String>()
                    if (page.strokes.isNotEmpty()) bits += "${page.strokes.size} marks"
                    if (page.texts.isNotEmpty()) bits += "${page.texts.size} text"
                    if (page.images.isNotEmpty()) bits += "${page.images.size} pics"
                    if (bits.isEmpty()) "$detail · Blank" else "$detail · " + bits.joinToString(" · ")
                } else detail
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onRedoFlag, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag,
                    if (page.redoFlag) "Remove practice flag" else "Flag page for practice",
                    tint = if (page.redoFlag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onBookmark, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (page.bookmarked) "Remove bookmark" else "Bookmark page",
                tint = if (page.bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box {
                IconButton({ menu = true }, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Page ${index + 1} options") }
                PageRowMenu(page, menu, { menu = false },
                    canMoveUp = canMoveUp, canMoveDown = canMoveDown, canDelete = canDelete,
                    onName = onName, onBookmark = onBookmark,
                    onRedoFlag = onRedoFlag, onMoveTo = onMoveTo, onMoveUp = onMoveUp, onMoveDown = onMoveDown,
                    onInsert = onInsert, onDuplicate = onDuplicate, onDelete = onDelete)
            }
        }
    }
}

/**
 * A page preview from the shared cache. The key carries the page's revision, so editing a page
 * simply fetches a new preview, and a preview that was drawn before is shown without reading the
 * page file at all.
 */
@Composable private fun PageThumbnail(noteId: String, page: NotePage, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier, previewWidth: Dp = 64.dp) {
    val widthPx = with(LocalDensity.current) { previewWidth.roundToPx() }
    var preview by remember(noteId, page.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(noteId, page.id, page.revision, page.loaded, widthPx) {
        preview = thumbnails.thumbnail(noteId, page, widthPx)
    }
    Surface(modifier, shape = FolioShapes.small, color = Color.White, shadowElevation = 1.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = preview
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (!page.loaded) LoadingIndicator(Modifier.size(20.dp).semanticsLabel("Loading preview"))
        }
    }
}

/**
 * Changes the look of a lasso selection. Each control starts from the selection's own style and
 * only touches what the user actually changes, so recolouring never silently re-thickens the ink.
 */
@Composable private fun RestyleSelectionPanel(
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

/** Creates or edits a typed text box: wording, size, wrap width, opacity, style and colour. */
@Composable private fun TextBoxDialog(
    box: TextBox, isNew: Boolean, colors: List<Int>,
    onDismiss: () -> Unit, onCreate: (TextBox) -> Unit, onUpdate: (TextBox) -> Unit, onDelete: () -> Unit,
    onDuplicate: (TextBox) -> Unit, onMove: (TextBox) -> Unit, canMove: Boolean
) {
    val textFocus = remember(box.id) { FocusRequester() }
    var text by rememberSaveable(box.id) { mutableStateOf(box.text) }
    var size by rememberSaveable(box.id) { mutableFloatStateOf(box.size) }
    var width by rememberSaveable(box.id) { mutableFloatStateOf(box.width) }
    var opacity by rememberSaveable(box.id) { mutableFloatStateOf(box.opacity.coerceIn(TextBox.MIN_OPACITY, TextBox.MAX_OPACITY)) }
    var color by rememberSaveable(box.id) { mutableIntStateOf(box.color) }
    var bold by rememberSaveable(box.id) { mutableStateOf(box.bold) }
    var italic by rememberSaveable(box.id) { mutableStateOf(box.italic) }
    var align by rememberSaveable(box.id) { mutableStateOf(box.align) }
    var formattingExpanded by rememberSaveable(box.id) { mutableStateOf(false) }
    var underline by rememberSaveable(box.id) { mutableStateOf(box.underline) }
    fun edited() = box.copy(
        text = text.trimEnd(), size = size.coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE),
        width = width.coerceIn(TextBox.MIN_WIDTH, TextBox.MAX_WIDTH),
        opacity = opacity.coerceIn(TextBox.MIN_OPACITY, TextBox.MAX_OPACITY),
        color = color, bold = bold, italic = italic, align = align, underline = underline
    )
    var discard by rememberSaveable(box.id) { mutableStateOf(false) }
    fun dismiss() { if (edited() != box) discard = true else onDismiss() }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = ::dismiss,
        icon = { Icon(Icons.Rounded.TextFields, null) },
        title = { Text(if (isNew) "Add text" else "Edit text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp).focusRequester(textFocus).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed && text.isNotBlank()) {
                            val done = edited(); if (isNew) onCreate(done) else onUpdate(done); true
                        } else false
                    },
                    label = { Text("Text") }, placeholder = { Text("Write a heading, a label or a note…") },
                    shape = FolioShapes.large,
                    supportingText = {
                        Text(
                            if (text.isBlank()) "Empty boxes are not added."
                            else "${text.trimEnd().length} characters · wraps at ${width.toInt()} pt",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    minLines = 3, maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
                LaunchedEffect(box.id) { textFocus.requestFocus() }
                TextButton({ formattingExpanded = !formattingExpanded }) {
                    Icon(Icons.Rounded.FormatSize, null); Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(if (formattingExpanded) "Hide formatting" else "Formatting · ${size.toInt()} pt")
                }
                if (formattingExpanded) {
                    // Live preview so size, width, fade and colour choices read before they land on the page.
                    if (text.isNotBlank()) {
                        Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                            Text(
                                text.trimEnd().take(220),
                                Modifier.fillMaxWidth().padding(FolioSpacing.dp12),
                                color = Color(color).copy(alpha = opacity.coerceIn(0f, 1f)),
                                fontSize = size.coerceIn(10f, 48f).sp,
                                fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                                textAlign = when (align) { TextAlignMode.CENTER -> TextAlign.Center; TextAlignMode.RIGHT -> TextAlign.End; else -> TextAlign.Start },
                                textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                                maxLines = 4, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Icon(Icons.Rounded.FormatSize, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(size, { size = it }, valueRange = TextBox.MIN_SIZE..TextBox.MAX_SIZE, modifier = Modifier.weight(1f).semanticsLabel("Text size, ${size.toInt()} points"))
                        Text("${size.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(30.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Icon(Icons.AutoMirrored.Rounded.WrapText, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(width, { width = it }, valueRange = TextBox.MIN_WIDTH..TextBox.MAX_WIDTH, modifier = Modifier.weight(1f).semanticsLabel("Text wrap width, ${width.toInt()} points"))
                        Text("${width.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                    }
                    Text("Wrap width · the box grows downwards as it wraps.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Icon(Icons.Rounded.Opacity, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(opacity, { opacity = it }, valueRange = TextBox.MIN_OPACITY..TextBox.MAX_OPACITY, modifier = Modifier.weight(1f).semanticsLabel("Text opacity, ${(opacity * 100).roundToInt()} percent"))
                        Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        FilterChip(bold, { bold = !bold }, { Text("Bold") })
                        FilterChip(italic, { italic = !italic }, { Text("Italic") })
                        FilterChip(underline, { underline = !underline }, { Text("Underline") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        FilterChip(align == TextAlignMode.LEFT, { align = TextAlignMode.LEFT }, { Text("Left") })
                        FilterChip(align == TextAlignMode.CENTER, { align = TextAlignMode.CENTER }, { Text("Centre") })
                        FilterChip(align == TextAlignMode.RIGHT, { align = TextAlignMode.RIGHT }, { Text("Right") })
                    }
                    Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        colors.forEach { option -> InkColorDot(option, option == color, { color = option }, touch = 36.dp, dot = 24.dp, label = "Text colour") }
                    }
                }
                if (!isNew && canMove) OutlinedButton({ onMove(edited()) }, enabled = text.isNotBlank()) { Text("Move to page…") }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                if (!isNew) {
                    TextButton({ onDuplicate(edited()) }, enabled = text.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Duplicate") }
                    TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text("Delete") }
                }
                TextButton(::dismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            }
        },
        confirmButton = {
            Button({ val done = edited(); if (isNew) onCreate(done) else onUpdate(done) }, enabled = text.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                Text(if (isNew) "Add text" else "Save")
            }
        }
    )
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text("Discard text changes?") }, text = { Text("Your text and formatting changes have not been applied.") },
        confirmButton = { TextButton(onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard") } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
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
            Modifier.defaultMinSize(minHeight = 40.dp).padding(horizontal = 24.dp, vertical = FolioSpacing.dp8),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(FolioSpacing.dp8))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private enum class FollowSub { AREAS }

/**
 * A small chip beside a printed "[4 marks]" label: tick awards every mark at once, cross opens a − / +
 * stepper for part marks. It floats over the page without taking focus, never blocks writing, and goes
 * away by itself — a pen touching the page dismisses it, and so does a few seconds of nothing.
 */
@Composable private fun MarkChip(
    zone: MarkZone, rect: android.graphics.RectF, page: NotePage, stamp: Long,
    onAward: (Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null
) {
    val existing = remember(zone, page.texts) { MarkZones.awarded(zone, page)?.roundToInt() }
    var adjusting by remember(zone) { mutableStateOf(false) }
    var value by remember(zone) { mutableIntStateOf(existing ?: 0) }
    // Every hover or tap restarts the clock; a chip being adjusted is given longer.
    LaunchedEffect(stamp, adjusting, value) { delay(if (adjusting) 10_000L else 3_000L); onDismiss() }
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(LocalDensity.current) { 8.dp.toPx() }
        Surface(
            Modifier.onSizeChanged { size = it }.pointerInput(onDismiss) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Exit) onDismiss()
                    }
                }
            }.offset {
                val x = (rect.centerX() - size.width / 2f).coerceIn(0f, (maxWidthPx - size.width).coerceAtLeast(0f))
                val above = rect.top - size.height - gap
                IntOffset(x.roundToInt(), (if (above >= 0f) above else rect.bottom + gap).roundToInt())
            },
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!adjusting) {
                    Text(if (existing != null) "+$existing / ${zone.marks}" else "${zone.marks} ${if (zone.marks == 1) "mark" else "marks"}",
                        Modifier.padding(start = 10.dp, end = 4.dp), style = MaterialTheme.typography.labelLarge)
                    IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAward(zone.marks) }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Check, "Award all ${zone.marks}", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton({ adjusting = true }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Close, "Award fewer marks", tint = MaterialTheme.colorScheme.error)
                    }
                    if (existing != null) IconButton(onClear, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Delete, "Remove this mark")
                    }
                    if (onEdit != null) IconButton(onEdit, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Edit, "Change this area's marks or remove it")
                    }
                } else {
                    IconButton({ value = (value - 1).coerceAtLeast(0) }, Modifier.size(44.dp), enabled = value > 0) { Icon(Icons.Rounded.Remove, "One fewer mark") }
                    Text("$value / ${zone.marks}", style = MaterialTheme.typography.titleSmall)
                    IconButton({ value = (value + 1).coerceAtMost(zone.marks) }, Modifier.size(44.dp), enabled = value < zone.marks) { Icon(Icons.Rounded.Add, "One more mark") }
                    IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAward(value) }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Check, "Confirm $value marks", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** A boxed allocation awaiting its value: [existing] is the hand-drawn zone being edited, null for a new one. */
private data class AreaEntry(val existing: MarkZone?, val x: Float, val y: Float, val width: Float, val height: Float, val rect: android.graphics.RectF)

/**
 * Asks how many marks the label the marker just boxed is worth. It sits beside the rectangle, stays until
 * answered or dismissed (it is a decision, not a hint), and a tick confirms — the box then behaves exactly
 * like an allocation the scan had found.
 */
@Composable private fun MarkAreaPopover(
    rect: android.graphics.RectF, initial: Int?, guess: Int?, onConfirm: (Int) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit
) {
    var value by remember { mutableIntStateOf(initial ?: 1) }
    var touched by remember { mutableStateOf(initial != null) }
    LaunchedEffect(guess) { if (guess != null && !touched) value = guess }
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(LocalDensity.current) { 8.dp.toPx() }
        Surface(
            Modifier.onSizeChanged { size = it }.offset {
                val x = (rect.centerX() - size.width / 2f).coerceIn(0f, (maxWidthPx - size.width).coerceAtLeast(0f))
                val above = rect.top - size.height - gap
                IntOffset(x.roundToInt(), (if (above >= 0f) above else rect.bottom + gap).roundToInt())
            },
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Marks", Modifier.padding(start = 12.dp, end = 2.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ touched = true; value = (value - 1).coerceAtLeast(1) }, Modifier.size(44.dp), enabled = value > 1) { Icon(Icons.Rounded.Remove, "One fewer mark") }
                Text("$value", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                IconButton({ touched = true; value = (value + 1).coerceAtMost(MarkZones.MAX_MARKS) }, Modifier.size(44.dp), enabled = value < MarkZones.MAX_MARKS) { Icon(Icons.Rounded.Add, "One more mark") }
                IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConfirm(value) }, Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Check, "Set this area to $value ${if (value == 1) "mark" else "marks"}", tint = MaterialTheme.colorScheme.primary)
                }
                if (onRemove != null) IconButton(onRemove, Modifier.size(44.dp)) { Icon(Icons.Rounded.Delete, "Remove this area") }
                IconButton(onDismiss, Modifier.size(44.dp)) { Icon(Icons.Rounded.Close, "Cancel") }
            }
        }
    }
}

/** Says what the mark-area tool wants while it is selected; non-interactive, so it never steals a drag. */
@Composable private fun MarkAreaHint(modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Text("Drag a box around a “[n marks]” label the scan missed", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge)
    }
}

private fun readPanFactor(p: android.content.SharedPreferences): Float = AppPrefs.panFactor(
    p.getBoolean(AppPrefs.FAST_PAN, AppPrefs.DEFAULT_FAST_PAN),
    p.getFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.DEFAULT_FAST_PAN_MULTIPLIER),
)
