package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.*

/** Native input surface keeps pointer events and in-progress ink outside Compose recomposition. */
class InkView(context: Context) : View(context) {
    var page = NotePage(); private set
    var background: Bitmap? = null
    var tool = Tool.PEN
        set(value) {
            if (field == value) return
            field = value
            // A selection only makes sense while the lasso is in hand.
            if (value != Tool.LASSO) clearSelection()
            // The outline belongs to the eraser, so it leaves with the tool.
            if (value != Tool.ERASER) eraserMark = null
            // A half-finished text gesture belongs to the text tool.
            if (value != Tool.TEXT) { movingText = null; pendingTextBox = null; textDx = 0f; textDy = 0f }
            // A half-dragged picture belongs to the hand tool.
            if (value != Tool.HAND) { movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null }
        }
    var inkColor = Color.rgb(47, 49, 47)
    var inkWidth = 3f
    var fingerDrawing = true
    var inkOpacity = 1f
    /** Line pattern for new shape strokes; freehand ink always draws solid. */
    var inkStyle: StrokeStyle = StrokeStyle.SOLID
    var pressureEnabled = true
    var pressureSensitivity = 1f
    var pressureVariation = 1f
    var eraserPressureEnabled = true
    var scribbleToErase = true
    var eraserWholeStroke = false
    var shapeMeasurements = true
    var multiTouchUndo = true
    var onEraserFinished: (() -> Unit)? = null
    var onUndoRequest: (() -> Unit)? = null
    var onRedoRequest: (() -> Unit)? = null
    /** When true, shape endpoints snap to the page's grid and lines snap to 15° steps. */
    var snapEnabled = true
    var onActive: () -> Unit = {}
    var onDocumentPan: (Float, Float) -> Unit = { _, _ -> }
    var onDocumentPanEnd: (Float) -> Unit = {}
    private val panVelocity = VelocityTracker()
    var onStrokesChanged: (List<Stroke>) -> Unit = {}
    /** Reports the ink, text and pictures inside the lasso loop so the editor can offer actions. */
    var onSelectionChanged: (CanvasSelection) -> Unit = {}
    /**
     * Commits a lasso drag that moved ink, text and pictures together, so the editor can store
     * it as one undoable step instead of three.
     */
    var onContentChanged: (List<Stroke>, List<TextBox>, List<PageImage>) -> Unit = { _, _, _ -> }
    /** When on, a neat pen drawing is replaced by a clean line, rectangle, ellipse or triangle. */
    var shapeRecognition = false
    /** A tap on an existing text box, and a tap on bare page asking for a new box there. */
    var onTextEdit: (TextBox) -> Unit = {}
    var onTextCreate: (InkPoint) -> Unit = {}
    var onTextsChanged: (List<TextBox>) -> Unit = {}
    /** Placed pictures decoded for drawing, keyed by image id. Missing entries simply do not draw. */
    var imageBitmaps: Map<String, Bitmap> = emptyMap()
    /** The picture showing resize handles, or null when none is selected. */
    var selectedImageId: String? = null
    var onImagesChanged: (List<PageImage>) -> Unit = {}
    /** A tap on a picture with the hand tool, so the editor can offer delete and layering. */
    var onImageSelected: (PageImage?) -> Unit = {}
    /** Tappable links of the shown PDF page, in Folio page coordinates. */
    var pdfLinks: List<PdfLink> = emptyList()
    /** A tap on a PDF link with the hand tool, so the editor can open or follow it. */
    var onPdfLink: (PdfLink) -> Unit = {}
    private var draft: Stroke? = null
    private var erasing: List<Stroke>? = null
    private var lasso: List<InkPoint>? = null
    private var selection: List<Stroke> = emptyList()
    private var selectedTexts: List<TextBox> = emptyList()
    private var selectedImages: List<PageImage> = emptyList()
    private var selectionDx = 0f; private var selectionDy = 0f
    private var movingSelection = false
    private var lastMoveX = 0f; private var lastMoveY = 0f
    private var pointerId = -1
    private var stylus = false
    private var ignored = false
    // Negative so a finger never counts as a palm before the stylus has ever been seen.
    private var lastStylusAt = -PALM_REJECT_MS
    private var lastX = 0f; private var lastY = 0f
    private var navigating = false
    private var multiTapMax = 1
    private var multiTapDownAt = 0L
    private var multiTapMoved = false
    private var multiTapActive = false
    /** Set once a stroke runs past the page edge, so the rest of the gesture cannot smear along it. */
    private var offPage = false
    /** Where the eraser outline sits, in page units, or null when it should not be shown. */
    private var eraserMark: InkPoint? = null
    // Text gestures: a box being dragged, or a tap waiting to become a new box.
    private var movingText: TextBox? = null
    private var pendingTextBox: InkPoint? = null
    private var textDx = 0f; private var textDy = 0f
    private var textFromX = 0f; private var textFromY = 0f
    // Picture gestures with the hand tool: the picture under the finger, or null while panning.
    private var movingImage: PageImage? = null
    private var resizingImage = false
    private var imageFromX = 0f; private var imageFromY = 0f
    private var imageMoved = false
    // A PDF link pressed with the hand tool: a tap follows it, a drag pans instead.
    private var pendingLink: PdfLink? = null
    private var linkFromX = 0f; private var linkFromY = 0f
    private val shadowPaint = Paint().apply { color = 0x18000000 }
    private val lassoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E2F6FBA; style = Paint.Style.FILL }
    private val lassoEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val eraserFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x222F6FBA; style = Paint.Style.FILL }
    private val eraserEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val textBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val imageEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val imageHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6FBA.toInt(); style = Paint.Style.FILL }
    private val imageHandleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val measurementTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val measurementBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1A1C1A.toInt() }
    private val measurementBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332F6FBA; style = Paint.Style.FILL }
    private val camera = InfiniteViewport()
    var onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {}
    private fun reportCanvasViewport() {
        if (page.infinite && width > 0 && height > 0) {
            onCanvasZoom(camera.zoom)
            onCanvasViewport(androidx.compose.ui.geometry.Rect(-camera.x / camera.zoom, -camera.y / camera.zoom,
                (width - camera.x) / camera.zoom, (height - camera.y) / camera.zoom))
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportCanvasViewport()
    }
    fun navigateCanvas(x: Float, y: Float) {
        if (!page.infinite) return
        cancelGesture()
        camera.centerOn(x, y, width.toFloat(), height.toFloat())
        reportCanvasViewport(); invalidate()
    }
    fun fitCanvas(bounds: androidx.compose.ui.geometry.Rect) {
        if (!page.infinite) return
        cancelGesture()
        camera.fit(bounds.left, bounds.top, bounds.right, bounds.bottom, width.toFloat(), height.toFloat())
        reportCanvasViewport(); invalidate()
    }
    var onCanvasZoom: (Float) -> Unit = {}
    private var resetToken = -1
    fun resetCanvas(token: Int) {
        if (resetToken == token) return
        resetToken = token
        cancelGesture(); camera.reset(); invalidate()
        reportCanvasViewport()
    }
    private val zoomDetector = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                camera.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                reportCanvasViewport(); invalidate(); return true
            }
        }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
    private val scale get() = if (page.infinite) camera.zoom else pageScale
    private val pageScale get() = min(width / page.width, height / page.height).coerceAtLeast(.01f)
    private val originX get() = if (page.infinite) camera.x else (width - page.width * scale) / 2
    private val originY get() = if (page.infinite) camera.y else (height - page.height * scale) / 2
    init {
        isFocusable = true; contentDescription = "Notebook page. Draw with a pen or finger. Palm touches are ignored while you write with a stylus. Use two fingers to zoom and pan."
    }
    /**
     * Stylus hover keeps palm rejection armed before the tip even touches the screen, and leaving
     * the stylus range releases it immediately so finger gestures are not blocked after writing.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((0 until event.pointerCount).any { isStylus(event, it) }) {
            val leaving = event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
            lastStylusAt = if (leaving) -PALM_REJECT_MS else SystemClock.uptimeMillis()
            // Hovering with the eraser previews the same outline it will cut with, before the tip lands.
            if (leaving) { if (eraserMark != null) { eraserMark = null; invalidate() } }
            else if (tool == Tool.ERASER) {
                val next = clampToPage(point(event, 0))
                val last = eraserMark
                if (last == null || hypot(next.x - last.x, next.y - last.y) > .75f) { eraserMark = next; invalidate() }
            }
        }
        return super.onGenericMotionEvent(event)
    }
    fun bind(value: NotePage, bitmap: Bitmap?, images: Map<String, Bitmap> = emptyMap()) {
        if (page.id != value.id || page.infinite != value.infinite) { cancelGesture(); camera.reset(); resetToken = -1 }
        page = value; background = bitmap; imageBitmaps = images
        // Content deleted from outside the view stops being selected, per list so one removed
        // stroke does not drop a still-present text box from the selection.
        val keptStrokes = selection.filter { it in value.strokes }
        val keptTexts = selectedTexts.filter { kept -> value.texts.any { it.id == kept.id } }
        val keptImages = selectedImages.filter { kept -> value.images.any { it.id == kept.id } }
        if (keptStrokes.size != selection.size || keptTexts.size != selectedTexts.size || keptImages.size != selectedImages.size) {
            selection = keptStrokes; selectedTexts = keptTexts; selectedImages = keptImages
            selectionDx = 0f; selectionDy = 0f
            onSelectionChanged(CanvasSelection(selection, selectedTexts, selectedImages))
        }
        // A text box that was edited or removed elsewhere cannot still be under the finger.
        if (movingText != null && value.texts.none { it.id == movingText!!.id }) { movingText = null; textDx = 0f; textDy = 0f }
        // A picture that was removed elsewhere cannot still be under the finger.
        if (movingImage != null && value.images.none { it.id == movingImage!!.id }) {
            movingImage = null; resizingImage = false; imageMoved = false
        }
        if (selectedImageId != null && value.images.none { it.id == selectedImageId }) selectedImageId = null
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(234, 232, 226))
        canvas.save(); canvas.translate(originX, originY); canvas.scale(scale, scale)
        if (!page.infinite) canvas.drawRect(-1f, -1f, page.width + 2f, page.height + 3f, shadowPaint)
        if (!page.infinite) canvas.clipRect(0f, 0f, page.width, page.height)
        val visible = if (erasing != null) page.copy(strokes = erasing!!) else page
        // A text box follows the finger while it is dragged, before the move is committed.
        val dragging = movingText
        val laid = if (dragging == null) visible else visible.copy(texts = visible.texts.map { if (it.id == dragging.id) it.moved(textDx, textDy) else it })
        // A picture follows the finger the same way, so a move or resize reads live.
        val liveImage = movingImage
        val placed = if (liveImage == null) laid else laid.copy(images = laid.images.map { if (it.id == liveImage.id) liveImage else it })
        // Selected content draws last, at its drag offset, so a move reads clearly.
        val hasSelection = selection.isNotEmpty() || selectedTexts.isNotEmpty() || selectedImages.isNotEmpty()
        val rest = if (!hasSelection) placed else placed.copy(
            strokes = placed.strokes.filterNot { it in selection },
            texts = placed.texts.filterNot { box -> selectedTexts.any { it.id == box.id } },
            images = placed.images.filterNot { image -> selectedImages.any { it.id == image.id } }
        )
        InkRenderer.page(canvas, rest, background, images = imageBitmaps)
        if (selection.isNotEmpty()) {
            val moved = selection.map { InkGeometry.translate(it, selectionDx, selectionDy) }
            moved.forEach { InkRenderer.stroke(canvas, it.copy(color = SELECTION_COLOR, width = it.width + 14f, opacity = .35f)) }
            moved.forEach { InkRenderer.stroke(canvas, it) }
        }
        if (selectedTexts.isNotEmpty()) {
            selectedTexts.forEach {
                val moved = it.moved(selectionDx, selectionDy)
                InkRenderer.text(canvas, moved)
                drawTextBox(canvas, moved)
            }
        }
        if (selectedImages.isNotEmpty()) {
            selectedImages.forEach {
                val moved = it.moved(selectionDx, selectionDy)
                imageBitmaps[moved.id]?.let { bitmap -> InkRenderer.image(canvas, bitmap, moved) }
                drawImageSelection(canvas, moved, withHandle = false)
            }
        }
        draft?.let { InkRenderer.stroke(canvas, it) }
        draft?.let { if (shapeMeasurements && it.tool in MEASURE_TOOLS) drawMeasurement(canvas, it) }
        lasso?.takeIf { it.size > 1 }?.let { drawLasso(canvas, it) }
        eraserMark?.let { drawEraser(canvas, it) }
        dragging?.let { drawTextBox(canvas, it.moved(textDx, textDy)) }
        // The selected picture keeps its outline while another picture is dragged, unless it is
        // part of the lasso selection, which already draws its own outline at the drag offset.
        val outlined = (liveImage?.takeIf { it.id == selectedImageId } ?: placed.images.find { it.id == selectedImageId })
            ?.takeIf { hand -> selectedImages.none { it.id == hand.id } }
        outlined?.let { drawImageSelection(canvas, it) }
        canvas.restore()
    }
    /**
     * Redraws only the neighbourhood of new samples while freehand ink or the eraser moves.
     * A full invalidate on every 8 ms sample forced the whole page (paper + every stroke) to
     * redraw at stylus rate; the dirty rect lets the hardware renderer keep the rest and, together
     * with InkRenderer's viewport culling, only the strokes under the tip are re-walked.
     */
    private fun invalidateForSamples(samples: List<InkPoint>): Boolean {
        if (samples.isEmpty()) return false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in samples) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        // Smoothing reshapes the tail slightly behind the newest sample; the margin covers the
        // stroke width, the spline overshoot and the eraser ring.
        val margin = inkWidth * scale + 64f * scale.coerceAtMost(2f) + 24f
        val l = (originX + minX * scale - margin).toInt()
        val t = (originY + minY * scale - margin).toInt()
        val r = (originX + maxX * scale + margin).toInt()
        val b = (originY + maxY * scale + margin).toInt()
        @Suppress("DEPRECATION")
        invalidate(l, t, r, b)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var dirtyInvalidated = false
        if (page.infinite && !stylus && (0 until event.pointerCount).none { isStylus(event, it) } && !isPalm(event, 0)) {
            zoomDetector.onTouchEvent(event)
        }
        // Stylus-first input: any stylus pointer refreshes the palm-rejection window.
        if ((0 until event.pointerCount).any { isStylus(event, it) }) lastStylusAt = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (handleMultiTapDown(event)) return true
                requestFocus()
                // Ask the system to hand each stylus sample over as it arrives rather than batching
                // samples into the next frame, so the ink keeps up with the tip instead of trailing.
                requestUnbufferedDispatch(event)
                offPage = false
                onActive()
                parent?.requestDisallowInterceptTouchEvent(true)
                pointerId = event.getPointerId(0)
                stylus = isStylus(event, 0)
                // A finger touching while (or just after) the stylus writes is a resting palm: ignore it.
                ignored = !stylus && isPalm(event, 0)
                // Typing is a finger job even when finger drawing is off, so the text tool never pans.
                navigating = !ignored && (tool == Tool.HAND || (tool != Tool.TEXT && !fingerDrawing && !stylus))
                lastX = event.rawX; lastY = event.rawY
                panVelocity.resetTracking()
                panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                if (tool == Tool.HAND && !ignored && beginImage(event, 0)) {
                    navigating = false
                } else if (tool == Tool.HAND && !ignored && beginLink(event, 0)) {
                    navigating = false
                } else if (lassoActive()) beginLasso(event, 0)
                else if (tool == Tool.TEXT && !ignored) beginText(event, 0)
                else if (!navigating && !ignored) beginStroke(event, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (updateMultiTapOnSecondFinger(event)) return true
                if (isStylus(event, event.actionIndex)) {
                    // The stylus landed over an in-progress palm stroke: drop it and follow the stylus.
                    pointerId = event.getPointerId(event.actionIndex)
                    stylus = true
                    ignored = false
                    navigating = tool == Tool.HAND
                    draft = null; erasing = null; lasso = null; movingSelection = false; offPage = false; eraserMark = null
                    movingText = null; pendingTextBox = null
                    movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null
                    if (lassoActive()) beginLasso(event, event.actionIndex)
                    else if (tool == Tool.TEXT) beginText(event, event.actionIndex)
                    else if (!navigating) beginStroke(event, event.actionIndex)
                } else if (!stylus && !ignored) {
                    draft = null; erasing = null; lasso = null; movingSelection = false; movingText = null; pendingTextBox = null; movingImage = null; resizingImage = false; pendingLink = null; navigating = true
                    lastX = centroidX(event); lastY = centroidY(event)
                    panVelocity.resetTracking()
                    panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTapActive) { trackMultiTapMove(event); return true }
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                if (pendingLink != null) {
                    val at = point(event, index)
                    if (hypot(at.x - linkFromX, at.y - linkFromY) > LINK_SLOP) {
                        // A drag that started on a link is a pan; the tap is cancelled.
                        pendingLink = null
                        navigating = true
                        lastX = centroidX(event); lastY = centroidY(event)
                        panVelocity.resetTracking()
                        panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                    }
                }
                if (movingImage != null) {
                    val at = point(event, index)
                    val current = movingImage!!
                    if (resizingImage) {
                        val targetWidth = current.width + (at.x - imageFromX)
                        val resized = InkGeometry.resizeImage(current, targetWidth)
                        if (resized != current) { movingImage = resized; imageMoved = true }
                    } else {
                        val dx = at.x - imageFromX; val dy = at.y - imageFromY
                        if (dx != 0f || dy != 0f) {
                            movingImage = if (page.infinite) current.moved(dx, dy)
                            else current.moved(dx, dy).let {
                                it.copy(x = it.x.coerceIn(-it.width + 40f, page.width - 40f),
                                    y = it.y.coerceIn(-it.height + 40f, page.height - 40f))
                            }
                            if (hypot(dx, dy) > 1f) imageMoved = true
                        }
                    }
                    imageFromX = at.x; imageFromY = at.y
                } else if (tool == Tool.TEXT && movingText != null) {
                    val moved = clampToPage(point(event, index))
                    textDx += moved.x - textFromX; textDy += moved.y - textFromY
                    textFromX = moved.x; textFromY = moved.y
                } else if (lassoActive()) {
                    if (movingSelection) {
                        val moved = clampToPage(point(event, index))
                        selectionDx += moved.x - lastMoveX; selectionDy += moved.y - lastMoveY
                        lastMoveX = moved.x; lastMoveY = moved.y
                    } else lasso = (lasso ?: emptyList()) + ((0 until event.historySize).map { point(event, index, it) } + point(event, index)).map { clampToPage(it) }
                } else if (navigating) {
                    val x = centroidX(event); val y = centroidY(event)
                    panVelocity.addPosition(event.eventTime, Offset(x, y))
                    if (page.infinite) { camera.pan(x - lastX, y - lastY); reportCanvasViewport() } else onDocumentPan(x - lastX, y - lastY)
                    lastX = x; lastY = y
                } else {
                    val points = (0 until event.historySize).map { point(event, index, it) } + point(event, index)
                    // The eraser cuts out the samples it touches and leaves the rest of the stroke behind.
                    // One pass applies all of this frame's samples: re-walking every stroke once per
                    // sample is what made a drag feel heavy once a page had ink on it.
                    val cutting = erasing
                    if (cutting != null) {
                        val centers = points.map { clampToPage(it) }
                        erasing = if (eraserWholeStroke) {
                            cutting.filterNot { hitStroke -> centers.any { c ->
                                val r = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(c.pressure) else inkWidth / 2f
                                InkGeometry.hits(hitStroke, c, r)
                            } }
                        } else if (eraserPressureEnabled && stylus) {
                            val radii = centers.map { inkWidth / 2f * InkGeometry.eraserScale(it.pressure) }
                            cutting.flatMap { InkGeometry.erase(it, centers, radii) }
                        } else {
                            cutting.flatMap { InkGeometry.erase(it, centers, inkWidth / 2f) }
                        }
                        eraserMark = centers[centers.size - 1]
                    }
                    draft?.let { current ->
                        val accepted = pagePoints(points)
                        draft = when {
                            accepted.isEmpty() -> current
                            current.tool in FREEHAND_TOOLS -> current.copy(points = current.points + accepted)
                            else -> {
                                var end = accepted.last()
                                var start = current.points.first()
                                if (snapEnabled) {
                                    if (page.paper.isGrid) {
                                        end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
                                    }
                                    if (current.tool == Tool.LINE) {
                                        end = InkGeometry.snapAngle(start, end, 15f)
                                    }
                                }
                                current.copy(points = listOf(start, end))
                            }
                        }
                        // Freehand grows incrementally, so only its tip needs redrawing; shapes
                        // re-derive from their start corner and fall through to a full invalidate.
                        if (draft?.tool in FREEHAND_TOOLS || erasing != null) {
                            dirtyInvalidated = invalidateForSamples(accepted.ifEmpty { points })
                        }
                    }
                    if (erasing != null && draft == null) {
                        dirtyInvalidated = invalidateForSamples(points)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multiTapActive) { if (handleMultiTapPointerUp(event)) return true }
                if (event.getPointerId(event.actionIndex) == pointerId && stylus) finishGesture()
                else if (!stylus && !ignored) {
                    if (event.getPointerId(event.actionIndex) == pointerId) {
                        pointerId = event.getPointerId(if (event.actionIndex == 0) 1 else 0)
                    }
                    lastX = centroidX(event, event.actionIndex); lastY = centroidY(event, event.actionIndex)
                    panVelocity.resetTracking()
                    panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (multiTapActive) { handleMultiTapUp(); return true }
                val hadImage = movingImage != null
                val hadLink = pendingLink != null
                if (hadImage) {
                    finishImage()
                } else if (hadLink) {
                    val link = pendingLink
                    pendingLink = null
                    link?.let(onPdfLink)
                } else if (navigating && !ignored) {
                    panVelocity.addPosition(event.eventTime, Offset(event.rawX, event.rawY))
                    onDocumentPanEnd(panVelocity.calculateVelocity().y)
                }
                if (!hadImage && !hadLink) {
                if (tool == Tool.TEXT) finishText()
                else if (lassoActive()) finishLasso()
                else {
                    draft?.let { current ->
                        // Follow the tracked pointer, which may be a stylus that took over from a finger.
                        var end = pagePoints(listOf(point(event, event.findPointerIndex(pointerId).coerceAtLeast(0)))).lastOrNull()
                        if (end != null) {
                            if (snapEnabled && current.tool in MEASURE_TOOLS) {
                                if (page.paper.isGrid) end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
                                if (current.tool == Tool.LINE) end = InkGeometry.snapAngle(current.points.first(), end, 15f)
                            }
                            draft = current.copy(points = if (current.tool in FREEHAND_TOOLS) current.points + end else listOf(current.points.first(), end))
                        }
                    }
                    finishGesture()
                }
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { multiTapActive = false; multiTapMax = 1; cancelGesture() }
        }
        if (!dirtyInvalidated) invalidate()
        return true
    }
    private fun finishGesture() {
        val wasErasing = erasing != null
        val drawn = draft?.copy(createdAt = System.currentTimeMillis())
        var scribbleErased: List<Stroke>? = null
        if (drawn != null && scribbleToErase && (drawn.tool == Tool.PEN || drawn.tool == Tool.HIGHLIGHTER) && InkGeometry.isScribble(drawn.points)) {
            val scrubbed = InkGeometry.scribbleErase(page.strokes, drawn, SCRIBBLE_RADIUS)
            if (scrubbed.size != page.strokes.size) scribbleErased = scrubbed
        }
        // "Tidy up": a pen drawing that reads as a shape lands as a clean one instead.
        val tidied = if (scribbleErased == null && drawn != null && shapeRecognition) InkGeometry.tidy(drawn)?.let(::snapShapes) else null
        val strokes = scribbleErased ?: (tidied ?: drawn?.let { listOf(it) })?.let { page.strokes + it } ?: erasing
        val changed = strokes != null && strokes != page.strokes
        if (changed) page = page.copy(strokes = strokes!!)
        val shouldNotifyEraser = wasErasing && tool == Tool.ERASER
        cancelGesture()
        if (changed) onStrokesChanged(page.strokes)
        if (shouldNotifyEraser) onEraserFinished?.invoke()
    }
    /** A tidied single line follows the grid and 15° snapping the user already has switched on. */
    private fun snapShapes(shapes: List<Stroke>): List<Stroke> {
        val line = shapes.singleOrNull()?.takeIf { it.tool == Tool.LINE } ?: return shapes
        if (!snapEnabled) return shapes
        val start = line.points.first()
        var end = line.points.last()
        if (page.paper.isGrid) end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
        return listOf(line.copy(points = listOf(start, InkGeometry.snapAngle(start, end, 15f))))
    }
    /** With the text tool, a press picks up the box under it or asks for a new box where it landed. */
    private fun beginText(event: MotionEvent, index: Int) {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        val at = clampToPage(raw)
        val hit = boxAt(at)
        if (hit != null) { movingText = hit; textDx = 0f; textDy = 0f; textFromX = at.x; textFromY = at.y }
        else pendingTextBox = at
    }
    /** A drag commits the box's new place; a tap edits the box, or creates one on empty page. */
    private fun finishText() {
        val box = movingText
        movingText = null
        if (box != null) {
            if (textDx != 0f || textDy != 0f) {
                val texts = page.texts.map { if (it.id == box.id) it.moved(textDx, textDy) else it }
                page = page.copy(texts = texts)
                onTextsChanged(texts)
            } else onTextEdit(box)
        } else pendingTextBox?.let(onTextCreate)
        pendingTextBox = null; textDx = 0f; textDy = 0f
    }
    /** The box under [at], searching back so the box drawn on top is the one picked up. */
    private fun boxAt(at: InkPoint): TextBox? = page.texts.lastOrNull {
        at.x >= it.x && at.x <= it.x + it.width && at.y >= it.y && at.y <= it.y + InkRenderer.textHeight(it)
    }

    /**
     * With the hand tool, a press picks up the picture under it. Returns true when a picture was
     * hit, so the gesture becomes a move or resize instead of a document pan. Tapping the selected
     * picture's handle resizes; dragging anywhere else on it moves.
     */
    private fun beginImage(event: MotionEvent, index: Int): Boolean {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) return false
        val at = if (page.infinite) raw else clampToPage(raw)
        val selected = selectedImageId?.let { id -> page.images.find { it.id == id } }
        if (selected != null && InkGeometry.imageHandleContains(selected, at)) {
            movingImage = selected; resizingImage = true; imageMoved = false
            imageFromX = at.x; imageFromY = at.y
            return true
        }
        val hit = InkGeometry.imageAt(page.images, at) ?: return false
        movingImage = hit; imageMoved = false
        // A new picture is selected on press so its outline is visible while it is dragged.
        if (selectedImageId != hit.id) {
            selectedImageId = hit.id
            onImageSelected(hit)
        }
        resizingImage = InkGeometry.imageHandleContains(hit, at)
        imageFromX = at.x; imageFromY = at.y
        return true
    }

    /** A drag commits the picture's new place or size; a tap reports it as selected. */
    private fun finishImage() {
        val preview = movingImage
        movingImage = null
        val wasResize = resizingImage
        resizingImage = false
        if (preview == null) return
        if (imageMoved) {
            val images = page.images.map { if (it.id == preview.id) preview else it }
            page = page.copy(images = images)
            selectedImageId = preview.id
            onImagesChanged(images)
        } else if (!wasResize) {
            selectedImageId = preview.id
            onImageSelected(preview)
        }
        imageMoved = false
    }

    /** Clears the picture selection, e.g. when tapping bare page with the hand tool. */
    fun clearImageSelection() {
        if (selectedImageId != null) {
            selectedImageId = null
            onImageSelected(null)
            invalidate()
        }
    }

    /**
     * With the hand tool, a press on a PDF link arms it. Returns true so the gesture becomes a
     * tap instead of a document pan; dragging past a small slop pans as usual.
     */
    private fun beginLink(event: MotionEvent, index: Int): Boolean {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) return false
        val at = if (page.infinite) raw else clampToPage(raw)
        val hit = pdfLinks.lastOrNull { it.contains(at.x, at.y) } ?: return false
        pendingLink = hit
        linkFromX = at.x; linkFromY = at.y
        return true
    }
    private fun cancelGesture() { draft = null; erasing = null; lasso = null; movingSelection = false; selectionDx = 0f; selectionDy = 0f; pointerId = -1; stylus = false; ignored = false; navigating = false; offPage = false; eraserMark = null; movingText = null; pendingTextBox = null; textDx = 0f; textDy = 0f; movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null; parent?.requestDisallowInterceptTouchEvent(false) }
    private fun lassoActive() = tool == Tool.LASSO && !navigating && !ignored
    /** Starts a fresh loop, or picks up the current selection when the drag begins inside it. */
    private fun beginLasso(event: MotionEvent, index: Int) {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        val start = clampToPage(raw)
        if (insideSelection(start)) { movingSelection = true; lastMoveX = start.x; lastMoveY = start.y; return }
        setSelection(CanvasSelection())
        lasso = listOf(start)
    }
    private fun finishLasso() {
        if (movingSelection) commitSelectionMove()
        else {
            val loop = lasso ?: emptyList()
            lasso = null
            // A tap sized loop is not a selection, so stroking elsewhere clears instead of flickering.
            setSelection(if (loop.size >= 3) CanvasSelection(
                strokes = page.strokes.filter { InkGeometry.lassoSelects(loop, it) },
                texts = page.texts.filter { InkGeometry.lassoSelectsText(loop, it, InkRenderer.textHeight(it)) },
                images = page.images.filter { InkGeometry.lassoSelectsImage(loop, it) }
            ) else CanvasSelection())
        }
        movingSelection = false
    }
    private fun commitSelectionMove() {
        if ((selectionDx != 0f || selectionDy != 0f) &&
            (selection.isNotEmpty() || selectedTexts.isNotEmpty() || selectedImages.isNotEmpty())
        ) {
            val movedStrokes = selection.map { InkGeometry.translate(it, selectionDx, selectionDy) }
            val movedTexts = selectedTexts.map { it.moved(selectionDx, selectionDy) }
            val movedImages = selectedImages.map { it.moved(selectionDx, selectionDy) }
            val strokes = page.strokes.filterNot { it in selection } + movedStrokes
            val texts = page.texts.filterNot { box -> selectedTexts.any { it.id == box.id } } + movedTexts
            val images = page.images.filterNot { image -> selectedImages.any { it.id == image.id } } + movedImages
            page = page.copy(strokes = strokes, texts = texts, images = images)
            setSelection(CanvasSelection(movedStrokes, movedTexts, movedImages))
            onContentChanged(strokes, texts, images)
        }
    }
    private fun setSelection(value: CanvasSelection) {
        selection = value.strokes; selectedTexts = value.texts; selectedImages = value.images
        selectionDx = 0f; selectionDy = 0f
        onSelectionChanged(value); invalidate()
    }
    /** Drops the lasso selection, e.g. when the page scrolls away or another tool is picked. */
    fun clearSelection() {
        if (selection.isNotEmpty() || selectedTexts.isNotEmpty() || selectedImages.isNotEmpty()) setSelection(CanvasSelection())
    }
    private fun insideSelection(point: InkPoint): Boolean {
        val bounds = selectionBounds() ?: return false
        return point.x in bounds[0]..bounds[2] && point.y in bounds[1]..bounds[3]
    }
    private fun selectionBounds(margin: Float = 14f): FloatArray? =
        InkGeometry.selectionBounds(selection, selectedTexts, selectedImages, { InkRenderer.textHeight(it) }, margin)
            ?.let { bounds ->
                // The drag offset counts: grabbing the moved selection's new position keeps working.
                floatArrayOf(bounds[0] + selectionDx, bounds[1] + selectionDy, bounds[2] + selectionDx, bounds[3] + selectionDy)
            }
    private fun drawLasso(canvas: Canvas, loop: List<InkPoint>) {
        val polygon = Path().apply { moveTo(loop.first().x, loop.first().y); loop.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        canvas.drawPath(polygon, lassoFillPaint); canvas.drawPath(polygon, lassoEdgePaint)
    }
    /** A dashed outline around a text box while it is dragged, so its extent is visible. */
    private fun drawTextBox(canvas: Canvas, box: TextBox) {
        canvas.drawRect(box.x - 4f, box.y - 4f, box.x + box.width + 4f, box.y + InkRenderer.textHeight(box) + 4f, textBoxPaint)
    }
    /** A dashed outline with a bottom-right handle around the selected picture. */
    private fun drawImageSelection(canvas: Canvas, image: PageImage, withHandle: Boolean = true) {
        canvas.drawRect(image.x - 4f, image.y - 4f, image.x + image.width + 4f, image.y + image.height + 4f, imageEdgePaint)
        if (!withHandle) return
        val cx = image.x + image.width
        val cy = image.y + image.height
        val r = 14f
        canvas.drawCircle(cx, cy, r, imageHandlePaint)
        canvas.drawCircle(cx, cy, r, imageHandleEdgePaint)
    }
    private fun drawMeasurement(canvas: Canvas, draft: Stroke) {
        val a = draft.points.firstOrNull() ?: return
        val b = draft.points.lastOrNull() ?: return
        val label = when (draft.tool) {
            Tool.LINE -> {
                val len = hypot(b.x - a.x, b.y - a.y)
                val deg = (Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())) + 360) % 360
                String.format(java.util.Locale.ROOT, "%.0f pt  %.0f°", len, deg)
            }
            Tool.RECTANGLE -> {
                val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                String.format(java.util.Locale.ROOT, "%.0f × %.0f", w, h)
            }
            Tool.ELLIPSE -> {
                val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                val r = (w + h) / 4f
                String.format(java.util.Locale.ROOT, "⌀ %.0f  r %.0f", kotlin.math.max(w, h), r)
            }
            else -> return
        }
        val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f - 18f
        val padH = 10f; val padV = 6f
        val tw = measurementTextPaint.measureText(label)
        val fm = measurementTextPaint.fontMetrics
        val bg = RectF(mx - tw / 2f - padH, my + fm.top - padV, mx + tw / 2f + padH, my + fm.bottom + padV)
        val rr = 10f
        canvas.drawRoundRect(bg, rr, rr, measurementBorderPaint)
        canvas.drawRoundRect(bg, rr, rr, measurementBgPaint)
        canvas.drawText(label, mx - tw / 2f, my, measurementTextPaint)
    }

    private fun handleMultiTapDown(event: MotionEvent): Boolean {
        if (!multiTouchUndo) return false
        if (event.pointerCount != 1) return false
        val isStylus = isStylus(event, 0)
        if (isStylus) return false
        multiTapMax = 1
        multiTapDownAt = SystemClock.uptimeMillis()
        multiTapMoved = false
        multiTapActive = true
        return false
    }

    private fun updateMultiTapOnSecondFinger(event: MotionEvent): Boolean {
        if (!multiTapActive || multiTapMoved) return false
        val elapsed = SystemClock.uptimeMillis() - multiTapDownAt
        if (elapsed > ViewConfiguration.getTapTimeout() + 180) return false
        val count = event.pointerCount
        if (count in 2..3) {
            multiTapMax = maxOf(multiTapMax, count)
            // Keep the first finger as the tracked pointer so ink bookkeeping stays valid.
            stylus = false; ignored = true; navigating = true
            lastX = centroidX(event); lastY = centroidY(event)
            panVelocity.resetTracking()
            panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
        }
        return false
    }

    private fun trackMultiTapMove(event: MotionEvent) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.2f
        val x = centroidX(event); val y = centroidY(event)
        if (hypot(x - lastX, y - lastY) > slop) multiTapMoved = true
        multiTapMax = maxOf(multiTapMax, event.pointerCount.coerceIn(1, 3))
    }

    private fun handleMultiTapPointerUp(event: MotionEvent): Boolean {
        // If a finger lifts but one remains, keep waiting for the final up to decide.
        return event.pointerCount > 1
    }

    private fun handleMultiTapUp() {
        val active = multiTapActive; val moved = multiTapMoved; val fingers = multiTapMax
        multiTapActive = false; multiTapMax = 1
        if (!active || !multiTouchUndo) return
        if (moved) return
        val elapsed = SystemClock.uptimeMillis() - multiTapDownAt
        if (elapsed > 420) return
        if (elapsed < 40) return
        // Require that the tap finished cleanly (single tap window) and did not become a pan/zoom.
        when (fingers) {
            2 -> onUndoRequest?.invoke()
            3 -> onRedoRequest?.invoke()
        }
        // Consume the tap so it does not also start a stroke or place text.
        draft = null; erasing = null; eraserMark = null
    }

    /** A ring under the tip, so the eraser's size is visible while it hovers and while it cuts. */
    private fun drawEraser(canvas: Canvas, at: InkPoint) {
        val radius = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(at.pressure) else inkWidth / 2f
        canvas.drawCircle(at.x, at.y, radius, eraserFillPaint)
        canvas.drawCircle(at.x, at.y, radius, eraserEdgePaint)
    }
    /** Selects every stroke, text box and picture on the current page; call from toolbar/overflow. */
    fun selectAll() {
        if (page.strokes.isEmpty() && page.texts.isEmpty() && page.images.isEmpty()) return
        tool = Tool.LASSO
        setSelection(CanvasSelection(page.strokes.toList(), page.texts.toList(), page.images.toList()))
    }

    /** Begins a stroke for [index] unless the touch started outside the page, which pans instead. */
    private fun beginStroke(event: MotionEvent, index: Int) {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        var start = clampToPage(raw)
        if (snapEnabled && tool in listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE) && page.paper.isGrid) {
            start = InkGeometry.snapToGrid(start, page.paper.gridSpacing)
        }
        if (tool == Tool.ERASER || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) {
            val radius = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(start.pressure) else inkWidth / 2f
            erasing = if (eraserWholeStroke) {
                page.strokes.filterNot { InkGeometry.hits(it, start, radius) }
            } else page.strokes.flatMap { InkGeometry.erase(it, start, radius) }
            eraserMark = start
        } else draft = Stroke(tool, inkColor, inkWidth, listOf(start), inkOpacity,
            style = if (tool == Tool.LINE || tool == Tool.RECTANGLE || tool == Tool.ELLIPSE) inkStyle else StrokeStyle.SOLID)
    }
    private fun isStylus(event: MotionEvent, index: Int) = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
    private fun isPalm(event: MotionEvent, index: Int) = !isStylus(event, index) && SystemClock.uptimeMillis() - lastStylusAt < PALM_REJECT_MS
    private fun point(e: MotionEvent, i: Int, history: Int? = null): InkPoint {
        val x = if (history == null) e.getX(i) else e.getHistoricalX(i, history)
        val y = if (history == null) e.getY(i) else e.getHistoricalY(i, history)
        val rawPressure = if (history == null) e.getPressure(i) else e.getHistoricalPressure(i, history)
        val pressure = when (tool) {
            Tool.PEN -> PenPressure.sample(rawPressure, stylus && pressureEnabled, pressureSensitivity, pressureVariation)
            Tool.ERASER -> if (!stylus || !eraserPressureEnabled) 1f else rawPressure.coerceIn(.25f, 1.8f)
            else -> if (!stylus || !pressureEnabled) 1f else rawPressure.coerceIn(.25f, 1.8f)
        }
        return InkPoint((x - originX) / scale, (y - originY) / scale, pressure)
    }
    /** Page coordinates, so a sample reported just off the page still lands on the boundary. */
    private fun clampToPage(p: InkPoint) = if (page.infinite) p else InkPoint(p.x.coerceIn(0f, page.width), p.y.coerceIn(0f, page.height), p.pressure)
    /** True on the page, with the slack that absorbs samples reported just outside a screen bezel. */
    private fun onPage(x: Float, y: Float) =
        page.infinite || x >= -EDGE_TOLERANCE && x <= page.width + EDGE_TOLERANCE && y >= -EDGE_TOLERANCE && y <= page.height + EDGE_TOLERANCE
    /**
     * Samples to add to the stroke in progress. Anything past the page edge is clamped once so the
     * line ends on the boundary, and the rest of that gesture is dropped rather than smeared along it.
     */
    private fun pagePoints(points: List<InkPoint>): List<InkPoint> {
        if (offPage) return emptyList()
        val accepted = mutableListOf<InkPoint>()
        for (p in points) {
            accepted += clampToPage(p)
            if (!onPage(p.x, p.y)) { offPage = true; break }
        }
        return accepted
    }
    // Screen coordinates stay stable as the containing document scrolls beneath this view.
    private fun centroidX(e: MotionEvent, skip: Int = -1) = (0 until e.pointerCount).filter { it != skip }.map { e.getX(it) }.average().toFloat() + e.rawX - e.x
    private fun centroidY(e: MotionEvent, skip: Int = -1) = (0 until e.pointerCount).filter { it != skip }.map { e.getY(it) }.average().toFloat() + e.rawY - e.y
    override fun performClick(): Boolean { super.performClick(); return true }
    private companion object {
        /** Freehand tools grow sample-by-sample; shapes re-derive from two corners. */
        val FREEHAND_TOOLS = setOf(Tool.PEN, Tool.HIGHLIGHTER)
        /** Shapes that show live measurements while drawn. */
        val MEASURE_TOOLS = setOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)
        /** How long after stylus activity a finger still counts as a resting palm. */
        const val PALM_REJECT_MS = 500L
        /** Page units of slack around the page edge, absorbing samples reported outside the view. */
        const val EDGE_TOLERANCE = 24f
        /** How far a press on a PDF link may wander before the gesture becomes a pan. */
        const val LINK_SLOP = 12f
        const val SCRIBBLE_RADIUS = 14f
        val SELECTION_COLOR = 0xFF2F6FBA.toInt()
    }
}
