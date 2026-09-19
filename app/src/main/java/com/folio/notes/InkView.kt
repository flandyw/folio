package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    var scribbleSensitivity = ScribbleSensitivity.DEFAULT
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
     * The selection's frame as fractions of this view (0..1), or null when empty,
     * so the editor can float its contextual pill near the selection. Reported
     * when the selection settles and when the camera moves under it — never
     * mid-gesture, where the pill simply holds its place until release.
     */
    var onSelectionViewBounds: (Rect?) -> Unit = {}
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
    /** Cached identity set of the lasso selection, rebuilt only when the selection itself changes. */
    private var selectionIdsKey: List<Stroke>? = null
    private var selectionIds: MutableSet<Stroke>? = null
    private var movingSelection = false
    private var lastMoveX = 0f; private var lastMoveY = 0f
    /** Direct frame handles: a resize drag, a rotate drag, or neither (a plain move). */
    private var resizingSelection = false
    private var rotatingSelection = false
    /** Grab-time pivot of a handle gesture, in page units including any drag offset. */
    private var handleCenter = InkPoint(0f, 0f)
    private var handleStartDist = 1f
    private var handleStartAngle = 0f
    /** Live handle preview: uniform scale and degrees about [handleCenter]. Committed on release. */
    private var selectionPreviewScale = 1f
    private var selectionPreviewDeg = 0f
    private var lastReportedSelectionBounds: Rect? = null
    private var pointerId = -1
    private var stylus = false
    private var ignored = false
    // Negative so a finger never counts as a palm before the stylus has ever been seen.
    private var lastStylusAt = -PALM_REJECT_MS
    private var lastX = 0f; private var lastY = 0f
    private var navigating = false
    /**
     * A resting hand wanders further than a fingertip's drag slop, so a finger-driven pan waits for
     * twice it before it may move the document.
     */
    private val panGate = PanGate(ViewConfiguration.get(context).scaledTouchSlop * 2f)
    private val touchChord = TouchChord(ViewConfiguration.get(context).scaledTouchSlop * 1.2f)
    /** Set once a stroke runs past the page edge, so the rest of the gesture cannot smear along it. */
    private var offPage = false
    /**
     * Geometry of the pen stroke in progress, carried between frames so only newly arrived samples
     * are re-smoothed. Keyed by the stroke's own sample list, so a new stroke re-arms it.
     */
    private var draftPenStroke: InkRenderer.IncrementalPenStroke? = null
    private var draftPenPoints: List<InkPoint>? = null
    /** Where the eraser outline sits, in page units, or null when it should not be shown. */
    private var eraserMark: InkPoint? = null
    // Text gestures: a box being dragged, or a tap waiting to become a new box.
    private var movingText: TextBox? = null
    private var pendingTextBox: InkPoint? = null
    private var textDx = 0f; private var textDy = 0f
    private var textDragged = false
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
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val selectionLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val selectionGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val measurementTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val measurementBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1A1C1A.toInt() }
    private val measurementBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332F6FBA; style = Paint.Style.FILL }
    val writingFollow = WritingFollow()
    var writingGuides: List<WritingGuide> = emptyList()
    private var lineAdvance: WritingAdvance? = null
    var followPreferences = FollowPreferences()
    var writingRegion: WritingLane? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var onWritingRegion: (WritingLane?) -> Unit = {}
    var onFollowStatus: (String) -> Unit = {}
    var writingStrip = false
    private var stripInitialized = false
    private var stripInitialArea: WritingLane? = null
    private var stripInitialView: PeekAnchor? = null
    fun initializeWritingStrip(region: WritingLane?, visible: PeekAnchor?) {
        if (stripInitialized) return
        stripInitialArea = region; stripInitialView = visible
        if (width == 0 || height == 0) return
        stripInitialized = true
        val area = region ?: visible?.let { WritingLane(it.left, it.top, it.right, it.bottom) }
            ?: WritingLane(36f, 40f, page.width - 36f, 180f)
        val stripHeight = min(96f, (area.bottom - area.top).coerceAtLeast(32f))
        val stripWidth = min((area.right - area.left).coerceAtLeast(48f), width.toFloat() / height * stripHeight)
        val left = if (followPreferences.direction == WritingDirection.LTR) area.left else area.right - stripWidth
        fitPeekAnchor(PeekAnchor(page.id, left, area.top, left + stripWidth, area.top + stripHeight))
    }
    private var selectingWritingRegion = false
    private var regionStart: InkPoint? = null
    private var regionDraft: WritingLane? = null
    private var followLastPoint: InkPoint? = null
    private var followPaused = false
    private var followLiftedAt = 0L
    private var pendingReturn: WritingAdvance? = null
    private var backPanX = 0f
    private var backPanY = 0f
    private var hasFollowBack = false
    private var backFollowState = WritingFollowState()
    fun selectWritingRegion() {
        suspendWritingFollow(); selectingWritingRegion = true
        onFollowStatus("Drag an answer area with your pen")
    }
    fun clearWritingRegion() { writingRegion = null; onWritingRegion(null); invalidate() }
    fun suggestWritingRegion() {
        val y = followLastPoint?.y ?: currentPeekAnchor()?.let { (it.top + it.bottom) / 2 } ?: 70f
        val line = writingGuides.minByOrNull { kotlin.math.abs(it.y - y) } ?: return selectWritingRegion()
        val group = mutableListOf(line)
        var cursor = line
        while (true) { val next = WritingGuides.next(cursor, writingGuides) ?: break; group += next; cursor = next }
        cursor = line
        while (true) { val previous = writingGuides.firstOrNull { WritingGuides.next(it, writingGuides) == cursor } ?: break; group += previous; cursor = previous }
        writingRegion = WritingLane(group.minOf { it.left }, group.minOf { it.y } - 28f, group.maxOf { it.right }, group.maxOf { it.y })
        onWritingRegion(writingRegion); onFollowStatus("Answer area selected"); invalidate()
    }
    private fun followRegion(): WritingLane {
        writingRegion?.let { return it }
        val point = followLastPoint
        val guide = point?.let { p -> writingGuides.filter { p.x in it.left..it.right && kotlin.math.abs(it.y - p.y) <= 16f }.minByOrNull { kotlin.math.abs(it.y - p.y) } }
        if (guide != null) {
            var end: WritingGuide = guide
            while (true) { end = WritingGuides.next(end, writingGuides) ?: break }
            return WritingLane(guide.left, guide.y - 32f, guide.right, end.y)
        }
        return WritingLane(36f, 0f, page.width - 36f, if (page.infinite) Float.MAX_VALUE else page.height - 24f)
    }
    fun nextWritingLine() {
        if (isWritingGesture || inputBlocked || readOnly) return
        val region = followRegion()
        val baseline = writingFollow.state.baselineY ?: followLastPoint?.y ?: (region.top + followPreferences.spacing)
        val next = FollowNavigation.next(baseline, region, writingGuides, followPreferences.spacing)
        if (next == null) { onFollowStatus("End of answer area"); return }
        followPaused = false; writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
        pendingReturn = null; backPanX = 0f; backPanY = 0f; hasFollowBack = true; backFollowState = writingFollow.state
        startLineAdvance(next)
    }
    fun backWritingView() {
        if (!hasFollowBack || isWritingGesture) return
        val dx = -backPanX; val dy = -backPanY
        suspendWritingFollow()
        if (page.infinite || writingStrip) { camera.pan(dx, dy); invalidate() } else onFollowPan(dx, dy)
        writingFollow.state = backFollowState
        hasFollowBack = false; onFollowStatus("View restored · follow paused")
    }
    var followEnabled = false
        set(value) {
            if (field && !value) suspendWritingFollow()
            field = value
        }
    var writingHand = WritingHand.RIGHT
    var documentFollowZoom = 1f
    var onFollowPan: (Float, Float) -> Pair<Float, Float> = { _, _ -> 0f to 0f }
    var inputBlocked = false
    val isWritingGesture get() = draft != null || erasing != null || lasso != null
    var peekRegion: PeekAnchor? = null
        set(value) { field = value; value?.let(::fitPeekAnchor) }
    private val followVisible = android.graphics.Rect()
    /** One-shot carriage return: remaining screen-pixel pan, eased out in [followFrame]. */
    private var advanceTotalX = 0f
    private var advanceTotalY = 0f
    private var advanceDoneX = 0f
    private var advanceDoneY = 0f
    private var advanceStartAt = 0L
    fun suspendWritingFollow() {
        followPaused = true; pendingReturn = null; hasFollowBack = false
        onFollowStatus("Follow paused · resume by writing")
        writingFollow.suspend(SystemClock.uptimeMillis())
        advanceTotalX = 0f; advanceTotalY = 0f; advanceDoneX = 0f; advanceDoneY = 0f; lineAdvance = null
        removeCallbacks(followFrame)
    }
    fun currentPeekAnchor(): PeekAnchor? {
        if (!getLocalVisibleRect(followVisible)) return null
        return PeekAnchor(page.id, (followVisible.left - originX) / scale, (followVisible.top - originY) / scale,
            (followVisible.right - originX) / scale, (followVisible.bottom - originY) / scale)
    }
    fun snapshot() = ViewportSnapshot(page.id, WorkspaceViewport(canvasX = camera.x, canvasY = camera.y, canvasZoom = camera.zoom))
    fun restore(snapshot: ViewportSnapshot) {
        if (snapshot.pageId != page.id) return
        camera.restore(snapshot.viewport.canvasX, snapshot.viewport.canvasY, snapshot.viewport.canvasZoom)
        reportCanvasViewport(); invalidate()
    }
    fun fitPeekAnchor(anchor: PeekAnchor) {
        if (width == 0 || height == 0 || anchor.pageId != page.id) return
        val base = if (page.infinite) 1f else pageScale
        val z = minOf(width / (anchor.right - anchor.left), height / (anchor.bottom - anchor.top)) / base
        val ox = if (page.infinite) 0f else (width - page.width * base) / 2
        val oy = if (page.infinite) 0f else (height - page.height * base) / 2
        val zoom = z.coerceIn(.1f, 8f)
        camera.restore(width / 2f - ((anchor.left + anchor.right) / 2 * base + ox) * zoom,
            height / 2f - ((anchor.top + anchor.bottom) / 2 * base + oy) * zoom, zoom)
        invalidate()
    }
    private val followFrame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (!followEnabled || inputBlocked || readOnly || !getLocalVisibleRect(followVisible)) {
                pendingReturn = null
                advanceTotalX = 0f; advanceTotalY = 0f; advanceDoneX = 0f; advanceDoneY = 0f; lineAdvance = null
                return
            }
            val down = isWritingGesture || selectingWritingRegion
            if (down || followPaused) return
            val pending = pendingReturn
            if (pending != null) {
                if (now - followLiftedAt < 650) { postOnAnimation(this); return }
                pendingReturn = null
                backPanX = 0f; backPanY = 0f; hasFollowBack = true; backFollowState = writingFollow.state
                startLineAdvance(pending)
            }
            // Brief gaps within a letter should not move the paper between its strokes.
            if (lineAdvance == null && now - followLiftedAt < 120) { postOnAnimation(this); return }
            var ax = 0f; var ay = 0f
            val advancing = !down && (advanceTotalX != 0f || advanceTotalY != 0f)
            if (advancing) {
                val t = writingFollow.lineAdvanceProgress(advanceStartAt, now)
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                val targetX = advanceTotalX * eased
                val targetY = advanceTotalY * eased
                ax = targetX - advanceDoneX
                ay = targetY - advanceDoneY
                advanceDoneX = targetX; advanceDoneY = targetY
                if (t >= 1f) { lineAdvance?.let { writingFollow.arrived(it); onFollowStatus("Next line · Back restores the view") }; advanceTotalX = 0f; advanceTotalY = 0f; advanceDoneX = 0f; advanceDoneY = 0f; lineAdvance = null }
            }
            val dx = ax
            val dy = ay
            if (dx == 0f && dy == 0f) {
                if (advanceTotalX != 0f || advanceTotalY != 0f) postOnAnimation(this)
                return
            }
            val applied = if (page.infinite || writingStrip) {
                camera.pan(dx, dy); reportCanvasViewport(); invalidate(); dx to dy
            } else onFollowPan(dx, dy)
            backPanX += applied.first; backPanY += applied.second
            postOnAnimation(this)
        }
    }
    private fun scheduleFollow() {
        removeCallbacks(followFrame)
        postOnAnimation(followFrame)
    }
    /** Place the next printed rule's start where the current rule was being written. */
    private fun startLineAdvance(advance: WritingAdvance) {
        if (!getLocalVisibleRect(followVisible)) return
        val margin = followVisible.width() * (if (writingHand == WritingHand.RIGHT) .18f else .28f)
        val desiredX = if (followPreferences.direction == WritingDirection.LTR) followVisible.left + margin
            else followVisible.right - margin
        val dx = desiredX - (originX + advance.startX(if (followPreferences.direction == WritingDirection.LTR) WritingHand.RIGHT else WritingHand.LEFT) * scale)
        val dy = -(advance.to.y - advance.from.y) * scale
        advanceTotalX = dx; advanceTotalY = dy; advanceDoneX = 0f; advanceDoneY = 0f
        lineAdvance = advance
        advanceStartAt = SystemClock.uptimeMillis()
        scheduleFollow()
    }
    var readOnly = false
    var onWorkspaceCamera: (WorkspaceViewport) -> Unit = {}
    private var workspaceCameraRestored = false
    fun restoreWorkspaceCamera(viewport: WorkspaceViewport?) {
        if (workspaceCameraRestored) return
        workspaceCameraRestored = true
        if (viewport != null) camera.restore(viewport.canvasX, viewport.canvasY, viewport.canvasZoom)
    }
    private val camera = InfiniteViewport()
    var onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {}
    private fun reportCanvasViewport() {
        if ((page.infinite || readOnly || writingStrip) && workspaceCameraRestored && width > 0 && height > 0) {
            onWorkspaceCamera(WorkspaceViewport(canvasX = camera.x, canvasY = camera.y, canvasZoom = camera.zoom))
            onCanvasZoom(camera.zoom)
            onCanvasViewport(androidx.compose.ui.geometry.Rect(-camera.x / camera.zoom, -camera.y / camera.zoom,
                (width - camera.x) / camera.zoom, (height - camera.y) / camera.zoom))
        }
        // A camera move under a live selection re-anchors the editor's pill,
        // but never mid-gesture: the release reports the settled frame.
        if (hasSelection() && !movingSelection && !resizingSelection && !rotatingSelection && lasso == null) {
            reportSelectionViewBounds()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        peekRegion?.let(::fitPeekAnchor)
        if (writingStrip) initializeWritingStrip(stripInitialArea, stripInitialView)
        reportCanvasViewport()
    }
    fun fitCanvas(bounds: androidx.compose.ui.geometry.Rect) {
        if (!page.infinite) return
        suspendWritingFollow()
        cancelGesture()
        camera.fit(bounds.left, bounds.top, bounds.right, bounds.bottom, width.toFloat(), height.toFloat())
        reportCanvasViewport(); invalidate()
    }
    var onCanvasZoom: (Float) -> Unit = {}
    private var resetToken = -1
    fun resetCanvas(token: Int) {
        if (resetToken == token) return
        resetToken = token
        suspendWritingFollow()
        cancelGesture(); camera.reset(); invalidate()
        reportCanvasViewport()
    }
    private val zoomDetector = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                suspendWritingFollow()
                camera.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                reportCanvasViewport(); invalidate(); return true
            }
        }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
    private val scale get() = if (page.infinite) camera.zoom else pageScale * (if (readOnly || writingStrip) camera.zoom else 1f)
    private val pageScale get() = min(width / page.width, height / page.height).coerceAtLeast(.01f)
    private val originX get() = if (page.infinite) camera.x else if (readOnly || writingStrip) (width - page.width * pageScale) / 2 * camera.zoom + camera.x else (width - page.width * scale) / 2
    private val originY get() = if (page.infinite) camera.y else if (readOnly || writingStrip) (height - page.height * pageScale) / 2 * camera.zoom + camera.y else (height - page.height * scale) / 2
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
        if (page.id != value.id || page.infinite != value.infinite) { stripInitialized = false; followPaused = false; pendingReturn = null; followLastPoint = null; hasFollowBack = false; writingFollow.state = WritingFollowState(); advanceTotalX = 0f; advanceTotalY = 0f; advanceDoneX = 0f; advanceDoneY = 0f; lineAdvance = null; removeCallbacks(followFrame); cancelGesture(); camera.reset(); resetToken = -1; workspaceCameraRestored = false; clearInkLayers(); renderCache.clear(); boundsCache.clear(); restCache = null; restCacheKeyPage = null; resetDraftGeometry() }
        else if (page.strokes !== value.strokes) {
            // Same page, new revision: drop geometry for strokes that are gone so the
            // caches track the live ink instead of every undone fragment.
            val keep = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>())
            keep.addAll(value.strokes); keep.addAll(selection)
            renderCache.retainAll(keep)
            boundsCache.retainAll(keep)
        }
        page = value; background = bitmap; imageBitmaps = images
        // Content deleted from outside the view stops being selected, per list so one removed
        // stroke does not drop a still-present text box from the selection.
        // Identity fast-path first (same objects from the ViewModel), structural fallback for
        // pages reloaded from disk where instances differ but values match.
        val valueStrokeIds = if (selection.isEmpty()) emptySet() else
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(value.strokes) }
        val keptStrokes = if (selection.isEmpty()) selection else selection.filter { it in valueStrokeIds || it in value.strokes }
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
    private val committedInk = CommittedInkCache()
    private val navigationInk = CommittedInkCache(maxPixels = 1_100_000L)
    private val zoomRenderState = ZoomRenderState()
    private val navigationBounds = android.graphics.Rect()
    private val requiredInkBounds = android.graphics.Rect()
    private val refreshInkDetail = Runnable { invalidate() }

    private fun clearInkLayers() {
        removeCallbacks(refreshInkDetail)
        zoomRenderState.reset()
        committedInk.clear()
        navigationInk.clear()
    }
    private fun selectionIdentities(): MutableSet<Stroke> {
        val key = selection
        val cached = selectionIds
        if (selectionIdsKey !== key || cached == null) {
            selectionIdsKey = key
            val built = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>())
            built.addAll(key)
            selectionIds = built
            return built
        }
        return cached
    }
    private fun resetDraftGeometry() { draftPenStroke = null; draftPenPoints = null }
    private fun draftGeometry(stroke: Stroke): InkRenderer.RenderedStroke {
        val points = stroke.points
        if (draftPenPoints !== points) { draftPenPoints = points; draftPenStroke = InkRenderer.IncrementalPenStroke() }
        return draftPenStroke!!.update(points)
    }
    /**
     * Smoothed ink geometry by stroke identity. Strokes are immutable and untouched strokes
     * keep their instance through erasing and page copies, so each stroke pays the spline
     * math once while cached, across frames, recordings and eraser passes.
     * UI thread only; bounded and pruned to the live page so undo generations never pin memory.
     */
    private val renderCache = IdentityCache<Stroke, InkRenderer.RenderedStroke>(MAX_CACHED_STROKES)
    private val boundsCache = IdentityCache<Stroke, FloatArray>(MAX_CACHED_STROKES)
    private fun renderedOf(stroke: Stroke): InkRenderer.RenderedStroke =
        renderCache.getOrPut(stroke) { InkRenderer.rendered(stroke) }
    private fun boundsOf(stroke: Stroke): FloatArray =
        boundsCache.getOrPut(stroke) { InkRenderer.rawBounds(stroke) }
    /** Reused base page (page minus selection) so a selection drag reuses the retained layer. */
    private var restCacheKeyPage: NotePage? = null
    private var restCacheKeyStrokes: List<Stroke>? = null
    private var restCacheKeyTexts: List<TextBox>? = null
    private var restCacheKeyImages: List<PageImage>? = null
    private var restCache: NotePage? = null

    private fun drawCommittedPage(canvas: Canvas, content: NotePage) {
        val preview = zoomRenderState.usePreview(scale, navigating || lineAdvance != null,
            SystemClock.uptimeMillis())
        if (preview) {
            // Coalesce rapid pinches and container resizes into one full-detail refresh.
            removeCallbacks(refreshInkDetail)
            postDelayed(refreshInkDetail, 90L)
        }
        InkRenderer.pageCached(canvas, content, background, images = imageBitmaps,
            boundsOf = ::boundsOf, renderOf = ::renderedOf,
            drawInk = { inkCanvas ->
                if (preview) drawNavigationInk(inkCanvas, content)
                else committedInk.draw(inkCanvas, content.strokes, scale, ::boundsOf, ::renderedOf)
            })
    }

    private fun drawNavigationInk(canvas: Canvas, content: NotePage) {
        if (content.infinite) canvas.getClipBounds(requiredInkBounds)
        else requiredInkBounds.set(0, 0, ceil(content.width).toInt(), ceil(content.height).toInt())
        // A whole-page writing bitmap already contains everything a finite-page pinch can reveal.
        if (committedInk.drawSnapshot(canvas, content.strokes, requiredInkBounds) ||
            navigationInk.drawSnapshot(canvas, content.strokes, requiredInkBounds)) return
        navigationBounds.set(requiredInkBounds)
        if (content.infinite) {
            // Overscan reduces rebuilds when panning or zooming out on an unbounded canvas.
            navigationBounds.inset(-requiredInkBounds.width() / 2, -requiredInkBounds.height() / 2)
        }
        val longest = max(navigationBounds.width(), navigationBounds.height()).coerceAtLeast(1)
        val previewScale = min(scale, 1024f / longest)
        navigationInk.draw(canvas, content.strokes, previewScale, ::boundsOf, ::renderedOf,
            rasterViewport = navigationBounds)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(followFrame)
        clearInkLayers(); renderCache.clear(); boundsCache.clear(); restCache = null; restCacheKeyPage = null
        resetDraftGeometry()
        super.onDetachedFromWindow()
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
        // Identity set avoids O(S_sel × S_page × pts) deep-equals per frame while dragging, and is
        // reused while the selection list itself is unchanged so a drag allocates nothing here.
        val selectedIds = if (!hasSelection || selection.isEmpty()) null else selectionIdentities()
        // Reuse the same base object while the page and the selection membership are stable:
        // a drag only changes the offset (drawn below via a canvas translate), so the
        // retained committed layer keeps hitting instead of re-recording the page per frame.
        // A live text/picture drag does change the base, so it always rebuilds.
        val rest = if (!hasSelection) placed
        else if (dragging != null || liveImage != null) placed.copy(
            strokes = if (selection.isEmpty()) placed.strokes else placed.strokes.filterNot { it in selectedIds!! || it in selection },
            texts = placed.texts.filterNot { box -> selectedTexts.any { it.id == box.id } },
            images = placed.images.filterNot { image -> selectedImages.any { it.id == image.id } }
        )
        else if (placed === restCacheKeyPage && selection === restCacheKeyStrokes &&
            selectedTexts === restCacheKeyTexts && selectedImages === restCacheKeyImages && restCache != null) restCache!!
        else {
            val built = placed.copy(
                strokes = if (selection.isEmpty()) placed.strokes else placed.strokes.filterNot { it in selectedIds!! || it in selection },
                texts = placed.texts.filterNot { box -> selectedTexts.any { it.id == box.id } },
                images = placed.images.filterNot { image -> selectedImages.any { it.id == image.id } }
            )
            restCacheKeyPage = placed; restCacheKeyStrokes = selection
            restCacheKeyTexts = selectedTexts; restCacheKeyImages = selectedImages; restCache = built
            built
        }
        if (erasing != null) {
            // The survivor set changes on every eraser MOVE, so retaining it would re-record
            // the whole page per sample. Draw it directly instead: the dirty rect culls to the
            // tip area and the geometry cache means only newly touched strokes pay any math.
            InkRenderer.pageCached(canvas, placed, background, images = imageBitmaps,
                boundsOf = ::boundsOf, renderOf = ::renderedOf)
        } else drawCommittedPage(canvas, rest)
        // A handle drag previews scale/turn live through one matrix instead of reallocating
        // transformed copies per frame; the release commits the real geometry in one step.
        val previewing = hasSelection() &&
            (selectionPreviewScale != 1f || selectionPreviewDeg != 0f)
        if (previewing) {
            canvas.save()
            val pivot = selectionBox()?.let { box -> InkPoint((box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f) }
                ?: handleCenter
            val matrix = Matrix()
            matrix.postTranslate(-pivot.x, -pivot.y)
            matrix.postScale(selectionPreviewScale, selectionPreviewScale)
            matrix.postRotate(selectionPreviewDeg)
            matrix.postTranslate(pivot.x, pivot.y)
            canvas.concat(matrix)
        }
        if (selection.isNotEmpty()) {
            // Draw the originals under a translate instead of allocating translated copies:
            // the geometry cache (and the text layout cache for boxes below) keeps hitting,
            // and no per-frame smoothing or measuring runs while the selection moves.
            canvas.save(); canvas.translate(selectionDx, selectionDy)
            // Skip the double-draw halo for huge selections; the drag offset already reads clearly.
            if (selection.size <= SELECTION_HALO_LIMIT) {
                selection.forEach { InkRenderer.drawHalo(canvas, it, renderedOf(it), SELECTION_COLOR) }
            }
            selection.forEach { InkRenderer.drawRendered(canvas, it, renderedOf(it)) }
            canvas.restore()
        }
        if (selectedTexts.isNotEmpty()) {
            canvas.save(); canvas.translate(selectionDx, selectionDy)
            selectedTexts.forEach {
                InkRenderer.text(canvas, it)
                drawTextBox(canvas, it)
            }
            canvas.restore()
        }
        if (selectedImages.isNotEmpty()) {
            canvas.save(); canvas.translate(selectionDx, selectionDy)
            selectedImages.forEach {
                imageBitmaps[it.id]?.let { bitmap -> InkRenderer.image(canvas, bitmap, it) }
                drawImageSelection(canvas, it, withHandle = false)
            }
            canvas.restore()
        }
        if (hasSelection()) drawSelectionFrame(canvas)
        if (previewing) canvas.restore()
        val draftStroke = draft
        // A pen stroke in progress draws from incremental geometry: only the section the tip is
        // still extending is re-smoothed, so a long line costs the same per frame as a short one
        // instead of re-resampling the whole stroke 144 times a second.
        if (draftStroke == null) resetDraftGeometry()
        else {
            val live = if (draftStroke.tool == Tool.PEN) draftGeometry(draftStroke) else null
            if (live != null) InkRenderer.drawRendered(canvas, draftStroke, live)
            else InkRenderer.stroke(canvas, draftStroke)
            if (shapeMeasurements && draftStroke.tool in MEASURE_TOOLS) drawMeasurement(canvas, draftStroke)
        }
        lasso?.takeIf { it.size > 1 }?.let { drawLasso(canvas, it) }
        eraserMark?.let { drawEraser(canvas, it) }
        dragging?.let { drawTextBox(canvas, it.moved(textDx, textDy)) }
        // The selected picture keeps its outline while another picture is dragged, unless it is
        // part of the lasso selection, which already draws its own outline at the drag offset.
        val outlined = selectedImageId?.let { id ->
            (liveImage?.takeIf { it.id == id } ?: placed.images.find { it.id == id })
                ?.takeIf { hand -> selectedImages.none { it.id == hand.id } }
        }
        outlined?.let { drawImageSelection(canvas, it) }
        (regionDraft ?: writingRegion)?.let { r ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA387C83.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f / scale }
            canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
        }
        canvas.restore()
    }
    /**
     * Supplies damage bounds on older software renderers. Hardware Views may ignore these bounds;
     * the retained committed layer above is what avoids rebuilding the page during pen updates.
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
        // Tight margin: stroke width + small spline overshoot + eraser ring. The old
        // 64px×scale margin dirtied half the screen per tip move.
        val margin = inkWidth * scale + 16f * scale.coerceAtMost(2f) + 12f
        val l = (originX + minX * scale - margin).toInt()
        val t = (originY + minY * scale - margin).toInt()
        val r = (originX + maxX * scale + margin).toInt()
        val b = (originY + maxY * scale + margin).toInt()
        @Suppress("DEPRECATION")
        invalidate(l, t, r, b)
        return true
    }

    /** Tight dirty rect for a shape drag re-derived from its two corners. */
    private fun invalidateForShape(start: InkPoint, end: InkPoint) {
        val margin = max(inkWidth, 14f) * scale + 16f
        val l = (originX + min(start.x, end.x) * scale - margin).toInt()
        val t = (originY + min(start.y, end.y) * scale - margin).toInt()
        val r = (originX + max(start.x, end.x) * scale + margin).toInt()
        val b = (originY + max(start.y, end.y) * scale + margin).toInt()
        @Suppress("DEPRECATION")
        invalidate(l, t, r, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inputBlocked) return true
        if (selectingWritingRegion) {
            if (!isStylus(event, 0) && !fingerDrawing) return true
            val pt = clampToPage(point(event, 0))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { regionStart = pt; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> regionStart?.let { regionDraft = WritingLane(min(it.x, pt.x), min(it.y, pt.y), max(it.x, pt.x), max(it.y, pt.y)) }
                MotionEvent.ACTION_UP -> {
                    regionStart?.let { val r = WritingLane(min(it.x, pt.x), min(it.y, pt.y), max(it.x, pt.x), max(it.y, pt.y))
                        if (r.right - r.left >= 48f && r.bottom - r.top >= 32f) { writingRegion = r; onWritingRegion(r); onFollowStatus("Answer area selected") }
                        else onFollowStatus("Area too small · try again") }
                    regionDraft = null; regionStart = null; selectingWritingRegion = false; parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> { regionDraft = null; regionStart = null; selectingWritingRegion = false; parent?.requestDisallowInterceptTouchEvent(false) }
            }
            invalidate(); return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (pendingReturn != null) onFollowStatus("Return cancelled · keep writing")
            pendingReturn = null; removeCallbacks(followFrame)
            advanceTotalX = 0f; advanceTotalY = 0f; lineAdvance = null
        }
        var dirtyInvalidated = false
        val hasStylus = (0 until event.pointerCount).any { isStylus(event, it) }
        if (hasStylus && (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            requestUnbufferedDispatch(event)
        }
        if (!multiTouchUndo || hasStylus || isPalm(event, 0)) touchChord.reset()
        else {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchChord.down(event.getPointerId(0), event.getX(0), event.getY(0), event.eventTime)
            } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val i = event.actionIndex
                touchChord.join(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            for (i in 0 until event.pointerCount) {
                for (h in 0 until event.historySize) {
                    touchChord.move(event.getPointerId(i), event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                }
                touchChord.move(event.getPointerId(i), event.getX(i), event.getY(i))
            }
        }
        if ((page.infinite || readOnly || writingStrip) && !stylus && (0 until event.pointerCount).none { isStylus(event, it) } && !isPalm(event, 0)) {
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
                if (navigating) suspendWritingFollow()
                lastX = event.rawX; lastY = event.rawY
                panVelocity.resetTracking()
                panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                if (!readOnly && tool == Tool.HAND && !ignored && beginImage(event, 0)) {
                    navigating = false
                } else if (tool == Tool.HAND && !ignored && beginLink(event, 0)) {
                    navigating = false
                } else if (lassoActive()) beginLasso(event, 0)
                else if (tool == Tool.TEXT && !ignored) beginText(event, 0)
                else if (!navigating && !ignored) beginStroke(event, 0)
                // Pen-only mode is where a finger means "navigate", so a hand that has not travelled
                // yet is left where it is. The pen takes the gesture over the moment its tip lands.
                panGate.arm(navigating && !stylus && !fingerDrawing, centroidX(event), centroidY(event))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (isStylus(event, event.actionIndex)) {
                    // The stylus landed over an in-progress palm stroke: drop it and follow the stylus.
                    pointerId = event.getPointerId(event.actionIndex)
                    stylus = true
                    ignored = false
                    navigating = tool == Tool.HAND
                    panGate.release()
                    draft = null; erasing = null; lasso = null; cancelSelectionGesture(); offPage = false; eraserMark = null
                    movingText = null; pendingTextBox = null
                    movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null
                    if (lassoActive()) beginLasso(event, event.actionIndex)
                    else if (tool == Tool.TEXT) beginText(event, event.actionIndex)
                    else if (!navigating) beginStroke(event, event.actionIndex)
                } else if (!stylus && !ignored) {
                    suspendWritingFollow()
                    draft = null; erasing = null; lasso = null; cancelSelectionGesture(); movingText = null; pendingTextBox = null; movingImage = null; resizingImage = false; pendingLink = null; navigating = true
                    // Two fingers are a deliberate pinch or pan, never a resting hand.
                    panGate.release()
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
                    val dx = moved.x - textFromX; val dy = moved.y - textFromY
                    if (hypot(dx, dy) * scale > ViewConfiguration.get(context).scaledTouchSlop) textDragged = true
                    if (textDragged) { textDx = dx; textDy = dy }
                } else if (lassoActive()) {
                    if (resizingSelection || rotatingSelection) {
                        val at = clampToPage(point(event, index))
                        if (resizingSelection) {
                            selectionPreviewScale =
                                (hypot(at.x - handleCenter.x, at.y - handleCenter.y) / handleStartDist)
                                    .coerceIn(0.2f, 5f)
                        } else {
                            selectionPreviewDeg = InkGeometry.rotationDelta(
                                handleStartAngle, InkGeometry.angleOf(handleCenter, at))
                        }
                    } else if (movingSelection) {
                        val moved = clampToPage(point(event, index))
                        selectionDx += moved.x - lastMoveX; selectionDy += moved.y - lastMoveY
                        lastMoveX = moved.x; lastMoveY = moved.y
                    } else {
                        // Reuse the backing list: rebuilding the whole loop per MOVE is O(N²).
                        val backing = (lasso as? ArrayList<InkPoint>) ?: ArrayList(lasso ?: emptyList())
                        for (history in 0 until event.historySize) backing += clampToPage(point(event, index, history))
                        backing += clampToPage(point(event, index))
                        lasso = backing
                    }
                } else if (navigating) {
                    suspendWritingFollow()
                    val x = centroidX(event)
                    val y = centroidY(event)
                    panVelocity.addPosition(event.eventTime, Offset(x, y))
                    // Held back until the contact reads as a drag, so a settling hand moves nothing.
                    if (panGate.moved(x, y)) {
                        if (page.infinite || readOnly || writingStrip) { camera.pan(x - lastX, y - lastY); reportCanvasViewport() } else onDocumentPan(x - lastX, y - lastY)
                    }
                    lastX = x; lastY = y
                } else {
                    val points = samples(event, index)
                    // The eraser cuts out the samples it touches and leaves the rest of the stroke behind.
                    // One pass applies all of this frame's samples: re-walking every stroke once per
                    // sample is what made a drag feel heavy once a page had ink on it.
                    val cutting = erasing
                    if (cutting != null) {
                        val centers = points.map { clampToPage(it) }
                        // Bounding-box prefilter on cached bounds: far strokes skip the segment
                        // walk entirely, so an eraser pass costs O(nearby) exact cuts instead of
                        // O(page) geometry per MOVE.
                        var cMinX = Float.MAX_VALUE; var cMinY = Float.MAX_VALUE
                        var cMaxX = -Float.MAX_VALUE; var cMaxY = -Float.MAX_VALUE
                        for (c in centers) {
                            if (c.x < cMinX) cMinX = c.x
                            if (c.x > cMaxX) cMaxX = c.x
                            if (c.y < cMinY) cMinY = c.y
                            if (c.y > cMaxY) cMaxY = c.y
                        }
                        erasing = if (eraserWholeStroke) {
                            val maxR = if (eraserPressureEnabled && stylus)
                                centers.maxOf { inkWidth / 2f * InkGeometry.eraserScale(it.pressure) } else inkWidth / 2f
                            cutting.filterNot { hitStroke ->
                                val b = boundsOf(hitStroke)
                                val reach = maxR + hitStroke.width / 2f
                                if (b[2] + reach < cMinX || b[0] - reach > cMaxX ||
                                    b[3] + reach < cMinY || b[1] - reach > cMaxY) return@filterNot false
                                centers.any { c ->
                                    val r = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(c.pressure) else inkWidth / 2f
                                    InkGeometry.hits(hitStroke, c, r)
                                }
                            }
                        } else if (eraserPressureEnabled && stylus) {
                            val radii = centers.map { inkWidth / 2f * InkGeometry.eraserScale(it.pressure) }
                            val maxR = radii.max()
                            cutting.flatMap {
                                val b = boundsOf(it)
                                val reach = maxR + it.width / 2f
                                if (b[2] + reach < cMinX || b[0] - reach > cMaxX ||
                                    b[3] + reach < cMinY || b[1] - reach > cMaxY) listOf(it)
                                else InkGeometry.erase(it, centers, radii)
                            }
                        } else {
                            val radius = inkWidth / 2f
                            cutting.flatMap {
                                val b = boundsOf(it)
                                val reach = radius + it.width / 2f
                                if (b[2] + reach < cMinX || b[0] - reach > cMaxX ||
                                    b[3] + reach < cMinY || b[1] - reach > cMaxY) listOf(it)
                                else InkGeometry.erase(it, centers, radius)
                            }
                        }
                        eraserMark = centers[centers.size - 1]
                    }
                    draft?.let { current ->
                        val accepted = pagePoints(points)
                        draft = when {
                            accepted.isEmpty() -> current
                            current.tool in FREEHAND_TOOLS -> {
                                // Reuse the backing list after the first batch: copying the whole
                                // point list per MOVE is O(N²) for a long stroke.
                                val backing = (current.points as? ArrayList<InkPoint>) ?: ArrayList(current.points)
                                backing.addAll(accepted)
                                current.copy(points = backing)
                            }
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
                        // invalidate their own tight bounds instead of the whole view.
                        if (draft?.tool in FREEHAND_TOOLS || erasing != null) {
                            dirtyInvalidated = invalidateForSamples(accepted.ifEmpty { points })
                        } else {
                            draft?.let { shape ->
                                if (shape.points.size >= 2) {
                                    invalidateForShape(shape.points.first(), shape.points.last())
                                    dirtyInvalidated = true
                                }
                            }
                        }
                    }
                    if (erasing != null && draft == null) {
                        dirtyInvalidated = invalidateForSamples(points)
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
                val chord = touchChord.finish(event.eventTime)
                if (chord != 0) {
                    cancelGesture()
                    if (chord == 2) onUndoRequest?.invoke() else onRedoRequest?.invoke()
                    invalidate()
                    return true
                }
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
                    // A hand that never panned must not fling the document when it lifts either.
                    onDocumentPanEnd(if (panGate.waitingForSlop) 0f else panVelocity.calculateVelocity().y)
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
            MotionEvent.ACTION_CANCEL -> { touchChord.reset(); cancelGesture() }
        }
        if (!dirtyInvalidated) invalidate()
        return true
    }
    private fun finishGesture() {
        val wasErasing = erasing != null
        val drawn = draft
        var scribbleErased: List<Stroke>? = null
        if (drawn != null && scribbleToErase && (drawn.tool == Tool.PEN || drawn.tool == Tool.HIGHLIGHTER)) {
            val scrubbed = InkGeometry.scribbleErase(page.strokes, drawn, SCRIBBLE_RADIUS, scribbleSensitivity)
            if (scrubbed.size != page.strokes.size) scribbleErased = scrubbed
        }
        // "Tidy up": a pen drawing that reads as a shape lands as a clean one instead.
        val tidied = if (scribbleErased == null && drawn != null && shapeRecognition) InkGeometry.tidy(drawn)?.let(::snapShapes) else null
        val strokes = scribbleErased ?: (tidied ?: drawn?.let { listOf(it) })?.let { page.strokes + it } ?: erasing
        if (followEnabled && drawn?.tool == Tool.PEN && scribbleErased == null && tidied == null) {
            val now = SystemClock.uptimeMillis()
            followPaused = false
            writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
            writingFollow.completed(drawn.points, now)
            followLastPoint = drawn.points.lastOrNull()
            followLiftedAt = now
            val region = followRegion()
            val pt = followLastPoint
            if (pt != null && pt.x in region.left..region.right && pt.y in region.top..region.bottom) {
                val baseline = writingFollow.state.baselineY ?: pt.y
                val next = FollowNavigation.next(baseline, region, writingGuides, followPreferences.spacing)
                val nearEnd = FollowNavigation.nearEnd(drawn.points, region, followPreferences.direction)
                if (nearEnd && next != null && followPreferences.mode == FollowMode.TEXT && FollowNavigation.isTextStroke(drawn.points, next.to.y - next.from.y)) {
                    onFollowStatus(if (followPreferences.automaticReturn) "Next line… keep writing to cancel" else "Next line ready")
                    if (followPreferences.automaticReturn) pendingReturn = next
                } else onFollowStatus(if (next == null) "End of answer area" else "Following")
                if (pendingReturn == null && getLocalVisibleRect(followVisible)) {
                    val zoom = (if (page.infinite || writingStrip) camera.zoom else documentFollowZoom)
                    if (zoom >= 1.4f) {
                        val sx = originX + pt.x * scale
                        val sy = originY + pt.y * scale
                        val target = followVisible.left + followVisible.width() * (followPreferences.horizontalPosition + if (writingHand == WritingHand.RIGHT) -.02f else .02f)
                        val edge = if (followPreferences.direction == WritingDirection.LTR) sx > followVisible.left + followVisible.width() * .72f else sx < followVisible.left + followVisible.width() * .28f
                        val dx = if (edge && followPreferences.mode == FollowMode.TEXT) target - sx else 0f
                        val desiredY = followVisible.top + followVisible.height() * followPreferences.position
                        val dy = if (sy > desiredY + followVisible.height() * .15f) desiredY - sy else 0f
                        advanceTotalX = dx; advanceTotalY = dy; advanceDoneX = 0f; advanceDoneY = 0f; advanceStartAt = now + 120
                        if (dx != 0f || dy != 0f) {
                            backPanX = 0f; backPanY = 0f; backFollowState = writingFollow.state; hasFollowBack = true
                        }
                    }
                }
                scheduleFollow()
            }
        }
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
        if (hit != null) { movingText = hit; textDragged = false; textDx = 0f; textDy = 0f; textFromX = at.x; textFromY = at.y }
        else pendingTextBox = at
    }
    /** A drag commits the box's new place; a tap edits the box, or creates one on empty page. */
    private fun finishText() {
        val box = movingText
        movingText = null
        if (box != null) {
            if (textDragged) {
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
    private fun cancelSelectionGesture() {
        movingSelection = false; resizingSelection = false; rotatingSelection = false
        selectionPreviewScale = 1f; selectionPreviewDeg = 0f
    }
    private fun cancelGesture() { panGate.release(); draft = null; erasing = null; lasso = null; cancelSelectionGesture(); selectionDx = 0f; selectionDy = 0f; pointerId = -1; stylus = false; ignored = false; navigating = false; offPage = false; eraserMark = null; movingText = null; pendingTextBox = null; textDx = 0f; textDy = 0f; movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null; parent?.requestDisallowInterceptTouchEvent(false) }
    private fun lassoActive() = tool == Tool.LASSO && !navigating && !ignored
    /** Starts a fresh loop, picks up the selection to move it, or grabs a frame handle. */
    private fun beginLasso(event: MotionEvent, index: Int) {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        val start = clampToPage(raw)
        if (hasSelection()) {
            val box = selectionBox()
            if (box != null) {
                when (InkGeometry.selectionHandleAt(box, start)) {
                    InkGeometry.SelectionHandle.RESIZE -> {
                        if (beginHandleGesture(start)) { resizingSelection = true; return }
                    }
                    InkGeometry.SelectionHandle.ROTATE -> {
                        if (beginHandleGesture(start)) { rotatingSelection = true; return }
                    }
                    InkGeometry.SelectionHandle.NONE -> Unit
                }
            }
        }
        if (insideSelection(start)) { movingSelection = true; lastMoveX = start.x; lastMoveY = start.y; return }
        setSelection(CanvasSelection())
        lasso = listOf(start)
    }
    /** Arms a resize/rotate gesture about the selection's current center. False when uncenterable. */
    private fun beginHandleGesture(start: InkPoint): Boolean {
        val center = InkGeometry.selectionCenter(selection, selectedTexts, selectedImages, { InkRenderer.textHeight(it) })
            ?: return false
        handleCenter = InkPoint(center.x + selectionDx, center.y + selectionDy)
        handleStartDist = hypot(start.x - handleCenter.x, start.y - handleCenter.y).coerceAtLeast(1f)
        handleStartAngle = InkGeometry.angleOf(handleCenter, start)
        selectionPreviewScale = 1f
        selectionPreviewDeg = 0f
        invalidate()
        return true
    }
    private fun hasSelection(): Boolean =
        selection.isNotEmpty() || selectedTexts.isNotEmpty() || selectedImages.isNotEmpty()
    private fun finishLasso() {
        if (resizingSelection || rotatingSelection) commitSelectionTransform()
        else if (movingSelection) commitSelectionMove()
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
        movingSelection = false; resizingSelection = false; rotatingSelection = false
    }
    private fun commitSelectionMove() {
        if ((selectionDx != 0f || selectionDy != 0f) &&
            (selection.isNotEmpty() || selectedTexts.isNotEmpty() || selectedImages.isNotEmpty())
        ) {
            replaceSelectionContent(
                selection.map { InkGeometry.translate(it, selectionDx, selectionDy) },
                selectedTexts.map { it.moved(selectionDx, selectionDy) },
                selectedImages.map { it.moved(selectionDx, selectionDy) }
            )
        }
    }
    private fun setSelection(value: CanvasSelection) {
        selection = value.strokes; selectedTexts = value.texts; selectedImages = value.images
        selectionDx = 0f; selectionDy = 0f
        selectionPreviewScale = 1f; selectionPreviewDeg = 0f
        onSelectionChanged(value); reportSelectionViewBounds(); invalidate()
    }
    /**
     * Commits a resize/rotate handle drag as one undoable step and keeps the
     * selection alive, mirroring a move. Uniform scale applies first, then the
     * turn, both about the grab-time pivot.
     */
    private fun commitSelectionTransform() {
        val factor = selectionPreviewScale
        val degrees = selectionPreviewDeg
        selectionPreviewScale = 1f; selectionPreviewDeg = 0f
        if ((factor == 1f && degrees == 0f) ||
            (selection.isEmpty() && selectedTexts.isEmpty() && selectedImages.isEmpty())
        ) {
            invalidate()
            return
        }
        var strokes = selection
        var texts = selectedTexts
        var images = selectedImages
        if (factor != 1f) {
            strokes = InkGeometry.scale(strokes, handleCenter, factor)
            texts = InkGeometry.scaleTexts(texts, handleCenter, factor)
            images = InkGeometry.scaleImages(images, handleCenter, factor)
        }
        if (degrees != 0f) {
            strokes = InkGeometry.rotate(strokes, handleCenter, degrees)
            texts = InkGeometry.rotateTexts(texts, handleCenter, degrees)
            images = InkGeometry.rotateImages(images, handleCenter, degrees)
        }
        replaceSelectionContent(strokes, texts, images)
    }
    /** Swaps the selection's members for transformed copies across the whole page. */
    private fun replaceSelectionContent(
        strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>
    ) {
        val doomed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(selection) }
        val allStrokes = page.strokes.filterNot { it in doomed || it in selection } + strokes
        val allTexts = page.texts.filterNot { box -> selectedTexts.any { it.id == box.id } } + texts
        val allImages = page.images.filterNot { image -> selectedImages.any { it.id == image.id } } + images
        page = page.copy(strokes = allStrokes, texts = allTexts, images = allImages)
        setSelection(CanvasSelection(strokes, texts, images))
        onContentChanged(allStrokes, allTexts, allImages)
    }
    /**
     * The selection frame in view fractions (0..1) for the editor's floating
     * pill. Null while empty or before first layout.
     */
    private fun reportSelectionViewBounds() {
        val rect = if (!hasSelection() || width <= 0 || height <= 0) null
        else selectionBox()?.let { box ->
            Rect(
                (originX + box[0] * scale) / width,
                (originY + box[1] * scale) / height,
                (originX + box[2] * scale) / width,
                (originY + box[3] * scale) / height
            )
        }
        if (rect != lastReportedSelectionBounds) {
            lastReportedSelectionBounds = rect
            onSelectionViewBounds(rect)
        }
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
    /** The frame the resize/rotate handles live on: tighter than the move grab area. */
    private fun selectionBox(): FloatArray? = selectionBounds(margin = 8f)
    private fun drawLasso(canvas: Canvas, loop: List<InkPoint>) {
        val polygon = Path().apply { moveTo(loop.first().x, loop.first().y); loop.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        canvas.drawPath(polygon, lassoFillPaint); canvas.drawPath(polygon, lassoEdgePaint)
    }
    /** A dashed outline around a text box while it is dragged, so its extent is visible. */
    private fun drawTextBox(canvas: Canvas, box: TextBox) {
        canvas.drawRect(box.x - 4f, box.y - 4f, box.x + box.width + 4f, box.y + InkRenderer.textHeight(box) + 4f, textBoxPaint)
    }
    /**
     * Dashed frame around the lasso selection with direct handles: a plain dot
     * on the bottom-right corner resizes about the center, a ringed dot with a
     * circular arrow above the top edge rotates about it. Drawn in page units,
     * inside the preview matrix while a handle drag is live.
     */
    private fun drawSelectionFrame(canvas: Canvas) {
        val box = selectionBox() ?: return
        canvas.drawRect(box[0], box[1], box[2], box[3], selectionBoxPaint)
        val cx = (box[0] + box[2]) / 2f
        val rotateY = box[1] - InkGeometry.SELECTION_ROTATE_LIFT
        canvas.drawLine(cx, box[1], cx, rotateY + SELECTION_HANDLE_RADIUS, selectionLinkPaint)
        canvas.drawCircle(box[2], box[3], SELECTION_HANDLE_RADIUS, imageHandlePaint)
        canvas.drawCircle(box[2], box[3], SELECTION_HANDLE_RADIUS, imageHandleEdgePaint)
        canvas.drawCircle(cx, rotateY, SELECTION_HANDLE_RADIUS, imageHandlePaint)
        canvas.drawCircle(cx, rotateY, SELECTION_HANDLE_RADIUS, imageHandleEdgePaint)
        // Circular arrow: 300° of arc plus a V head at its end (420° == 60°).
        val r = 8f
        canvas.drawArc(RectF(cx - r, rotateY - r, cx + r, rotateY + r), 120f, 300f, false, selectionGlyphPaint)
        val end = Math.toRadians(60.0)
        val ex = (cx + r * cos(end)).toFloat()
        val ey = (rotateY + r * sin(end)).toFloat()
        // Unit tangent of clockwise travel at 60°; wings fan the backward
        // direction ±25° into a V, in page units.
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val head = 5.5f
        val spread = Math.toRadians(25.0)
        fun wing(flip: Double): Pair<Float, Float> {
            val a = flip * spread
            val bx = -tx
            val by = -ty
            val wx = bx * cos(a) - by * sin(a)
            val wy = bx * sin(a) + by * cos(a)
            return (ex + head * wx).toFloat() to (ey + head * wy).toFloat()
        }
        val (x1, y1) = wing(1.0)
        val (x2, y2) = wing(-1.0)
        canvas.drawLine(ex, ey, x1, y1, selectionGlyphPaint)
        canvas.drawLine(ex, ey, x2, y2, selectionGlyphPaint)
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
        // Cancel on contact, including strokes that finish before the next animation frame.
        advanceTotalX = 0f; advanceTotalY = 0f; advanceDoneX = 0f; advanceDoneY = 0f; lineAdvance = null
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        var start = clampToPage(raw)
        if (snapEnabled && tool in listOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE) && page.paper.isGrid) {
            start = InkGeometry.snapToGrid(start, page.paper.gridSpacing)
        }
        if (tool == Tool.ERASER || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) {
            val radius = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(start.pressure) else inkWidth / 2f
            erasing = if (eraserWholeStroke) {
                page.strokes.filterNot {
                    val b = boundsOf(it)
                    val reach = radius + it.width / 2f
                    if (start.x < b[0] - reach || start.x > b[2] + reach ||
                        start.y < b[1] - reach || start.y > b[3] + reach) return@filterNot false
                    InkGeometry.hits(it, start, radius)
                }
            } else page.strokes.flatMap {
                val b = boundsOf(it)
                val reach = radius + it.width / 2f
                if (start.x < b[0] - reach || start.x > b[2] + reach ||
                    start.y < b[1] - reach || start.y > b[3] + reach) listOf(it)
                else InkGeometry.erase(it, start, radius)
            }
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
    // Summed directly: the filter/map/average chain allocated two lists and a boxed Float per
    // pointer on every MOVE, which is the busiest path in the whole view.
    private fun centroidX(e: MotionEvent, skip: Int = -1): Float {
        var sum = 0f; var count = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); count++ }
        val average = if (count == 0) Float.NaN else sum / count
        return average + e.rawX - e.x
    }
    private fun centroidY(e: MotionEvent, skip: Int = -1): Float {
        var sum = 0f; var count = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); count++ }
        val average = if (count == 0) Float.NaN else sum / count
        return average + e.rawY - e.y
    }
    /** This event's samples for [index] in one list, history first, instead of two plus a concat. */
    private fun samples(event: MotionEvent, index: Int): ArrayList<InkPoint> {
        val points = ArrayList<InkPoint>(event.historySize + 1)
        for (history in 0 until event.historySize) points += point(event, index, history)
        points += point(event, index)
        return points
    }
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
        /** Above this many selected strokes the halo double-draw is skipped to avoid 2× overdraw. */
        const val SELECTION_HALO_LIMIT = 40
        /** Drawn radius of each selection frame handle, in page units. */
        const val SELECTION_HANDLE_RADIUS = 16f
        /** Geometry caches evict one old entry at capacity, even on pages with more live strokes. */
        const val MAX_CACHED_STROKES = 4000
        val SELECTION_COLOR = 0xFF2F6FBA.toInt()
    }
}
