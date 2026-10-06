@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folio.notes.AppPrefs
import com.folio.notes.InkGeometry
import com.folio.notes.InkPoint
import com.folio.notes.ScribbleSensitivity
import com.folio.notes.ShapeMeasurement
import com.folio.notes.ShapeMeasurementTooltip
import com.folio.notes.TouchChord
import com.folio.notes.EditorGlassSurface
import com.folio.notes.EditorQuickPrefs
import com.folio.notes.FloatingInkToolbar
import com.folio.notes.FolioExpand
import com.folio.notes.FolioPopover
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.PopoverGroup
import com.folio.notes.PopoverRow
import com.folio.notes.PopoverTile
import com.folio.notes.QuickColorsState
import com.folio.notes.StrokeStyle
import com.folio.notes.Tool
import com.folio.notes.ToolOptions
import com.folio.notes.ToolPreset
import com.folio.notes.ToolPresetState
import com.folio.notes.ToolbarLayoutState
import com.folio.notes.ToolbarSlot
import com.folio.notes.ShapePickerTools
import com.folio.notes.ShapeTools
import com.folio.notes.WidthPresets
import com.folio.notes.guardUiTouches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Music's own rehearsal blue, the default colour of every annotation tool. */
internal val MusicPencil = Color(MUSIC_INK)

/**
 * The sheet a score is engraved on. A rendered page is white whatever the theme, so each page is
 * painted this colour and the desk behind it stays the theme's own (surfaceContainerLow) — the same
 * paper-on-matte pair an editor page and its canvas use.
 */
internal val MusicPaper = Color.White

private enum class ReaderPopover { MARKS, METRONOME, PAGES, MORE }

/** One snapshot of a score's annotations, so undo can put back ink and labels together. */
private data class MusicSnapshot(val ink: List<MusicStroke>, val texts: List<MusicText>)

/** Tools the reader carries over from the editor; marking and sticky notes are deliberately out. */
private val ReaderTools = ShapeTools

/**
 * The reader wears the editor's chrome: the editor's tonal canvas, its measured floating glass
 * docks and its actual ink tool strip — the same pens, highlighter, eraser, shapes, text and lasso,
 * with the same colour, width, opacity and preset popovers — so a score is annotated with Folio's
 * tools rather than a viewer's own. Two tools are left out: **marking** (a marking bar belongs to a
 * marked response, not a score) and **sticky notes** (they live beside a notebook page, and a score
 * has no space beside it). Performance mode drops all of it and leaves only the page.
 */
@Composable internal fun MusicReader(score: MusicScore, model: MusicViewModel, onBack: () -> Unit,
    setLabel: String?, next: MusicScore?, onNextScore: () -> Unit,
    onDetails: () -> Unit, onExport: () -> Unit, onExtract: () -> Unit, onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    // The same prefs the editor's strip uses, so a score offers the user's own inks and presets.
    val prefs = remember(context) { context.getSharedPreferences("ink-tools", 0) }
    val appPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    val quick = remember(prefs) { QuickColorsState(prefs) }
    val toolPresets = remember(prefs) { ToolPresetState(prefs) }
    val toolbarLayouts = remember(appPrefs) { ToolbarLayoutState(appPrefs) }
    // The editor's input prefs: "Draw with a finger" and palm rejection apply to a score exactly as to a page.
    var finger by remember { mutableStateOf(appPrefs.getBoolean("finger", true)) }
    var palmRejectMs by remember { mutableLongStateOf(AppPrefs.palmMs(appPrefs.getLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS).takeIf { appPrefs.contains(AppPrefs.PALM_MS) })) }
    var eraserSingleStroke by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, false)) }
    var snapEnabled by remember { mutableStateOf(appPrefs.getBoolean("mathSnap", true)) }
    var scribbleToErase by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, true)) }
    var scribbleSensitivity by remember { mutableFloatStateOf(appPrefs.getFloat(EditorQuickPrefs.SCRIBBLE_SENSITIVITY, ScribbleSensitivity.DEFAULT)) }
    var eraserPressure by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_PRESSURE, true)) }
    var shapeMeasurements by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, true)) }
    var multiTouchUndo by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, true)) }
    fun setFlag(key: String, value: Boolean) = appPrefs.edit().putBoolean(key, value).apply()
    DisposableEffect(appPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "finger" -> finger = appPrefs.getBoolean(key, true)
                AppPrefs.PALM_MS -> palmRejectMs = AppPrefs.palmMs(appPrefs.getLong(key, AppPrefs.DEFAULT_PALM_MS))
                EditorQuickPrefs.ERASER_SINGLE_STROKE -> eraserSingleStroke = appPrefs.getBoolean(key, false)
                "mathSnap" -> snapEnabled = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.SCRIBBLE_TO_ERASE -> scribbleToErase = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.SCRIBBLE_SENSITIVITY -> scribbleSensitivity = appPrefs.getFloat(key, ScribbleSensitivity.DEFAULT)
                EditorQuickPrefs.ERASER_PRESSURE -> eraserPressure = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.SHAPE_MEASUREMENTS -> shapeMeasurements = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.MULTI_TOUCH_UNDO -> multiTouchUndo = appPrefs.getBoolean(key, true)
            }
        }
        appPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var tool by rememberSaveable { mutableStateOf(Tool.PEN) }
    var options by remember(tool) { mutableStateOf(ToolOptions.load(prefs, tool)) }
    fun changeOptions(value: ToolOptions) { options = value; value.save(prefs, tool) }
    var selectionPage by remember(score.id) { mutableIntStateOf(score.page) }
    var selectedStrokes by remember(score.id) { mutableStateOf(emptySet<Int>()) }
    var selectedTexts by remember(score.id) { mutableStateOf(emptySet<Int>()) }
    fun dismissSelection() { selectedStrokes = emptySet(); selectedTexts = emptySet() }
    // Single-stroke eraser: remember where the user came from and go back after one stroke, as the editor does.
    var eraserReturnTool by remember { mutableStateOf<Tool?>(null) }
    fun selectTool(value: Tool) {
        if (value == tool) return
        dismissSelection()
        if (value == Tool.ERASER && eraserSingleStroke) eraserReturnTool = tool
        else if (tool == Tool.ERASER) eraserReturnTool = null
        tool = value
        options = ToolOptions.load(prefs, value)
    }
    fun finishSingleStrokeEraser() {
        if (!eraserSingleStroke || tool != Tool.ERASER) return
        val back = eraserReturnTool ?: Tool.PEN
        eraserReturnTool = null
        tool = back
        options = ToolOptions.load(prefs, back)
    }
    fun applyPreset(preset: ToolPreset) {
        selectTool(preset.tool)
        val next = ToolOptions.load(prefs, preset.tool).copy(
            color = preset.color, width = preset.width, opacity = preset.opacity, style = preset.style)
        options = next
        next.save(prefs, preset.tool)
    }
    var palette by rememberSaveable { mutableStateOf(false) }
    var eraserWholeStroke by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, false)) }
    fun setEraserWholeStroke(v: Boolean) { eraserWholeStroke = v; appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, v).apply() }
    var page by rememberSaveable(score.id) { mutableIntStateOf(score.page) }
    var performance by rememberSaveable { mutableStateOf(false) }
    // Landscape can show two pages side by side; this is the user's choice to turn that off.
    var twoUp by rememberSaveable { mutableStateOf(true) }
    var popover by remember { mutableStateOf<ReaderPopover?>(null) }
    var running by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }
    // The label being typed; lasso selection state lives with the tool state above.
    var labelDraft by remember { mutableStateOf<MusicText?>(null) }
    var undoStack by remember(score.id) { mutableStateOf(emptyList<MusicSnapshot>()) }
    var redoStack by remember(score.id) { mutableStateOf(emptyList<MusicSnapshot>()) }
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val focus = remember { FocusRequester() }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous; model.pageCache.clear() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) running = false }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(score.id) { focus.requestFocus(); running = false }
    LaunchedEffect(page) { selectedStrokes = emptySet(); selectedTexts = emptySet() }
    // Visual beat is always available; audio is explicitly enabled and released on leaving.
    LaunchedEffect(running, score.bpm, score.beats, sound) {
        if (!running) { beat = 0; return@LaunchedEffect }
        val tone = if (sound) runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }.getOrNull() else null
        try {
            val period = 60_000L / score.bpm
            var deadline = SystemClock.elapsedRealtime()
            var tick = 0
            while (true) {
                beat = tick % score.beats + 1
                tone?.startTone(if (beat == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 45)
                tick++
                deadline += period
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
        } finally { tone?.release() }
    }
    /**
     * Writes the score's annotations. A drag that edits frame by frame records its undo step once,
     * when it starts, so dragging a selection is one undo rather than one per frame.
     */
    fun applyInk(ink: List<MusicStroke>, texts: List<MusicText>, record: Boolean = true) {
        if (record) {
            undoStack = (undoStack + MusicSnapshot(score.ink, score.texts)).takeLast(60)
            redoStack = emptyList()
        }
        model.score(score.id) { it.copy(ink = ink, texts = texts) }
    }
    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + MusicSnapshot(score.ink, score.texts)).takeLast(60)
        undoStack = undoStack.dropLast(1)
        model.score(score.id) { it.copy(ink = previous.ink, texts = previous.texts) }
    }
    fun redo() {
        val following = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + MusicSnapshot(score.ink, score.texts)).takeLast(60)
        redoStack = redoStack.dropLast(1)
        model.score(score.id) { it.copy(ink = following.ink, texts = following.texts) }
    }
    /** Replaces one page's annotations, leaving every other page's ink exactly where it was. */
    fun replacePage(index: Int, strokes: List<MusicStroke>, texts: List<MusicText>, record: Boolean = true) {
        applyInk(score.ink.filterNot { it.page == index } + strokes, score.texts.filterNot { it.page == index } + texts, record)
    }
    BackHandler {
        when {
            labelDraft != null -> labelDraft = null
            performance -> performance = false
            selectedStrokes.isNotEmpty() || selectedTexts.isNotEmpty() -> dismissSelection()
            else -> onBack()
        }
    }
    labelDraft?.let { draft ->
        MusicLabelDialog(draft, onDismiss = { labelDraft = null }, onDone = { edited ->
            labelDraft = null
            if (edited != null) {
                val existing = score.texts.indexOfFirst { it.page == draft.page && it.x == draft.x && it.y == draft.y }
                // An emptied label is a deletion; a new one is appended where it was tapped.
                if (existing >= 0) applyInk(score.ink, score.texts.filterIndexed { i, _ -> i != existing } + listOfNotNull(edited.takeIf { it.text.isNotBlank() }))
                else if (edited.text.isNotBlank()) applyInk(score.ink, score.texts + edited)
            }
        })
    }
    // The canvas is the editor's desk, not the sheet itself: the score is the one bright surface on
    // it, and the glass docks float above that desk. The page is pushed down by the strip's measured
    // height, exactly as an editor page clears the floating toolbar, so no chrome covers the music.
    Column(Modifier.fillMaxSize()) {
        var dockTop by remember { mutableStateOf(64.dp) }
        // The quick bar (colours, widths) floats under the strip; the sheet must clear it too, or it
        // covers the top staves. Measured in full but applied once it settles, so opening or closing
        // the bar re-fits the page once instead of re-rendering it every animation frame.
        var toolbarFull by remember { mutableIntStateOf(0) }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.TopCenter
        ) {
            val density = LocalDensity.current
            val viewWidth = maxWidth
            // Landscape can lay two pages side by side (never more); portrait always shows one.
            val landscape = maxWidth > maxHeight
            val step = if (landscape && twoUp) 2 else 1
            val lastStart = maxOf(0, score.pages - step)
            fun go(target: Int) {
                val following = target.coerceIn(0, lastStart)
                if (following != page) { page = following; model.score(score.id) { it.copy(page = following) } }
            }
            // A saved position (or a rotation) can leave the window hanging past the end.
            LaunchedEffect(step) { if (page > lastStart) go(lastStart) }
            val last = page >= lastStart
            val visible = page until minOf(page + step, score.pages)
            val pageLabel = if (step > 1 && page + 1 < score.pages) "${page + 1}–${minOf(page + step, score.pages)} of ${score.pages}" else "${page + 1} of ${score.pages}"
            val pageInk = score.ink.filter { it.page in visible }
            val annotated = pageInk.isNotEmpty() || score.texts.any { it.page in visible }
            fun close() { popover = null; focus.requestFocus() }
            @Composable fun Anchored(kind: ReaderPopover, width: androidx.compose.ui.unit.Dp = 320.dp, content: @Composable ColumnScope.() -> Unit) {
                if (popover == kind) FolioPopover(::close, width = width, content = content)
            }
            @Composable fun LeftRail() {
                FloatingRail {
                    ReaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Music", onClick = onBack)
                    Box {
                        ReaderButton(Icons.Rounded.Bookmarks, "Rehearsal marks") { popover = ReaderPopover.MARKS }
                        Anchored(ReaderPopover.MARKS) { MarksPopover(score, page, visible, model) { go(it); close() } }
                    }
                    Box {
                        if (running) BeatButton(score.bpm, beat) { popover = ReaderPopover.METRONOME }
                        else ReaderButton(Icons.Rounded.Speed, "Metronome") { popover = ReaderPopover.METRONOME }
                        Anchored(ReaderPopover.METRONOME) {
                            MetronomePopover(score, model, running, { running = it }, sound, { sound = it }, beat)
                        }
                    }
                    FilledTonalIconButton({ go(page - step) }, modifier = Modifier.size(48.dp), enabled = page > 0, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page")
                    }
                }
            }
            @Composable fun RightRail() {
                FloatingRail {
                    Box {
                        ReaderButton(Icons.Rounded.MoreVert, "Score options") { popover = ReaderPopover.MORE }
                        Anchored(ReaderPopover.MORE) {
                            Text(score.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(score.composer.ifBlank { null }, score.part.ifBlank { null }, setLabel).joinToString(" · ").ifEmpty { "${score.pages} pages" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                PopoverTile(Icons.Rounded.EditNote, "Details", Modifier.weight(1f)) { close(); onDetails() }
                                PopoverTile(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Favourite", Modifier.weight(1f), active = score.starred) {
                                    model.score(score.id) { it.copy(starred = !it.starred) }
                                }
                                PopoverTile(Icons.Rounded.IosShare, "Export", Modifier.weight(1f)) { close(); onExport() }
                            }
                            if (score.notes.isNotBlank()) PopoverGroup("Rehearsal notes") {
                                Text(score.notes, Modifier.padding(horizontal = FolioSpacing.dp8), style = MaterialTheme.typography.bodyMedium)
                            }
                            Column {
                                if (landscape) PopoverRow(Icons.Rounded.AutoStories, if (twoUp) "Show one page at a time" else "Show two pages side by side") { twoUp = !twoUp; close() }
                                PopoverRow(Icons.Rounded.ContentCut, "Extract instrument parts") { close(); onExtract() }
                                PopoverRow(Icons.Rounded.Tune, "App settings") { close(); onSettings() }
                                if (annotated) PopoverRow(Icons.Rounded.DeleteSweep, "Clear annotations on these pages") {
                                    close()
                                    visible.forEach { index -> replacePage(index, emptyList(), emptyList()) }
                                }
                            }
                            HorizontalDivider()
                            Text("Tap the left or right of a page to turn it, or use the arrows. Pinch to zoom, double-tap to fit. Arrow, Space and Page keys work with page-turn pedals; Home/End jump to the ends. In landscape you can show two pages side by side. The pens, highlighter, eraser, shapes, text and lasso all work on a score.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ReaderButton(Icons.Rounded.Fullscreen, "Performance mode") { palette = false; dismissSelection(); popover = null; performance = true; focus.requestFocus() }
                    Box {
                        // The position readout is also the way into the page grid.
                        TextButton({ popover = ReaderPopover.PAGES }, modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 40.dp), shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(horizontal = FolioSpacing.dp4)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (step > 1 && page + 1 < score.pages) "${page + 1}–${minOf(page + step, score.pages)}" else "${page + 1}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text("of ${score.pages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        Anchored(ReaderPopover.PAGES) { PagesPopover(score, visible) { go(it); close() } }
                    }
                    if (last && next != null) FilledTonalIconButton(onNextScore, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.SkipNext, "Next: ${next.title}")
                    } else FilledTonalIconButton({ go(page + step) }, modifier = Modifier.size(48.dp), enabled = !last, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page")
                    }
                }
            }
            // Everything below shares one focus target, so a page-turn pedal reaches the keys whether
            // the strip or the sheet itself were touched last.
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (popover != null || labelDraft != null) return@onPreviewKeyEvent false
                // Home/End jump to the ends of the score, matching what pedal users expect of a viewer.
                val target = when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> page + step
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> page - step
                    KeyEvent.KEYCODE_MOVE_HOME -> 0
                    KeyEvent.KEYCODE_MOVE_END -> score.pages - 1
                    else -> return@onPreviewKeyEvent false
                }
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) go(target)
                true
            }.focusRequester(focus).focusable()) {
                LaunchedEffect(Unit) {
                    snapshotFlow { toolbarFull }.collectLatest { full -> if (full > 0) { delay(160); dockTop = with(density) { toolbarFull.toDp() } + FolioSpacing.dp6 } }
                }
                val topInset = if (performance) FolioSpacing.dp8 else dockTop + FolioSpacing.dp4
                val bottomInset = FolioSpacing.dp8
                // The floating rails sit in the gutters either side of the sheets, so the pages stay clear of them.
                val sideInset = if (performance) FolioSpacing.dp10 else 58.dp
                val gap = FolioSpacing.dp8
                val pageWidth = (viewWidth - sideInset * 2 - gap * (step - 1)) / step
                // Every page sits in one horizontal strip; turning slides it, a swipe is left to the pen.
                val rowState = rememberLazyListState()
                var placed by remember { mutableStateOf(false) }
                // Page width changes with the layout (one up, two up, rotation), and the list keeps its old pixel
                // offset through that, which leaves a page off-centre — so a layout change re-seats the strip
                // exactly, and only a real page turn slides.
                var seated by remember { mutableStateOf<Pair<Int, androidx.compose.ui.unit.Dp>?>(null) }
                LaunchedEffect(page, pageWidth) {
                    val layout = step to pageWidth
                    if (placed && seated?.second == layout.second) rowState.animateScrollToItem(page)
                    else { rowState.scrollToItem(page); placed = true }
                    seated = layout
                }
                LazyRow(Modifier.fillMaxSize().padding(start = sideInset, end = sideInset, top = topInset, bottom = bottomInset),
                    state = rowState, userScrollEnabled = false, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    items(score.pages, key = { it }) { index ->
                        MusicPage(model.store.pdf(score.id), index, score.pages, model.pageCache,
                            strokes = score.ink.filter { it.page == index }, texts = score.texts.filter { it.page == index },
                            tool = if (performance) Tool.HAND else tool, options = options, performance = performance,
                            // A selection belongs to one page: a spread never mirrors it onto its partner.
                            selectedStrokes = if (index == selectionPage) selectedStrokes else emptySet(),
                            selectedTexts = if (index == selectionPage) selectedTexts else emptySet(),
                            onSelect = { selected, labels -> selectionPage = index; selectedStrokes = selected; selectedTexts = labels },
                            onInk = { strokes, labels, record -> replacePage(index, strokes, labels, record) },
                            eraserWholeStroke = eraserWholeStroke,
                            finger = finger, palmRejectMs = palmRejectMs, onEraserFinished = ::finishSingleStrokeEraser,
                            snapEnabled = snapEnabled, scribbleToErase = scribbleToErase, scribbleSensitivity = scribbleSensitivity,
                            eraserPressure = eraserPressure, shapeMeasurements = shapeMeasurements,
                            multiTouchUndo = multiTouchUndo, onUndo = ::undo, onRedo = ::redo,
                            onEditLabel = { labelDraft = it },
                            onTurn = { forward -> go(page + if (forward) step else -step) }, modifier = Modifier.width(pageWidth).fillMaxHeight())
                    }
                }
                if (!performance) {
                    Box(Modifier.align(Alignment.CenterStart).zIndex(11f).padding(start = FolioSpacing.dp6, top = topInset, bottom = bottomInset)) { LeftRail() }
                    Box(Modifier.align(Alignment.CenterEnd).zIndex(11f).padding(end = FolioSpacing.dp6, top = topInset, bottom = bottomInset)) { RightRail() }
                }
                // The tool strip floats over the desk, flush to the top; the page is inset by its full
                // measured height. Back, marks, metronome, options and page turns live in two floating
                // rails at the left and right edges instead of a row above the strip and a dock below.
                FloatingInkToolbar(
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(11f).fillMaxWidth()
                            .padding(top = FolioSpacing.dp6, start = FolioSpacing.dp6, end = FolioSpacing.dp6),
                        onFullHeight = { if (!performance) toolbarFull = it },
                        tool = tool,
                        onTool = ::selectTool,
                        options = options,
                        onOptions = { value ->
                            changeOptions(value)
                            // With a lasso selection in hand, a new colour restyles it, as the editor does.
                            if (selectedStrokes.isNotEmpty() || selectedTexts.isNotEmpty()) {
                                val strokes = score.ink.filter { it.page == selectionPage }
                                val texts = score.texts.filter { it.page == selectionPage }
                                replacePage(selectionPage,
                                    strokes.mapIndexed { i, stroke -> if (i in selectedStrokes) MusicInk.recolored(stroke, value.color) else stroke },
                                    texts.mapIndexed { i, text -> if (i in selectedTexts) MusicInk.recolored(text, value.color) else text })
                            }
                        },
                        quick = quick,
                        canUndo = undoStack.isNotEmpty(),
                        canRedo = redoStack.isNotEmpty(),
                        undo = ::undo,
                        redo = ::redo,
                        palette = palette,
                        snapEnabled = snapEnabled,
                        onSnap = { snapEnabled = it; setFlag("mathSnap", it) },
                        scribbleToErase = scribbleToErase, onScribbleToErase = { scribbleToErase = it; setFlag(EditorQuickPrefs.SCRIBBLE_TO_ERASE, it) },
                        eraserPressureEnabled = eraserPressure, onEraserPressure = { eraserPressure = it; setFlag(EditorQuickPrefs.ERASER_PRESSURE, it) },
                        shapeMeasurements = shapeMeasurements, onShapeMeasurements = { shapeMeasurements = it; setFlag(EditorQuickPrefs.SHAPE_MEASUREMENTS, it) },
                        multiTouchUndo = multiTouchUndo, onMultiTouchUndo = { multiTouchUndo = it; setFlag(EditorQuickPrefs.MULTI_TOUCH_UNDO, it) },
                        onPalette = { palette = it },
                        markAreaAvailable = false,
                        eraserWholeStroke = eraserWholeStroke,
                        onEraserWholeStroke = ::setEraserWholeStroke,
                        eraserSingleStroke = eraserSingleStroke,
                        onEraserSingleStroke = { eraserSingleStroke = it; appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, it).apply() },
                        onSelectAll = {
                            val strokes = score.ink.filter { it.page == page }
                            val labels = score.texts.filter { it.page == page }
                            if (strokes.isNotEmpty() || labels.isNotEmpty()) {
                                selectTool(Tool.LASSO)
                                selectionPage = page
                                selectedStrokes = strokes.indices.toSet()
                                selectedTexts = labels.indices.toSet()
                            }
                        },
                        textColor = options.color,
                        onTextColor = { changeOptions(options.copy(color = it)) },
                        presets = toolPresets.presets,
                        onApplyPreset = ::applyPreset,
                        toolPresetsState = toolPresets,
                        toolbarLayoutState = toolbarLayouts,
                        hiddenSlots = setOf(ToolbarSlot.STICKY_NOTE, ToolbarSlot.MARK_AREA),
                        shapeTools = ReaderTools.toList(),
                        header = { strip -> FolioExpand(!performance) { strip() } }
                    )
                // A lasso selection keeps its actions beside the page instead of inside the strip.
                if (selectionPage in visible && (selectedStrokes.isNotEmpty() || selectedTexts.isNotEmpty())) {
                    val strokes = score.ink.filter { it.page == selectionPage }
                    val texts = score.texts.filter { it.page == selectionPage }
                    SelectionPill(
                        count = selectedStrokes.size + selectedTexts.size,
                        modifier = Modifier.align(Alignment.BottomCenter).zIndex(12f).padding(bottom = bottomInset + FolioSpacing.dp8),
                        onDelete = {
                            replacePage(selectionPage, strokes.filterIndexed { i, _ -> i !in selectedStrokes }, texts.filterIndexed { i, _ -> i !in selectedTexts })
                            dismissSelection()
                        },
                        onDuplicate = {
                            replacePage(selectionPage, strokes + selectedStrokes.sorted().mapNotNull { strokes.getOrNull(it)?.let(MusicInk::duplicate) },
                                texts + selectedTexts.sorted().mapNotNull { texts.getOrNull(it)?.let(MusicInk::duplicate) })
                            dismissSelection()
                        },
                        onDone = ::dismissSelection)
                }
                if (performance) {
                    // On stage only the page matters: a quiet way out, where we are, and the next piece.
                    FilledTonalIconButton({ performance = false; focus.requestFocus() }, modifier = Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp12).guardUiTouches(),
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f))) {
                        Icon(Icons.Rounded.FullscreenExit, "Exit performance mode")
                    }
                    Row(Modifier.align(Alignment.BottomCenter).padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f)) {
                            Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                if (running) BeatDots(score.beats, beat)
                                Text(pageLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (last && next != null) NextScoreButton(next, onNextScore)
                    }
                }
            }
        }
    }
}

/** The reader's name for a tool, so the dock can say what the finger will do. */
private fun toolLabel(tool: Tool): String = when (tool) {
    Tool.PEN -> "Draw"
    Tool.HIGHLIGHTER -> "Highlight"
    Tool.ERASER -> "Erase"
    Tool.TEXT -> "Tap to add a label"
    Tool.LASSO -> "Loop to select"
    Tool.HAND -> "Move or zoom"
    else -> if (tool in ShapePickerTools) "Drag a ${tool.name.lowercase()}" else tool.name.lowercase()
}

/** Delete, duplicate or dismiss a lasso selection without leaving the page. */
@Composable private fun SelectionPill(count: Int, modifier: Modifier, onDelete: () -> Unit, onDuplicate: () -> Unit, onDone: () -> Unit) {
    Surface(modifier = modifier.guardUiTouches(), shape = FolioShapes.panel, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Text("$count selected", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = FolioSpacing.dp4))
            ReaderButton(Icons.Rounded.ContentCopy, "Duplicate selection", onClick = onDuplicate)
            ReaderButton(Icons.Rounded.Delete, "Delete selection", onClick = onDelete)
            ReaderButton(Icons.Rounded.Close, "Deselect", onClick = onDone)
        }
    }
}

/** Typing a label on a score: block capitals read best, so the keyboard starts capitalised. */
@Composable private fun MusicLabelDialog(draft: MusicText, onDismiss: () -> Unit, onDone: (MusicText?) -> Unit) {
    var text by rememberSaveable(draft) { mutableStateOf(TextFieldValue(draft.text, selection = TextRange(0, draft.text.length))) }
    // Typing is the only thing this dialog is for, so the keyboard opens with it.
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = onDismiss,
        title = { Text(if (draft.text.isBlank()) "Add a label" else "Edit label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Rehearsal letters, dynamics and reminders sit on the page at the spot you tapped.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text, { if (it.text.length <= 40) text = it }, modifier = Modifier.fillMaxWidth().focusRequester(field), singleLine = true,
                    label = { Text("Label") }, keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(draft.copy(text = text.text.trim())) }))
            }
        },
        confirmButton = { Button({ onDone(draft.copy(text = text.text.trim())) }, shapes = ButtonDefaults.shapes()) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                // Clearing the text of an existing label is the delete gesture.
                if (draft.text.isNotBlank()) TextButton({ onDone(draft.copy(text = "")) }, shapes = ButtonDefaults.shapes()) { Text("Delete") }
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            }
        })
}

@Composable private fun NextScoreButton(next: MusicScore, onClick: () -> Unit) {
    Button(onClick, modifier = Modifier.heightIn(min = 48.dp).guardUiTouches(), shapes = ButtonDefaults.shapes()) {
        Text("Next: ${next.title}", Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.Rounded.SkipNext, null)
    }
}

/** A dock button that matches the editor's: 40dp, tooltip, and a tonal fill when [active]. */
@Composable private fun ReaderButton(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.size(40.dp), enabled = enabled,
            colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                else IconButtonDefaults.iconButtonColors()) {
            Icon(icon, label, Modifier.size(20.dp))
        }
    }
}

/** A vertical glass pill that floats at a screen edge, holding the reader's button controls. */
@Composable private fun FloatingRail(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.guardUiTouches(), shape = FolioShapes.panel, color = colors.surfaceContainerHigh.copy(alpha = .9f), contentColor = colors.onSurface,
        shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .35f))) {
        Column(Modifier.padding(FolioSpacing.dp4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2), content = content)
    }
}

/** The running metronome as a rail button: the tempo, flashing on the downbeat. */
@Composable private fun BeatButton(bpm: Int, beat: Int, onClick: () -> Unit) {
    val container by animateColorAsState(if (beat == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, label = "beat")
    Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = if (beat == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Text("$bpm", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable private fun BeatDots(beats: Int, beat: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..beats) {
            val on = i == beat
            val color by animateColorAsState(when {
                on && i == 1 -> MaterialTheme.colorScheme.primary
                on -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outlineVariant
            }, label = "beat")
            Box(Modifier.size(if (on) 8.dp else 6.dp).background(color, CircleShape))
        }
    }
}

@Composable private fun MarksPopover(score: MusicScore, page: Int, visible: IntRange, model: MusicViewModel, go: (Int) -> Unit) {
    var name by rememberSaveable(score.id, page) { mutableStateOf("") }
    fun add() { if (name.isNotBlank()) { model.score(score.id) { it.copy(marks = it.marks + MusicMark(page, name.trim())) }; name = "" } }
    val previous = previousMark(page, score.marks)
    val following = nextMark(page, score.marks)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.Bookmarks, null, Modifier.size(20.dp))
        Text("Rehearsal marks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        // Jump along the marks in order without opening the list again.
        FilledTonalIconButton({ previous?.let { go(it.page) } }, enabled = previous != null,
            shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous rehearsal mark", Modifier.size(18.dp))
        }
        FilledTonalIconButton({ following?.let { go(it.page) } }, enabled = following != null,
            shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next rehearsal mark", Modifier.size(18.dp))
        }
    }
    if (score.marks.isEmpty()) Text("Mark movements, codas and rehearsal letters to jump straight back to them.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
        score.marks.sortedBy { it.page }.forEach { mark ->
            val here = mark.page in visible
            Surface(onClick = { go(mark.page) }, shape = FolioShapes.medium,
                color = if (here) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Text(mark.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("p. ${mark.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ model.score(score.id) { it.copy(marks = it.marks - mark) } }, Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, "Remove ${mark.name}", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, shape = FolioShapes.medium,
            placeholder = { Text("Mark page ${page + 1}, e.g. Coda") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
        FilledTonalIconButton(::add, enabled = name.isNotBlank(), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add rehearsal mark") }
    }
}

@Composable private fun MetronomePopover(score: MusicScore, model: MusicViewModel, running: Boolean, onRunning: (Boolean) -> Unit,
    sound: Boolean, onSound: (Boolean) -> Unit, beat: Int) {
    var bpm by remember(score.bpm) { mutableFloatStateOf(score.bpm.toFloat()) }
    var lastTap by remember { mutableLongStateOf(0L) }
    fun tempo(value: Int) { val clamped = value.coerceIn(30, 240); bpm = clamped.toFloat(); model.score(score.id) { it.copy(bpm = clamped) } }
    fun meter(value: Int) { model.score(score.id) { it.copy(beats = value.coerceIn(1, 12)) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.Speed, null, Modifier.size(20.dp))
        Text("Metronome", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        BeatDots(score.beats, beat)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        FilledTonalIconButton({ tempo(bpm.roundToInt() - 1) }, enabled = bpm > 30, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Slower") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${bpm.roundToInt()}", style = MaterialTheme.typography.displaySmall)
            Text("beats per minute", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton({ tempo(bpm.roundToInt() + 1) }, enabled = bpm < 240, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Faster") }
    }
    Slider(bpm, { bpm = it }, valueRange = 30f..240f, onValueChangeFinished = { tempo(bpm.roundToInt()) })
    // One tap to a familiar marking, then fine-tune with the slider.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        MusicTempos.forEach { preset ->
            FilterChip(bpm.roundToInt() == preset.bpm, { tempo(preset.bpm) }, { Text("${preset.label} · ${preset.bpm}") })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        PopoverTile(Icons.Rounded.TouchApp, "Tap tempo", Modifier.weight(1f)) {
            val now = SystemClock.elapsedRealtime()
            if (lastTap > 0 && now - lastTap in 250..2000) tempo((60_000f / (now - lastTap)).roundToInt())
            lastTap = now
        }
        PopoverTile(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            if (sound) "Click on" else "Silent", Modifier.weight(1f), active = sound) { onSound(!sound) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Beats per bar", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton({ meter(score.beats - 1) }, enabled = score.beats > 1, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Fewer beats per bar") }
        Text("${score.beats}", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton({ meter(score.beats + 1) }, enabled = score.beats < 12, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "More beats per bar") }
    }
    Button({ onRunning(!running) }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
        Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (running) "Stop" else "Start")
    }
    Text("Tempo and meter are saved with this score. The metronome stops when you leave the score or Folio.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Every page as a tile; pages with a rehearsal mark carry a dot, so codas are easy to find. */
@Composable private fun PagesPopover(score: MusicScore, visible: IntRange, go: (Int) -> Unit) {
    val marked = score.marks.map { it.page }.toSet()
    val annotated = (score.ink.map { it.page } + score.texts.map { it.page }).toSet()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.GridView, null, Modifier.size(20.dp))
        Text("Go to page", style = MaterialTheme.typography.titleMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        for (index in 0 until score.pages) {
            val here = index in visible
            Surface(onClick = { go(index) }, shape = FolioShapes.medium, modifier = Modifier.size(44.dp),
                color = if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (here) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                    if (index in marked) Box(Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp4).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    if (index in annotated) Box(Modifier.align(Alignment.BottomEnd).padding(FolioSpacing.dp4).size(6.dp).background(MusicPencil, CircleShape))
                }
            }
        }
    }
    if (marked.isNotEmpty() || annotated.isNotEmpty()) Text(
        listOfNotNull(if (marked.isNotEmpty()) "a dot marks a rehearsal mark" else null,
            if (annotated.isNotEmpty()) "a blue dot marks pencil notes" else null).joinToString("; ").replaceFirstChar { it.uppercase() } + ".",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private data class MusicPageImage(val bitmap: Bitmap? = null, val error: String? = null)

/**
 * One score page, rendered off the UI thread, with the editor's tools on top of it. Everything an
 * annotation needs is in page fractions, so ink and labels stay put at any zoom or spread.
 */
@Composable internal fun MusicPage(file: File, page: Int, pageCount: Int, cache: MusicPageCache,
    strokes: List<MusicStroke> = emptyList(), texts: List<MusicText> = emptyList(),
    tool: Tool = Tool.HAND, options: ToolOptions = ToolOptions.defaults(Tool.PEN), performance: Boolean = false,
    eraserWholeStroke: Boolean = false, finger: Boolean = true, palmRejectMs: Long = AppPrefs.DEFAULT_PALM_MS,
    onEraserFinished: () -> Unit = {},
    snapEnabled: Boolean = false, scribbleToErase: Boolean = false, scribbleSensitivity: Float = ScribbleSensitivity.DEFAULT,
    eraserPressure: Boolean = false, shapeMeasurements: Boolean = false,
    multiTouchUndo: Boolean = false, onUndo: () -> Unit = {}, onRedo: () -> Unit = {},
    selectedStrokes: Set<Int> = emptySet(), selectedTexts: Set<Int> = emptySet(),
    onSelect: (Set<Int>, Set<Int>) -> Unit = { _, _ -> },
    onInk: (List<MusicStroke>, List<MusicText>, Boolean) -> Unit = { _, _, _ -> },
    onEditLabel: (MusicText) -> Unit = {},
    onTurn: (Boolean) -> Unit = { _ -> }, modifier: Modifier) {
    var retry by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val width = (maxWidth.value * density).roundToInt().coerceIn(1, 2000)
        val result by produceState(MusicPageImage(), file, page, width, retry) {
            value = MusicPageImage()
            value = withContext(Dispatchers.IO) {
                try {
                    MusicPageImage(cache.render(file, page, width))
                } catch (e: Exception) { MusicPageImage(error = e.message ?: "Could not render page") }
            }
        }
        LaunchedEffect(result.bitmap, file, page, width) {
            if (result.bitmap != null) withContext(Dispatchers.IO) {
                for (next in page + 1..minOf(page + 2, pageCount - 1)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    runCatching { cache.render(file, next, width) }
                }
            }
        }

        val bitmap = result.bitmap
        if (bitmap != null) {
            val ratio = bitmap.width.toFloat() / bitmap.height
            val w = minOf(maxWidth, maxHeight * ratio)
            val h = w / ratio
            var scale by remember(file, page, performance) { mutableFloatStateOf(1f) }
            var offset by remember(file, page, performance) { mutableStateOf(Offset.Zero) }
            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                val boundX = w.value * density * (scale - 1) / 2
                val boundY = h.value * density * (scale - 1) / 2
                offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX), (offset.y + pan.y).coerceIn(-boundY, boundY))
            }
            val turn by rememberUpdatedState(onTurn)
            val commit by rememberUpdatedState(onInk)
            val select by rememberUpdatedState(onSelect)
            val editLabel by rememberUpdatedState(onEditLabel)
            val eraserDone by rememberUpdatedState(onEraserFinished)
            val undoNow by rememberUpdatedState(onUndo)
            val redoNow by rememberUpdatedState(onRedo)
            // Last time a pen was seen at the glass, for palm rejection.
            var lastStylusAt by remember { mutableLongStateOf(-1_000_000L) }
            // A running gesture outlives the composition that started it, so it reads the live page
            // rather than the lists captured when its pointer handler was created.
            val liveStrokes by rememberUpdatedState(strokes)
            val liveTexts by rememberUpdatedState(texts)
            var points by remember(file, page, tool) { mutableStateOf(emptyList<MusicPoint>()) }
            // The eraser's ring under the tip: shown while a pen hovers and while it cuts, like the editor's.
            var eraserAt by remember(file, page, tool) { mutableStateOf<MusicPoint?>(null) }
            var eraserRadius by remember(file, page, tool) { mutableFloatStateOf(0f) }
            val drawing = !performance && (MusicInk.isFreehand(tool.name) || MusicInk.isShape(tool.name))
            val erasing = !performance && tool == Tool.ERASER
            val lassoing = !performance && tool == Tool.LASSO
            // Page fractions of a pointer position inside the sheet. The sheet's pixel size is
            // known here, so the gesture handlers never need the pointer scope's own `size`.
            val pageWidthPx = w.value * density
            val pageHeightPx = h.value * density
            fun fraction(p: Offset) = MusicPoint((p.x / pageWidthPx).coerceIn(0f, 1f), (p.y / pageHeightPx).coerceIn(0f, 1f))
            val gesture = when {
                performance -> Modifier.pointerInput(file, page) {
                    // Stage taps must not wait for the double-tap timeout or start a pan.
                    detectTapGestures(onTap = { turn(it.x >= size.width / 2f) })
                }
                drawing || erasing || lassoing -> Modifier.pointerInput(file, page, tool, options, eraserWholeStroke, selectedStrokes, selectedTexts, finger, snapEnabled, scribbleToErase, scribbleSensitivity, eraserPressure) {
                    val reach = (options.width / 2000f).coerceAtLeast(0.006f)
                    var movedFrom = Offset.Zero
                    var moved = false
                    var recorded = false
                    // A drag that edits frame by frame keeps its own working copy: a frame's writes are
                    // not guaranteed to be visible back through recomposition before the next pointer
                    // event arrives, so accumulating from the live page would drop part of the drag.
                    var working: List<MusicStroke>? = null
                    var workingTexts: List<MusicText>? = null
                    detectInkDrag(
                        // "Draw with a finger" off: only the pen writes; a finger falls through to pan and zoom.
                        accept = { finger || it.type == PointerType.Stylus || it.type == PointerType.Eraser },
                        onDragStart = { start ->
                            recorded = false
                            working = null
                            workingTexts = null
                            // A lasso drag that begins inside the selection moves it instead.
                            moved = lassoing && (selectedStrokes.isNotEmpty() || selectedTexts.isNotEmpty()) &&
                                inSelection(fraction(start), liveStrokes, liveTexts, selectedStrokes, selectedTexts)
                            movedFrom = start
                            points = if (lassoing) emptyList() else listOf(fraction(start))
                        },
                        onDrag = { change ->
                            val at = fraction(change.position)
                            when {
                                lassoing && moved -> {
                                    val delta = change.position - movedFrom
                                    val dx = delta.x / pageWidthPx
                                    val dy = delta.y / pageHeightPx
                                    working = (working ?: liveStrokes).mapIndexed { i, s -> if (i in selectedStrokes) MusicInk.translated(s, dx, dy) else s }
                                    workingTexts = (workingTexts ?: liveTexts).mapIndexed { i, t -> if (i in selectedTexts) MusicInk.translated(t, dx, dy) else t }
                                    commit(working!!, workingTexts!!, !recorded)
                                    recorded = true
                                    movedFrom = change.position
                                }
                                lassoing -> points = points + at
                                erasing -> {
                                    val stylusNow = change.type == PointerType.Stylus || change.type == PointerType.Eraser
                                    val r = if (eraserPressure && stylusNow) reach * InkGeometry.eraserScale(change.pressure) else reach
                                    eraserAt = at; eraserRadius = r
                                    working = MusicInk.erased(working ?: liveStrokes, at, r, eraserWholeStroke)
                                    commit(working!!, liveTexts, !recorded)
                                    recorded = true
                                }
                                // A shape keeps its two drag corners, exactly as the editor stores one.
                                else -> points = if (MusicInk.isShape(tool.name)) {
                                    val start = points.firstOrNull() ?: at
                                    listOf(start, if (snapEnabled && tool == Tool.LINE) snapLine(start, at, pageWidthPx, pageHeightPx) else at)
                                } else points + at
                            }
                        },
                        onDragEnd = {
                            when {
                                lassoing -> if (!moved && points.size >= 3) {
                                    select(liveStrokes.indices.filter { MusicInk.selects(points, liveStrokes[it]) }.toSet(),
                                        liveTexts.indices.filter { MusicInk.selects(points, liveTexts[it]) }.toSet())
                                }
                                erasing -> { working = null; workingTexts = null; eraserAt = null; eraserDone() }
                                points.isNotEmpty() && scribbleToErase && MusicInk.isFreehand(tool.name) &&
                                    scribbled(liveStrokes, points, pageWidthPx, pageHeightPx, scribbleSensitivity)?.also { commit(it, liveTexts, true) } != null -> Unit
                                points.isNotEmpty() -> commit(liveStrokes + MusicStroke(page, points, tool.name, options.color, options.width, options.opacity, options.style.name), liveTexts, true)
                            }
                            points = emptyList()
                        },
                        onDragCancel = { points = emptyList(); working = null; workingTexts = null; eraserAt = null })
                }
                else -> Modifier.pointerInput(file, page, tool) {
                    detectTapGestures(
                        onDoubleTap = { scale = 1f; offset = Offset.Zero },
                        onTap = { position ->
                            val at = fraction(position)
                            val hit = if (tool == Tool.TEXT) MusicInk.textAt(liveTexts, page, at) else null
                            when {
                                tool == Tool.TEXT -> editLabel(hit?.let { liveTexts[it] } ?: MusicText(page, at.x, at.y, ""))
                                scale == 1f -> if (selectedStrokes.isNotEmpty() || selectedTexts.isNotEmpty()) select(emptySet(), emptySet()) else turn(position.x >= size.width / 2f)
                            }
                        })
                }
            }
            // The sheet is a rounded page on the desk, like an editor page: rounded corners keep the
            // score from reading as a floating poster.
            Box(Modifier.size(w, h).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .clip(FolioShapes.medium)
                .background(MusicPaper)
                // Palm rejection runs first (Initial pass): a finger landing just after the pen is a resting
                // hand, so it is consumed before any gesture below can turn the page or draw.
                .pointerInput(palmRejectMs, multiTouchUndo) {
                    val palms = HashSet<androidx.compose.ui.input.pointer.PointerId>()
                    // Two fingers tapped together undo, three redo — never a pen or a resting palm.
                    val chord = TouchChord(viewConfiguration.touchSlop * 1.2f)
                    var chordOpen = false
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val now = SystemClock.uptimeMillis()
                            for (change in event.changes) {
                                if (change.type == PointerType.Stylus || change.type == PointerType.Eraser) { lastStylusAt = now; chord.reset(); chordOpen = false }
                                else if (palmRejectMs > 0 && change.pressed && !change.previousPressed && now - lastStylusAt < palmRejectMs) palms += change.id
                                if (change.id in palms) { change.consume(); if (!change.pressed) palms -= change.id }
                            }
                            if (!multiTouchUndo) continue
                            val touches = event.changes.filter { it.type == PointerType.Touch && it.id !in palms }
                            for (change in touches) {
                                val id = change.id.value.toInt()
                                val t = change.uptimeMillis
                                if (change.pressed && !change.previousPressed) {
                                    if (!chordOpen) { chord.down(id, change.position.x, change.position.y, t); chordOpen = true }
                                    else chord.join(id, change.position.x, change.position.y, t)
                                } else if (change.pressed) chord.move(id, change.position.x, change.position.y)
                            }
                            if (chordOpen && event.changes.none { it.pressed }) {
                                chordOpen = false
                                when (chord.finish(event.changes.first().uptimeMillis)) { 2 -> undoNow(); 3 -> redoNow() }
                            }
                        }
                    }
                }
                // A hovering pen previews the eraser's ring before the tip lands; leaving the glass clears it.
                .then(if (erasing) Modifier.pointerInput(options.width, eraserPressure, pageWidthPx, pageHeightPx) {
                    val reach = (options.width / 2000f).coerceAtLeast(0.006f)
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pen = event.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser } ?: continue
                            if (event.type == PointerEventType.Exit || pen.pressed && event.type != PointerEventType.Move) { eraserAt = null; continue }
                            if (!pen.pressed) {
                                eraserAt = fraction(pen.position)
                                eraserRadius = if (eraserPressure) reach * InkGeometry.eraserScale(pen.pressure) else reach
                            }
                        }
                    }
                } else Modifier)
                // Pinch and pan are always available; a drawing gesture below consumes what it draws first.
                .then(if (performance) Modifier else Modifier.transformable(transform))
                .then(gesture)) {
                Image(bitmap.asImageBitmap(), "Score page ${page + 1}", Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    fun path(line: List<MusicPoint>): Path = Path().apply {
                        moveTo(line[0].x * size.width, line[0].y * size.height)
                        line.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                    }
                    fun draw(line: List<MusicPoint>, stroke: MusicStroke, highlight: Boolean) {
                        if (line.isEmpty()) return
                        val ink = Color(stroke.color).copy(alpha = stroke.opacity)
                        // Widths are in the editor's scale, where a page is 1000 units wide.
                        val thickness = (stroke.width / 1000f * size.width).coerceAtLeast(1f)
                        if (line.size == 1) drawCircle(ink, radius = thickness / 2f, center = Offset(line[0].x * size.width, line[0].y * size.height))
                        else {
                            val effect = when (stroke.style) {
                                StrokeStyle.DASHED.name -> PathEffect.dashPathEffect(floatArrayOf(thickness * 3f, thickness * 2f))
                                StrokeStyle.DOTTED.name -> PathEffect.dashPathEffect(floatArrayOf(thickness * 0.1f, thickness * 2.4f), 0f)
                                else -> null
                            }
                            drawPath(path(line), ink, style = Stroke(width = if (highlight) thickness + 2f else thickness, cap = if (highlight) StrokeCap.Square else StrokeCap.Round,
                                join = StrokeJoin.Round, pathEffect = effect))
                        }
                    }
                    strokes.forEachIndexed { index, stroke ->
                        draw(MusicInk.outline(stroke), stroke, index in selectedStrokes)
                    }
                    eraserAt?.takeIf { erasing }?.let { at ->
                        val c = Offset(at.x * size.width, at.y * size.height)
                        val r = eraserRadius * size.width
                        drawCircle(Color(0x222F6FBA), r, c)
                        drawCircle(Color(0xCC2F6FBA), r, c, style = Stroke(width = 1.5f))
                    }
                    // The stroke in hand is drawn in the tool's own colour as it grows.
                    if (points.isNotEmpty()) draw(
                        if (MusicInk.isShape(tool.name)) MusicInk.shapePoints(tool.name, points) else points,
                        MusicStroke(page, points, tool.name, options.color, options.width, options.opacity, options.style.name), false)
                }
                // (labels follow below)
                // Labels sit in the page's own coordinates, so they zoom and pan with the sheet.
                texts.forEachIndexed { index, label ->
                    if (label.text.isNotBlank()) Box(Modifier.graphicsLayer {
                        translationX = label.x * w.value * density
                        translationY = label.y * h.value * density
                    }) {
                        Surface(shape = FolioShapes.small, color = if (index in selectedTexts) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                            Text(label.text, modifier = Modifier.padding(1.dp), color = Color(label.color),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (label.size / 1000f * w.value).sp), maxLines = 1)
                        }
                    }
                }
            }
            if (shapeMeasurements && drawing && MusicInk.isShape(tool.name) && points.size >= 2) {
                val a = points.first(); val b = points.last()
                // Page units match the editor's: a page is 1000 wide, whatever the screen.
                val dx = (b.x - a.x) * 1000f
                val dy = (b.y - a.y) * 1000f * pageHeightPx / pageWidthPx
                val label = when (tool) {
                    Tool.LINE -> String.format(java.util.Locale.ROOT, "%.0f pt  %.0f°", kotlin.math.hypot(dx, dy),
                        (Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360) % 360)
                    Tool.ELLIPSE -> String.format(java.util.Locale.ROOT, "⌀ %.0f  r %.0f", maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)), (kotlin.math.abs(dx) + kotlin.math.abs(dy)) / 4f)
                    else -> String.format(java.util.Locale.ROOT, "%.0f × %.0f", kotlin.math.abs(dx), kotlin.math.abs(dy))
                }
                // The tooltip's anchor is in this box's pixels: the page is centred, then zoomed about its centre.
                val boxW = maxWidth.value * density; val boxH = maxHeight.value * density
                val anchorX = boxW / 2f + offset.x + ((a.x + b.x) / 2f - 0.5f) * pageWidthPx * scale
                val anchorY = boxH / 2f + offset.y + ((a.y + b.y) / 2f - 0.5f) * pageHeightPx * scale
                ShapeMeasurementTooltip(remember(label, anchorX, anchorY) { mutableStateOf<ShapeMeasurement?>(ShapeMeasurement(label, anchorX, anchorY)) })
            }
        } else if (result.error != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Page ${page + 1} couldn't be drawn", style = MaterialTheme.typography.bodyMedium)
            TextButton({ retry++ }, shapes = ButtonDefaults.shapes()) { Text("Retry") }
        } else LoadingIndicator()
    }
}

/** A line's far end snapped to 15° steps, measured on screen so the angle matches what the eye sees. */
private fun snapLine(start: MusicPoint, end: MusicPoint, widthPx: Float, heightPx: Float): MusicPoint {
    val snapped = InkGeometry.snapAngle(InkPoint(start.x * widthPx, start.y * heightPx), InkPoint(end.x * widthPx, end.y * heightPx), 15f)
    return MusicPoint((snapped.x / widthPx).coerceIn(0f, 1f), (snapped.y / heightPx).coerceIn(0f, 1f))
}

/**
 * Scribble-to-erase: a fast back-and-forth over existing marks removes them rather than leaving a
 * scrawl. Runs the editor's own detector on the score's ink in page units, and returns the surviving
 * strokes, or null when the drawn path was not a scribble over anything (so it is kept as ink).
 */
private fun scribbled(strokes: List<MusicStroke>, drawn: List<MusicPoint>, widthPx: Float, heightPx: Float, sensitivity: Float): List<MusicStroke>? {
    val toolFor = { name: String -> runCatching { Tool.valueOf(name) }.getOrDefault(Tool.PEN) }
    val k = 1000f / widthPx
    fun editor(s: MusicStroke) = com.folio.notes.Stroke(toolFor(s.tool), s.color, s.width,
        s.points.map { InkPoint(it.x * 1000f, it.y * 1000f * heightPx / widthPx) }, s.opacity,
        runCatching { StrokeStyle.valueOf(s.style) }.getOrDefault(StrokeStyle.SOLID))
    val mapped = strokes.map(::editor)
    val scribble = com.folio.notes.Stroke(Tool.PEN, 0, 1f, drawn.map { InkPoint(it.x * 1000f, it.y * 1000f * heightPx / widthPx) })
    val kept = InkGeometry.scribbleErase(mapped, scribble, 2f, sensitivity)
    if (kept === mapped) return null
    val survivors = java.util.IdentityHashMap<com.folio.notes.Stroke, Boolean>().also { map -> kept.forEach { map[it] = true } }
    return strokes.filterIndexed { i, _ -> survivors.containsKey(mapped[i]) }
}

/**
 * A one-pointer drag like `detectDragGestures`, except [accept] sees the pointer first: a rejected
 * pointer (a finger while "Draw with a finger" is off) is left untouched so pan and zoom still get it.
 */
private suspend fun PointerInputScope.detectInkDrag(
    accept: (PointerInputChange) -> Boolean,
    onDragStart: (Offset) -> Unit, onDrag: (PointerInputChange) -> Unit,
    onDragEnd: () -> Unit, onDragCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        if (!accept(down)) return@awaitEachGesture
        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        onDragStart(down.position)
        onDrag(slop)
        val finished = drag(down.id) { change -> change.consume(); onDrag(change) }
        if (finished) onDragEnd() else onDragCancel()
    }
}

/**
 * True when a pointer went down on something the lasso already holds, so the drag moves the
 * selection instead of drawing a new loop. A small margin means a near miss still counts.
 */
private fun inSelection(at: MusicPoint, strokes: List<MusicStroke>, texts: List<MusicText>,
    selectedStrokes: Set<Int>, selectedTexts: Set<Int>): Boolean {
    val pad = 0.03f
    return selectedStrokes.any { index ->
        strokes.getOrNull(index)?.let { stroke -> MusicInk.outline(stroke).any { MusicInk.within(it, at, pad) } } ?: false
    } || selectedTexts.any { index ->
        texts.getOrNull(index)?.let { MusicInk.hits(it, at, pad) } ?: false
    }
}
