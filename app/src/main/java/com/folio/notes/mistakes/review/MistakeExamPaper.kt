@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes.review

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.toolbar.ToolOptions
import com.folio.notes.ink.InkView
import com.folio.notes.input.LocalStylusActivity
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeAttachment
import com.folio.notes.mistakes.richtext.RichText
import com.folio.notes.mistakes.sync.ExamContext
import com.folio.notes.mistakes.sync.MistakeAttachmentRepository
import com.folio.notes.model.NotePage
import com.folio.notes.model.Tool
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.editor.EditorToolState
import com.folio.notes.editor.toolbar.EditorQuickPrefs
import com.folio.notes.editor.toolbar.FloatingInkToolbar
import com.folio.notes.editor.toolbar.QuickColorsState
import com.folio.notes.editor.toolbar.ToolPresetState
import com.folio.notes.editor.toolbar.ExamTopBar
import com.folio.notes.editor.toolbar.ToolbarOptions
import com.folio.notes.editor.toolbar.ToolbarSlot
import androidx.compose.ui.graphics.compositeOver
import com.folio.notes.FolioApplication
import com.folio.notes.editor.rememberEditorPreferences
import com.folio.notes.input.StylusShortcut
import com.folio.notes.input.StylusShortcutEffect
import com.folio.notes.input.StylusShortcutManager
import com.folio.notes.input.StylusShortcuts
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioShapes
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import com.folio.notes.ink.InkRenderer
import com.folio.notes.export.exportCacheDir
import com.folio.notes.export.pruneExportCache
import com.folio.notes.export.uniqueShareFilename
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.launch
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.workspace.WorkspaceViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Sizes for the exam-paper response. Sheet units are the practice page's own units: the first
 * answer line's top edge is page y = 0, so answers stay on their lines whatever the question above
 * them does, and question highlights sit at negative y.
 */
internal object ExamSheet {
    const val WIDTH = 640f
    const val MARGIN = 44f
    const val LINE_GAP = 34f
    const val GUTTER = 16f
    const val CONTENT = WIDTH - 2 * MARGIN
    /** About three lines a mark, as on VCAA papers; [extra] is what the student added. */
    fun lines(marks: Double?, extra: Int): Int =
        ((marks?.takeIf { it > 0 && it.isFinite() }?.let { ceil(it * 3).toInt() } ?: 8).coerceIn(4, 30) + extra).coerceIn(2, 90)
    fun marksLabel(marks: Double?): String? = marks?.takeIf { it > 0 && it.isFinite() }?.let {
        val n = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        "($n mark${if (it == 1.0) "" else "s"})"
    }
    /** VCAA papers allow about 1.5 minutes a mark. */
    fun paceSeconds(marks: Double?): Long? = marks?.takeIf { it > 0 && it.isFinite() }?.let { (it * 90).roundToLong() }
    private val trailingMarks = Regex("""\s*\(\s*\d+(\.\d+)?\s*marks?\s*\)\s*$""", RegexOption.IGNORE_CASE)
    /** The question text without a trailing "(N marks)" when the header already shows the marks. */
    fun questionBody(text: String?, marks: Double?): String? =
        text?.let { if (marksLabel(marks) != null) it.replace(trailingMarks, "") else it }?.takeIf(String::isNotBlank)
    fun imageWidth(raw: Float): Float = if (raw.isFinite()) raw.coerceIn(.2f, 1f) else 1f
    fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
    fun linesKey(mistakeId: String) = "mistake.exam.lines.$mistakeId"
    fun widthKey(storagePath: String) = "mistake.exam.imageWidth.$storagePath"
    fun aspectKey(storagePath: String) = "mistake.exam.imageAspect.$storagePath"
    /** The editor toolbar slots the sheet keeps; everything else is hidden from its strip and menu. */
    val SLOTS = setOf(ToolbarSlot.PEN, ToolbarSlot.SHAPES, ToolbarSlot.HIGHLIGHTER, ToolbarSlot.ERASER)
    val TOOLS = ShapePickerTools + setOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER)

    /**
     * Dark paper: inverts lightness and turns hue half way round, which restores the original hue
     * (a "smart invert"), then lifts black to a dark grey. White paper becomes #242424-ish, a step above the desk, black
     * ink near-white, and coloured ink and highlights keep their colour family. The hue matrix's rows
     * sum to 1, so inverting before or after it is the same.
     */
    fun nightMatrix(): FloatArray {
        val hue = floatArrayOf(-.574f, 1.430f, .144f, .426f, .430f, .144f, .426f, 1.430f, -.856f)
        val k = .82f; val offset = 255 * k + 36
        return FloatArray(20).also { m ->
            for (r in 0..2) { for (c in 0..2) m[r * 5 + c] = -k * hue[r * 3 + c]; m[r * 5 + 4] = offset }
            m[18] = 1f
        }
    }
    /** The [nightMatrix] as a render effect for the sheet and its ink. */
    @androidx.annotation.RequiresApi(31)
    fun nightEffect() = android.graphics.RenderEffect.createColorFilterEffect(
        android.graphics.ColorMatrixColorFilter(nightMatrix())).asComposeRenderEffect()

    /**
     * Keeps ink on the question when a centred picture is resized by [scale]. The picture's bottom
     * edge ([bottom], page units) stays put because the question grows upwards, so ink above the
     * picture's old [top] moves with the text there and ink on the picture scales with it about
     * its bottom centre ([centreX]). Ink below stays. The same list comes back when nothing moved.
     */
    fun reflow(strokes: List<Stroke>, top: Float, bottom: Float, centreX: Float, scale: Float): List<Stroke> {
        if (scale == 1f || !scale.isFinite() || scale <= 0f || bottom <= top) return strokes
        val shift = (bottom - top) * (scale - 1f)
        var changed = false
        val out = strokes.map { stroke ->
            if (stroke.points.isEmpty()) return@map stroke
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            stroke.points.forEach { lo = minOf(lo, it.y); hi = maxOf(hi, it.y) }
            val middle = (lo + hi) / 2
            when {
                middle >= bottom -> stroke
                middle < top -> { changed = true; stroke.copy(points = stroke.points.map { InkPoint(it.x, it.y - shift, it.pressure) }) }
                else -> { changed = true; stroke.copy(points = stroke.points.map {
                    InkPoint(centreX + (it.x - centreX) * scale, bottom + (it.y - bottom) * scale, it.pressure) }) }
            }
        }
        return if (changed) out else strokes
    }
}

private val paperInk = Color(0xFF1B1B1B)

/**
 * The question printed on an exam sheet with ruled answer space beneath, written on with the
 * editor's own ink surface. The sheet is laid out at a fixed width in page units and drawn under a
 * transparent [InkView] that follows the practice page's canvas camera, so ink, highlights on the
 * question and the printed lines move and zoom together.
 */
@Composable internal fun MistakeExamPaper(
    m: ExamTrackMistake, context: ExamContext?, user: String, attachments: MistakeAttachmentRepository,
    page: NotePage?, folio: FolioViewModel, state: FolioState, finger: Boolean, shapes: Boolean, haptics: Boolean,
    busy: Boolean, revealed: Boolean, onSettings: () -> Unit, modifier: Modifier = Modifier,
) {
    val prefs = rememberPrefs()
    val androidContext = LocalContext.current
    // Widths, pressure and brushes are the editor's own saved tool options.
    val inkPrefs = remember(androidContext) { androidContext.getSharedPreferences("ink-tools", 0) }
    val stylus = LocalStylusActivity.current
    val editorPrefs by rememberEditorPreferences(prefs, shapes)
    val penSmoothing by rememberPref(prefs, AppPrefs.PEN_SMOOTHING) { AppPrefs.penSmoothing(it.getFloat(AppPrefs.PEN_SMOOTHING, AppPrefs.DEFAULT_PEN_SMOOTHING)) }
    val shapeHoldMs by rememberPref(prefs, AppPrefs.SHAPE_HOLD_MS) { AppPrefs.shapeHoldMs(it.getLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.DEFAULT_SHAPE_HOLD_MS)) }
    // The editor's own tool state over the editor's saved tool settings: the same widths, brushes,
    // quick colours and presets, and the same single-stroke eraser return.
    val tools = remember(inkPrefs) { EditorToolState(inkPrefs, Tool.PEN) }
    DisposableEffect(tools) { onDispose { tools.flushSave() } }
    var tool by tools.tool
    val options by tools.options
    val quick = remember(inkPrefs) { QuickColorsState(inkPrefs) }
    val presets = remember(inkPrefs) { ToolPresetState(inkPrefs) }
    var palette by remember { mutableStateOf(false) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val toolbarOptions by rememberPref(prefs, *ToolbarOptions.KEYS) { ToolbarOptions.read(it) }
    var extraLines by remember(m.id) { mutableIntStateOf(prefs.getInt(ExamSheet.linesKey(m.id), 0)) }
    val lines = ExamSheet.lines(m.totalMarks, extraLines)
    var resizing by remember(m.id) { mutableStateOf(false) }
    val imageWidths = remember(m.id) { mutableStateMapOf<String, Float>() }
    val imageRects = remember(m.id) { mutableStateMapOf<String, Rect>() }
    var questionHeight by remember(m.id) { mutableFloatStateOf(0f) }
    var cam by remember { mutableStateOf(WorkspaceViewport()) }
    var fitted by remember(m.id) { mutableStateOf<WorkspaceViewport?>(null) }
    var inkView by remember { mutableStateOf<InkView?>(null) }
    // Exam pacing: the clock stops once the answer is revealed.
    var elapsed by remember(m.id) { mutableLongStateOf(0L) }
    LaunchedEffect(m.id, revealed) { while (!revealed) { delay(1000); elapsed++ } }
    val active = if (resizing) Tool.HAND else tool
    fun selectTool(value: Tool) {
        resizing = false
        if (value !in ExamSheet.TOOLS) return
        if (tools.select(value, editorPrefs.eraserSingleStroke) && StylusShortcuts.isDrawingTool(value)) tools.previousTool.value = value
    }
    fun optionsFor(t: Tool): ToolOptions = tools.optionsFor(t)
    fun setPref(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    // Pen haptics and writing feedback, as in the editor.
    val app = androidContext.applicationContext as? FolioApplication
    val penHaptics = app?.penHaptics
    val penFeedback = app?.penFeedback
    val writingFeedback by rememberPref(prefs, AppPrefs.WRITING_FEEDBACK) { it.getBoolean(AppPrefs.WRITING_FEEDBACK, AppPrefs.DEFAULT_WRITING_FEEDBACK) }
    DisposableEffect(penHaptics, haptics) {
        if (haptics) penHaptics?.start() else penHaptics?.stop()
        onDispose { penHaptics?.stop() }
    }
    DisposableEffect(writingFeedback) {
        if (writingFeedback) penFeedback?.start()
        onDispose { penFeedback?.stop() }
    }
    // The pen's button / double-tap: the configured shortcut, limited to the sheet's tools.
    fun applyShortcut(action: StylusShortcut) {
        val effect = StylusShortcuts.effect(action, tool, tools.previousTool.value)
        val handled = when (effect) {
            is StylusShortcutEffect.SwitchTool -> (effect.tool in ExamSheet.TOOLS).also { ok ->
                if (ok) {
                    selectTool(effect.tool)
                    // The double-tap is a broadcast, not input: hand the view its tool now, not on the next frame.
                    inkView?.let { view ->
                        val o = tools.options.value
                        view.tool = effect.tool; view.inkColor = o.color; view.inkWidth = o.width; view.inkOpacity = o.opacity
                        view.pressureEnabled = o.pressure; view.pressureSensitivity = o.pressureSensitivity
                        view.pressureVariation = o.pressureVariation; view.pressureCurve = o.pressureCurve
                    }
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                }
            }
            StylusShortcutEffect.OpenPalette -> true.also { palette = true }
            StylusShortcutEffect.Undo -> true.also { folio.undo() }
            StylusShortcutEffect.NextLine, StylusShortcutEffect.None -> false
        }
        if (handled && haptics && penFeedback?.pulse() != true) penHaptics?.pulse()
    }
    val shortcutHandler = rememberUpdatedState<(StylusShortcut) -> Unit> { applyShortcut(it) }
    val shortcuts = remember(androidContext) { StylusShortcutManager(androidContext) }
    DisposableEffect(shortcuts) {
        shortcuts.start { shortcutHandler.value(StylusShortcut.of(prefs.getString(StylusShortcut.PREF_KEY, null))) }
        onDispose { shortcuts.stop() }
    }
    // Where a picture's resize started, so its release can carry the ink on the question with it.
    val resizeStart = remember { mutableMapOf<String, Triple<Rect, Float, Float>>() }
    fun endResize(key: String) {
        val width = imageWidths[key] ?: 1f
        prefs.edit().putFloat(ExamSheet.widthKey(key), width).apply()
        val (rect, from, height) = resizeStart.remove(key) ?: return
        val current = inkView?.page?.takeIf { it.loaded && it.id == page?.id } ?: return
        val moved = ExamSheet.reflow(current.strokes, rect.top - height, rect.bottom - height, rect.center.x, width / from)
        if (moved !== current.strokes) folio.pageMutations(current.id).strokes(moved)
    }
    val sheetLayer = rememberGraphicsLayer()
    var sheetScale by remember { mutableFloatStateOf(1f) }
    val shareScope = rememberCoroutineScope()
    fun share(questionOnly: Boolean) {
        val text = mistakeShareText(m, if (questionOnly) emptyList() else workingTexts(page))
        if (questionOnly) { shareText(androidContext, text); return }
        shareScope.launch {
            val png = runCatching {
                // The whole sheet as laid out (always the white paper), with the page's ink drawn on in sheet pixels.
                val bitmap = sheetLayer.toImageBitmap().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                inkView?.page?.takeIf { it.loaded }?.let { ink ->
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.translate(0f, questionHeight * sheetScale); canvas.scale(sheetScale, sheetScale)
                    InkRenderer.overlay(canvas, ink)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dir = exportCacheDir(androidContext.cacheDir)
                    pruneExportCache(dir)
                    File(dir, uniqueShareFilename("exam-answer.png")).also { out ->
                        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }.getOrNull()
            if (png == null) shareText(androidContext, text)
            else { copyText(androidContext, text); shareText(androidContext, null, png) }
        }
    }
    // A dark theme darkens the paper and its ink together, keeping hues, so what is shared stays the real sheet.
    val night = MaterialTheme.colorScheme.surface.luminance() < .5f && android.os.Build.VERSION.SDK_INT >= 31
    val nightEffect = remember(night) { if (night) ExamSheet.nightEffect() else null }
    // The desk sits a step below the review's surface so the sheet reads as paper in either theme.
    BoxWithConstraints(modifier.clipToBounds().background(if (night) Color.Black else MaterialTheme.colorScheme.surfaceDim)) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val screenDensity = LocalDensity.current.density
        // The sheet first fits below the floating strip; the ink options bar floats over it.
        val toolbarRoom = (if (toolbarHeight > 0) toolbarHeight.toFloat() else 56 * screenDensity) + 16 * screenDensity
        // Sheet px per unit when it fits the width; capped so a wide tablet shows a page, not a poster.
        val sheetDensity = minOf(viewW / (ExamSheet.WIDTH + 2 * ExamSheet.GUTTER), screenDensity * 1.6f).coerceAtLeast(.5f)
        SideEffect { sheetScale = sheetDensity }
        fun fit() = WorkspaceViewport(canvasX = (viewW - ExamSheet.WIDTH * sheetDensity) / 2,
            canvasY = toolbarRoom + questionHeight * sheetDensity, canvasZoom = sheetDensity)
        // Fit until the student moves the sheet: pictures loading above the lines grow the
        // question upwards, so the camera follows them while nobody has panned yet.
        LaunchedEffect(inkView, questionHeight, sheetDensity, page?.id, fitted) {
            val view = inkView ?: return@LaunchedEffect
            if (questionHeight <= 0f || !view.page.infinite) return@LaunchedEffect
            val target = fit()
            if (fitted == null || cam == fitted) { view.returnToCanvasView(target); cam = target; fitted = target }
        }
        // Revealing the answer scrolls the marking guide into view under the last lines.
        LaunchedEffect(revealed) {
            val view = inkView ?: return@LaunchedEffect
            if (!revealed) return@LaunchedEffect
            val guideTop = (lines * ExamSheet.LINE_GAP + ExamSheet.MARGIN) * cam.canvasZoom + cam.canvasY
            if (guideTop > viewH * .7f) view.returnToCanvasView(cam.copy(canvasY = cam.canvasY - (guideTop - viewH * .45f)))
        }
        Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = nightEffect }) {
            CompositionLocalProvider(LocalDensity provides Density(sheetDensity, 1f)) {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val s = cam.canvasZoom / sheetDensity
                    scaleX = s; scaleY = s
                    translationX = cam.canvasX
                    translationY = cam.canvasY - questionHeight * cam.canvasZoom
                    alpha = if (fitted == null) 0f else 1f
                }.drawWithContent {
                    // Recorded for sharing; the record holds the whole sheet, not just what is on screen.
                    sheetLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(sheetLayer)
                }) {
                    ExamSheetContent(m, context, user, attachments, prefs, lines, revealed, night, imageWidths,
                        onQuestionHeight = { questionHeight = it }, onImageRect = { key, rect -> imageRects[key] = rect })
                }
            }
            if (page != null && page.loaded) AndroidView(
                factory = { c -> InkView(c).apply {
                    paperless = true
                    contentDescription = "Answer space. Write on the lines with a pen; highlight the question with the highlighter. Use two fingers to scroll and zoom."
                }.also { inkView = it } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { if (inkView === it) inkView = null },
                update = { view ->
                    view.inputStylusActivity = stylus
                    if (view.page !== page) { view.bind(page, null); view.restoreWorkspaceCamera(null) }
                    view.onWorkspaceCamera = { cam = it }
                    view.frontBuffer = !night
                    val options = if (resizing) ToolOptions.load(inkPrefs, Tool.HAND) else options
                    view.tool = active
                    view.inkColor = options.color; view.inkWidth = options.width; view.inkOpacity = options.opacity
                    view.inkStyle = options.style; view.pressureEnabled = options.pressure
                    view.pressureSensitivity = options.pressureSensitivity; view.pressureVariation = options.pressureVariation
                    view.pressureCurve = options.pressureCurve
                    view.penStartOptionsFor = ::optionsFor
                    view.penSmoothing = penSmoothing
                    view.fingerDrawing = finger
                    view.shapeRecognition = editorPrefs.shapeRecognition; view.shapeHoldMs = shapeHoldMs
                    view.snapEnabled = editorPrefs.snapEnabled; view.graphStyle = editorPrefs.graphStyle
                    view.shapeMeasurements = editorPrefs.shapeMeasurements
                    view.eraserPressureEnabled = editorPrefs.eraserPressure; view.eraserWholeStroke = editorPrefs.eraserWholeStroke
                    view.scribbleToErase = editorPrefs.scribbleToErase; view.scribbleSensitivity = editorPrefs.scribbleSensitivity
                    view.scribbleHold = editorPrefs.scribbleHold
                    view.multiTouchUndo = editorPrefs.multiTouchUndo
                    view.palmRejectMs = editorPrefs.palmRejectMs; view.panMultiplier = editorPrefs.panMultiplier
                    view.maxZoom = AppPrefs.maxZoom(prefs.getFloat(AppPrefs.MAX_ZOOM, AppPrefs.DEFAULT_MAX_ZOOM))
                    view.inputBlocked = busy
                    view.onEraserFinished = { tools.finishEraser(editorPrefs.eraserSingleStroke) }
                    val mutations = folio.pageMutations(page.id)
                    view.onStrokesChanged = mutations::strokes
                    view.onStrokeAppended = mutations::appendStroke
                    view.onStrokeWithdrawn = mutations::withdrawStroke
                    view.onPenInput = mutations::penActivity
                    view.onContentChanged = mutations::content
                    view.onTextsChanged = mutations::texts
                    view.onImagesChanged = mutations::images
                    view.onUndoRequest = folio::undo; view.onRedoRequest = folio::redo
                },
            )
        }
        if (page == null || !page.loaded) LoadingIndicator(Modifier.align(Alignment.Center))
        if (resizing) imageRects.forEach { (key, rect) ->
            ResizeHandle(key, rect, cam, questionHeight, imageWidths[key] ?: 1f,
                onStart = { resizeStart.getOrPut(key) { Triple(rect, imageWidths[key] ?: 1f, questionHeight) } },
                onWidth = { imageWidths[key] = it },
                onDone = { endResize(key) })
        }
        // The notebook screen's own bar and tool strip, cut down to the sheet's tools, with the
        // exam's controls either side, on the review's own surface so the header reads as one band.
        MaterialTheme(colorScheme = barColors(MaterialTheme.colorScheme)) {
            FloatingInkToolbar(
                modifier = Modifier.align(Alignment.TopCenter),
                onMainHeight = { toolbarHeight = it },
                tool = tool, onTool = ::selectTool, options = options, onOptions = tools::changeOptions, quick = quick,
                canUndo = state.canUndo && !busy, canRedo = state.canRedo && !busy, undo = folio::undo, redo = folio::redo,
                palette = palette, onPalette = { palette = it },
                snapEnabled = editorPrefs.snapEnabled, onSnap = { setPref("mathSnap", it) },
                eraserSingleStroke = editorPrefs.eraserSingleStroke, onEraserSingleStroke = { setPref(EditorQuickPrefs.ERASER_SINGLE_STROKE, it) },
                scribbleToErase = editorPrefs.scribbleToErase, onScribbleToErase = { setPref(EditorQuickPrefs.SCRIBBLE_TO_ERASE, it) },
                eraserPressureEnabled = editorPrefs.eraserPressure, onEraserPressure = { setPref(EditorQuickPrefs.ERASER_PRESSURE, it) },
                eraserWholeStroke = editorPrefs.eraserWholeStroke, onEraserWholeStroke = { setPref(EditorQuickPrefs.ERASER_WHOLE_STROKE, it) },
                shapeMeasurements = editorPrefs.shapeMeasurements, onShapeMeasurements = { setPref(EditorQuickPrefs.SHAPE_MEASUREMENTS, it) },
                multiTouchUndo = editorPrefs.multiTouchUndo, onMultiTouchUndo = { setPref(EditorQuickPrefs.MULTI_TOUCH_UNDO, it) },
                presets = presets.presets.filter { it.tool in ExamSheet.TOOLS },
                onApplyPreset = { preset -> if (preset.tool in ExamSheet.TOOLS) { selectTool(preset.tool); tools.apply(preset) } },
                toolPresetsState = presets,
                hiddenSlots = ToolbarSlot.entries.toSet() - ExamSheet.SLOTS,
                toolbarOptions = toolbarOptions,
                header = { mainTools ->
                    ExamTopBar(
                        mainTools = mainTools,
                        timer = { ExamClock(elapsed, ExamSheet.paceSeconds(m.totalMarks)) },
                        onMoreLines = { extraLines += 4; prefs.edit().putInt(ExamSheet.linesKey(m.id), extraLines).apply() },
                        onFewerLines = if (lines > 2 && extraLines > -30) ({ extraLines -= 2; prefs.edit().putInt(ExamSheet.linesKey(m.id), extraLines).apply() }) else null,
                        resizing = resizing,
                        onResize = if (m.attachments.isNotEmpty()) ({ resizing = !resizing }) else null,
                        onFit = { fitted = null },
                        onShare = { share(false) }, onShareQuestion = { share(true) },
                        onSettings = onSettings,
                    )
                },
            )
        }
    }
}

/** The printed page: header, question, pictures, ruled answer space and, once revealed, the guide. */
@Composable private fun ExamSheetContent(
    m: ExamTrackMistake, context: ExamContext?, user: String, attachments: MistakeAttachmentRepository,
    prefs: SharedPreferences, lines: Int, revealed: Boolean, night: Boolean, imageWidths: MutableMap<String, Float>,
    onQuestionHeight: (Float) -> Unit, onImageRect: (String, Rect) -> Unit,
) {
    val unit = LocalDensity.current.density
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val body = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 25.sp, color = paperInk)
    // Paper is white in a dark theme too; rich text and maths read their colours from the theme.
    MaterialTheme(colorScheme = lightColorScheme(onSurface = paperInk, onSurfaceVariant = Color(0xFF55575A))) {
        // Inverted, a shadow would glow around dark paper, so the dark sheet stands on the desk's contrast alone.
        Column(Modifier.requiredWidth(ExamSheet.WIDTH.dp).then(if (night) Modifier else Modifier.shadow(3.dp)).background(Color.White)
            .onGloballyPositioned { root[0] = it }) {
            Column(Modifier.fillMaxWidth().padding(start = ExamSheet.MARGIN.dp, end = ExamSheet.MARGIN.dp, top = ExamSheet.MARGIN.dp, bottom = 6.dp)
                .onSizeChanged { onQuestionHeight(it.height / unit) }) {
                context?.let { c ->
                    val crumb = listOf(c.subject, c.title, c.paper).filter(String::isNotBlank).joinToString(" · ")
                    if (crumb.isNotBlank()) Text(crumb.uppercase(), Modifier.padding(bottom = 14.dp), fontSize = 10.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B6D70), fontFamily = FontFamily.SansSerif)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(m.question, Modifier.weight(1f, fill = false), fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = paperInk)
                    ExamSheet.marksLabel(m.totalMarks)?.let { Text(" $it", fontFamily = FontFamily.SansSerif, fontSize = 17.sp, color = paperInk) }
                }
                ExamSheet.questionBody(m.questionText, m.totalMarks)?.let { RichText(it, Modifier.padding(top = 12.dp), style = body) }
                m.attachments.forEach { attachment ->
                    ExamImage(attachment, user, attachments, prefs, imageWidths) { coords ->
                        root[0]?.takeIf { it.isAttached && coords.isAttached }?.let { r ->
                            val box = r.localBoundingBoxOf(coords)
                            onImageRect(attachment.storagePath, Rect(box.left / unit, box.top / unit, box.right / unit, box.bottom / unit))
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height((lines * ExamSheet.LINE_GAP + ExamSheet.MARGIN).dp)) {
                val left = ExamSheet.MARGIN.dp.toPx(); val right = size.width - left
                for (i in 1..lines) {
                    val y = (i * ExamSheet.LINE_GAP).dp.toPx()
                    drawLine(Color(0xFFA7A9AC), Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
            }
            if (revealed) Column(Modifier.fillMaxWidth().padding(start = ExamSheet.MARGIN.dp, end = ExamSheet.MARGIN.dp, bottom = ExamSheet.MARGIN.dp)
                .background(Color(0xFFEEF6EC)).border(BorderStroke(1.dp, Color(0xFFB9D7B2))).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Marking guide", fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF2E6B2A))
                RichText(m.correction.ifBlank { "No correction saved in Focal." }, style = body)
                Text("What went wrong", Modifier.padding(top = 6.dp), fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF2E6B2A))
                RichText(m.explanation.ifBlank { "No explanation saved in Focal." }, style = body.copy(fontSize = 15.sp, lineHeight = 22.sp))
            }
        }
    }
}

/**
 * A question picture at its saved width. Its aspect ratio is remembered too, so the space it takes
 * is known before the file loads and highlights over the question do not jump while it does.
 */
@Composable private fun ExamImage(
    attachment: MistakeAttachment, user: String, attachments: MistakeAttachmentRepository,
    prefs: SharedPreferences, widths: MutableMap<String, Float>, onPlaced: (LayoutCoordinates) -> Unit,
) {
    val key = attachment.storagePath
    val context = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(context).components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    var file by remember(user, key) { mutableStateOf<File?>(null) }
    var failed by remember(user, key) { mutableStateOf(false) }
    var aspect by remember(key) { mutableFloatStateOf(prefs.getFloat(ExamSheet.aspectKey(key), 0f)) }
    LaunchedEffect(user, key) {
        try { file = attachments.get(user, attachment) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    LaunchedEffect(key) { if (key !in widths) widths[key] = ExamSheet.imageWidth(prefs.getFloat(ExamSheet.widthKey(key), 1f)) }
    val fraction = widths[key] ?: 1f
    Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
        val sized = Modifier.fillMaxWidth(fraction).aspectRatio(if (aspect > 0f) aspect else 1.6f).onGloballyPositioned(onPlaced)
        when {
            file != null -> AsyncImage(file, attachment.name, imageLoader = loader, modifier = sized, contentScale = ContentScale.Fit,
                onSuccess = { result ->
                    val size = result.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) {
                        val next = size.width / size.height
                        if (kotlin.math.abs(next - aspect) > .001f) { aspect = next; prefs.edit().putFloat(ExamSheet.aspectKey(key), next).apply() }
                    }
                })
            failed -> Text("${attachment.name} · unavailable offline", fontSize = 13.sp, color = Color(0xFF6B6D70))
            else -> Box(sized.background(Color(0xFFF2F2F2)))
        }
    }
}

/** Frames a picture and drags its corner to resize it, mirrored about its centre like the layout. */
@Composable private fun ResizeHandle(
    key: String, rect: Rect, cam: WorkspaceViewport, questionHeight: Float, width: Float,
    onStart: () -> Unit, onWidth: (Float) -> Unit, onDone: () -> Unit,
) {
    val z = cam.canvasZoom
    val left = cam.canvasX + rect.left * z; val top = cam.canvasY + (rect.top - questionHeight) * z
    val right = cam.canvasX + rect.right * z; val bottom = cam.canvasY + (rect.bottom - questionHeight) * z
    val density = LocalDensity.current
    val current by rememberUpdatedState(width)
    val zoom by rememberUpdatedState(z)
    val handle = 44.dp
    val handlePx = with(density) { handle.toPx() }
    with(density) {
        Box(Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size((right - left).coerceAtLeast(1f).toDp(), (bottom - top).coerceAtLeast(1f).toDp())
            .border(2.dp, MaterialTheme.colorScheme.primary))
    }
    Surface(
        Modifier.offset { IntOffset((right - handlePx / 2).roundToInt(), (bottom - handlePx / 2).roundToInt()) }.size(handle)
            .semantics { contentDescription = "Resize picture, ${(width * 100).roundToInt()}% wide. Drag to resize, double-tap for full width." }
            .pointerInput(key) {
                detectDragGestures(onDragStart = { onStart() }, onDragEnd = onDone, onDragCancel = onDone) { change, drag ->
                    change.consume()
                    // The picture is centred, so its edge moves half as far as its width changes.
                    val next = current + 2 * drag.x / zoom / ExamSheet.CONTENT
                    onWidth(ExamSheet.imageWidth((next * 40).roundToInt() / 40f))
                }
            }
            .pointerInput(key) { detectTapGestures(onDoubleTap = { onStart(); onWidth(1f); onDone() }) },
        shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("${(width * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Time on this question against exam pace, as a chip on the bar. */
@Composable private fun ExamClock(elapsed: Long, pace: Long?) {
    val colors = MaterialTheme.colorScheme
    val over = pace != null && elapsed > pace
    Surface(Modifier.semantics(mergeDescendants = true) {
        contentDescription = "Time on this question ${ExamSheet.clock(elapsed)}" + (pace?.let { ", about ${ExamSheet.clock(it)} at exam pace" } ?: "")
    }, shape = CircleShape, color = if (over) colors.errorContainer else colors.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp6),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Icon(Icons.Rounded.Timer, null, Modifier.size(16.dp), tint = if (over) colors.onErrorContainer else colors.primary)
            Text(ExamSheet.clock(elapsed) + (pace?.let { " / ${ExamSheet.clock(it)}" } ?: ""),
                style = MaterialTheme.typography.labelLarge, color = if (over) colors.onErrorContainer else colors.onSurface)
        }
    }
}

/** The bar on the review's surface, as the embedded editor is in Write mode: one header band, not two tones. */
private fun barColors(base: ColorScheme): ColorScheme = base.copy(
    surfaceContainerHigh = base.surface,
    surfaceBright = base.surface,
)
