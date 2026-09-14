package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
    var pressureEnabled = true
    var pressureSensitivity = 1f
    var pressureVariation = 1f
    /** When true, shape endpoints snap to the page's grid and lines snap to 15° steps. */
    var snapEnabled = true
    var onActive: () -> Unit = {}
    var onDocumentPan: (Float, Float) -> Unit = { _, _ -> }
    var onDocumentPanEnd: (Float) -> Unit = {}
    private val panVelocity = VelocityTracker()
    var onStrokesChanged: (List<Stroke>) -> Unit = {}
    /** Reports the strokes inside the lasso loop so the editor can offer actions such as delete. */
    var onSelectionChanged: (List<Stroke>) -> Unit = {}
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
    private val camera = InfiniteViewport()
    var onCanvasZoom: (Float) -> Unit = {}
    private var resetToken = -1
    fun resetCanvas(token: Int) {
        if (resetToken == token) return
        resetToken = token
        camera.reset(); invalidate()
        if (page.infinite) onCanvasZoom(camera.zoom)
    }
    private val zoomDetector = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                camera.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                onCanvasZoom(camera.zoom); invalidate(); return true
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
        // Strokes deleted from outside the view simply stop being selected.
        if (selection.any { it !in value.strokes }) { selection = emptyList(); selectionDx = 0f; selectionDy = 0f }
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
        // Selected strokes draw last, at their drag offset, so a move reads clearly.
        val rest = if (selection.isEmpty()) placed else placed.copy(strokes = placed.strokes.filterNot { it in selection })
        InkRenderer.page(canvas, rest, background, images = imageBitmaps)
        if (selection.isNotEmpty()) {
            val moved = selection.map { InkGeometry.translate(it, selectionDx, selectionDy) }
            moved.forEach { InkRenderer.stroke(canvas, it.copy(color = SELECTION_COLOR, width = it.width + 14f, opacity = .35f)) }
            moved.forEach { InkRenderer.stroke(canvas, it) }
        }
        draft?.let { InkRenderer.stroke(canvas, it) }
        lasso?.takeIf { it.size > 1 }?.let { drawLasso(canvas, it) }
        eraserMark?.let { drawEraser(canvas, it) }
        dragging?.let { drawTextBox(canvas, it.moved(textDx, textDy)) }
        // The selected picture keeps its outline while another picture is dragged.
        val outlined = liveImage?.takeIf { it.id == selectedImageId } ?: placed.images.find { it.id == selectedImageId }
        outlined?.let { drawImageSelection(canvas, it) }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (page.infinite && !stylus && (0 until event.pointerCount).none { isStylus(event, it) } && !isPalm(event, 0)) {
            zoomDetector.onTouchEvent(event)
        }
        // Stylus-first input: any stylus pointer refreshes the palm-rejection window.
        if ((0 until event.pointerCount).any { isStylus(event, it) }) lastStylusAt = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                    if (page.infinite) camera.pan(x - lastX, y - lastY) else onDocumentPan(x - lastX, y - lastY)
                    lastX = x; lastY = y
                } else {
                    val points = (0 until event.historySize).map { point(event, index, it) } + point(event, index)
                    // The eraser cuts out the samples it touches and leaves the rest of the stroke behind.
                    // One pass applies all of this frame's samples: re-walking every stroke once per
                    // sample is what made a drag feel heavy once a page had ink on it.
                    val cutting = erasing
                    if (cutting != null) {
                        val centers = points.map { clampToPage(it) }
                        erasing = cutting.flatMap { InkGeometry.erase(it, centers, inkWidth / 2) }
                        eraserMark = centers[centers.size - 1]
                    }
                    draft?.let { current ->
                        val accepted = pagePoints(points)
                        draft = when {
                            accepted.isEmpty() -> current
                            current.tool in listOf(Tool.PEN, Tool.HIGHLIGHTER) -> current.copy(points = current.points + accepted)
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
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
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
                            if (snapEnabled && current.tool in listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)) {
                                if (page.paper.isGrid) end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
                                if (current.tool == Tool.LINE) end = InkGeometry.snapAngle(current.points.first(), end, 15f)
                            }
                            draft = current.copy(points = if (current.tool in listOf(Tool.PEN, Tool.HIGHLIGHTER)) current.points + end else listOf(current.points.first(), end))
                        }
                    }
                    finishGesture()
                }
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        invalidate(); return true
    }
    private fun finishGesture() {
        val drawn = draft?.copy(createdAt = System.currentTimeMillis())
        // "Tidy up": a pen drawing that reads as a shape lands as a clean one instead.
        val tidied = if (drawn != null && shapeRecognition) InkGeometry.tidy(drawn)?.let(::snapShapes) else null
        val strokes = (tidied ?: drawn?.let { listOf(it) })?.let { page.strokes + it } ?: erasing
        val changed = strokes != null && strokes != page.strokes
        if (changed) page = page.copy(strokes = strokes!!)
        cancelGesture()
        if (changed) onStrokesChanged(page.strokes)
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
        setSelection(emptyList())
        lasso = listOf(start)
    }
    private fun finishLasso() {
        if (movingSelection) commitSelectionMove()
        else {
            val loop = lasso ?: emptyList()
            lasso = null
            // A tap sized loop is not a selection, so stroking elsewhere clears instead of flickering.
            setSelection(if (loop.size >= 3) page.strokes.filter { InkGeometry.lassoSelects(loop, it) } else emptyList())
        }
        movingSelection = false
    }
    private fun commitSelectionMove() {
        if (selection.isNotEmpty() && (selectionDx != 0f || selectionDy != 0f)) {
            val moved = selection.map { InkGeometry.translate(it, selectionDx, selectionDy) }
            val strokes = page.strokes.filterNot { it in selection } + moved
            page = page.copy(strokes = strokes)
            setSelection(moved)
            onStrokesChanged(strokes)
        }
    }
    private fun setSelection(value: List<Stroke>) {
        selection = value; selectionDx = 0f; selectionDy = 0f
        onSelectionChanged(value); invalidate()
    }
    /** Drops the lasso selection, e.g. when the page scrolls away or another tool is picked. */
    fun clearSelection() { if (selection.isNotEmpty()) setSelection(emptyList()) }
    private fun insideSelection(point: InkPoint): Boolean {
        val bounds = selectionBounds() ?: return false
        return point.x in bounds[0]..bounds[2] && point.y in bounds[1]..bounds[3]
    }
    private fun selectionBounds(margin: Float = 14f): FloatArray? {
        val xs = mutableListOf<Float>(); val ys = mutableListOf<Float>()
        selection.forEach { stroke -> InkGeometry.pathPoints(stroke).forEach { xs.add(it.x + selectionDx); ys.add(it.y + selectionDy) } }
        if (xs.isEmpty()) return null
        return floatArrayOf(xs.minOrNull()!! - margin, ys.minOrNull()!! - margin, xs.maxOrNull()!! + margin, ys.maxOrNull()!! + margin)
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
    private fun drawImageSelection(canvas: Canvas, image: PageImage) {
        canvas.drawRect(image.x - 4f, image.y - 4f, image.x + image.width + 4f, image.y + image.height + 4f, imageEdgePaint)
        val cx = image.x + image.width
        val cy = image.y + image.height
        val r = 14f
        canvas.drawCircle(cx, cy, r, imageHandlePaint)
        canvas.drawCircle(cx, cy, r, imageHandleEdgePaint)
    }
    /** A ring under the tip, so the eraser's size is visible while it hovers and while it cuts. */
    private fun drawEraser(canvas: Canvas, at: InkPoint) {
        canvas.drawCircle(at.x, at.y, inkWidth / 2, eraserFillPaint)
        canvas.drawCircle(at.x, at.y, inkWidth / 2, eraserEdgePaint)
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
            erasing = page.strokes.flatMap { InkGeometry.erase(it, start, inkWidth / 2) }
            eraserMark = start
        } else draft = Stroke(tool, inkColor, inkWidth, listOf(start), inkOpacity)
    }
    private fun isStylus(event: MotionEvent, index: Int) = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
    private fun isPalm(event: MotionEvent, index: Int) = !isStylus(event, index) && SystemClock.uptimeMillis() - lastStylusAt < PALM_REJECT_MS
    private fun point(e: MotionEvent, i: Int, history: Int? = null): InkPoint {
        val x = if (history == null) e.getX(i) else e.getHistoricalX(i, history)
        val y = if (history == null) e.getY(i) else e.getHistoricalY(i, history)
        val rawPressure = if (history == null) e.getPressure(i) else e.getHistoricalPressure(i, history)
        val pressure = if (tool == Tool.PEN) {
            PenPressure.sample(rawPressure, stylus && pressureEnabled, pressureSensitivity, pressureVariation)
        } else if (!stylus || !pressureEnabled) 1f else rawPressure.coerceIn(.25f, 1.8f)
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
        /** How long after stylus activity a finger still counts as a resting palm. */
        const val PALM_REJECT_MS = 500L
        /** Page units of slack around the page edge, absorbing samples reported outside the view. */
        const val EDGE_TOLERANCE = 24f
        /** How far a press on a PDF link may wander before the gesture becomes a pan. */
        const val LINK_SLOP = 12f
        val SELECTION_COLOR = 0xFF2F6FBA.toInt()
    }
}
