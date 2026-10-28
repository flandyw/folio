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
import com.folio.notes.editor.toolbar.ToolIcons
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
    fun imageWidth(raw: Float): Float = if (raw.isFinite()) raw.coerceIn(.2f, 1f) else 1f
    fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
    fun linesKey(mistakeId: String) = "mistake.exam.lines.$mistakeId"
    fun widthKey(storagePath: String) = "mistake.exam.imageWidth.$storagePath"
    fun aspectKey(storagePath: String) = "mistake.exam.imageAspect.$storagePath"
    fun colorKey(tool: Tool) = "mistake.exam.${tool.name}.color"
    val PEN_COLORS = listOf(0xFF1B1B1B.toInt(), 0xFF1F4EB4.toInt(), 0xFFC62828.toInt())
    val HIGHLIGHT_COLORS = listOf(0xFFFFE135.toInt(), 0xFF7CE36F.toInt(), 0xFFFF8FC7.toInt())
    val SHAPES = listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE, Tool.GRAPH)

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
    page: NotePage?, folio: FolioViewModel, state: FolioState, finger: Boolean, shapes: Boolean,
    busy: Boolean, revealed: Boolean, modifier: Modifier = Modifier,
) {
    val prefs = rememberPrefs()
    val stylus = LocalStylusActivity.current
    val penSmoothing by rememberPref(prefs, AppPrefs.PEN_SMOOTHING) { AppPrefs.penSmoothing(it.getFloat(AppPrefs.PEN_SMOOTHING, AppPrefs.DEFAULT_PEN_SMOOTHING)) }
    val shapeHoldMs by rememberPref(prefs, AppPrefs.SHAPE_HOLD_MS) { AppPrefs.shapeHoldMs(it.getLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.DEFAULT_SHAPE_HOLD_MS)) }
    var tool by rememberSaveable { mutableStateOf(Tool.PEN) }
    var shapeTool by rememberSaveable { mutableStateOf(Tool.LINE) }
    var penColor by remember { mutableIntStateOf(prefs.getInt(ExamSheet.colorKey(Tool.PEN), ExamSheet.PEN_COLORS[0])) }
    var highlightColor by remember { mutableIntStateOf(prefs.getInt(ExamSheet.colorKey(Tool.HIGHLIGHTER), ExamSheet.HIGHLIGHT_COLORS[0])) }
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
    val androidContext = LocalContext.current
    val shareScope = rememberCoroutineScope()
    fun share(questionOnly: Boolean) {
        val text = mistakeShareText(m, if (questionOnly) emptyList() else workingTexts(page))
        if (questionOnly) { shareText(androidContext, text); return }
        shareScope.launch {
            val png = runCatching {
                // The whole sheet as laid out, with the page's ink drawn on in sheet pixels.
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
    Column(modifier) {
        ExamToolbar(
            tool = tool, shapeTool = shapeTool, penColor = penColor, highlightColor = highlightColor,
            onTool = { tool = it; resizing = false; if (it in ExamSheet.SHAPES) shapeTool = it },
            onColor = { t, c ->
                if (t == Tool.HIGHLIGHTER) highlightColor = c else penColor = c
                prefs.edit().putInt(ExamSheet.colorKey(if (t == Tool.HIGHLIGHTER) t else Tool.PEN), c).apply()
            },
            canUndo = state.canUndo && !busy, canRedo = state.canRedo && !busy,
            onUndo = folio::undo, onRedo = folio::redo,
            onMoreLines = { extraLines += 4; prefs.edit().putInt(ExamSheet.linesKey(m.id), extraLines).apply() },
            onFewerLines = if (lines > 2 && extraLines > -30) ({ extraLines -= 2; prefs.edit().putInt(ExamSheet.linesKey(m.id), extraLines).apply() }) else null,
            resizing = resizing, onResize = if (m.attachments.isNotEmpty()) ({ resizing = !resizing }) else null,
            onFit = { fitted = null },
            elapsed = elapsed, pace = ExamSheet.paceSeconds(m.totalMarks), onShare = ::share,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainer)) {
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            val screenDensity = LocalDensity.current.density
            // Sheet px per unit when it fits the width; capped so a wide tablet shows a page, not a poster.
            val sheetDensity = minOf(viewW / (ExamSheet.WIDTH + 2 * ExamSheet.GUTTER), screenDensity * 1.6f).coerceAtLeast(.5f)
            SideEffect { sheetScale = sheetDensity }
            fun fit() = WorkspaceViewport(canvasX = (viewW - ExamSheet.WIDTH * sheetDensity) / 2,
                canvasY = (ExamSheet.GUTTER + questionHeight) * sheetDensity, canvasZoom = sheetDensity)
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
                    ExamSheetContent(m, context, user, attachments, prefs, lines, revealed, imageWidths,
                        onQuestionHeight = { questionHeight = it }, onImageRect = { key, rect -> imageRects[key] = rect })
                }
            }
            if (page == null || !page.loaded) LoadingIndicator(Modifier.align(Alignment.Center))
            else AndroidView(
                factory = { c -> InkView(c).apply {
                    paperless = true; multiTouchUndo = true; scribbleToErase = true
                    contentDescription = "Answer space. Write on the lines with a pen; highlight the question with the highlighter. Use two fingers to scroll and zoom."
                }.also { inkView = it } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { if (inkView === it) inkView = null },
                update = { view ->
                    view.inputStylusActivity = stylus
                    if (view.page !== page) { view.bind(page, null); view.restoreWorkspaceCamera(null) }
                    view.onWorkspaceCamera = { cam = it }
                    val options = when (active) {
                        Tool.HIGHLIGHTER -> ToolOptions.load(prefs, Tool.HIGHLIGHTER).copy(color = highlightColor)
                        in ExamSheet.SHAPES -> ToolOptions.load(prefs, active).copy(color = penColor)
                        else -> ToolOptions.load(prefs, if (active == Tool.ERASER) Tool.ERASER else Tool.PEN).copy(color = penColor)
                    }
                    view.tool = active
                    view.inkColor = options.color; view.inkWidth = options.width; view.inkOpacity = options.opacity
                    view.inkStyle = options.style; view.pressureEnabled = options.pressure
                    view.pressureSensitivity = options.pressureSensitivity; view.pressureVariation = options.pressureVariation
                    view.pressureCurve = options.pressureCurve
                    view.penSmoothing = penSmoothing
                    view.fingerDrawing = finger
                    view.shapeRecognition = shapes; view.shapeHoldMs = shapeHoldMs
                    view.inputBlocked = busy
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
            if (resizing) imageRects.forEach { (key, rect) ->
                ResizeHandle(key, rect, cam, questionHeight, imageWidths[key] ?: 1f,
                    onStart = { resizeStart.getOrPut(key) { Triple(rect, imageWidths[key] ?: 1f, questionHeight) } },
                    onWidth = { imageWidths[key] = it },
                    onDone = { endResize(key) })
            }
        }
    }
}

/** The printed page: header, question, pictures, ruled answer space and, once revealed, the guide. */
@Composable private fun ExamSheetContent(
    m: ExamTrackMistake, context: ExamContext?, user: String, attachments: MistakeAttachmentRepository,
    prefs: SharedPreferences, lines: Int, revealed: Boolean, imageWidths: MutableMap<String, Float>,
    onQuestionHeight: (Float) -> Unit, onImageRect: (String, Rect) -> Unit,
) {
    val unit = LocalDensity.current.density
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val body = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 25.sp, color = paperInk)
    // Paper is white in a dark theme too; rich text and maths read their colours from the theme.
    MaterialTheme(colorScheme = lightColorScheme(onSurface = paperInk, onSurfaceVariant = Color(0xFF55575A))) {
        Column(Modifier.requiredWidth(ExamSheet.WIDTH.dp).shadow(3.dp).background(Color.White)
            .onGloballyPositioned { root[0] = it }) {
            Column(Modifier.fillMaxWidth().padding(start = ExamSheet.MARGIN.dp, end = ExamSheet.MARGIN.dp, top = ExamSheet.MARGIN.dp, bottom = 18.dp)
                .onSizeChanged { onQuestionHeight(it.height / unit) }) {
                context?.let { c ->
                    val crumb = listOf(c.subject, c.title, c.paper).filter(String::isNotBlank).joinToString(" · ")
                    if (crumb.isNotBlank()) Text(crumb, Modifier.padding(bottom = 10.dp), fontSize = 12.sp, color = Color(0xFF6B6D70), fontFamily = FontFamily.SansSerif)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(m.question, Modifier.weight(1f, fill = false), fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = paperInk)
                    ExamSheet.marksLabel(m.totalMarks)?.let { Text(" $it", fontFamily = FontFamily.SansSerif, fontSize = 18.sp, color = paperInk) }
                }
                if (!m.questionText.isNullOrBlank()) RichText(m.questionText, Modifier.padding(top = 10.dp), style = body)
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

/** A slim strip for answering on paper: pens, highlighter, eraser, shapes, undo, space and time. */
@Composable private fun ExamToolbar(
    tool: Tool, shapeTool: Tool, penColor: Int, highlightColor: Int,
    onTool: (Tool) -> Unit, onColor: (Tool, Int) -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    onMoreLines: () -> Unit, onFewerLines: (() -> Unit)?,
    resizing: Boolean, onResize: (() -> Unit)?, onFit: () -> Unit,
    elapsed: Long, pace: Long?, onShare: (questionOnly: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        @Composable fun ToolButton(value: Tool, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean = tool == value && !resizing, onClick: () -> Unit = { onTool(value) }) {
            IconToggleButton(selected, { onClick() }, shapes = IconButtonDefaults.toggleableShapes(),
                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = colors.secondaryContainer, checkedContentColor = colors.onSecondaryContainer)) {
                Icon(icon, label)
            }
        }
        @Composable fun Swatches(of: Tool, options: List<Int>, chosen: Int) {
            options.forEach { c ->
                Box(Modifier.size(32.dp).padding(4.dp).clip(CircleShape).background(Color(c))
                    .then(if (c == chosen) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier.border(1.dp, colors.outlineVariant, CircleShape))
                    .pointerInput(c) { detectTapGestures { onColor(of, c) } }
                    .semantics { contentDescription = "Colour${if (c == chosen) ", selected" else ""}" })
            }
        }
        ToolButton(Tool.PEN, Icons.Rounded.Edit, "Pen")
        if (tool == Tool.PEN || tool in ExamSheet.SHAPES) Swatches(Tool.PEN, ExamSheet.PEN_COLORS, penColor)
        ToolButton(Tool.HIGHLIGHTER, ToolIcons.Highlighter, "Highlighter · mark key words in the question")
        if (tool == Tool.HIGHLIGHTER) Swatches(Tool.HIGHLIGHTER, ExamSheet.HIGHLIGHT_COLORS, highlightColor)
        ToolButton(Tool.ERASER, ToolIcons.Eraser, "Eraser")
        var shapeMenu by remember { mutableStateOf(false) }
        Box {
            // The first tap takes up the last shape; a tap while holding it picks another.
            ToolButton(shapeTool, if (shapeTool == Tool.LINE) ToolIcons.Line else Icons.Rounded.Category, "Shapes", tool in ExamSheet.SHAPES && !resizing,
                onClick = { if (tool in ExamSheet.SHAPES && !resizing) shapeMenu = true else onTool(shapeTool) })
            DropdownMenu(shapeMenu, { shapeMenu = false }) {
                listOf(Tool.LINE to "Straight line", Tool.RECTANGLE to "Rectangle", Tool.ELLIPSE to "Ellipse", Tool.GRAPH to "Axes").forEach { (t, label) ->
                    DropdownMenuItem({ Text(label) }, { shapeMenu = false; onTool(t) },
                        trailingIcon = { if (t == tool) Icon(Icons.Rounded.Check, null) })
                }
            }
        }
        VerticalDivider(Modifier.height(24.dp).padding(horizontal = FolioSpacing.dp4))
        IconButton(onUndo, enabled = canUndo, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
        IconButton(onRedo, enabled = canRedo, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
        VerticalDivider(Modifier.height(24.dp).padding(horizontal = FolioSpacing.dp4))
        IconButton(onMoreLines, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add answer lines") }
        if (onFewerLines != null) IconButton(onFewerLines, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Remove answer lines") }
        if (onResize != null) IconToggleButton(resizing, { onResize() }, shapes = IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = colors.secondaryContainer, checkedContentColor = colors.onSecondaryContainer)) {
            Icon(Icons.Rounded.PhotoSizeSelectLarge, if (resizing) "Done resizing pictures" else "Resize pictures")
        }
        IconButton(onFit, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.FitScreen, "Fit the page to the screen") }
        ShareTextButton(typed = false, onShare = onShare)
        VerticalDivider(Modifier.height(24.dp).padding(horizontal = FolioSpacing.dp4))
        val over = pace != null && elapsed > pace
        Row(Modifier.padding(horizontal = FolioSpacing.dp8).semantics(mergeDescendants = true) {
            contentDescription = "Time on this question ${ExamSheet.clock(elapsed)}" + (pace?.let { ", about ${ExamSheet.clock(it)} at exam pace" } ?: "")
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp), tint = if (over) colors.error else colors.onSurfaceVariant)
            Text(ExamSheet.clock(elapsed) + (pace?.let { " / ${ExamSheet.clock(it)}" } ?: ""),
                style = MaterialTheme.typography.labelLarge, color = if (over) colors.error else colors.onSurfaceVariant)
        }
    }
}
