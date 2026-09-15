@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.graphics.Bitmap
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private fun paperLabel(p: Paper): String = when (p) {
    Paper.PLAIN -> "Plain"
    Paper.RULED -> "Ruled"
    Paper.DOTS -> "Dots"
    Paper.GRID -> "Grid"
    Paper.MATH_GRID -> "Maths grid"
    Paper.GRAPH -> "Graph (with axes)"
    Paper.MC_SHEET -> "Multiple choice"
    Paper.TIAN_GRID -> "Tian grid (田字格)"
    Paper.MI_GRID -> "Mi grid (米字格)"
}

@Composable fun EditorScreen(state: FolioState, model: FolioViewModel, finger: Boolean, toolbarPosition: ToolbarPosition, haptics: Boolean, shapeRecognition: Boolean, onSettings: () -> Unit, onExport: () -> Unit) {
    val note = state.active ?: return
    val page = state.page ?: return
    val context = LocalContext.current
    val session = state.tabs.find { it.notebookId == note.id }
    var tool by rememberSaveable { mutableStateOf(session?.tool ?: Tool.PEN) }
    var previousTool by rememberSaveable { mutableStateOf(Tool.PEN) }
    var palette by rememberSaveable { mutableStateOf(false) }
    val prefs = context.getSharedPreferences("ink-tools", 0)
    val appPrefs = context.getSharedPreferences("preferences", 0)
    val quick = remember(prefs) { QuickColorsState(prefs) }
    val toolPresets = remember(prefs) { ToolPresetState(prefs) }
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
    fun setScribbleToErase(v: Boolean) { scribbleToErase = v; appPrefs.edit().putBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, v).apply() }
    var eraserWholeStroke by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, false)) }
    fun setEraserWholeStroke(v: Boolean) { eraserWholeStroke = v; appPrefs.edit().putBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, v).apply() }
    var shapeMeasurements by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, true)) }
    fun setShapeMeasurements(v: Boolean) { shapeMeasurements = v; appPrefs.edit().putBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, v).apply() }
    var multiTouchUndo by remember { mutableStateOf(appPrefs.getBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, true)) }
    fun setMultiTouchUndo(v: Boolean) { multiTouchUndo = v; appPrefs.edit().putBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, v).apply() }
    // Observe external pref changes (e.g. from ToolOptionsPanel): re-read when screen re-enters foreground
    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                EditorQuickPrefs.ERASER_SINGLE_STROKE -> { eraserSingleStroke = appPrefs.getBoolean(key, false) }
                EditorQuickPrefs.ERASER_PRESSURE -> eraserPressure = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.SCRIBBLE_TO_ERASE -> scribbleToErase = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.ERASER_WHOLE_STROKE -> eraserWholeStroke = appPrefs.getBoolean(key, false)
                EditorQuickPrefs.SHAPE_MEASUREMENTS -> shapeMeasurements = appPrefs.getBoolean(key, true)
                EditorQuickPrefs.MULTI_TOUCH_UNDO -> multiTouchUndo = appPrefs.getBoolean(key, true)
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
    fun applyStylusShortcut(action: StylusShortcut) {
        val effect = StylusShortcuts.effect(action, tool, previousTool)
        if (effect != StylusShortcutEffect.None) penHaptics?.pulse()
        when (effect) {
            is StylusShortcutEffect.SwitchTool -> selectTool(effect.tool)
            StylusShortcutEffect.OpenPalette -> palette = true
            StylusShortcutEffect.Undo -> model.undo()
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
    val selected = selection?.takeIf { it.first == page.id }?.second ?: CanvasSelection()
    LaunchedEffect(tool, page.id) { selection = null }
    // The bound canvas, so toolbar actions can drive it directly (select-all fallback, deselect).
    var activeInkView by remember { mutableStateOf<InkView?>(null) }
    // Text defaults live with the app, not the notebook, so a new label keeps the last look.
    var textSize by rememberSaveable { mutableFloatStateOf(appPrefs.getFloat("text.size", 26f)) }
    var textColor by rememberSaveable { mutableIntStateOf(appPrefs.getInt("text.color", 0xFF303431.toInt())) }
    var textBold by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.bold", false)) }
    var textItalic by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.italic", false)) }
    var textAlign by rememberSaveable { mutableStateOf(try { TextAlignMode.valueOf(appPrefs.getString("text.align", "LEFT") ?: "LEFT") } catch (_: Exception) { TextAlignMode.LEFT }) }
    var textUnderline by rememberSaveable { mutableStateOf(appPrefs.getBoolean("text.underline", false)) }
    var textEditor by remember { mutableStateOf<TextBox?>(null) }
    var textEditorNew by remember { mutableStateOf(false) }
    // Notebook-wide typed-text search and reusable diagram elements.
    var noteSearchOpen by remember { mutableStateOf(false) }
    var noteQuery by remember { mutableStateOf("") }
    var stampPicker by remember { mutableStateOf(false) }
    // Holds the selection being restyled, so the sheet always edits from the original strokes.
    var restyleSelection by remember { mutableStateOf<List<Stroke>?>(null) }
    // The picture tapped with the hand tool, so the editor can offer delete and layering.
    var selectedImage by remember { mutableStateOf<Pair<String, PageImage>?>(null) }
    LaunchedEffect(page.id) { if (selectedImage?.first != page.id) selectedImage = null }
    fun rememberTextLook(box: TextBox) {
        textSize = box.size; textColor = box.color; textBold = box.bold; textItalic = box.italic
        textAlign = box.align; textUnderline = box.underline
        appPrefs.edit().putFloat("text.size", box.size).putInt("text.color", box.color)
            .putBoolean("text.bold", box.bold).putBoolean("text.italic", box.italic)
            .putString("text.align", box.align.name).putBoolean("text.underline", box.underline).apply()
    }
    /** A toolbar quick colour: boxes created after this start with it. */
    fun setTextColor(value: Int) {
        textColor = value
        appPrefs.edit().putInt("text.color", value).apply()
    }
    /** Applies a whole-selection edit to ink, text and pictures together, then drops the selection. */
    fun transformSelection(
        strokeTransform: (List<Stroke>, InkPoint) -> List<Stroke>,
        textTransform: (List<TextBox>, InkPoint) -> List<TextBox>,
        imageTransform: (List<PageImage>, InkPoint) -> List<PageImage>
    ) {
        val current = selected
        if (current.isEmpty()) return
        val center = InkGeometry.selectionCenter(
            current.strokes, current.texts, current.images, { InkRenderer.textHeight(it) }) ?: return
        // New objects replace the originals, so the canvas drops its own copy of the selection
        // when the edited page arrives; the bar's copy is dropped here.
        model.updateContent(
            page.id,
            page.strokes.filterNot { it in current.strokes } + strokeTransform(current.strokes, center),
            page.texts.filterNot { box -> current.texts.any { it.id == box.id } } + textTransform(current.texts, center),
            page.images.filterNot { image -> current.images.any { it.id == image.id } } + imageTransform(current.images, center)
        )
        selection = null
    }
    /** A tap on bare page drops a fresh text box where the finger landed, clear of the right edge. */
    fun placeTextBox(at: InkPoint) {
        val width = if (page.infinite) TextBox.DEFAULT_WIDTH else (page.width - at.x - 16f).coerceIn(TextBox.MIN_WIDTH, TextBox.DEFAULT_WIDTH)
        textEditor = TextBox(x = at.x, y = at.y, width = width, text = "", size = textSize, color = textColor, bold = textBold, italic = textItalic, align = textAlign, underline = textUnderline)
        textEditorNew = true
    }
    var documentZoom by rememberSaveable(note.id) { mutableFloatStateOf(session?.viewport?.zoom ?: 1f) }
    var documentPan by rememberSaveable(note.id) { mutableFloatStateOf(session?.viewport?.pan ?: 0f) }
    var pageBrowser by remember { mutableStateOf(false) }
    var pageQuery by rememberSaveable(note.id) { mutableStateOf("") }
    var pageFilter by rememberSaveable(note.id) { mutableStateOf(PageFilter.ALL) }
    var namedPage by remember { mutableStateOf<NotePage?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var movingPage by remember { mutableStateOf<String?>(null) }
    var destinationPage by remember { mutableStateOf("") }
    var deletingPage by remember { mutableStateOf<String?>(null) }
    var pageNumber by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf(note.title) }
    var more by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var paperMenu by remember { mutableStateOf(false) }
    var timerPanel by remember { mutableStateOf(false) }
    var examPanel by remember { mutableStateOf(false) }
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
    val pages = rememberLazyListState(initialFirstVisibleItemIndex = state.pageIndex, initialFirstVisibleItemScrollOffset = session?.viewport?.scrollOffset ?: 0)
    var savedCanvas by remember(page.id) { mutableStateOf(session?.viewport ?: WorkspaceViewport()) }
    LaunchedEffect(note.id, pages) {
        snapshotFlow { WorkspaceViewport(documentZoom, documentPan, pages.firstVisibleItemScrollOffset,
            savedCanvas.canvasX, savedCanvas.canvasY, savedCanvas.canvasZoom) to tool }
            .distinctUntilChanged().collect { (viewport, selectedTool) -> model.updateTabViewport(note.id, viewport, selectedTool) }
    }
    val scope = rememberCoroutineScope()
    val motionDensity = LocalDensity.current.density
    val motion = remember(pages, note.id, motionDensity) { DocumentMotion(pages::dispatchRawDelta, scope, motionDensity) }
    DisposableEffect(motion) { onDispose { motion.reset() } }
    var canvasViewport by remember(page.id) { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var canvasReset by remember { mutableIntStateOf(0) }
    fun resetZoom() {
        canvasReset++
        motion.reset()
        pages.requestScrollToItem(pages.firstVisibleItemIndex, (pages.firstVisibleItemScrollOffset / documentZoom).roundToInt())
        documentZoom = 1f
        documentPan = 0f
    }
    fun jumpTo(index: Int) { motion.reset(); model.selectPage(index); scope.launch { pages.scrollToItem(index) } }
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
        model.addPage()
        if (page.infinite) return
        scope.launch {
            snapshotFlow { pages.layoutInfo.totalItemsCount }.first { it > index + 1 }
            pages.scrollToItem(index)
            model.selectPage(index)
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
    LaunchedEffect(note.id, page.infinite) {
        if (page.infinite) return@LaunchedEffect
        snapshotFlow { pages.firstVisibleItemIndex }.distinctUntilChanged().collect { model.selectPage(it) }
    }
    // The pages beside the open one are read before they are scrolled to, so previous/next and
    // the fast-scroll thumb land on ink instead of a spinner. Loading is deduplicated in the
    // ViewModel, so asking twice costs nothing.
    LaunchedEffect(note.id, state.pageIndex) {
        note.pages.getOrNull(state.pageIndex - 1)?.let { model.loadPage(it.id) }
        note.pages.getOrNull(state.pageIndex + 1)?.let { model.loadPage(it.id) }
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) when (event.key) {
            Key.Z -> { if (event.isShiftPressed) model.redo() else model.undo(); true }
            Key.Y -> { model.redo(); true }
            else -> false
        } else false
    }) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            val density = LocalDensity.current
            val viewportWidth = with(density) { maxWidth.toPx() }
            val baseWidth = (maxWidth - 32.dp).coerceAtMost(900.dp)
            val baseWidthPx = with(density) { baseWidth.toPx() }
            val stripWidth = 26.dp
            val stripInset = 2.dp
            val stackedToolbar = toolbarPosition == ToolbarPosition.TOP && maxWidth < 840.dp
            val toolbarTopInset = if (stackedToolbar) 190.dp else 130.dp
            val trackTop = if (toolbarPosition == ToolbarPosition.TOP) toolbarTopInset else 76.dp
            val trackBottom = 20.dp
            val stripWidthPx = with(density) { stripWidth.toPx() }
            val stripInsetPx = with(density) { stripInset.toPx() }
            val trackTopPx = with(density) { trackTop.toPx() }
            val trackBottomPx = with(density) { trackBottom.toPx() }
            val minimumThumbPx = with(density) { 24.dp.toPx() }
            LaunchedEffect(viewportWidth, baseWidthPx) {
                documentPan = DocumentViewport.clampPan(documentPan, baseWidthPx * documentZoom, viewportWidth)
            }
            fun panBy(dx: Float, dy: Float) {
                documentPan = DocumentViewport.clampPan(documentPan + dx, baseWidthPx * documentZoom, viewportWidth)
                motion.drag(dy)
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
            if (page.infinite) {
                EditorPage(note.id, page, model, tool, options, finger, snapEnabled, shapeRecognition, true,
                    onActive = {}, onPan = { _, _ -> }, onPanEnd = {},
                    onSelection = { selection = page.id to it },                    onTextEdit = { textEditor = it; textEditorNew = false }, onTextCreate = ::placeTextBox,
                    onLoad = { model.loadPage(page.id) }, fullscreen = true, canvasReset = canvasReset,
                    onCanvasZoom = { documentZoom = it }, onCanvasViewport = { canvasViewport = it },
                    initialViewport = session?.viewport, onCameraChanged = { savedCanvas = it },
                    selectedImageId = selectedImage?.takeIf { it.first == page.id }?.second?.id,
                    onImageSelected = { image -> selectedImage = image?.let { page.id to it } },
                    pdfLinks = pdfLinks, onPdfLink = ::openPdfLink,
                    eraserPressureEnabled = eraserPressure, scribbleToErase = scribbleToErase,
                    eraserWholeStroke = eraserWholeStroke, shapeMeasurements = shapeMeasurements, multiTouchUndo = multiTouchUndo,
                    onEraserFinished = ::finishSingleStrokeEraser, onUndo = model::undo, onRedo = model::redo,
                    onSelectAllView = { activeInkView = it }, inkStyle = options.style)
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
                    val geometry = fastScrollGeometry(pages, span, minimumThumbPx)
                    val onThumb = geometry != null &&
                        down.position.x in (size.width - stripInsetPx - stripWidthPx)..(size.width - stripInsetPx) &&
                        down.position.y in (trackTopPx + geometry.top)..(trackTopPx + geometry.top + geometry.height)
                    if (onThumb) {
                        motion.reset()
                        down.consume()
                        val travelSpan = (span - geometry.height).coerceAtLeast(1f)
                        val startProgress = DocumentViewport.scrollProgress(pages.firstVisibleItemIndex,
                            pages.firstVisibleItemScrollOffset, pages.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0,
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
                                documentPan = DocumentViewport.zoomPan(documentPan, centroid.x, viewportWidth, baseWidthPx * newZoom, ratio)
                                documentZoom = newZoom
                                val offset = DocumentViewport.zoomScroll(pages.firstVisibleItemScrollOffset, centroid.y - pages.layoutInfo.beforeContentPadding, ratio)
                                if (kotlin.math.abs(factor - 1f) > .001f) {
                                    motion.reset()
                                    pages.requestScrollToItem(pages.firstVisibleItemIndex, offset - delta.y.roundToInt())
                                } else motion.drag(delta.y)
                                documentPan = DocumentViewport.clampPan(documentPan + delta.x, baseWidthPx * newZoom, viewportWidth)
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
                        } while (event.changes.any { it.pressed })
                        if (transforming) motion.release(velocity.calculateVelocity().y)
                        else if (motion.stretch != 0f) motion.release(0f)
                    }
                }
            }, contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = pages,
                    modifier = Modifier.requiredWidth(baseWidth * documentZoom).fillMaxHeight().offset { IntOffset(documentPan.roundToInt(), 0) }.graphicsLayer { translationY = motion.stretch },
                    contentPadding = PaddingValues(top = if (toolbarPosition == ToolbarPosition.TOP) toolbarTopInset else 64.dp, bottom = if (toolbarPosition == ToolbarPosition.BOTTOM) 142.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp * documentZoom), horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(note.pages, key = { _, item -> item.id }) { index, item ->
                        EditorPage(note.id, item, model, tool, options, finger, snapEnabled, shapeRecognition, active = item.id == page.id,
                            onActive = { model.selectPage(index) }, onPan = ::panBy, onPanEnd = motion::release,
                            onSelection = { picked -> if (item.id == page.id) selection = item.id to picked },
                            onTextEdit = { box -> textEditor = box; textEditorNew = false },
                            onTextCreate = ::placeTextBox,
                            onLoad = { model.loadPage(item.id) },
                            selectedImageId = selectedImage?.takeIf { it.first == item.id }?.second?.id,
                            onImageSelected = { image ->
                                selectedImage = image?.let { item.id to it }
                            },
                            pdfLinks = pdfLinks, onPdfLink = ::openPdfLink,
                            eraserPressureEnabled = eraserPressure, scribbleToErase = scribbleToErase,
                            eraserWholeStroke = eraserWholeStroke, shapeMeasurements = shapeMeasurements, multiTouchUndo = multiTouchUndo,
                            onEraserFinished = ::finishSingleStrokeEraser, onUndo = model::undo, onRedo = model::redo,
                            onSelectAllView = { if (item.id == page.id) activeInkView = it }, inkStyle = options.style)
                    }
                    item { OutlinedButton({ addPage() }, modifier = Modifier.guardUiTouches()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add page — ${paperLabel(page.paper)}") } }
                }
            }
            if (!page.infinite) Box(Modifier.align(Alignment.CenterEnd).padding(end = stripInset).padding(top = trackTop, bottom = trackBottom).width(110.dp).fillMaxHeight()) {
                FastScrollTrack(pages, note.pages.size, scrubbing, Modifier.fillMaxSize())
            }
            if (page.infinite && page.loaded) {
                CanvasNavigator(page, canvasViewport,
                    onNavigate = { x, y -> activeInkView?.navigateCanvas(x, y) },
                    onFit = { activeInkView?.fitCanvas(it) }, onHome = ::resetZoom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp,
                        bottom = if (toolbarPosition == ToolbarPosition.BOTTOM) 152.dp else 12.dp))
            }
            val toolbarAlignment = when (toolbarPosition) {
                ToolbarPosition.TOP -> Alignment.TopCenter
                ToolbarPosition.BOTTOM -> Alignment.BottomCenter
            }
            val toolbarUpTop = toolbarPosition == ToolbarPosition.TOP
            val chromeHeight = 52.dp
            if (toolbarUpTop && !stackedToolbar) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    EditorChromeChip(Modifier.height(chromeHeight)) {
                        IconButton(model::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
                        ExamTimerChip(state.timer, chromeHeight) { timerPanel = true }
                    }
                    Box(
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        FloatingInkToolbar(
                            modifier = Modifier,
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
                            onAxes = model::insertAxes,
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
                            toolPresetsState = toolPresets
                        )
                    }
                    EditorChromeChip(Modifier.height(chromeHeight)) {
                        IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Editor settings") }
                        IconButton(onExport) { Icon(Icons.Rounded.IosShare, "Export or share") }
                        Box {
                            IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Page options") }
                            PageOptionsMenu(more, { more = false }, page, snapEnabled, state.saveFailed, state.clipboard.isNotEmpty(),
                                onResetZoom = ::resetZoom, onAxes = model::insertAxes, onPaper = { paperMenu = true },
                                onSnap = { setSnap(!snapEnabled) }, onPaste = { model.pasteClipboard() },
                                onClear = { clear = true }, onRetry = model::retrySave,
                                onRedo = model::toggleRedoFlag, onExam = { examPanel = true }, onTimer = { timerPanel = true },
                                onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                                onSearchPdf = { pdfQuery = state.pdfSearch.query; pdfSearchOpen = true },
                                onContents = { pdfContentsOpen = true; loadOutline() },
                                onSearchNotes = { noteQuery = ""; noteSearchOpen = true },
                                onInsertElement = { stampPicker = true },
                                onOrganize = { pageBrowser = true },
                                onBookmark = { model.togglePageBookmark(page.id) },
                                onNamePage = { namedPage = page; pageTitle = page.title })
                        }
                    }
                }
            } else {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).height(chromeHeight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorChromeChip(Modifier.height(chromeHeight)) {
                        IconButton(model::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
                        ExamTimerChip(state.timer, chromeHeight) { timerPanel = true }
                    }
                    Spacer(Modifier.weight(1f))
                    EditorChromeChip(Modifier.height(chromeHeight)) {
                        IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Editor settings") }
                        IconButton(onExport) { Icon(Icons.Rounded.IosShare, "Export or share") }
                        Box {
                            IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Page options") }
                            PageOptionsMenu(more, { more = false }, page, snapEnabled, state.saveFailed, state.clipboard.isNotEmpty(),
                                onResetZoom = ::resetZoom, onAxes = model::insertAxes, onPaper = { paperMenu = true },
                                onSnap = { setSnap(!snapEnabled) }, onPaste = { model.pasteClipboard() },
                                onClear = { clear = true }, onRetry = model::retrySave,
                                onRedo = model::toggleRedoFlag, onExam = { examPanel = true }, onTimer = { timerPanel = true },
                                onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
                                onSearchPdf = { pdfQuery = state.pdfSearch.query; pdfSearchOpen = true },
                                onContents = { pdfContentsOpen = true; loadOutline() },
                                onSearchNotes = { noteQuery = ""; noteSearchOpen = true },
                                onInsertElement = { stampPicker = true },
                                onOrganize = { pageBrowser = true },
                                onBookmark = { model.togglePageBookmark(page.id) },
                                onNamePage = { namedPage = page; pageTitle = page.title })
                        }
                    }
                }
            }
            if (!toolbarUpTop || stackedToolbar) FloatingInkToolbar(
                Modifier.align(toolbarAlignment).padding(start = 12.dp, end = 12.dp, top = if (stackedToolbar) 68.dp else 76.dp, bottom = 12.dp),
                tool, { selectTool(it) }, options, ::changeOptions, quick, state.canUndo, state.canRedo, model::undo, model::redo, palette, snapEnabled, ::setSnap, model::insertAxes, { palette = it },
                eraserSingleStroke = eraserSingleStroke, onEraserSingleStroke = ::setEraserSingleStroke,
                scribbleToErase = scribbleToErase, onScribbleToErase = ::setScribbleToErase,
                eraserPressureEnabled = eraserPressure, onEraserPressure = ::setEraserPressure,
                eraserWholeStroke = eraserWholeStroke, onEraserWholeStroke = ::setEraserWholeStroke,
                shapeMeasurements = shapeMeasurements, onShapeMeasurements = ::setShapeMeasurements,
                multiTouchUndo = multiTouchUndo, onMultiTouchUndo = ::setMultiTouchUndo,
                onSelectAll = ::selectAllInk,
                textColor = textColor, onTextColor = ::setTextColor,
                presets = toolPresets.presets, onApplyPreset = ::applyPreset,
                toolPresetsState = toolPresets
            )
        }
        EditorBottomBar(
            state = state, zoomPercent = (documentZoom * 100).roundToInt(), selectedCount = selected.size,
            canRestyle = selected.strokes.isNotEmpty(),
            onRename = { renameTitle = note.title; rename = true }, onStar = { model.star(note) }, onRetry = model::retrySave,
            onPages = { pageBrowser = true }, onPrevious = { jumpTo(state.pageIndex - 1) },
            onNext = { jumpTo(state.pageIndex + 1) }, onAdd = ::addPage, onFit = ::resetZoom,
            onDeselect = { activeInkView?.clearSelection(); selection = null; selectTool(Tool.PEN) },
            onCopySelection = { model.copyToClipboard(selected) },
            onCutSelection = { model.cutSelection(selected); selection = null },
            onDuplicateSelection = {
                model.duplicateSelection(selected)
                // The originals stay on the page, so the canvas keeps its own copy of the
                // selection after the edited page arrives; drop both copies explicitly.
                activeInkView?.clearSelection()
                selection = null
            },
            onRotateSelection = { degrees -> transformSelection(
                { strokes, center -> InkGeometry.rotate(strokes, center, degrees) },
                { texts, center -> InkGeometry.rotateTexts(texts, center, degrees) },
                { images, center -> InkGeometry.rotateImages(images, center, degrees) }
            ) },
            onResizeSelection = { factor -> transformSelection(
                { strokes, center -> InkGeometry.scale(strokes, center, factor) },
                { texts, center -> InkGeometry.scaleTexts(texts, center, factor) },
                { images, center -> InkGeometry.scaleImages(images, center, factor) }
            ) },
            onRestyleSelection = { restyleSelection = selected.strokes },
            onDeleteSelection = { model.deleteSelection(selected); selection = null }
        )
    }
    if (pageBrowser) FolioPanel(title = "Notebook pages", onDismissRequest = { pageBrowser = false }) {

        val visiblePages = remember(note.pages, pageQuery, pageFilter) { organizePages(note.pages, pageQuery, pageFilter) }
        val canDrag = pageQuery.isBlank() && pageFilter == PageFilter.ALL
        OutlinedTextField(pageQuery, { pageQuery = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            label = { Text("Find a page by name or number") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (pageQuery.isNotEmpty()) IconButton({ pageQuery = "" }) { Icon(Icons.Rounded.Close, "Clear page search") } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageFilter.entries.forEach { option ->
                FilterChip(pageFilter == option, { pageFilter = option }, { Text(option.label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(pageNumber, { pageNumber = it.filter(Char::isDigit).take(9) },
                label = { Text("Go to page (1–${note.pages.size})") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            TextButton({ pageNumber.toIntOrNull()?.let { jumpTo(it - 1) }; pageBrowser = false; pageNumber = "" },
                enabled = pageNumber.toIntOrNull()?.let { it in 1..note.pages.size } == true) { Text("Go") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (canDrag) "Long-press a page and drag to reorder it." else "${visiblePages.size} pages found. Use page options to move a page.", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({
                model.duplicatePage()?.let { pages.requestScrollToItem(it) }
                pageBrowser = false
            }) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Duplicate page ${state.pageIndex + 1}") }
        }
        val rowHeight = 108.dp
        val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragDelta by remember { mutableFloatStateOf(0f) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (visiblePages.isEmpty()) item { Text("No pages match. Try another name or filter.", Modifier.padding(16.dp)) }
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
                    onMoveTo = { movingPage = item.id; destinationPage = (index + 1).toString() },
                    canMoveUp = index > 0, canMoveDown = index < note.pages.lastIndex, canDelete = note.pages.size > 1,
                    noteId = note.id, thumbnails = model.thumbnails)
            }
            item { TextButton({ addPage(); pageBrowser = false }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a blank page — ${paperLabel(page.paper)}") } }
        }
    }
    namedPage?.let { target ->
        AlertDialog(onDismissRequest = { namedPage = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Name page") }, text = {
                OutlinedTextField(pageTitle, { pageTitle = it.take(120) }, label = { Text("Page name") },
                    supportingText = { Text("Leave blank to use the page number.") }, singleLine = true)
            }, dismissButton = { TextButton({ namedPage = null }) { Text("Cancel") } },
            confirmButton = { TextButton({ model.renamePage(target.id, pageTitle); namedPage = null }) { Text("Save") } })
    }
    movingPage?.let { pageId ->
        val destination = destinationPage.toIntOrNull()
        AlertDialog(onDismissRequest = { movingPage = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Move page") }, text = {
                OutlinedTextField(destinationPage, { destinationPage = it.filter(Char::isDigit).take(9) },
                    label = { Text("New position (1–${note.pages.size})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }, dismissButton = { TextButton({ movingPage = null }) { Text("Cancel") } },
            confirmButton = { TextButton({
                val from = note.pages.indexOfFirst { it.id == pageId }
                if (from >= 0 && destination != null) model.movePage(from, destination - 1)
                movingPage = null
            }, enabled = destination != null && destination in 1..note.pages.size) { Text("Move") } })
    }
    deletingPage?.let { pageId ->
        val index = note.pages.indexOfFirst { it.id == pageId }
        AlertDialog(onDismissRequest = { deletingPage = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Delete ${note.pages.getOrNull(index)?.displayTitle(index) ?: "page"}?") },
            text = { Text("This removes the page and its content. This cannot be undone.") },
            dismissButton = { TextButton({ deletingPage = null }) { Text("Cancel") } },
            confirmButton = { TextButton({ if (index >= 0 && note.pages.size > 1) model.deletePage(index); deletingPage = null }) { Text("Delete") } })
    }
    if (rename) AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { rename = false }, title = { Text("Rename notebook") }, text = {
        OutlinedTextField(renameTitle, { renameTitle = it }, label = { Text("Notebook title") }, singleLine = true)
    }, dismissButton = { TextButton({ rename = false }) { Text("Cancel") } }, confirmButton = {
        TextButton({ model.rename(note, renameTitle); rename = false }, enabled = renameTitle.isNotBlank()) { Text("Save") }
    })
    if (paperMenu) AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { paperMenu = false }, title = { Text("Change paper") }, text = {
        Column {
            listOf(Paper.MATH_GRID, Paper.GRAPH, Paper.GRID, Paper.DOTS, Paper.PLAIN, Paper.RULED, Paper.MC_SHEET, Paper.TIAN_GRID, Paper.MI_GRID).forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { model.setPaper(p); paperMenu = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(page.paper == p, { model.setPaper(p); paperMenu = false })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(paperLabel(p)); if (p == Paper.MATH_GRID) Text("Fine 20 px grid, bold every 5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p == Paper.GRAPH) Text("Same grid + centred axes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p == Paper.MC_SHEET) Text("Exam 2 Section A answer sheet, 25 questions A–E", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p == Paper.TIAN_GRID) Text("田字格 — one character per square, dashed cross", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p == Paper.MI_GRID) Text("米字格 — cross plus diagonals per square", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ paperMenu = false }) { Text("Done") } })
    if (clear) AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { clear = false }, title = { Text("Clear this page?") }, text = { Text("Your paper or PDF stays in place. Ink, text and pictures are removed. You can undo this change.") }, dismissButton = { TextButton({ clear = false }) { Text("Cancel") } }, confirmButton = { TextButton({ model.clearPage(); selectedImage = null; clear = false }) { Text("Clear page") } })
    if (timerPanel) ExamTimerPanel(
        timer = state.timer,
        onDismiss = { timerPanel = false },
        onStart = { model.startTimer(it) },
        onStop = { model.stopTimer() },
        onAdjust = model::adjustTimer,
        onSkip = model::skipTimerPhase,
        onPauseResume = model::toggleTimerPause
    )
    if (examPanel) ExamDetailsPanel(
        note = note,
        onDismiss = { examPanel = false },
        onSave = { tags -> model.updateExamTags(note.id, tags); examPanel = false },
        onRecordMark = { attempt -> model.recordAttempt(note.id, attempt) },
        onDeleteAttempt = { attempt -> model.deleteAttempt(note.id, attempt.id) },
        suggestedSeconds = state.lastTimedSeconds,
        suggestedTelemetry = state.lastTelemetry
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
    textEditor?.let { box ->
        TextBoxDialog(
            box = box, isNew = textEditorNew, colors = quick.colors(InkColors.INK_GROUP),
            onDismiss = { textEditor = null },
            onCreate = { created -> model.addText(created); rememberTextLook(created); textEditor = null },
            onUpdate = { updated -> model.updateText(updated); rememberTextLook(updated); textEditor = null },
            onDelete = { model.removeText(box.id); textEditor = null }
        )
    }
    selectedImage?.let { (ownerId, image) ->
        val live = note.pages.find { it.id == ownerId }?.images?.find { it.id == image.id }
        if (live != null && ownerId == page.id) {
            FolioPanel(title = "Picture", onDismissRequest = { selectedImage = null }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Drag with the hand tool to move. Drag the blue dot to resize. Ink draws over the picture.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ model.bringImageToFront(live.id) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FlipToFront, null); Spacer(Modifier.width(8.dp)); Text("Bring to front")
                    }
                    OutlinedButton({ model.sendImageToBack(live.id) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FlipToBack, null); Spacer(Modifier.width(8.dp)); Text("Send to back")
                    }
                    TextButton(
                        { model.removeImage(live.id); selectedImage = null },
                        Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Remove picture")
                    }
                }
            }
        }
    }
    if (noteSearchOpen) FolioPanel(title = "Find in notes", onDismissRequest = { noteSearchOpen = false }) {
        val hits = remember(noteQuery, note.pages) {
            NotebookTextSearch.search(note.pages, noteQuery)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                noteQuery, { noteQuery = it },
                Modifier.fillMaxWidth(),
                label = { Text("Find typed text") },
                placeholder = { Text("e.g. quadratic formula") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                trailingIcon = {
                    if (noteQuery.isNotEmpty()) IconButton({ noteQuery = "" }) {
                        Icon(Icons.Rounded.Clear, "Clear search")
                    }
                }
            )
            if (noteQuery.isBlank()) {
                Text("Searches every typed text box in this notebook. Handwriting is not searched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (hits.isEmpty()) {
                Text("No typed text matches “${noteQuery.trim().take(80)}”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${hits.size} ${if (hits.size == 1) "page matches" else "pages match"} — most matches first.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hits, key = { it.pageIndex }) { hit ->
                        Surface(onClick = { jumpTo(hit.pageIndex); noteSearchOpen = false }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    if (stampPicker) FolioPanel(title = "Insert element", onDismissRequest = { stampPicker = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Adds a clean, editable shape as ordinary ink in the middle of this page.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            InkStamps.kinds.forEach { kind ->
                FilledTonalButton({
                    model.insertStamp(kind, color = options.color, width = options.width)
                    stampPicker = false
                }, Modifier.fillMaxWidth()) {
                    Icon(
                        when (kind) {
                            InkStamps.Kind.ARROW -> Icons.AutoMirrored.Rounded.ArrowForward
                            InkStamps.Kind.DOUBLE_ARROW -> Icons.Rounded.SwapHoriz
                            InkStamps.Kind.STAR -> Icons.Rounded.Star
                            InkStamps.Kind.CHECKBOX -> Icons.Rounded.CheckBoxOutlineBlank
                            InkStamps.Kind.CALLOUT -> Icons.Rounded.ChatBubbleOutline
                            InkStamps.Kind.UNDERLINE -> Icons.Rounded.FormatUnderlined
                        }, null
                    )
                    Spacer(Modifier.width(8.dp)); Text(InkStamps.label(kind))
                }
            }
        }
    }
    if (pdfSearchOpen) FolioPanel(title = "Search this PDF", onDismissRequest = { pdfSearchOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                pdfQuery, { pdfQuery = it },
                Modifier.fillMaxWidth(),
                label = { Text("Find in this PDF") },
                placeholder = { Text("e.g. quadratic formula") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.searchPdf(pdfQuery) }),
                trailingIcon = {
                    if (pdfQuery.isNotEmpty()) IconButton({ pdfQuery = ""; model.searchPdf("") }) {
                        Icon(Icons.Rounded.Clear, "Clear search")
                    }
                }
            )
            Button({ model.searchPdf(pdfQuery) }, enabled = pdfQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Search")
            }
            val search = state.pdfSearch
            if (search.searching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(24.dp).semanticsLabel("Searching PDF"))
                    Text("Reading this PDF's text…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (search.searched && search.query.isNotBlank()) {
                if (search.results.isEmpty()) {
                    Text(
                        "No matches for “${search.query.trim().take(80)}”. Scanned or locked PDFs have no searchable text.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${search.results.size} ${if (search.results.size == 1) "page matches" else "pages match"} — most matches first.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(search.results, key = { it.pageIndex }) { hit ->
                            Surface(onClick = { jumpTo(hit.pageIndex); pdfSearchOpen = false }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Page ${hit.pageIndex + 1} · ${hit.matchCount}×", style = MaterialTheme.typography.titleSmall)
                                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open page ${hit.pageIndex + 1}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pdfContentsOpen) FolioPanel(title = "Contents", onDismissRequest = { pdfContentsOpen = false }) {
        val outline = pdfOutline
        if (outline == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(24.dp).semanticsLabel("Loading contents"))
                Text("Reading bookmarks…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (outline.isEmpty()) {
            Text(
                "This PDF has no bookmarks.",
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(bottom = 16.dp), contentPadding = PaddingValues(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(outline) { _, entry ->
                    Surface(onClick = { jumpTo(entry.pageIndex); pdfContentsOpen = false }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp + 16.dp * entry.depth, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
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
 * The exam timer's place in the editor chrome: an icon while idle, the live clock while a sitting
 * is running, so the countdown stays visible without covering any of the page.
 */
@Composable private fun ExamTimerChip(timer: ExamTimerState, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val active = timer.phase == ExamTimerPhase.READING || timer.phase == ExamTimerPhase.WRITING || timer.phase == ExamTimerPhase.DONE
    if (!active) {
        IconButton(onClick) { Icon(Icons.Rounded.Timer, "Exam timer") }
    } else {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = if (timer.phase == ExamTimerPhase.DONE) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (timer.phase == ExamTimerPhase.DONE) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = (height - 36.dp) / 2)
        ) {
            Text(
                if (timer.paused) "Paused · ${timer.clockText()}"
                else if (timer.phase == ExamTimerPhase.WRITING) timer.clockText()
                else if (timer.phase == ExamTimerPhase.READING) "R · ${timer.clockText()}"
                else "Pens down",
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private data class FastScrollGeometry(val top: Float, val height: Float)

/** Shared geometry keeps the touch target aligned with the visible thumb. */
private fun fastScrollGeometry(pages: LazyListState, height: Float, minimumThumb: Float): FastScrollGeometry? {
    val info = pages.layoutInfo
    if (height <= 0f || (!pages.canScrollBackward && !pages.canScrollForward)) return null
    val progress = if (!pages.canScrollForward) 1f else DocumentViewport.scrollProgress(pages.firstVisibleItemIndex, pages.firstVisibleItemScrollOffset, info.visibleItemsInfo.firstOrNull()?.size ?: 0, info.totalItemsCount)
    val share = DocumentViewport.thumbFraction(info.visibleItemsInfo.size, info.totalItemsCount)
    val thumb = (height * share).coerceAtLeast(minimumThumb).coerceAtMost(height)
    return FastScrollGeometry((height - thumb) * progress, thumb)
}

/** The page count follows the thumb without making the label a touch target. */
@Composable private fun FastScrollTrack(pages: LazyListState, pageCount: Int, scrubbing: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // The thumb brightens smoothly when grabbed instead of snapping between two alphas.
    val thumbAlpha by animateFloatAsState(if (scrubbing) 1f else .6f, label = "fastScrollAlpha")
    BoxWithConstraints(modifier) {
        val geometry = fastScrollGeometry(pages, constraints.maxHeight.toFloat(), with(density) { 24.dp.toPx() })
            ?: return@BoxWithConstraints
        Box(Modifier.align(Alignment.CenterEnd).width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().width(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f)))
            Box(Modifier.offset { IntOffset(0, geometry.top.roundToInt()) }.width(5.dp).height(with(density) { geometry.height.toDp() }).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha)))
        }
        val labelHeightPx = with(density) { 28.dp.toPx() }
        val labelTop = (geometry.top + geometry.height / 2 - labelHeightPx / 2)
            .coerceIn(0f, (constraints.maxHeight - labelHeightPx).coerceAtLeast(0f))
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, labelTop.roundToInt()) }.padding(end = 30.dp)
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Text("${(pages.firstVisibleItemIndex + 1).coerceAtMost(pageCount)} / $pageCount",
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        }
    }
}

/** A floating piece of editor chrome: a rounded surface holding one row of controls over the page. */
@Composable private fun EditorChromeChip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.guardUiTouches(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable private fun ToolButton(value: Tool, selected: Tool, icon: ImageVector, label: String, indicatorColor: Color? = null, change: (Tool) -> Unit) {

    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        FolioToolToggle(value == selected, { change(value) }, icon, label, indicatorColor = indicatorColor)
    }
}

private val ShapeTools = setOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)
private val DrawingTools = setOf(Tool.PEN, Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE, Tool.HIGHLIGHTER)

@Composable internal fun EditorPage(noteId: String, page: NotePage, model: FolioViewModel, tool: Tool, options: ToolOptions, finger: Boolean, snapEnabled: Boolean, shapeRecognition: Boolean, active: Boolean, onActive: () -> Unit, onPan: (Float, Float) -> Unit, onPanEnd: (Float) -> Unit, onSelection: (CanvasSelection) -> Unit, onTextEdit: (TextBox) -> Unit, onTextCreate: (InkPoint) -> Unit, onLoad: () -> Unit, fullscreen: Boolean = false, canvasReset: Int = 0, onCanvasZoom: (Float) -> Unit = {}, onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {}, selectedImageId: String? = null, onImageSelected: (PageImage?) -> Unit = {}, pdfLinks: List<PdfLink> = emptyList(), onPdfLink: (PdfLink) -> Unit = {}, eraserPressureEnabled: Boolean = true, scribbleToErase: Boolean = true, eraserWholeStroke: Boolean = false, shapeMeasurements: Boolean = true, multiTouchUndo: Boolean = true, onEraserFinished: (() -> Unit)? = null, onUndo: (() -> Unit)? = null, onRedo: (() -> Unit)? = null, onSelectAllView: ((InkView) -> Unit)? = null, inkStyle: StrokeStyle = StrokeStyle.SOLID, readOnly: Boolean = false, initialViewport: WorkspaceViewport? = null, onCameraChanged: (WorkspaceViewport) -> Unit = {}) {
    var background by remember(page.id) { mutableStateOf<Bitmap?>(null) }
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
    // Pictures arrive with the page content; a missing file simply leaves no bitmap to draw.
    // Keyed by image ids only: ink edits bump the page revision but never change picture bytes,
    // so redrawing a stroke must not re-decode every photo on the page.
    LaunchedEffect(noteId, page.id, page.loaded, page.images.map { it.id }) {
        if (!page.loaded) return@LaunchedEffect
        if (page.images.isEmpty()) {
            pictures = emptyMap()
            return@LaunchedEffect
        }
        val decoded = model.repository.loadImages(noteId, page)
        pictures = decoded
    }
    Surface(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(page.width / page.height), shape = RoundedCornerShape(3.dp), shadowElevation = 3.dp, color = Color.White) {
        // Nothing is drawn on a page until its own ink has arrived, so a stroke can never land on top
        // of a blank stand-in and replace the content that is still on disk.
        if (!page.loaded) Box(contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.semanticsLabel("Loading page")) }
        else if (ready) AndroidView(factory = { context -> InkView(context) }, modifier = Modifier.fillMaxSize(), update = { view ->
            if (readOnly) view.contentDescription = "Reference page. Use the hand or two fingers to pan and zoom. Read only."
            view.onCanvasViewport = onCanvasViewport; view.onCanvasZoom = onCanvasZoom; view.bind(page, background, pictures); view.resetCanvas(canvasReset); view.restoreWorkspaceCamera(initialViewport); view.onWorkspaceCamera = onCameraChanged; view.readOnly = readOnly; view.tool = tool; view.inkColor = options.color
            view.inkWidth = options.width; view.inkOpacity = options.opacity; view.inkStyle = inkStyle; view.pressureEnabled = options.pressure; view.fingerDrawing = finger
            view.pressureSensitivity = options.pressureSensitivity; view.pressureVariation = options.pressureVariation
            view.eraserPressureEnabled = eraserPressureEnabled; view.scribbleToErase = scribbleToErase; view.eraserWholeStroke = eraserWholeStroke; view.shapeMeasurements = shapeMeasurements; view.multiTouchUndo = multiTouchUndo; view.onEraserFinished = onEraserFinished
            view.onUndoRequest = onUndo; view.onRedoRequest = onRedo
            if (onSelectAllView != null) view.tag = onSelectAllView else if (view.tag is Function1<*, *>) view.tag = null
            view.snapEnabled = snapEnabled
            view.shapeRecognition = shapeRecognition
            view.onActive = onActive; view.onDocumentPan = onPan; view.onDocumentPanEnd = onPanEnd
            view.onStrokesChanged = { if (!readOnly) model.strokes(page.id, it) }
            view.onSelectionChanged = onSelection
            view.onContentChanged = { strokes, texts, images -> if (!readOnly) model.updateContent(page.id, strokes, texts, images) }
            view.onTextEdit = onTextEdit; view.onTextCreate = onTextCreate
            view.onTextsChanged = { if (!readOnly) model.texts(page.id, it) }
            view.selectedImageId = selectedImageId?.takeIf { id -> page.images.any { it.id == id } }
            view.onImagesChanged = { if (!readOnly) model.images(page.id, it) }
            view.onImageSelected = onImageSelected
            view.pdfLinks = pdfLinks.filter { it.pageIndex == page.pdfIndex }
            view.onPdfLink = onPdfLink
            if (!active) view.clearSelection()
        }) else Box(contentAlignment = Alignment.Center) {
            if (error) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't open this PDF page", color = Color.DarkGray)
                TextButton({ retry++ }) { Text("Try again") }
            } else LoadingIndicator(Modifier.semanticsLabel("Loading page"))
        }
    }
}

@Composable private fun FloatingInkToolbar(
    modifier: Modifier, tool: Tool, onTool: (Tool) -> Unit, options: ToolOptions, onOptions: (ToolOptions) -> Unit, quick: QuickColorsState,
    canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit, palette: Boolean, snapEnabled: Boolean, onSnap: (Boolean) -> Unit, onAxes: () -> Unit, onPalette: (Boolean) -> Unit,
    eraserSingleStroke: Boolean = false, onEraserSingleStroke: ((Boolean) -> Unit)? = null,
    scribbleToErase: Boolean = true, onScribbleToErase: ((Boolean) -> Unit)? = null,
    eraserPressureEnabled: Boolean = true, onEraserPressure: ((Boolean) -> Unit)? = null,
    eraserWholeStroke: Boolean = false, onEraserWholeStroke: ((Boolean) -> Unit)? = null,
    shapeMeasurements: Boolean = true, onShapeMeasurements: ((Boolean) -> Unit)? = null,
    multiTouchUndo: Boolean = true, onMultiTouchUndo: ((Boolean) -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    textColor: Int = 0, onTextColor: ((Int) -> Unit)? = null,
    presets: List<ToolPreset> = emptyList(), onApplyPreset: ((ToolPreset) -> Unit)? = null,
    toolPresetsState: ToolPresetState? = null
) {
    var shapes by remember { mutableStateOf(false) }
    var shapePicker by remember { mutableStateOf(false) }
    var showWidth by remember { mutableStateOf(false) }
    var lastShape by rememberSaveable { mutableStateOf(Tool.LINE) }
    val isShape = tool in ShapeTools
    val isDrawing = tool in DrawingTools
    // The text tool gets its own quick row for the colour new boxes are created with.
    val showQuickBar = isDrawing || tool == Tool.ERASER || (tool == Tool.TEXT && onTextColor != null)
    val feedback = LocalHapticFeedback.current
    /** A light tick on real tool changes; tapping the active tool stays silent. */
    fun pick(next: Tool) {
        if (next != tool) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onTool(next)
    }
    // The highlighter gets its own quick row and presets; every other ink tool shares one.
    val colorGroup = InkColors.groupOf(tool)
    // Dots on the pen/highlighter show their own stored colours, not the active tool's, so the
    // inactive button still reads correctly. Reads are in-memory SharedPreferences lookups.
    val toolPrefsContext = LocalContext.current
    val toolPrefs = remember(toolPrefsContext) { toolPrefsContext.getSharedPreferences("ink-tools", 0) }
    val penDot = if (tool == Tool.PEN) options.color else toolPrefs.getInt("PEN.color", 0xFF303431.toInt())
    val highlighterDot = if (tool == Tool.HIGHLIGHTER) options.color else toolPrefs.getInt("HIGHLIGHTER.color", 0xFFE9BF44.toInt())
    val widthRange = when (tool) { Tool.ERASER -> 4f..72f; Tool.HIGHLIGHTER -> 4f..48f; Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE -> 0.7f..10f; else -> 0.7f..12f }
    @Composable fun ToolbarDivider() {
        Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
    }
    @Composable fun QuickColors() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
            quick.colors(colorGroup).forEachIndexed { index, c ->
                InkColorDot(c, options.color == c, { onOptions(options.copy(color = c)) }, label = "Quick colour ${index + 1}")
            }
            IconButton({ onPalette(true) }) {
                Icon(Icons.Rounded.Palette, "More colours", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable fun WidthControl() {
        Box {
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Stroke width ${String.format(java.util.Locale.ROOT, "%.1f", options.width)} pt — tap to adjust") } }, state = rememberTooltipState()) {
                AssistChip(
                    onClick = { showWidth = true },
                    label = { Text(String.format(java.util.Locale.ROOT, "%.1f", options.width), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Rounded.LineWeight, null, Modifier.size(16.dp)) },
                    modifier = Modifier.height(32.dp)
                )
            }
            DropdownMenu(expanded = showWidth, onDismissRequest = { showWidth = false }, modifier = Modifier.guardUiTouches()) {
                Column(Modifier.widthIn(min = 260.dp, max = 300.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Stroke width", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.LineWeight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = options.width.coerceIn(widthRange), onValueChange = { onOptions(options.copy(width = it)) }, valueRange = widthRange, modifier = Modifier.weight(1f))
                        Text(String.format(java.util.Locale.ROOT, "%.1f", options.width), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (tool) {
                            Tool.PEN -> {
                                AssistChip({ onOptions(options.copy(width = 1.4f)); showWidth = false }, { Text("Fine") })
                                AssistChip({ onOptions(options.copy(width = 2.2f)); showWidth = false }, { Text("Regular") })
                                AssistChip({ onOptions(options.copy(width = 3.5f)); showWidth = false }, { Text("Bold") })
                            }
                            Tool.HIGHLIGHTER -> {
                                AssistChip({ onOptions(options.copy(width = 12f)); showWidth = false }, { Text("Thin") })
                                AssistChip({ onOptions(options.copy(width = 18f)); showWidth = false }, { Text("Regular") })
                                AssistChip({ onOptions(options.copy(width = 28f)); showWidth = false }, { Text("Wide") })
                            }
                            Tool.ERASER -> {
                                AssistChip({ onOptions(options.copy(width = 14f)); showWidth = false }, { Text("Small") })
                                AssistChip({ onOptions(options.copy(width = 26f)); showWidth = false }, { Text("Medium") })
                                AssistChip({ onOptions(options.copy(width = 42f)); showWidth = false }, { Text("Large") })
                            }
                            else -> {
                                AssistChip({ onOptions(options.copy(width = 1.2f)); showWidth = false }, { Text("Hairline") })
                                AssistChip({ onOptions(options.copy(width = 2f)); showWidth = false }, { Text("Regular") })
                                AssistChip({ onOptions(options.copy(width = 3.5f)); showWidth = false }, { Text("Heavy") })
                            }
                        }
                    }
                    if (tool != Tool.ERASER && tool != Tool.HAND && tool != Tool.LASSO) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Opacity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                            Slider(value = options.opacity, onValueChange = { onOptions(options.copy(opacity = it)) }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                            Text("${(options.opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                        }
                    }
                    TextButton({ onPalette(true); showWidth = false }, Modifier.align(Alignment.End)) { Text("More settings") }
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        IconButton(undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
        IconButton(redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
        ToolbarDivider()
        ToolButton(Tool.PEN, tool, Icons.Rounded.Edit, "Pen", indicatorColor = Color(penDot)) { if (it == tool) onPalette(true) else pick(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shapeIcon = when (tool) {
                Tool.LINE -> Icons.AutoMirrored.Rounded.ShowChart
                Tool.ELLIPSE -> Icons.Rounded.Circle
                else -> Icons.Rounded.CropSquare
            }
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (isShape) "Shapes, ${tool.name.lowercase()} — tap for options" else "Shapes — tap for ${lastShape.name.lowercase()}") } }, state = rememberTooltipState()) {
                FolioToolToggle(isShape, { if (isShape) shapePicker = true else pick(lastShape) }, shapeIcon,
                    if (isShape) "Shapes, ${tool.name.lowercase()}" else "Shapes")
            }
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Choose shape") } }, state = rememberTooltipState()) {
                IconButton({ shapePicker = true }, Modifier.size(32.dp)) { Icon(Icons.Rounded.ArrowDropDown, "Choose shape", Modifier.size(20.dp)) }
            }
            DropdownMenu(shapePicker, { shapePicker = false }, modifier = Modifier.guardUiTouches()) {
                listOf(
                    Triple(Tool.LINE, "Straight line", Icons.AutoMirrored.Rounded.ShowChart),
                    Triple(Tool.RECTANGLE, "Rectangle", Icons.Rounded.CropSquare),
                    Triple(Tool.ELLIPSE, "Ellipse", Icons.Rounded.Circle)
                ).forEach { (value, label, icon) ->
                    DropdownMenuItem({ Text(label) }, { lastShape = value; pick(value); shapePicker = false },
                        leadingIcon = { Icon(icon, null) },
                        trailingIcon = { if (tool == value) Icon(Icons.Rounded.Check, "Selected") })
                }
            }
        }
        ToolButton(Tool.HIGHLIGHTER, tool, Icons.Rounded.BorderColor, "Highlighter", indicatorColor = Color(highlighterDot)) { if (it == tool) onPalette(true) else pick(it) }
        ToolButton(Tool.ERASER, tool, Icons.Rounded.AutoFixNormal, "Eraser") { if (it == tool) onPalette(true) else pick(it) }
        ToolButton(Tool.TEXT, tool, Icons.Rounded.TextFields, "Text") { pick(it) }
        ToolButton(Tool.LASSO, tool, Icons.Rounded.Gesture, "Lasso select") { pick(it) }
        ToolButton(Tool.HAND, tool, Icons.Rounded.PanTool, "Hand — follow links, move pictures, scroll and zoom") { pick(it) }
        // Overflow for less frequent actions — keep palette access separate from quick controls
        Box {
            IconButton({ shapes = true }) { Icon(Icons.Rounded.MoreHoriz, "More options") }
            DropdownMenu(shapes, { shapes = false }, modifier = Modifier.guardUiTouches()) {
                if (presets.isNotEmpty() && onApplyPreset != null) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            { Text("${preset.name} · ${preset.tool.name.lowercase()}") },
                            { onApplyPreset(preset); shapes = false },
                            leadingIcon = { Icon(Icons.Rounded.Bookmark, null) }
                        )
                    }
                    HorizontalDivider()
                }
                if (onSelectAll != null) {
                    DropdownMenuItem({ Text("Select all") }, { onSelectAll(); shapes = false }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) })
                    HorizontalDivider()
                }
                if (!isDrawing && tool != Tool.ERASER) {
                    DropdownMenuItem({ Text(if (snapEnabled) "Snap to grid: on" else "Snap to grid: off") }, { onSnap(!snapEnabled); shapes = false }, leadingIcon = { Icon(if (snapEnabled) Icons.Rounded.GridView else Icons.Rounded.GridOff, null) })
                    if (onShapeMeasurements != null) DropdownMenuItem({ Text(if (shapeMeasurements) "Measurements: on" else "Measurements: off") }, { onShapeMeasurements(!shapeMeasurements); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Straighten, null) })
                    HorizontalDivider()
                }
                if (tool == Tool.ERASER || tool == Tool.PEN || tool == Tool.HIGHLIGHTER) {
                    if (onEraserSingleStroke != null) DropdownMenuItem({ Text(if (eraserSingleStroke) "Single-stroke eraser: on" else "Single-stroke eraser: off") }, { onEraserSingleStroke(!eraserSingleStroke); shapes = false }, leadingIcon = { Icon(Icons.Rounded.AutoFixNormal, null) })
                    if (onEraserPressure != null) DropdownMenuItem({ Text(if (eraserPressureEnabled) "Eraser pressure: on" else "Eraser pressure: off") }, { onEraserPressure(!eraserPressureEnabled); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Compress, null) })
                    if (onEraserWholeStroke != null) DropdownMenuItem({ Text(if (eraserWholeStroke) "Whole-stroke eraser: on" else "Whole-stroke eraser: off") }, { onEraserWholeStroke(!eraserWholeStroke); shapes = false }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) })
                    if (onScribbleToErase != null) DropdownMenuItem({ Text(if (scribbleToErase) "Scribble to erase: on" else "Scribble to erase: off") }, { onScribbleToErase(!scribbleToErase); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Brush, null) })
                    HorizontalDivider()
                }
                if (onMultiTouchUndo != null) {
                    DropdownMenuItem({ Text(if (multiTouchUndo) "Two-finger undo: on" else "Two-finger undo: off") }, { onMultiTouchUndo(!multiTouchUndo); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Gesture, null) })
                    HorizontalDivider()
                }
                DropdownMenuItem({ Text("Insert graph axes") }, { onAxes(); shapes = false }, leadingIcon = { Icon(Icons.Rounded.AddChart, null) })
                DropdownMenuItem({ Text("Tool settings") }, { onPalette(true); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Tune, null) })
            }
        }
    }
    // The bar hugs its content: capped width fits narrow phones without clipping, and the
    // quick row only takes space when the active tool has quick settings. Each row scrolls.
    Column(modifier.guardUiTouches().widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { controls() }
        }
        if (showQuickBar) {
            Box(Modifier.width(20.dp).height(6.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Surface(Modifier.widthIn(max = 560.dp).height(48.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tool == Tool.TEXT && onTextColor != null) {
                        quick.colors(colorGroup).forEachIndexed { index, c ->
                            InkColorDot(c, textColor == c, { onTextColor(c) }, label = "Text colour ${index + 1}")
                        }
                    } else {
                        if (tool != Tool.ERASER) QuickColors()
                        WidthControl()
                        if (tool == Tool.ERASER && onEraserPressure != null) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (eraserPressureEnabled) "Eraser pressure on — slight size change" else "Eraser pressure off") } }, state = rememberTooltipState()) {
                                FilterChip(selected = eraserPressureEnabled, onClick = { onEraserPressure(!eraserPressureEnabled) }, label = { Text("Pressure", style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        if (tool == Tool.ERASER && onEraserWholeStroke != null) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (eraserWholeStroke) "Eraser removes whole strokes" else "Eraser cuts strokes") } }, state = rememberTooltipState()) {
                                FilterChip(selected = eraserWholeStroke, onClick = { onEraserWholeStroke(!eraserWholeStroke) }, label = { Text("Whole", style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        if ((tool == Tool.PEN || tool == Tool.HIGHLIGHTER) && onScribbleToErase != null) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (scribbleToErase) "Scribble to erase: on" else "Scribble to erase: off") } }, state = rememberTooltipState()) {
                                FilterChip(selected = scribbleToErase, onClick = { onScribbleToErase(!scribbleToErase) }, label = { Text("Scribble", style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        if (tool == Tool.ERASER && onEraserSingleStroke != null) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (eraserSingleStroke) "Returns to previous tool after one stroke" else "Stays on eraser") } }, state = rememberTooltipState()) {
                                FilterChip(selected = eraserSingleStroke, onClick = { onEraserSingleStroke(!eraserSingleStroke) }, label = { Text("Single", style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        if (isShape && onShapeMeasurements != null) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (shapeMeasurements) "Measurements on" else "Measurements off") } }, state = rememberTooltipState()) {
                                FilterChip(selected = shapeMeasurements, onClick = { onShapeMeasurements(!shapeMeasurements) }, label = { Text("Measure", style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        if (isShape) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Line style: ${options.style.name.lowercase()} — tap to cycle") } }, state = rememberTooltipState()) {
                                val styleLabel = when (options.style) { StrokeStyle.SOLID -> "Solid"; StrokeStyle.DASHED -> "Dashed"; StrokeStyle.DOTTED -> "Dotted" }
                                FilterChip(selected = options.style != StrokeStyle.SOLID, onClick = {
                                    onOptions(options.copy(style = when (options.style) {
                                        StrokeStyle.SOLID -> StrokeStyle.DASHED
                                        StrokeStyle.DASHED -> StrokeStyle.DOTTED
                                        StrokeStyle.DOTTED -> StrokeStyle.SOLID
                                    }))
                                }, label = { Text(styleLabel, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (snapEnabled) "Snap to grid on — lines lock to grid & 15°" else "Snap to grid off") } }, state = rememberTooltipState()) {
                            IconButton({ onSnap(!snapEnabled) }) {
                                Icon(if (snapEnabled) Icons.Rounded.GridView else Icons.Rounded.GridOff, if (snapEnabled) "Snap on" else "Snap off", tint = if (snapEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                        }
                    }
                }
            }
        }
    }
    if (palette) FolioPanel(title = "Tool settings", onDismissRequest = { onPalette(false) }) {
        ToolOptionsPanel(tool, options, onOptions, quick, toolPresetsState)
    }
}

/** The editor's page menu. Shared by the floating and stacked chrome so both stay in step. */
@Composable private fun PageOptionsMenu(
    expanded: Boolean, onDismiss: () -> Unit, page: NotePage, snapEnabled: Boolean, saveFailed: Boolean,
    canPaste: Boolean, onResetZoom: () -> Unit, onAxes: () -> Unit, onPaper: () -> Unit,
    onSnap: () -> Unit, onPaste: () -> Unit, onClear: () -> Unit, onRetry: () -> Unit,
    onRedo: () -> Unit, onExam: () -> Unit, onTimer: () -> Unit, onInsertImage: () -> Unit, onSearchPdf: () -> Unit,
    onContents: () -> Unit, onSearchNotes: () -> Unit = {}, onInsertElement: () -> Unit = {},
    onOrganize: () -> Unit, onBookmark: () -> Unit, onNamePage: () -> Unit
) {
    DropdownMenu(expanded, onDismiss, modifier = Modifier.guardUiTouches()) {
        DropdownMenuItem({ Text("Organise pages") }, { onDismiss(); onOrganize() }, leadingIcon = { Icon(Icons.Rounded.AutoStories, null) })
        DropdownMenuItem({ Text("Name page") }, { onDismiss(); onNamePage() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
        DropdownMenuItem({ Text(if (page.bookmarked) "Remove bookmark" else "Bookmark page") }, { onDismiss(); onBookmark() }, leadingIcon = { Icon(Icons.Rounded.Bookmark, null) })
        HorizontalDivider()
        DropdownMenuItem(
            { Text(if (page.redoFlag) "Remove redo flag" else "Flag this page to redo") },
            { onDismiss(); onRedo() },
            leadingIcon = { Icon(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag, null) }
        )
        DropdownMenuItem({ Text("Exam details") }, { onDismiss(); onExam() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) })
        DropdownMenuItem({ Text("Exam timer") }, { onDismiss(); onTimer() }, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
        HorizontalDivider()
        DropdownMenuItem({ Text("Insert picture") }, { onDismiss(); onInsertImage() }, leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) })
        DropdownMenuItem({ Text("Insert element") }, { onDismiss(); onInsertElement() }, leadingIcon = { Icon(Icons.Rounded.Category, null) })
        DropdownMenuItem({ Text("Find in notes") }, { onDismiss(); onSearchNotes() }, leadingIcon = { Icon(Icons.Rounded.FindInPage, null) })
        DropdownMenuItem({ Text("Search PDF text") }, { onDismiss(); onSearchPdf() }, enabled = page.pdfIndex != null, leadingIcon = { Icon(Icons.Rounded.Search, null) })
        DropdownMenuItem({ Text("Contents") }, { onDismiss(); onContents() }, enabled = page.pdfIndex != null, leadingIcon = { Icon(Icons.Rounded.FormatListBulleted, null) })
        DropdownMenuItem({ Text("Reset document zoom") }, { onDismiss(); onResetZoom() }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
        DropdownMenuItem({ Text("Add maths axes") }, { onDismiss(); onAxes() }, leadingIcon = { Icon(Icons.Rounded.AddChart, null) })
        DropdownMenuItem({ Text("Paste") }, { onDismiss(); onPaste() }, enabled = canPaste, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) })
        DropdownMenuItem({ Text("Paper style: ${paperLabel(page.paper)}") }, { onDismiss(); onPaper() }, enabled = page.pdfIndex == null, leadingIcon = { Icon(Icons.Rounded.GridOn, null) })
        DropdownMenuItem({ Text(if (snapEnabled) "Snap to grid: on" else "Snap to grid: off") }, { onDismiss(); onSnap() }, leadingIcon = { Icon(if (snapEnabled) Icons.Rounded.GridView else Icons.Rounded.GridOff, null) })
        DropdownMenuItem({ Text("Clear page") }, { onDismiss(); onClear() }, enabled = page.strokes.isNotEmpty() || page.texts.isNotEmpty() || page.images.isNotEmpty(), leadingIcon = { Icon(Icons.Rounded.LayersClear, null) })
        if (saveFailed) DropdownMenuItem({ Text("Retry save") }, { onDismiss(); onRetry() }, leadingIcon = { Icon(Icons.Rounded.Save, null) })
    }
}

/** One row of the page browser: a rendered thumbnail, its details, and reorder/duplicate/delete. */
@Composable private fun PageRow(
    page: NotePage, index: Int, current: Boolean, dragging: Boolean, modifier: Modifier = Modifier,
    onOpen: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onDuplicate: () -> Unit, onInsert: () -> Unit, onDelete: () -> Unit,
    onName: () -> Unit, onBookmark: () -> Unit, onMoveTo: () -> Unit,
    canMoveUp: Boolean, canMoveDown: Boolean, canDelete: Boolean,
    noteId: String, thumbnails: PageThumbnailCache
) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth(),
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (dragging) 6.dp else 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageThumbnail(noteId, page, thumbnails, Modifier.width(56.dp).aspectRatio(page.width / page.height).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                Text(page.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A page that has not been read yet cannot say how much ink it holds.
                val detail = "Page ${index + 1} · " + (if (page.pdfIndex != null) "Imported PDF" else paperLabel(page.paper))
                Text(
                    if (page.loaded) "$detail · ${page.strokes.size} marks" else detail,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onBookmark) { Icon(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (page.bookmarked) "Remove bookmark" else "Bookmark page", tint = MaterialTheme.colorScheme.primary) }
            if (current) Icon(Icons.Rounded.Check, "Current page", tint = MaterialTheme.colorScheme.primary)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Page ${index + 1} options") }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                    DropdownMenuItem({ Text("Name page") }, { menu = false; onName() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("Move to position…") }, { menu = false; onMoveTo() }, leadingIcon = { Icon(Icons.Rounded.LowPriority, null) })
                    DropdownMenuItem({ Text("Move up") }, { menu = false; onMoveUp() }, enabled = canMoveUp, leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null) })
                    DropdownMenuItem({ Text("Move down") }, { menu = false; onMoveDown() }, enabled = canMoveDown, leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null) })
                    DropdownMenuItem({ Text("Duplicate page") }, { menu = false; onDuplicate() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    DropdownMenuItem({ Text("Insert blank page after") }, { menu = false; onInsert() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                    DropdownMenuItem({ Text("Delete page") }, { menu = false; onDelete() }, enabled = canDelete, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                }
            }
        }
    }
}

/**
 * A page preview from the shared cache. The key carries the page's revision, so editing a page
 * simply fetches a new preview, and a preview that was drawn before is shown without reading the
 * page file at all.
 */
@Composable private fun PageThumbnail(noteId: String, page: NotePage, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val widthPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    var preview by remember(noteId, page.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(noteId, page.id, page.revision, page.loaded) {
        preview = thumbnails.thumbnail(noteId, page, widthPx)
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
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
    val hasShape = remember(originals) { originals.any { it.tool == Tool.LINE || it.tool == Tool.RECTANGLE || it.tool == Tool.ELLIPSE } }
    FolioPanel(title = "Restyle selection", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Text("${originals.size} ${if (originals.size == 1) "stroke" else "strokes"}. Leave a control alone to keep it as it is.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(color == null, { color = null }, { Text("Keep") })
                Spacer(Modifier.width(6.dp))
                quickColors.forEach { option -> InkColorDot(option, color == option, { color = option }, touch = 38.dp, dot = 26.dp, label = "Selection colour") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Thickness", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
                Slider(scale, { scale = it }, valueRange = 0.5f..3f, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.ROOT, "%.1fx", scale), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            if (hasShape) {
                Text("Line style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(lineStyle == null, { lineStyle = null }, { Text("Keep") })
                    FilterChip(lineStyle == StrokeStyle.SOLID, { lineStyle = StrokeStyle.SOLID }, { Text("Solid") })
                    FilterChip(lineStyle == StrokeStyle.DASHED, { lineStyle = StrokeStyle.DASHED }, { Text("Dashed") })
                    FilterChip(lineStyle == StrokeStyle.DOTTED, { lineStyle = StrokeStyle.DOTTED }, { Text("Dotted") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Opacity", style = MaterialTheme.typography.labelMedium)
                    Text("Off keeps each stroke's own fade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(fade, { fade = it })
            }
            if (fade) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(opacity, { opacity = it }, valueRange = 0.15f..1f, modifier = Modifier.weight(1f))
                Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onDismiss) { Text("Cancel") }
                Button({ onApply(color, scale.takeIf { it != 1f }, opacity.takeIf { fade }, lineStyle) }) { Text("Apply") }
            }
        }
    }
}

/** Creates or edits a typed text box: wording, size, weight, italics, alignment and colour. */
@Composable private fun TextBoxDialog(
    box: TextBox, isNew: Boolean, colors: List<Int>,
    onDismiss: () -> Unit, onCreate: (TextBox) -> Unit, onUpdate: (TextBox) -> Unit, onDelete: () -> Unit
) {
    val textFocus = remember(box.id) { FocusRequester() }
    var text by remember(box.id) { mutableStateOf(box.text) }
    var size by remember(box.id) { mutableFloatStateOf(box.size) }
    var color by remember(box.id) { mutableIntStateOf(box.color) }
    var bold by remember(box.id) { mutableStateOf(box.bold) }
    var italic by remember(box.id) { mutableStateOf(box.italic) }
    var align by remember(box.id) { mutableStateOf(box.align) }
    var underline by remember(box.id) { mutableStateOf(box.underline) }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.TextFields, null) },
        title = { Text(if (isNew) "Add text" else "Edit text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(textFocus),
                    label = { Text("Text") }, placeholder = { Text("Write a heading, a label or a note…") })
                LaunchedEffect(box.id) { textFocus.requestFocus() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.FormatSize, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(size, { size = it }, valueRange = TextBox.MIN_SIZE..TextBox.MAX_SIZE, modifier = Modifier.weight(1f))
                    Text("${size.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(30.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(bold, { bold = !bold }, { Text("Bold") })
                    FilterChip(italic, { italic = !italic }, { Text("Italic") })
                    FilterChip(underline, { underline = !underline }, { Text("Underline") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(align == TextAlignMode.LEFT, { align = TextAlignMode.LEFT }, { Text("Left") })
                    FilterChip(align == TextAlignMode.CENTER, { align = TextAlignMode.CENTER }, { Text("Centre") })
                    FilterChip(align == TextAlignMode.RIGHT, { align = TextAlignMode.RIGHT }, { Text("Right") })
                }
                Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    colors.forEach { option -> InkColorDot(option, option == color, { color = option }, touch = 36.dp, dot = 24.dp, label = "Text colour") }
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
        confirmButton = {
            Button({ val edited = box.copy(text = text.trimEnd(), size = size, color = color, bold = bold, italic = italic, align = align, underline = underline); if (isNew) onCreate(edited) else onUpdate(edited) }, enabled = text.isNotBlank()) {
                Text(if (isNew) "Add text" else "Save")
            }
        }
    )
}
