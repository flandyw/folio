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
import android.view.animation.AnimationUtils
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
    /** Theme canvas behind the paper, including reference and peek views. */
    var canvasBackgroundColor: Int = Color.TRANSPARENT
        set(value) { if (field != value) { field = value; invalidate() } }
    var tool = Tool.PEN
        set(value) {
            if (field == value) return
            stickyNotes.reset()
            field = value
            // A selection only makes sense while the lasso is in hand.
            if (value != Tool.LASSO) clearSelection()
            // The outline belongs to the eraser, so it leaves with the tool.
            if (value != Tool.ERASER) eraserMark = null
            // A half-finished text gesture belongs to the text tool.
            if (value != Tool.TEXT) { movingText = null; pendingTextBox = null; textDx = 0f; textDy = 0f }
            // A half-dragged picture belongs to the hand tool.
            // A boxed allocation still waiting for its marks goes with the mark-area tool.
            if (value != Tool.MARK_AREA && markRegionShown != null) { markRegionShown = null; onMarkRegion(null, null); invalidate() }
            if (value != Tool.HAND) { movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null; endImageCrop(false); clearImageSelection() }
        }
    var inkColor = Color.rgb(47, 49, 47)
    var inkWidth = 3f
    var fingerDrawing = true
    var inkOpacity = 1f
    /** The layer new ink, text and pictures land on; the page's [NotePage.layers] says what it is. */
    var activeLayer = 0
    /** Told when a stroke is refused because the active layer is hidden or locked. */
    var onLayerBlocked: () -> Unit = {}
    /** Line pattern for new shape strokes; freehand ink always draws solid. */
    var inkStyle: StrokeStyle = StrokeStyle.SOLID
    var pressureEnabled = true
    var pressureSensitivity = 1f
    var pressureVariation = 1f
    var eraserPressureEnabled = true
    var scribbleToErase = true
    var scribbleSensitivity = ScribbleSensitivity.DEFAULT
    var eraserWholeStroke = false
    /** How long after stylus activity a finger still counts as a resting palm. 0 disables. */
    var palmRejectMs: Long = PALM_REJECT_MS
    /** Scales finger pan distance and fling speed (the "fast pan" setting); 1 leaves panning unchanged. */
    var panMultiplier = 1f
    var shapeMeasurements = true
    var multiTouchUndo = true
    var onEraserFinished: (() -> Unit)? = null
    var onUndoRequest: (() -> Unit)? = null
    var onRedoRequest: (() -> Unit)? = null
    /** When true, shape endpoints snap to the page's grid and lines snap to 15° steps. */
    var snapEnabled = true
    /** How the graph tool dresses the axes it draws; the editor keeps this in the shared prefs. */
    var graphStyle = GraphStyle.DEFAULT
    var onActive: () -> Unit = {}
    var onDocumentPan: (Float, Float) -> Unit = { _, _ -> }
    var onDocumentPanEnd: (Float) -> Unit = {}
    private val panVelocity = VelocityTracker()
    var onStrokesChanged: (List<Stroke>) -> Unit = {}
    /** Exact append from a pen gesture; [before] is the page list held at pen-up. */
    var onStrokeAppended: ((before: List<Stroke>, stroke: Stroke, after: List<Stroke>) -> Unit)? = null
    /**
     * Ink under the pen: true when a fresh stroke begins, false while one grows or the eraser
     * works. Lets the exam timer resume on a pen-down and measure idleness from real writing.
     */
    var onPenInput: (beginsStroke: Boolean) -> Unit = {}
    /** Reports the ink, text and pictures inside the lasso loop so the editor can offer actions. */
    var onSelectionChanged: (CanvasSelection) -> Unit = {}
    /**
     * The selection's frame as fractions of this view (0..1), or null when empty,
     * so the editor can anchor its context menu near the selection. Reported
     * when the selection settles and when the camera moves under it — never
     * mid-gesture, when null hides the menu until release.
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
    /** The native field draws this box while typing; keep its stored words out of the ink layer. */
    internal var editingText: TextBox? = null
        set(value) {
            if (field == value) return
            if (field?.id != value?.id) paperLayerSource = null
            field = value
            invalidate()
        }
    internal var onTextEditorFrame: (InlineTextFrame?) -> Unit = {}
    internal var onTextEditingExit: () -> Unit = {}
    private var pendingTextFrame: InlineTextFrame? = null
    private var reportedTextFrame: InlineTextFrame? = null
    private val reportTextFrame = Runnable {
        if (reportedTextFrame != pendingTextFrame) {
            reportedTextFrame = pendingTextFrame
            onTextEditorFrame(pendingTextFrame)
        }
    }
    private fun followTextEditor() {
        pendingTextFrame = editingText?.let { box ->
            InlineTextFrame(box.id, originX + box.x * scale, originY + box.y * scale,
                box.width * scale, InkRenderer.textHeight(box) * scale, scale)
        }
        removeCallbacks(reportTextFrame)
        if (pendingTextFrame != reportedTextFrame) post(reportTextFrame)
    }
    /** Only an active typing session may move the camera to keep its caret above the keyboard. */
    internal fun revealTextCaret(box: TextBox, caret: Rect, topInset: Float) {
        if ((!page.infinite && !cameraPage) || editingText?.id != box.id || height <= 0) return
        val margin = 12f * resources.displayMetrics.density
        val top = originY + box.y * scale + caret.top
        val bottom = originY + box.y * scale + caret.bottom
        val left = originX + box.x * scale + caret.left
        val right = originX + box.x * scale + caret.right
        // A line larger than the remaining viewport aligns to the bottom without oscillating.
        val upper = (topInset + margin).coerceAtMost(height - margin - caret.height)
        val dx = when { right > width - margin -> width - margin - right; left < margin -> margin - left; else -> 0f }
        val dy = when { bottom > height - margin -> height - margin - bottom; top < upper -> upper - top; else -> 0f }
        if (abs(dx) >= 1f || abs(dy) >= 1f) {
            suspendWritingFollow()
            camera.pan(dx, dy); reportCanvasViewport(); invalidate()
        }
    }
    /** The focused sticky note, its frame in view fractions (0..1), and whether it is being typed in. */
    var onStickyFocus: (TextBox?, Rect?, Boolean) -> Unit = { _, _, _ -> }
    fun typeInSticky() { stickyNotes.focused()?.let { stickyNotes.focus(it, type = true) } }
    fun finishStickyTyping() { stickyNotes.focused()?.let { stickyNotes.focus(it) } }
    fun setStickyText(id: String, text: String) = stickyNotes.setText(id, text)
    fun deleteSticky(id: String) = stickyNotes.delete(id)
    private var stickyFrameReported: Rect? = null
    private val stickyNotes = StickyNoteInput(this, { page }, { texts ->
        page = page.copy(texts = texts)
        onTextsChanged(texts)
        invalidate()
    }, { box -> RectF(originX + box.x * scale, originY + box.y * scale,
        originX + (box.x + box.width) * scale, originY + (box.y + box.stickyHeight) * scale) },
        { event -> List(event.historySize) { point(event, 0, it) } + point(event, 0) },
        { box, typing ->
            stickyFrameReported = box?.let(::stickyFraction)
            onStickyFocus(box, stickyFrameReported, typing)
        })
    private fun stickyFraction(box: TextBox): Rect {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        return Rect((originX + box.x * scale) / w, (originY + box.y * scale) / h,
            (originX + (box.x + box.width) * scale) / w, (originY + (box.y + box.stickyHeight) * scale) / h)
    }
    /** Keeps the menu and typing field on the note while an infinite canvas pans or the view resizes. */
    private fun followStickyFrame() {
        val box = stickyNotes.focused() ?: return
        val frame = stickyFraction(box)
        if (frame != stickyFrameReported) post { stickyNotes.refresh() }
    }
    /** Placed pictures decoded for drawing, keyed by image id. Missing entries simply do not draw. */
    var imageBitmaps: Map<String, Bitmap> = emptyMap()
    /** The picture showing resize handles, or null when none is selected. */
    var selectedImageId: String? = null
        set(value) {
            if (field == value) return
            field = value
            // The editor can select a picture itself (a fresh insert); re-announce the frame so its menu appears.
            post { lastReportedSelectionBounds = null; reportSelectionViewBounds() }
        }
    var onImagesChanged: (List<PageImage>) -> Unit = {}
    /** A tap on a picture with the hand tool, so the editor can offer delete and layering. */
    var onImageSelected: (PageImage?) -> Unit = {}
    /** Tappable links of the shown PDF page, in Folio page coordinates. */
    var pdfLinks: List<PdfLink> = emptyList()
    /** A tap on a PDF link with the hand tool, so the editor can open or follow it. */
    var onPdfLink: (PdfLink) -> Unit = {}
    /**
     * Printed "[4 marks]" labels of the shown PDF page. A stylus hovering over one, or a finger tapping it,
     * offers the editor a tick/cross chip; nothing is drawn here and a stylus touch still writes normally.
     */
    var markZones: List<MarkZone> = emptyList()
    /** The zone being offered (null when none) with its rectangle in this view's pixels. */
    var onMarkZone: (MarkZone?, android.graphics.RectF?) -> Unit = { _, _ -> }
    /** Drag a rectangle around a printed allocation the scan missed: reported in page units and as view pixels. */
    var onMarkRegion: (WritingLane?, android.graphics.RectF?) -> Unit = { _, _ -> }
    private val markRegionMode get() = tool == Tool.MARK_AREA && !readOnly && page.pdfIndex != null
    private var markRegionStart: InkPoint? = null
    private var markRegionDraft: WritingLane? = null
    private var markRegionShown: WritingLane? = null
    private var pendingZone: MarkZone? = null
    private var zoneFromX = 0f
    private var zoneFromY = 0f
    private var zoneOffered = false
    private var lastZoneOfferAt = 0L
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
    /**
     * Called when a selection drag ends off this page, with the pointer in window pixels. Returns true when
     * the editor moved the selection onto the page under it, so this view only drops its own ghost.
     */
    var onSelectionDrop: (CanvasSelection, Float, Float) -> Boolean = { _, _, _ -> false }
    private var dragWindowX = 0f; private var dragWindowY = 0f; private var dragOffPage = false
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
    private var draftHighlighter: InkRenderer.IncrementalHighlighterStroke? = null
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
    /** Corner being dragged (0 TL, 1 TR, 2 BR, 3 BL) and the opposite corner, which stays fixed. */
    private var imageCorner = 2
    private var imageAnchorX = 0f; private var imageAnchorY = 0f
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
    private val selectionHandleEdgePaint = Paint(imageHandleEdgePaint)
    private val selectionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val selectionLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2F6FBA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val selectionGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // Reused across onDraw frames so selection previews and writing lanes allocate nothing per frame.
    private val selectionPreviewMatrix = Matrix()
    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val predicted = FloatArray(2)
    private val writingRegionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    /** The answer-area box takes the app's accent, so it reads as part of the theme rather than as ink. */
    var answerAreaColor = 0xFF387C83.toInt()
        set(value) { if (field != value) { field = value; invalidate() } }
    /** False hides the box; an area being dragged out is always shown, so selecting one still works. */
    var showAnswerAreas = true
        set(value) { if (field != value) { field = value; invalidate() } }
    val writingFollow = WritingFollow()
    private var guideRegions: List<WritingLane> = emptyList()
    var writingGuides: List<WritingGuide> = emptyList()
        set(value) {
            if (field != value) { field = value; guideRegions = WritingGuides.regions(value) }
        }
    private var lineAdvance: WritingAdvance? = null
    var followPreferences = FollowPreferences()
    var writingRegion: WritingLane? = null
        set(value) { if (field != value) { suspendWritingFollow(); field = value; invalidate() } }
    var writingRegions: List<WritingLane> = emptyList()
    var onWritingRegions: (List<WritingLane>) -> Unit = {}
    var onWritingRegion: (WritingLane?) -> Unit = {}
    private var followMessage = "Write to start following"
    var onFollowStatus: (WritingFollowStatus) -> Unit = {}
        set(value) {
            field = value
            value(WritingFollowStatus(followMessage, followPaused || followManuallyPaused, followBack.entry != null))
        }
    /** [attention] marks a message the writer needs to see now, because the view is not doing what they expect. */
    private fun reportFollowStatus(message: String, attention: Boolean = false) {
        followMessage = message
        onFollowStatus(WritingFollowStatus(message, followPaused || followManuallyPaused, followBack.entry != null, attention))
    }
    /**
     * Where the next line will begin, drawn while a return is pending and after it arrives until
     * the first accepted stroke, so blank paper and canvases show where to write.
     */
    private var landingGuide: WritingGuide? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    /** System animations off (accessibility "Remove animations") means follow moves jump, not glide. */
    private val reducedMotion get() = !android.animation.ValueAnimator.areAnimatorsEnabled()
    private var selectingWritingRegion = false
    private var regionStart: InkPoint? = null
    private var regionDraft: WritingLane? = null
    private var followLastPoint: InkPoint? = null
    private var followPaused = false
    private var followManuallyPaused = false
    val isWritingFollowManuallyPaused get() = followManuallyPaused
    private var returnStartAt = 0L
    private var pendingReturn: WritingAdvance? = null
    private var resumeFollowAfterMark = false
    private val followBack = FollowBackHistory()
    /** Forgets the rectangle drawn with the mark-area tool, whether mid-drag or already handed to the editor. */
    fun clearMarkRegion() {
        markRegionShown = null; markRegionDraft = null; markRegionStart = null; invalidate()
    }
    fun selectWritingRegion() {
        suspendWritingFollow(); selectingWritingRegion = true
        reportFollowStatus("Drag an answer area with your pen")
    }
    fun clearWritingRegion() {
        writingRegions = emptyList(); onWritingRegions(writingRegions)
        writingRegion = null; onWritingRegion(null); invalidate()
    }
    fun suggestWritingRegion() {
        val regions = WritingGuides.regions(writingGuides)
        if (regions.isEmpty()) return selectWritingRegion()
        writingRegions = regions; onWritingRegions(regions)
        val anchor = currentPeekAnchor()
        val x = followLastPoint?.x ?: anchor?.let { (it.left + it.right) / 2 } ?: page.width / 2
        val y = followLastPoint?.y ?: anchor?.let { (it.top + it.bottom) / 2 } ?: 70f
        writingRegion = WritingGuides.regionAt(regions, x, y) ?: regions.minByOrNull {
            val dx = x - x.coerceIn(it.left, it.right)
            val dy = y - y.coerceIn(it.top, it.bottom)
            dx * dx + dy * dy
        }
        onWritingRegion(writingRegion)
        reportFollowStatus("${regions.size} answer ${if (regions.size == 1) "area" else "areas"} detected"); invalidate()
    }
    var canvasWritingSession: CanvasWritingSession? = null
        set(value) {
            if (field != value) { suspendWritingFollow(); field = value; invalidate() }
        }
    private val canFollow get() = followEnabled && (!page.infinite || canvasWritingSession != null)
    private val responseStart get() = if (page.infinite) canvasWritingSession?.startX else null
    private fun automaticFollowReturn(region: WritingLane): WritingAdvance? = writingFollow.returnFor(
        region, writingGuides, followPreferences, responseStart)
    /**
     * A canvas has no edge to stop a pan, so a screen-position target would put the response's
     * margin mid-screen. In a response, a proposed sideways pan [dx] frames the column instead.
     * Needs [followVisible] measured.
     */
    private fun responseDx(dx: Float): Float {
        val session = canvasWritingSession?.takeIf { page.infinite } ?: return dx
        val viewLeft = (followVisible.left - originX) / scale
        val viewWidth = followVisible.width() / scale
        return (viewLeft - session.framedLeft(viewLeft, viewWidth, viewLeft - dx / scale)) * scale
    }

    private fun followRegion(points: List<InkPoint>? = null): WritingLane {
        if (page.infinite) canvasWritingSession?.let { return it.column }
        writingRegion?.let { return it }
        val point = followLastPoint
        val box = points?.let(FollowNavigation::bounds)
        val guide = if (box != null) writingFollow.guideFor(box, writingGuides, followPreferences)
            else point?.let { p ->
                val y = writingFollow.state.baselineY ?: p.y
                writingGuides.filter { p.x in it.left..it.right && kotlin.math.abs(it.y - y) <= 16f }
                    .minByOrNull { kotlin.math.abs(it.y - y) }
            }
        if (guide != null) {
            WritingGuides.regionAt(guideRegions, (guide.left + guide.right) * .5f, guide.y)?.let { return it }
            var end: WritingGuide = guide
            while (true) { end = WritingGuides.next(end, writingGuides) ?: break }
            return WritingLane(guide.left, guide.y - 32f, guide.right, end.y)
        }
        if (page.infinite) {
            // An unbounded canvas has no printed line end, so the lane is the visible viewport:
            // the line ends where the writer can see it end, and returns go back to the column
            // they started from. Manual returns still use the actual writing start, including
            // handwriting to the left or above the origin.
            val anchor = currentPeekAnchor()
            val startX = writingFollow.state.lineStartX ?: box?.let {
                if (followPreferences.direction == WritingDirection.LTR) it.left else it.right
            } ?: point?.x
            if (anchor != null) return FollowNavigation.infiniteRegion(
                WritingLane(anchor.left, anchor.top, anchor.right, anchor.bottom),
                followPreferences.direction, startX, followPreferences.endMargin)
            val start = startX ?: 0f
            val left = if (followPreferences.direction == WritingDirection.LTR) start else start - page.width
            return WritingLane(left, -Float.MAX_VALUE, left + page.width, Float.MAX_VALUE)
        }
        return WritingLane(36f, 0f, page.width - 36f, page.height - 24f)
    }
    private fun nextFollowLine(baseline: Float, region: WritingLane): WritingAdvance? = FollowNavigation.next(
        baseline, region, writingGuides, writingFollow.lineSpacing(followPreferences.spacing,
            followPreferences.adaptiveSpacing && followPreferences.mode == FollowMode.TEXT),
        responseStart ?: writingFollow.state.lineStartX, followPreferences.direction)

    fun nextWritingLine() {
        if (!canFollow || isWritingGesture || inputBlocked || readOnly || lineAdvance != null) return
        val region = followRegion()
        val baseline = writingFollow.state.baselineY ?: followLastPoint?.y
            ?: ((if (page.infinite) currentPeekAnchor()?.top ?: 0f else region.top) + followPreferences.spacing)
        val next = nextFollowLine(baseline, region)
        if (next == null) { reportFollowStatus("End of answer area", attention = true); return }
        followPaused = false; writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
        pendingReturn = null
        startLineAdvance(next)
    }
    /** Goes up one line, to correct or add to it; the opposite of [nextWritingLine]. */
    fun previousWritingLine() {
        if (!canFollow || isWritingGesture || inputBlocked || readOnly || lineAdvance != null) return
        val region = followRegion()
        val baseline = writingFollow.state.baselineY ?: followLastPoint?.y
            ?: run { reportFollowStatus("Write a line first", attention = true); return }
        val previous = FollowNavigation.previous(baseline, region, writingGuides, writingFollow.lineSpacing(followPreferences.spacing,
            followPreferences.adaptiveSpacing && followPreferences.mode == FollowMode.TEXT),
            responseStart ?: writingFollow.state.lineStartX, followPreferences.direction)
            ?: run { reportFollowStatus("Already on the first line", attention = true); return }
        followPaused = false; writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
        pendingReturn = null
        startLineAdvance(previous)
    }
    /** Reverses follow moves one at a time, newest first; navigation clears the history. */
    fun backWritingView() {
        if (isWritingGesture || inputBlocked || readOnly) return
        val back = followBack.pop() ?: run { reportFollowStatus("No previous follow movement"); return }
        val dx = -back.x; val dy = -back.y
        // Keep the older moves: a suspend would clear them, so stop motion without it.
        followPaused = true
        cancelFollowMotion()
        landingGuide = null
        if (page.infinite) { camera.pan(dx, dy); reportCanvasViewport(); invalidate() } else onFollowPan(dx, dy)
        writingFollow.state = back.state.copy(suspendedUntil = 0)
        val more = if (followBack.depth > 0) " · Back again for ${followBack.depth} more" else ""
        reportFollowStatus((if (followManuallyPaused) "View restored · paused" else "View restored · write to resume") + more)
    }
    var followEnabled = false
        set(value) {
            if (field && !value) suspendWritingFollow()
            field = value
        }
    var writingHand = WritingHand.RIGHT
    var documentFollowZoom = 1f
    var onFollowPan: (Float, Float) -> Pair<Float, Float> = { _, _ -> 0f to 0f }
    private val followVisible = android.graphics.Rect()
    private val followGlide = FollowGlide()
    private var captureFollowBack = false
    private fun cancelFollowMotion() {
        pendingReturn = null; followBack.cancelPending()
        followGlide.cancel()
        lineAdvance = null; captureFollowBack = false; resumeFollowAfterMark = false
        removeCallbacks(followFrame)
    }
    fun pauseWritingFollow() {
        followManuallyPaused = true
        cancelFollowMotion()
        reportFollowStatus("Paused · tap Resume when ready")
    }
    fun resumeWritingFollow() {
        followManuallyPaused = false; followPaused = false
        writingFollow.state = writingFollow.state.copy(suspendedUntil = 0, candidateLane = null, candidateAt = null)
        reportFollowStatus("Write to start following")
    }
    fun suspendWritingFollow(clearBack: Boolean = true) {
        followPaused = true
        cancelFollowMotion()
        if (clearBack) followBack.clear()
        landingGuide = null
        writingFollow.suspend(SystemClock.uptimeMillis())
        if (page.infinite) followLastPoint = null
        reportFollowStatus(if (followManuallyPaused) "Paused · tap Resume when ready" else "Follow paused · write to resume")
    }
    var inputBlocked = false
    val isWritingGesture get() = draft != null || erasing != null || lasso != null
    /** The peek view this read-only page frames; only a different anchor re-frames it, so panning a kept-open peek sticks. */
    var peekRegion: PeekAnchor? = null
        set(value) { if (field != value) { field = value; value?.let(::fitPeekAnchor) } }
    /** The part of this page currently on screen, as a peek view. */
    fun currentPeekAnchor(): PeekAnchor? {
        if (!getLocalVisibleRect(followVisible)) return null
        // A page scrolled down to a sliver would pin an unreadable strip; the caller pins the page instead.
        if (!page.infinite && followVisible.height() < resources.displayMetrics.density * 96) return null
        return PeekAnchor(page.id, (followVisible.left - originX) / scale, (followVisible.top - originY) / scale,
            (followVisible.right - originX) / scale, (followVisible.bottom - originY) / scale).fittedTo(page)
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
            // Frame-aligned time, so jitter in when this callback runs does not show up as judder.
            val now = AnimationUtils.currentAnimationTimeMillis()
            if (!canFollow || inputBlocked || readOnly || !getLocalVisibleRect(followVisible)) {
                cancelFollowMotion()
                return
            }
            if (isWritingGesture || selectingWritingRegion || followPaused || (followManuallyPaused && lineAdvance == null)) return
            pendingReturn?.let { pending ->
                val remaining = returnStartAt - now
                if (remaining > 0) { postDelayed(this, remaining); return }
                pendingReturn = null
                startLineAdvance(pending)
                return
            }
            if (!followGlide.active) return
            val step = followGlide.step(now)
            if (step.waitMs > 0) { postDelayed(this, step.waitMs); return }
            if (step.dx == 0f && step.dy == 0f) {
                if (step.finished) finishFollowMotion() else postOnAnimation(this)
                return
            }
            if (captureFollowBack) {
                followBack.begin(writingFollow.state)
                captureFollowBack = false
            }
            val wasMoving = followGlide.moved
            val applied = if (page.infinite) {
                camera.pan(step.dx, step.dy); reportCanvasViewport(); invalidate(); step.dx to step.dy
            } else onFollowPan(step.dx, step.dy)
            followGlide.applied(applied.first, applied.second)
            followBack.moved(applied.first, applied.second)
            if (!wasMoving && followGlide.moved)
                reportFollowStatus(if (lineAdvance != null) "Moving to next line · touch down to stop" else "Following · touch down to stop")
            // A viewport clamp must not count as completing the vertical line return.
            val blocked = (kotlin.math.abs(step.dx) >= .5f || kotlin.math.abs(step.dy) >= .5f) &&
                applied.first == 0f && applied.second == 0f
            if (step.finished || blocked) finishFollowMotion() else postOnAnimation(this)
        }
    }
    private fun finishFollowMotion() {
        val advance = lineAdvance
        val placing = advance == null && writingFollow.state.needsPlacement
        val arrived = advance != null && followGlide.reachedLine
        if (arrived) { writingFollow.arrived(advance!!); landingGuide = advance.to }
        if (advance == null) writingFollow.placed()
        val moved = followGlide.moved
        cancelFollowMotion()
        val blocked = !followManuallyPaused && !arrived && (!moved || advance != null)
        reportFollowStatus(when {
            followManuallyPaused -> "Paused · tap Resume when ready"
            arrived -> "Next line · Back restores the view"
            !moved || advance != null -> "View edge · pan or zoom to continue"
            else -> "Following · Back restores the view"
        }, attention = blocked)
        // A whole cursive line may already have reached its end while its placement was
        // interrupted. Once placed, it can return after a fresh pause without another mark.
        if (placing && moved && !followManuallyPaused && followPreferences.automaticReturn) {
            automaticFollowReturn(followRegion())?.let(::queueFollowReturn)
        }
    }
    private fun queueFollowReturn(advance: WritingAdvance) {
        val delay = followPreferences.automaticReturnDelayMs
        pendingReturn = advance; returnStartAt = SystemClock.uptimeMillis() + delay
        landingGuide = advance.to
        reportFollowStatus("Next line in ${FollowPreferences.returnDelayLabel(delay)} · touch down to cancel")
        scheduleFollow()
    }
    private fun scheduleFollow() {
        removeCallbacks(followFrame)
        postOnAnimation(followFrame)
    }
    /** Aim at an absolute writing position, so an interrupted return cannot double its step. */
    private fun startLineAdvance(advance: WritingAdvance) {
        if (!getLocalVisibleRect(followVisible)) return
        followBack.begin(writingFollow.state)
        val target = followPreferences.horizontalPosition + if (writingHand == WritingHand.RIGHT) -.02f else .02f
        val desiredX = followVisible.left + followVisible.width() * target
        val desiredY = followVisible.top + followVisible.height() * followPreferences.position
        val dx = if (followPreferences.mode == FollowMode.MATH) 0f else responseDx(desiredX -
            (originX + advance.startX(if (followPreferences.direction == WritingDirection.LTR) WritingHand.RIGHT else WritingHand.LEFT) * scale))
        val dy = desiredY - (originY + advance.to.y * scale)
        followGlide.start(dx, dy, SystemClock.uptimeMillis(), 0, followPreferences.glideDurationMs,
            followVisible.width().toFloat(), followVisible.height().toFloat(), followPreferences.lineSpeedMs.toFloat(),
            instant = reducedMotion)
        landingGuide = advance.to
        lineAdvance = advance
        captureFollowBack = false
        scheduleFollow()
    }
    var readOnly = false
    /**
     * An editable finite page that carries its own fit-to-view camera, so it can be pinched and
     * panned like a reference page while still being written on. A score on the music stand is one.
     */
    var pageCamera = false
    private val cameraPage get() = readOnly || pageCamera
    /** Page-turn keys and pedals, for a score: a key this view does not use is offered here first. */
    var onPageKey: ((android.view.KeyEvent) -> Boolean)? = null
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        onPageKey?.invoke(event) ?: false || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        onPageKey?.invoke(event) ?: false || super.onKeyUp(keyCode, event)
    var onWorkspaceCamera: (WorkspaceViewport) -> Unit = {}
    private var workspaceCameraRestored = false
    fun restoreWorkspaceCamera(viewport: WorkspaceViewport?) {
        if (workspaceCameraRestored) return
        workspaceCameraRestored = true
        // Re-opening on a saved canvas position is navigation, not writing; do not let the
        // restored viewport yank the view again the moment a stroke lands.
        if (page.infinite && viewport != null) suspendWritingFollow(clearBack = false)
        if (viewport != null) camera.restore(viewport.canvasX, viewport.canvasY, viewport.canvasZoom)
    }
    private val camera = InfiniteViewport()
    var onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {}
    /** Keeps a zoomed score on its sheet: never smaller than the fit, never panned past an edge. */
    private fun clampPageCamera() {
        if (!pageCamera || page.infinite || width <= 0 || height <= 0 || page.width <= 0f || page.height <= 0f) return
        val zoom = camera.zoom.coerceIn(1f, 4f)
        val fitted = pageScale
        val pageW = page.width * fitted * zoom
        val pageH = page.height * fitted * zoom
        val baseX = (width - page.width * fitted) / 2 * zoom
        val baseY = (height - page.height * fitted) / 2 * zoom
        fun bounded(offset: Float, base: Float, size: Float, view: Float): Float {
            val left = base + offset
            val clamped = if (size <= view) left.coerceIn(0f, view - size) else left.coerceIn(view - size, 0f)
            return clamped - base
        }
        val x = bounded(camera.x, baseX, pageW, width.toFloat())
        val y = bounded(camera.y, baseY, pageH, height.toFloat())
        if (zoom != camera.zoom || x != camera.x || y != camera.y) camera.restore(x, y, zoom)
    }
    private fun reportCanvasViewport() {
        clampPageCamera()
        if ((page.infinite || cameraPage) && workspaceCameraRestored && width > 0 && height > 0) {
            onWorkspaceCamera(WorkspaceViewport(canvasX = camera.x, canvasY = camera.y, canvasZoom = camera.zoom))
            onCanvasZoom(camera.zoom)
            onCanvasViewport(androidx.compose.ui.geometry.Rect(-camera.x / camera.zoom, -camera.y / camera.zoom,
                (width - camera.x) / camera.zoom, (height - camera.y) / camera.zoom))
        }
        // A camera move under a live selection re-anchors the editor's menu,
        // but never mid-gesture: the release reports the settled frame.
        if ((hasSelection() || selectedImageId != null) && !movingSelection && !resizingSelection && !rotatingSelection && lasso == null) {
            reportSelectionViewBounds()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prewarmInk()
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) suspendWritingFollow(clearBack = false)
        peekRegion?.let(::fitPeekAnchor)
        reportCanvasViewport()
    }
    fun canvasView(): WorkspaceViewport = WorkspaceViewport(
        canvasX = camera.x, canvasY = camera.y, canvasZoom = camera.zoom)

    fun returnToCanvasView(viewport: WorkspaceViewport) {
        if (!page.infinite || isWritingGesture) return
        suspendWritingFollow()
        camera.restore(viewport.canvasX, viewport.canvasY, viewport.canvasZoom)
        reportCanvasViewport(); invalidate()
    }

    fun fitCanvas(bounds: androidx.compose.ui.geometry.Rect) {
        if (!page.infinite) return
        suspendWritingFollow()
        cancelGesture()
        camera.fit(bounds.left, bounds.top, bounds.right, bounds.bottom, width.toFloat(), height.toFloat())
        reportCanvasViewport(); invalidate()
    }
    var onCanvasZoom: (Float) -> Unit = {}
    /**
     * Reference-pane zoom. The page already fits the view, so a notch scales the read-only camera
     * about the middle of the pane; the pinch gesture keeps working on the same range.
     */
    fun zoomReference(steps: Int) {
        if (page.infinite || width <= 0 || height <= 0) return
        suspendWritingFollow(); cancelGesture()
        val target = PdfReference.zoomStep(camera.zoom, steps)
        if (target != camera.zoom) camera.scaleBy(target / camera.zoom, width / 2f, height / 2f)
        reportCanvasViewport(); invalidate()
    }
    /** Re-frames a read-only page: the whole page, the full page width, or true size. */
    fun fitReference(fit: PdfFit) {
        if (page.infinite || page.width <= 0f || page.height <= 0f || width <= 0 || height <= 0) return
        suspendWritingFollow(); cancelGesture()
        val zoom = PdfReference.fitZoom(page.width, page.height, width.toFloat(), height.toFloat(), fit) / pageScale
        camera.restore(0f, 0f, PdfReference.clampZoom(zoom))
        reportCanvasViewport(); invalidate()
    }
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
                reportNavigating(true)
                camera.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                reportCanvasViewport(); invalidate(); return true
            }
        }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
    private val scale get() = if (page.infinite) camera.zoom else pageScale * (if (cameraPage) camera.zoom else 1f)
    /**
     * A finite page in the document is drawn at this paper width, centred, inside a view that is
     * wider (the workspace beside the paper) and starts [documentTop] page units above it. 0 fits
     * the paper to the view, as reference and peek views do.
     */
    var documentPaperWidth = 0f
    var documentTop = 0f
    private val pageScale get() = if (!readOnly && !page.infinite && documentPaperWidth > 0f)
        (documentPaperWidth / page.width).coerceAtLeast(.01f)
        else min(width / page.width, height / page.height).coerceAtLeast(.01f)
    private val originX get() = if (page.infinite) camera.x else if (cameraPage) (width - page.width * pageScale) / 2 * camera.zoom + camera.x else (width - page.width * scale) / 2
    private val originY get() = if (page.infinite) camera.y else if (cameraPage) (height - page.height * pageScale) / 2 * camera.zoom + camera.y else if (documentPaperWidth > 0f) -documentTop * scale else (height - page.height * scale) / 2
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
            lastStylusAt = if (leaving) -palmRejectMs else SystemClock.uptimeMillis()
            // Hovering with the eraser previews the same outline it will cut with, before the tip lands.
            if (!leaving && !readOnly && markZones.isNotEmpty()) {
                val at = point(event, 0)
                val zone = markZones.lastOrNull { it.contains(at.x, at.y) }
                val now = SystemClock.uptimeMillis()
                // Re-offered while hovering so the chip's timeout restarts, but never faster than a glance.
                if (zone != null && now - lastZoneOfferAt > 150L) offerZone(zone)
            }
            if (leaving) { if (eraserMark != null) { eraserMark = null; invalidate() } }
            else if (tool == Tool.ERASER) {
                val next = clampToPage(point(event, 0))
                val last = eraserMark
                if (last == null || hypot(next.x - last.x, next.y - last.y) > .75f) { eraserMark = next; invalidate() }
            }
        }
        return super.onGenericMotionEvent(event)
    }
    private fun offerZone(zone: MarkZone) {
        lastZoneOfferAt = SystemClock.uptimeMillis()
        zoneOffered = true
        val s = scale
        onMarkZone(zone, android.graphics.RectF(originX + zone.x * s, originY + zone.y * s,
            originX + (zone.x + zone.width) * s, originY + (zone.y + zone.height) * s))
    }
    private fun dismissZone() {
        pendingZone = null
        if (zoneOffered) { zoneOffered = false; onMarkZone(null, null) }
    }
    /** A finger pressing a printed allocation arms it as a tap, like a PDF link; dragging pans as usual. */
    private fun beginZone(event: MotionEvent, index: Int): Boolean {
        if (markZones.isEmpty() || tool == Tool.TEXT || tool == Tool.LASSO || tool == Tool.ERASER) return false
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) return false
        val at = clampToPage(raw)
        val hit = markZones.lastOrNull { it.contains(at.x, at.y) } ?: return false
        pendingZone = hit
        zoneFromX = at.x; zoneFromY = at.y
        return true
    }
    fun bind(value: NotePage, bitmap: Bitmap?, images: Map<String, Bitmap> = emptyMap()) {
        if (page.id != value.id || page.infinite != value.infinite) {
            stickyNotes.reset()
            followPaused = false; followLastPoint = null
            followBack.clear(); writingFollow.state = WritingFollowState(); cancelFollowMotion()
            reportFollowStatus(if (followManuallyPaused) "Paused · tap Resume when ready" else "Write to start following")
            cancelGesture(); camera.reset(); resetToken = -1; workspaceCameraRestored = false
            clearInkLayers(); renderCache.clear(); boundsCache.clear(); restCache = null; restCacheKeyPage = null; resetDraftGeometry()
        }
        else if (page.strokes !== value.strokes && value.strokes.size <= page.strokes.size) {
            if (value.strokes.size < page.strokes.size) followRetracted(value.strokes)
            // Ordinary pen commits only append. Growing replacements are also safe because
            // both geometry caches are identity keyed and bounded. Prune on shrink/equal-size
            // replacements so undo generations do not remain resident.
            val keep = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>())
            keep.addAll(value.strokes); keep.addAll(selection)
            renderCache.retainAll(keep)
            boundsCache.retainAll(keep)
        }
        page = value; background = bitmap; imageBitmaps = images
        if (stickyNotes.focusedId != null) post { stickyNotes.refresh() }
        // Content deleted from outside the view stops being selected, per list so one removed
        // stroke does not drop a still-present text box from the selection. Identity only:
        // the ViewModel reuses untouched stroke instances, so a deep value walk over every
        // InkPoint per bind is what stalled dense pages with a live selection. A reload from
        // disk brings new instances and simply clears the stroke selection.
        val keptStrokes = if (selection.isEmpty()) selection else {
            val valueStrokeIds =
                java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(value.strokes) }
            selection.filter { it in valueStrokeIds }
        }
        val keptTexts = if (selectedTexts.isEmpty()) selectedTexts else {
            val ids = HashSet<String>(value.texts.size * 2 + 1).apply { value.texts.forEach { add(it.id) } }
            selectedTexts.filter { it.id in ids }
        }
        val keptImages = if (selectedImages.isEmpty()) selectedImages else {
            val ids = HashSet<String>(value.images.size * 2 + 1).apply { value.images.forEach { add(it.id) } }
            selectedImages.filter { it.id in ids }
        }
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
        prewarmInk()
        invalidate()
    }
    // A dense page's raster is built on a worker; its arrival just needs the view drawn again.
    private val committedInk = CommittedInkCache(onRasterReady = { invalidate() })
    private val navigationInk = CommittedInkCache(maxPixels = NAVIGATION_INK_MAX_PIXELS)
    private val zoomRenderState = ZoomRenderState()
    private val navigationBounds = android.graphics.Rect()
    private val requiredInkBounds = android.graphics.Rect()
    private val stableInkBounds = android.graphics.Rect()
    private val refreshInkDetail = Runnable { invalidate() }

    private fun clearInkLayers() {
        removeCallbacks(refreshInkDetail)
        zoomRenderState.reset()
        // Kept for the next visit rather than dropped: the page may be scrolled straight back to.
        committedInk.release()
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
    private fun resetDraftGeometry() { draftPenStroke = null; draftPenPoints = null; draftHighlighter = null }
    private fun draftGeometry(stroke: Stroke): InkRenderer.RenderedStroke {
        val points = stroke.points
        if (draftPenPoints !== points) {
            draftPenPoints = points
            draftPenStroke = InkRenderer.IncrementalPenStroke()
            draftHighlighter = InkRenderer.IncrementalHighlighterStroke()
        }
        return if (stroke.tool == Tool.HIGHLIGHTER) draftHighlighter!!.update(points)
            else draftPenStroke!!.update(points)
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

    /** What is drawn: the page minus hidden layers, in layer order. Memoized so retained rasters keep hitting. */
    private var layerViewSource: NotePage? = null
    private var layerViewValue: NotePage? = null
    private var paperLayerSource: NotePage? = null
    private var paperLayerValue: NotePage? = null
    private fun layerView(): NotePage {
        if (layerViewSource !== page) { layerViewValue = PageLayers.view(page); layerViewSource = page }
        return layerViewValue!!
    }

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
                else {
                    // Invalidating just the moving tip changes Canvas's damage clip each frame.
                    // Rasterize the stable visible viewport instead, so committed ink remains a
                    // single bitmap draw throughout the gesture even on a dense page.
                    updateStableInkBounds(content)
                    committedInk.draw(inkCanvas, content.strokes, scale, ::boundsOf, ::renderedOf,
                        rasterViewport = stableInkBounds, deferred = !content.infinite)
                }
            })
    }

    private fun updateStableInkBounds(content: NotePage) {
        val left = floor(-originX / scale).toInt()
        val top = floor(-originY / scale).toInt()
        val right = ceil((width - originX) / scale).toInt()
        val bottom = ceil((height - originY) / scale).toInt()
        if (content.infinite) stableInkBounds.set(left, top, right, bottom)
        else stableInkBounds.set(left.coerceAtLeast(0), top.coerceAtLeast(0),
            right.coerceAtMost(ceil(content.width).toInt()),
            bottom.coerceAtMost(ceil(content.height).toInt()))
    }

    /** Starts a finite page's ink raster as soon as the view has a size, ahead of the first frame that needs it. */
    private fun prewarmInk() {
        val content = layerView()
        if (content.infinite || width <= 0 || height <= 0) return
        updateStableInkBounds(content)
        committedInk.prewarm(content.strokes, stableInkBounds, scale)
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
        // Resolution follows an area budget rather than a longest-side cap, so a viewport-sized
        // preview stays close to screen sharpness and only an overscanned or huge area softens.
        val area = (navigationBounds.width().toDouble() * navigationBounds.height()).coerceAtLeast(1.0)
        val previewScale = min(scale, kotlin.math.sqrt(NAVIGATION_INK_PIXEL_BUDGET / area).toFloat())
        // Preview trades taper detail for one draw per stroke so a dense page pans at rate;
        // the settled frame below repaints full detail after 90ms without motion.
        navigationInk.draw(canvas, content.strokes, previewScale, ::boundsOf, ::renderedOf,
            rasterViewport = navigationBounds, fastPreview = true)
    }

    override fun onDetachedFromWindow() {
        stickyNotes.reset()
        removeCallbacks(followFrame)
        removeCallbacks(reportMeasurement)
        removeCallbacks(reportTextFrame)
        removeCallbacks(longPressRunnable)
        clearInkLayers(); renderCache.clear(); boundsCache.clear(); restCache = null; restCacheKeyPage = null
        resetDraftGeometry()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        followTextEditor()
        reportShapeMeasurement()
        canvas.drawColor(canvasBackgroundColor)
        canvas.save(); canvas.translate(originX, originY); canvas.scale(scale, scale)
        if (!page.infinite) canvas.drawRect(-1f, -1f, page.width + 2f, page.height + 3f, shadowPaint)
        canvas.save()
        if (!page.infinite) canvas.clipRect(0f, 0f, page.width, page.height)
        val layered = layerView()
        if (paperLayerSource !== layered) {
            paperLayerSource = layered
            // Sticky notes may cross the paper edge, so they draw after the page clip below.
            paperLayerValue = if (layered.texts.any { it.isSticky || it.id == editingText?.id })
                layered.copy(texts = layered.texts.filterNot { it.isSticky || it.id == editingText?.id }) else layered
        }
        val shown = paperLayerValue!!
        val visible = if (erasing != null) shown.copy(strokes = PageLayers.viewStrokes(erasing!!, page.layers)) else shown
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
            val previewBox = selectionBox()
            val pivotX = if (previewBox != null) (previewBox[0] + previewBox[2]) / 2f else handleCenter.x
            val pivotY = if (previewBox != null) (previewBox[1] + previewBox[3]) / 2f else handleCenter.y
            selectionPreviewMatrix.reset()
            selectionPreviewMatrix.postTranslate(-pivotX, -pivotY)
            selectionPreviewMatrix.postScale(selectionPreviewScale, selectionPreviewScale)
            selectionPreviewMatrix.postRotate(selectionPreviewDeg)
            selectionPreviewMatrix.postTranslate(pivotX, pivotY)
            canvas.concat(selectionPreviewMatrix)
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
            selectedTexts.filterNot { it.isSticky }.forEach {
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
        // Live freehand ink retains settled spline segments and resamples only the changing tip.
        // Drawing/tapering still visits the centreline, but smoothing no longer grows with the line.
        if (draftStroke == null) resetDraftGeometry()
        else {
            val live = if (draftStroke.tool in FREEHAND_TOOLS) draftGeometry(draftStroke) else null
            if (live != null) {
                InkRenderer.drawRendered(canvas, draftStroke, live)
                // Stylus only: a finger is slow and its tail would read as lag the other way.
                if (stylus && draftStroke.tool == Tool.PEN &&
                    StrokePrediction.predict(draftStroke.points, scale, predicted)) {
                    val tip = draftStroke.points.last()
                    predictionPaint.color = draftStroke.color
                    predictionPaint.alpha = (Color.alpha(draftStroke.color) * draftStroke.opacity).toInt().coerceIn(0, 255)
                    predictionPaint.strokeWidth = draftStroke.width * InkRenderer.penPressureScale(tip.pressure)
                    canvas.drawLine(tip.x, tip.y, predicted[0], predicted[1], predictionPaint)
                }
            }
            else if (draftStroke.tool == Tool.GRAPH) GraphAxes.strokes(draftStroke, graphStyle).forEach { InkRenderer.stroke(canvas, it) }
            else InkRenderer.stroke(canvas, draftStroke)
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
        outlined?.let { if (cropImageId == null) drawImageSelection(canvas, it) }
        cropImageId?.let { id -> placed.images.find { it.id == id } }?.let { drawCropOverlay(canvas, it) }
        // Shown during See all working too, so the overview says where the response is.
        if (page.infinite) canvasWritingSession?.column?.let { column ->
            val unit = selectionUiUnit()
            writingRegionPaint.color = answerAreaColor
            writingRegionPaint.alpha = 0x80
            writingRegionPaint.strokeWidth = unit
            writingRegionPaint.pathEffect = DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)
            val top = -originY / scale
            val bottom = (height - originY) / scale
            canvas.drawLine(column.left, top, column.left, bottom, writingRegionPaint)
            canvas.drawLine(column.right, top, column.right, bottom, writingRegionPaint)
        }
        val areas = (if (showAnswerAreas) writingRegions + listOfNotNull(writingRegion) else emptyList()) + listOfNotNull(regionDraft)
        if (areas.isNotEmpty()) {
            // A constant on-screen dash and width, whatever the zoom: sized in dp and divided by the canvas scale.
            val unit = selectionUiUnit()
            writingRegionPaint.color = answerAreaColor
            writingRegionPaint.alpha = 0xCC
            writingRegionPaint.strokeWidth = 1.5f * unit
            writingRegionPaint.pathEffect = DashPathEffect(floatArrayOf(6f * unit, 4f * unit), 0f)
            areas.distinct().forEach { r -> canvas.drawRect(r.left, r.top, r.right, r.bottom, writingRegionPaint) }
        }
        if (canFollow && followPreferences.showLandingGuide) landingGuide?.let { drawLandingGuide(canvas, it) }
        (markRegionDraft ?: markRegionShown)?.let { r ->
            val unit = selectionUiUnit()
            writingRegionPaint.color = answerAreaColor
            writingRegionPaint.style = Paint.Style.FILL
            writingRegionPaint.alpha = 0x26
            writingRegionPaint.pathEffect = null
            canvas.drawRect(r.left, r.top, r.right, r.bottom, writingRegionPaint)
            writingRegionPaint.style = Paint.Style.STROKE
            writingRegionPaint.alpha = 0xFF
            writingRegionPaint.strokeWidth = 2f * unit
            canvas.drawRect(r.left, r.top, r.right, r.bottom, writingRegionPaint)
        }
        canvas.restore() // Page clip: sticky notes sit on top and may hang past the paper edge.
        val selectedStickies = selectedTexts.filter { it.isSticky }
        stickyNotes.draw(canvas, layered.texts.filter { box -> box.isSticky && selectedStickies.none { it.id == box.id } }, selectionUiUnit())
        if (selectedStickies.isNotEmpty()) {
            canvas.save()
            if (previewing) canvas.concat(selectionPreviewMatrix)
            canvas.translate(selectionDx, selectionDy)
            selectedStickies.forEach { InkRenderer.text(canvas, it); drawTextBox(canvas, it) }
            canvas.restore()
        }
        followStickyFrame()
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
        val margin = inkWidth * scale + 16f * scale.coerceAtMost(2f) + 12f + StrokePrediction.MAX_TAIL_PX
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && editingText != null && !isPalm(event, 0)) onTextEditingExit()
        if (!readOnly && !selectingWritingRegion && !isPalm(event, 0)) {
            val pen = isStylus(event, 0)
            if (pen) { stylus = true; lastStylusAt = SystemClock.uptimeMillis() }
            if (stickyNotes.touch(event, point(event, 0), tool, pen || fingerDrawing, activeLayer,
                    inkColor, inkWidth, inkOpacity, eraserWholeStroke)) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { onActive(); suspendWritingFollow(); removeCallbacks(longPressRunnable) }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) stylus = false
                return true
            }
        }
        if (markRegionMode) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { markRegionDraft = null; markRegionStart = null; invalidate(); return true }
            val pt = clampToPage(point(event, 0))
            fun box(from: InkPoint) = WritingLane(min(from.x, pt.x), min(from.y, pt.y), max(from.x, pt.x), max(from.y, pt.y))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onActive(); dismissZone()
                    // Starting again abandons a rectangle still waiting for its marks.
                    if (markRegionShown != null) { markRegionShown = null; onMarkRegion(null, null) }
                    markRegionStart = pt; parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> markRegionStart?.let { markRegionDraft = box(it) }
                MotionEvent.ACTION_UP -> {
                    markRegionStart?.let { from ->
                        val r = box(from)
                        val s = scale
                        if (r.right - r.left >= 16f && r.bottom - r.top >= 8f) {
                            markRegionShown = r
                            onMarkRegion(r, android.graphics.RectF(originX + r.left * s, originY + r.top * s, originX + r.right * s, originY + r.bottom * s))
                        } else markZones.lastOrNull { it.contains(pt.x, pt.y) }?.let(::offerZone)
                    }
                    markRegionDraft = null; markRegionStart = null; parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> { markRegionDraft = null; markRegionStart = null; parent?.requestDisallowInterceptTouchEvent(false) }
            }
            invalidate(); return true
        }
        if (selectingWritingRegion) {
            if (!isStylus(event, 0) && !fingerDrawing) return true
            val pt = clampToPage(point(event, 0))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { regionStart = pt; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> regionStart?.let { regionDraft = WritingLane(min(it.x, pt.x), min(it.y, pt.y), max(it.x, pt.x), max(it.y, pt.y)) }
                MotionEvent.ACTION_UP -> {
                    regionStart?.let { val r = WritingLane(min(it.x, pt.x), min(it.y, pt.y), max(it.x, pt.x), max(it.y, pt.y))
                        if (r.right - r.left >= 48f && r.bottom - r.top >= 32f) { writingRegions = (writingRegions + r).distinct(); onWritingRegions(writingRegions); writingRegion = r; onWritingRegion(r); reportFollowStatus("Answer area selected · write to resume") }
                        else reportFollowStatus("Area too small · try again") }
                    regionDraft = null; regionStart = null; selectingWritingRegion = false; parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> { regionDraft = null; regionStart = null; selectingWritingRegion = false; parent?.requestDisallowInterceptTouchEvent(false) }
            }
            invalidate(); return true
        }
        if ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
            !isPalm(event, event.actionIndex)) {
            if (pendingReturn != null) { reportFollowStatus("Return cancelled · keep writing"); landingGuide = null }
            else if (lineAdvance != null || followGlide.moved) reportFollowStatus("Movement stopped · keep writing")
            val resume = pendingReturn != null || followGlide.active
            cancelFollowMotion()
            resumeFollowAfterMark = resume
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
        if ((page.infinite || cameraPage) && !stylus && (0 until event.pointerCount).none { isStylus(event, it) } && !isPalm(event, 0)) {
            zoomDetector.onTouchEvent(event)
        }
        // Stylus-first input: any stylus pointer refreshes the palm-rejection window.
        if ((0 until event.pointerCount).any { isStylus(event, it) }) lastStylusAt = SystemClock.uptimeMillis()
        if (longPressFired && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) longPressFired = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissZone()
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
                navigating = !ignored && (tool == Tool.HAND || tool == Tool.STICKY_NOTE || (tool != Tool.TEXT && !fingerDrawing && !stylus))
                if (navigating) suspendWritingFollow()
                lastX = event.rawX; lastY = event.rawY
                panVelocity.resetTracking()
                panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                longPressFired = false
                removeCallbacks(longPressRunnable)
                if (!stylus && !ignored && !readOnly && !inputBlocked) {
                    longPressX = event.x; longPressY = event.y
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                tapOffX = event.x; tapOffY = event.y
                tapOff = false
                if (cropImageId != null && !readOnly && tool == Tool.HAND && !ignored) {
                    if (beginCrop(event, 0)) navigating = false
                    else tapOff = !cropFrameContains(point(event, 0))
                } else if (!readOnly && tool == Tool.HAND && !ignored && beginImage(event, 0)) {
                    navigating = false
                } else if (tool == Tool.HAND && !ignored && beginLink(event, 0)) {
                    navigating = false
                } else if (!stylus && !ignored && !readOnly && beginZone(event, 0)) {
                    navigating = false
                } else if (lassoActive()) beginLasso(event, 0)
                else if (tool == Tool.TEXT && !ignored) beginText(event, 0)
                else if (!navigating && !ignored) beginStroke(event, 0)
                // Pen-only mode is where a finger means "navigate", so a hand that has not travelled
                // yet is left where it is. The pen takes the gesture over the moment its tip lands.
                if (cropImageId == null) tapOff = !readOnly && !ignored &&
                    ((tool == Tool.HAND && selectedImageId != null && movingImage == null) || (tool == Tool.LASSO && hasSelection() && navigating))
                panGate.arm(navigating && !stylus && !fingerDrawing, centroidX(event), centroidY(event))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable); tapOff = false
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
                } else if (!stylus && !ignored && !isPalm(event, event.actionIndex)) {
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
                // A palm often lands as a small contact and spreads: once it is plainly a hand, drop what it started.
                if (!stylus && !ignored && !readOnly && palmRejectMs > 0 && isLargeContact(event, index)) {
                    removeCallbacks(longPressRunnable)
                    draft = null; erasing = null; lasso = null; cancelSelectionGesture(); movingText = null; pendingTextBox = null
                    movingImage = null; resizingImage = false; pendingLink = null; pendingZone = null; eraserMark = null
                    navigating = false; ignored = true; panGate.release()
                    invalidate(); return true
                }
                if (hypot(event.x - longPressX, event.y - longPressY) > ViewConfiguration.get(context).scaledTouchSlop) removeCallbacks(longPressRunnable)
                // A stroke or eraser still in progress is work, even when the pen never lifts.
                if (draft != null || erasing != null) onPenInput(false)
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
                if (pendingZone != null) {
                    val at = point(event, index)
                    if (hypot(at.x - zoneFromX, at.y - zoneFromY) > LINK_SLOP) {
                        pendingZone = null
                        navigating = !fingerDrawing
                        if (navigating) {
                            lastX = centroidX(event); lastY = centroidY(event)
                            panVelocity.resetTracking()
                            panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                        } else beginStroke(event, index)
                    }
                }
                if (cropEdges != 0) dragCrop(point(event, index))
                else if (movingImage != null) {
                    val at = point(event, index)
                    val current = movingImage!!
                    if (resizingImage) {
                        val right = imageCorner == 1 || imageCorner == 2
                        // Follow the finger along the frame's diagonal, so dragging straight up or down shrinks too.
                        val sx = if (right) 1f else -1f
                        val sy = if (imageCorner <= 1) -1f else 1f
                        val along = ((at.x - imageAnchorX) * sx * current.width + (at.y - imageAnchorY) * sy * current.height) /
                            (current.width * current.width + current.height * current.height)
                        val resized = InkGeometry.resizeImage(current, along * current.width)
                        val placed = resized.copy(
                            x = if (right) imageAnchorX else imageAnchorX - resized.width,
                            y = if (imageCorner <= 1) imageAnchorY - resized.height else imageAnchorY)
                        if (placed != current) { movingImage = placed; imageMoved = true }
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
                        val rawPoint = point(event, index)
                        dragOffPage = !page.infinite && (rawPoint.x < 0f || rawPoint.x > page.width || rawPoint.y < 0f || rawPoint.y > page.height)
                        val where = IntArray(2).also { getLocationInWindow(it) }
                        dragWindowX = where[0] + event.getX(index); dragWindowY = where[1] + event.getY(index)
                        val moved = clampToPage(rawPoint)
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
                        reportNavigating(true)
                        if (page.infinite || cameraPage) { camera.pan((x - lastX) * panMultiplier, (y - lastY) * panMultiplier); reportCanvasViewport() } else onDocumentPan((x - lastX) * panMultiplier, (y - lastY) * panMultiplier)
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
                            // Copy-on-write: most MOVEs remove nothing, so keep the same list
                            // instead of allocating a fresh 4k-entry copy per input event.
                            var removedAt = -1
                            for (i in cutting.indices) {
                                val hitStroke = cutting[i]
                                if (!erasable(hitStroke)) continue
                                val b = boundsOf(hitStroke)
                                val reach = maxR + hitStroke.width / 2f
                                if (b[2] + reach < cMinX || b[0] - reach > cMaxX ||
                                    b[3] + reach < cMinY || b[1] - reach > cMaxY) continue
                                val hit = centers.any { c ->
                                    val r = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(c.pressure) else inkWidth / 2f
                                    InkGeometry.hits(hitStroke, c, r)
                                }
                                if (hit) { removedAt = i; break }
                            }
                            if (removedAt < 0) cutting
                            else cutting.filterNot { hitStroke ->
                                if (!erasable(hitStroke)) return@filterNot false
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
                            eraseCopyOnWrite(cutting, cMinX, cMinY, cMaxX, cMaxY, maxR) { stroke ->
                                InkGeometry.erase(stroke, centers, radii)
                            }
                        } else {
                            val radius = inkWidth / 2f
                            eraseCopyOnWrite(cutting, cMinX, cMinY, cMaxX, cMaxY, radius) { stroke ->
                                InkGeometry.erase(stroke, centers, radius)
                            }
                        }
                        eraserMark = centers[centers.size - 1]
                    }
                    draft?.let { current ->
                        val accepted = pagePoints(points)
                        draft = when {
                            accepted.isEmpty() -> current
                            current.tool in FREEHAND_TOOLS -> {
                                // Drafts own their mutable samples until pen-up. Neither the list
                                // nor the Stroke needs allocating again for each input event.
                                (current.points as ArrayList<InkPoint>).addAll(accepted)
                                current
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
                reportNavigating(false)
                removeCallbacks(longPressRunnable)
                cropEdges = 0
                if (tapOff) {
                    tapOff = false
                    if (hypot(event.x - tapOffX, event.y - tapOffY) <= ViewConfiguration.get(context).scaledTouchSlop) {
                        if (cropImageId != null) endImageCrop(true) else { clearImageSelection(); clearSelection() }
                    }
                }
                val chord = touchChord.finish(event.eventTime)
                if (chord != 0) {
                    cancelGesture()
                    if (chord == 2) onUndoRequest?.invoke() else onRedoRequest?.invoke()
                    invalidate()
                    return true
                }
                val hadImage = movingImage != null
                val hadLink = pendingLink != null
                val tappedZone = pendingZone
                pendingZone = null
                if (tappedZone != null) offerZone(tappedZone)
                if (hadImage) {
                    finishImage()
                } else if (hadLink) {
                    val link = pendingLink
                    pendingLink = null
                    link?.let(onPdfLink)
                } else if (navigating && !ignored) {
                    panVelocity.addPosition(event.eventTime, Offset(event.rawX, event.rawY))
                    // A hand that never panned must not fling the document when it lifts either.
                    onDocumentPanEnd(if (panGate.waitingForSlop) 0f else panVelocity.calculateVelocity().y * panMultiplier)
                }
                if (!hadImage && !hadLink && tappedZone == null) {
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
                            draft = if (current.tool in FREEHAND_TOOLS) {
                                (current.points as ArrayList<InkPoint>).add(end)
                                current
                            } else current.copy(points = listOf(current.points.first(), end))
                        }
                    }
                    finishGesture()
                }
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { reportNavigating(false); removeCallbacks(longPressRunnable); tapOff = false; touchChord.reset(); cancelGesture() }
        }
        reportSelectionViewBounds()
        if (!dirtyInvalidated) invalidate()
        return true
    }
    private fun finishGesture() {
        val wasErasing = erasing != null
        val drawn = draft?.let { it.copy(points = it.points.toList()) }
        var scribbleErased: List<Stroke>? = null
        if (drawn != null && scribbleToErase && (drawn.tool == Tool.PEN || drawn.tool == Tool.HIGHLIGHTER)) {
            val scrubbed = InkGeometry.scribbleErase(page.strokes, drawn, SCRIBBLE_RADIUS, scribbleSensitivity, ::boundsOf, ::erasable)
            if (scrubbed !== page.strokes) scribbleErased = scrubbed
        }
        // "Tidy up": a pen drawing that reads as a shape lands as a clean one instead.
        val tidied = if (scribbleErased == null && drawn != null && shapeRecognition) InkGeometry.tidy(drawn)?.let(::snapShapes) else null
        val strokes = scribbleErased ?: (tidied ?: drawn?.let { if (it.tool == Tool.GRAPH) GraphAxes.strokes(it, graphStyle) else listOf(it) })
            // Shapes tidied or generated from the drag are new strokes; they join the layer being drawn on.
            ?.map { if (it.layer == activeLayer) it else it.copy(layer = activeLayer) }
            ?.let { page.strokes + it } ?: erasing
        // Shape tidy can straighten an "l" or a crossbar. Short pen lines still carry
        // writing progress; larger underlines are rejected by the follow geometry rules.
        val followableTidy = tidied == null || tidied.singleOrNull()?.tool == Tool.LINE
        if (canFollow && drawn?.tool == Tool.PEN && scribbleErased == null && followableTidy) {
            followCompletedStroke(drawn)
        }
        val appendedStroke = drawn?.takeIf { scribbleErased == null && tidied == null && it.tool != Tool.GRAPH }
        val beforeStrokes = page.strokes
        val changed = strokes != null && (appendedStroke != null || strokes != beforeStrokes)
        if (changed) {
            if (drawn != null && drawn.tool in FREEHAND_TOOLS && scribbleErased == null && tidied == null) {
                // Hand the final live geometry to the retained layer; pen-up need not smooth it again.
                val geometry = draftGeometry(draft!!)
                renderCache.getOrPut(drawn) {
                    // Detach from the live builder so cached ink does not retain its scratch buffers.
                    geometry.copy(centre = geometry.centre.toList(),
                        widths = geometry.widths?.copyOf(geometry.centre.size))
                }
            }
            page = page.copy(strokes = strokes!!)
        }
        val shouldNotifyEraser = wasErasing && tool == Tool.ERASER
        cancelGesture()
        if (changed) {
            if (tidied != null && drawn != null) {
                // Commit the hand-drawn stroke first, then swap in the clean shape as its own step,
                // so one undo brings the original drawing back instead of removing the ink entirely.
                val original = drawn.let { if (it.layer == activeLayer) it else it.copy(layer = activeLayer) }
                val withOriginal = beforeStrokes + original
                if (onStrokeAppended != null) onStrokeAppended!!.invoke(beforeStrokes, original, withOriginal)
                else onStrokesChanged(withOriginal)
                onStrokesChanged(page.strokes)
            } else if (appendedStroke != null && onStrokeAppended != null)
                onStrokeAppended!!.invoke(beforeStrokes, appendedStroke, page.strokes)
            else onStrokesChanged(page.strokes)
        }
        if (shouldNotifyEraser) onEraserFinished?.invoke()
    }

    private fun followCompletedStroke(drawn: Stroke) {
        val box = FollowNavigation.bounds(drawn.points) ?: return
        val now = SystemClock.uptimeMillis()
        val previousArea = if (writingRegion == null && writingFollow.state.baselineY != null && writingGuides.isNotEmpty())
            followRegion() else null
        val guide = writingFollow.guideFor(box, writingGuides, followPreferences)
        val point = InkPoint(box.centerX, guide?.y ?: writingFollow.anchorY(box))
        if (followPreferences.autoSwitchAreas) {
            WritingGuides.regionAt(writingRegions, point.x, point.y)?.let { area ->
                if (area != writingRegion) {
                    suspendWritingFollow()
                    writingRegion = area; onWritingRegion(area)
                }
            }
        }
        followLastPoint = point
        val strokeRegion = followRegion(drawn.points)
        if (writingRegion == null && previousArea != null && previousArea != strokeRegion) suspendWritingFollow()
        if ((!page.infinite || writingRegion != null || canvasWritingSession != null) && !FollowNavigation.contains(box, strokeRegion, followPreferences.spacing)) {
            resumeFollowAfterMark = false
            reportFollowStatus(if (page.infinite) "Outside response column · view held" else "Outside answer area · select an area to follow here",
                attention = true)
            return
        }
        followPaused = false
        writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
        val progress = writingFollow.completed(drawn.points, now, followPreferences, writingGuides)
        if (progress != WritingProgress.NONE && pendingReturn == null) landingGuide = null
        val resumeMark = resumeFollowAfterMark && writingFollow.finishingMark(box, followPreferences)
        resumeFollowAfterMark = false
        if (followManuallyPaused) { reportFollowStatus("Paused · tap Resume when ready"); return }
        if (progress == WritingProgress.NONE && !resumeMark) {
            reportFollowStatus(if (writingFollow.state.candidateLane != null) "New line · keep writing" else "View held · keep writing")
            return
        }
        val baseline = writingFollow.state.baselineY ?: return
        // A natural canvas line break can establish a different start column. Use it
        // immediately rather than deciding whether to return against the previous lane.
        val region = if (page.infinite && writingRegion == null) followRegion(drawn.points) else strokeRegion
        val next = nextFollowLine(baseline, region)
        if (page.infinite) {
            if (!FollowLegibility.isReadable(writingFollow.laneHeight(), camera.zoom)) {
                reportFollowStatus("Zoom in a little for writing follow · Next line moves manually", attention = true)
                return
            }
        } else if (documentFollowZoom < followPreferences.minimumZoom) {
            reportFollowStatus("Zoom to ${"%.1f".format(followPreferences.minimumZoom)}× to follow · Next line moves manually", attention = true)
            return
        }
        val frontier = if (followPreferences.direction == WritingDirection.LTR)
            writingFollow.state.frontierRight ?: point.x else writingFollow.state.frontierLeft ?: point.x
        // Finishing a word can end with a dot/crossbar behind its furthest letter. The
        // accepted line frontier, rather than that last pen sample, determines the return.
        val returnAdvance = automaticFollowReturn(region)
        val atEnd = returnAdvance != null
        val returnDelay = followPreferences.automaticReturnDelayMs
        reportFollowStatus(when {
            atEnd && followPreferences.automaticReturn -> "Next line in ${FollowPreferences.returnDelayLabel(returnDelay)} · touch down to cancel"
            atEnd -> "Next line ready · tap Next line"
            next == null -> "End of answer area"
            else -> "Following"
        })
        if (atEnd && followPreferences.automaticReturn) {
            queueFollowReturn(returnAdvance!!)
            return
        }
        if (!getLocalVisibleRect(followVisible)) return
        val placing = writingFollow.state.needsPlacement
        val sx = originX + frontier * scale
        val sy = originY + baseline * scale
        val fraction = (sx - followVisible.left) / followVisible.width().coerceAtLeast(1)
        val target = followPreferences.horizontalPosition + if (writingHand == WritingHand.RIGHT) -.02f else .02f
        val dx = if (!followPreferences.horizontalFollow || followPreferences.mode != FollowMode.TEXT) 0f
        else responseDx(if (placing) {
            val startX = writingFollow.state.lineStartX ?: frontier
            (followVisible.left + followVisible.width() * target) - (originX + startX * scale)
        } else writingFollow.horizontalShift(fraction, target, followPreferences.direction,
            followPreferences.edgeThreshold) * followVisible.width())
        val desiredY = followVisible.top + followVisible.height() * followPreferences.position
        val dy = if (!followPreferences.verticalFollow) 0f
        else if (placing || sy > desiredY + followVisible.height() * followPreferences.verticalDeadBand)
            desiredY - sy else 0f
        if (kotlin.math.abs(dx) < .5f && kotlin.math.abs(dy) < .5f) {
            if (placing) {
                writingFollow.placed()
                if (followPreferences.automaticReturn)
                    automaticFollowReturn(region)?.let(::queueFollowReturn)
            }
            return
        }
        val delay = followPreferences.glideDelayMs
        followGlide.start(dx, dy, now, delay, followPreferences.glideDurationMs,
            followVisible.width().toFloat(), followVisible.height().toFloat(), instant = reducedMotion)
        captureFollowBack = true
        scheduleFollow()
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
    /** Hidden and locked layers cannot be drawn on, erased, selected or moved. */
    private fun editableLayer(layer: Int) = PageLayers.editable(page.layers, layer)
    private fun erasable(stroke: Stroke) = editableLayer(stroke.layer)
    /** The box under [at], searching back so the box drawn on top is the one picked up. */
    private fun boxAt(at: InkPoint): TextBox? = page.texts.lastOrNull {
        editableLayer(it.layer) && at.x >= it.x && at.x <= it.x + it.width && at.y >= it.y && at.y <= it.y + InkRenderer.textHeight(it)
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
        val reach = SelectionChrome.TOUCH_RADIUS_DP * selectionUiUnit()
        if (selected != null) {
            val corner = cornerAt(selected, at, reach)
            if (corner >= 0) {
                startResize(selected, corner)
                imageFromX = at.x; imageFromY = at.y
                return true
            }
        }
        val hit = InkGeometry.imageAt(page.images.filter { editableLayer(it.layer) }, at) ?: return false
        movingImage = hit; imageMoved = false
        // A new picture is selected on press so its outline is visible while it is dragged.
        if (selectedImageId != hit.id) {
            selectedImageId = hit.id
            onImageSelected(hit)
        }
        imageFromX = at.x; imageFromY = at.y
        return true
    }

    // ---- Inline crop: insets of the displayed frame, applied to the photo when the crop ends. ----
    /** Called when crop mode starts or ends so the editor can swap the picture menu for Done/Cancel. */
    var onCropMode: (Boolean) -> Unit = {}
    private var cropImageId: String? = null
    private val cropInset = FloatArray(4)
    private var cropEdges = 0
    private var tapOff = false
    private var tapOffX = 0f; private var tapOffY = 0f
    private val cropScrimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0); style = Paint.Style.FILL }
    private val cropFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }

    private fun cropImage(): PageImage? = cropImageId?.let { id -> page.images.find { it.id == id } }
    private fun cropMinShare(image: PageImage) = max(0.1f, PageImage.MIN_CROP_SPAN / min(image.cropWidth(), image.cropHeight()).coerceAtLeast(0.05f))
    private fun cropFrameContains(at: InkPoint): Boolean {
        val image = cropImage() ?: return false
        return at.x in (image.x + cropInset[0] * image.width)..(image.x + (1f - cropInset[2]) * image.width) &&
            at.y in (image.y + cropInset[1] * image.height)..(image.y + (1f - cropInset[3]) * image.height)
    }

    /** Starts cropping the selected picture directly on the page. */
    fun beginImageCrop() {
        val id = selectedImageId ?: return
        if (readOnly || cropImageId == id) return
        cropImageId = id; cropEdges = 0; cropInset.fill(0f)
        onCropMode(true); invalidate()
    }

    /** Leaves crop mode, trimming the photo to the frame when [apply] is set. */
    fun endImageCrop(apply: Boolean) {
        val image = cropImage()
        val inset = cropInset.copyOf()
        if (cropImageId == null) return
        cropImageId = null; cropEdges = 0
        onCropMode(false)
        if (apply && image != null) {
            val cw = image.cropWidth(); val ch = image.cropHeight()
            var l = image.cropLeft; var t = image.cropTop; var r = image.cropRight; var b = image.cropBottom
            val (il, it, ir, ib) = inset.toList()
            // Displayed edges map to photo edges according to the picture's rotation.
            when (image.normalizedRotation()) {
                0 -> { l += il * cw; r -= ir * cw; t += it * ch; b -= ib * ch }
                90 -> { l += it * cw; t += ir * ch; r -= ib * cw; b -= il * ch }
                180 -> { l += ir * cw; r -= il * cw; t += ib * ch; b -= it * ch }
                else -> { r -= it * cw; b -= ir * ch; l += ib * cw; t += il * ch }
            }
            val cropped = image.withCrop(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
            if (cropped != image) {
                // Keep the kept part where it was instead of shrinking about the centre.
                val placed = cropped.copy(x = image.x + il * image.width, y = image.y + it * image.height)
                val images = page.images.map { if (it.id == image.id) placed else it }
                page = page.copy(images = images)
                onImagesChanged(images)
            }
        }
        reportSelectionViewBounds(); invalidate()
    }

    private fun beginCrop(event: MotionEvent, index: Int): Boolean {
        val image = cropImage() ?: return false
        val at = point(event, index)
        val reach = SelectionChrome.TOUCH_RADIUS_DP * selectionUiUnit()
        val l = image.x + cropInset[0] * image.width; val r = image.x + (1f - cropInset[2]) * image.width
        val t = image.y + cropInset[1] * image.height; val b = image.y + (1f - cropInset[3]) * image.height
        val inY = at.y > t - reach && at.y < b + reach
        val inX = at.x > l - reach && at.x < r + reach
        var edges = 0
        if (inY && abs(at.x - l) <= reach) edges = edges or 1
        if (inY && abs(at.x - r) <= reach && (edges and 1 == 0 || abs(at.x - r) < abs(at.x - l))) edges = (edges and 1.inv()) or 4
        if (inX && abs(at.y - t) <= reach) edges = edges or 2
        if (inX && abs(at.y - b) <= reach && (edges and 2 == 0 || abs(at.y - b) < abs(at.y - t))) edges = (edges and 2.inv()) or 8
        cropEdges = edges
        return edges != 0
    }

    /**
     * How far past the picture's current edge the photo still reaches, as a share of the displayed
     * frame (0 when that edge already shows the photo's own edge). Insets may go negative by this
     * much, so a crop is undone by dragging the same handles back out.
     */
    private fun cropRoom(image: PageImage, edge: Int): Float {
        val cw = image.cropWidth().coerceAtLeast(0.01f); val ch = image.cropHeight().coerceAtLeast(0.01f)
        val left = image.cropLeft / cw; val right = (1f - image.cropRight) / cw
        val top = image.cropTop / ch; val bottom = (1f - image.cropBottom) / ch
        // Displayed edges (0 left, 1 top, 2 right, 3 bottom) map to photo edges by rotation.
        return when (image.normalizedRotation()) {
            0 -> floatArrayOf(left, top, right, bottom)
            90 -> floatArrayOf((1f - image.cropBottom) / ch, image.cropLeft / cw, image.cropTop / ch, (1f - image.cropRight) / cw)
            180 -> floatArrayOf(right, bottom, left, top)
            else -> floatArrayOf(image.cropTop / ch, (1f - image.cropRight) / cw, (1f - image.cropBottom) / ch, image.cropLeft / cw)
        }[edge]
    }

    private fun dragCrop(at: InkPoint) {
        val image = cropImage() ?: return
        val min = cropMinShare(image)
        fun clamp(value: Float, edge: Int, opposite: Float) = value.coerceIn(-cropRoom(image, edge), (1f - opposite - min).coerceAtLeast(-cropRoom(image, edge)))
        if (cropEdges and 1 != 0) cropInset[0] = clamp((at.x - image.x) / image.width, 0, cropInset[2])
        if (cropEdges and 4 != 0) cropInset[2] = clamp((image.x + image.width - at.x) / image.width, 2, cropInset[0])
        if (cropEdges and 2 != 0) cropInset[1] = clamp((at.y - image.y) / image.height, 1, cropInset[3])
        if (cropEdges and 8 != 0) cropInset[3] = clamp((image.y + image.height - at.y) / image.height, 3, cropInset[1])
    }

    private val landingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    /** A short dashed baseline and a start dot, in the accent colour, at a constant on-screen size. */
    private fun drawLandingGuide(canvas: Canvas, guide: WritingGuide) {
        val unit = selectionUiUnit()
        val ltr = followPreferences.direction == WritingDirection.LTR
        val start = if (ltr) guide.left else guide.right
        val length = minOf(guide.right - guide.left, writingFollow.lineSpacing(followPreferences.spacing) * 6f)
        if (!start.isFinite() || !guide.y.isFinite() || length <= 0f) return
        landingPaint.color = answerAreaColor
        landingPaint.style = Paint.Style.STROKE
        landingPaint.alpha = 0x70
        landingPaint.strokeWidth = 1.5f * unit
        landingPaint.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 4f * unit), 0f)
        canvas.drawLine(start, guide.y, if (ltr) start + length else start - length, guide.y, landingPaint)
        landingPaint.style = Paint.Style.FILL
        landingPaint.pathEffect = null
        landingPaint.alpha = 0xB0
        canvas.drawCircle(start, guide.y, 3f * unit, landingPaint)
    }

    /** Undo or erasing removed ink: stop any pending move and pull the line frontier back to what remains. */
    private fun followRetracted(remaining: List<Stroke>) {
        if (!canFollow) return
        val baseline = writingFollow.state.baselineY ?: return
        val reach = writingFollow.lineSpacing(followPreferences.spacing) * 2f
        if (pendingReturn != null || followGlide.active) { cancelFollowMotion(); landingGuide = null }
        // Cached sample bounds, the same boxes the follow engine measured when the ink was written.
        val near = remaining.asSequence().filter { it.tool == Tool.PEN && it.points.isNotEmpty() }
            .map { boundsOf(it).let { b -> WritingLane(b[0], b[1], b[2], b[3]) } }
            .filter { it.bottom >= baseline - reach && it.top <= baseline + reach }.toList()
        writingFollow.retract(near, followPreferences.direction)
        reportFollowStatus("Ink removed · line end updated")
    }

    private fun drawCropOverlay(canvas: Canvas, image: PageImage) {
        val unit = selectionUiUnit(preview = true)
        val l = image.x + cropInset[0] * image.width; val r = image.x + (1f - cropInset[2]) * image.width
        val t = image.y + cropInset[1] * image.height; val b = image.y + (1f - cropInset[3]) * image.height
        val x2 = image.x + image.width; val y2 = image.y + image.height
        // Dim only what lies inside the picture; a frame dragged outwards (restoring the photo) extends past it.
        val ct = t.coerceAtLeast(image.y); val cb = b.coerceAtMost(y2)
        canvas.drawRect(image.x, image.y, x2, ct, cropScrimPaint)
        canvas.drawRect(image.x, cb, x2, y2, cropScrimPaint)
        canvas.drawRect(image.x, ct, l.coerceAtLeast(image.x), cb, cropScrimPaint)
        canvas.drawRect(r.coerceAtMost(x2), ct, x2, cb, cropScrimPaint)
        cropFramePaint.strokeWidth = 1.5f * unit
        canvas.drawRect(l, t, r, b, cropFramePaint)
        val mx = (l + r) / 2f; val my = (t + b) / 2f
        val radius = SelectionChrome.HANDLE_RADIUS_DP * unit
        imageHandleEdgePaint.strokeWidth = unit
        listOf(l to t, r to t, l to b, r to b, mx to t, mx to b, l to my, r to my).forEach { (cx, cy) ->
            canvas.drawCircle(cx, cy, radius, imageHandlePaint)
            canvas.drawCircle(cx, cy, radius, imageHandleEdgePaint)
        }
    }

    // ---- Finger long-press: the editor offers a page context menu (paste, select all, ...). ----
    /** Window position of the press and the page point under it. */
    var onLongPress: (Float, Float, InkPoint) -> Unit = { _, _, _ -> }
    /** True while a pan or pinch is moving the view, so the editor can hide its floating menus. */
    var onNavigating: (Boolean) -> Unit = {}
    private var navigatingReported = false
    private fun reportNavigating(on: Boolean) {
        if (navigatingReported == on) return
        navigatingReported = on
        onNavigating(on)
    }
    private var longPressFired = false
    private var longPressX = 0f; private var longPressY = 0f
    private val longPressRunnable = Runnable {
        if (stylus || ignored || readOnly || movingSelection || resizingSelection || rotatingSelection || movingImage != null || cropEdges != 0) return@Runnable
        longPressFired = true
        // The held finger may have started a dot, an eraser pass or a lasso: drop them, it was a hold.
        cancelGesture()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val at = IntArray(2); getLocationInWindow(at)
        reportSelectionViewBounds()
        onLongPress(at[0] + longPressX, at[1] + longPressY, InkPoint((longPressX - originX) / scale, (longPressY - originY) / scale))
        invalidate()
    }

    private fun corners(image: PageImage) = arrayOf(
        image.x to image.y, (image.x + image.width) to image.y,
        (image.x + image.width) to (image.y + image.height), image.x to (image.y + image.height))

    /** The corner handle under [at], or -1. */
    private fun cornerAt(image: PageImage, at: InkPoint, reach: Float): Int =
        corners(image).indexOfFirst { (cx, cy) -> abs(at.x - cx) <= reach && abs(at.y - cy) <= reach }

    private fun startResize(image: PageImage, corner: Int) {
        movingImage = image; resizingImage = true; imageMoved = false; imageCorner = corner
        val (ax, ay) = corners(image)[(corner + 2) % 4]
        imageAnchorX = ax; imageAnchorY = ay
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
            reportSelectionViewBounds()
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
    /**
     * Copy-on-write eraser pass over a dense page. Far strokes skip the segment walk via
     * cached bounds, and untouched strokes keep their instance; the outer list is only
     * allocated once the first fragment appears, so a MOVE that cuts nothing allocates
     * nothing instead of a fresh N-entry copy per input event.
     */
    private inline fun eraseCopyOnWrite(
        cutting: List<Stroke>,
        cMinX: Float, cMinY: Float, cMaxX: Float, cMaxY: Float,
        maxR: Float,
        erase: (Stroke) -> List<Stroke>
    ): List<Stroke> {
        var result: ArrayList<Stroke>? = null
        for (i in cutting.indices) {
            val stroke = cutting[i]
            val b = boundsOf(stroke)
            val reach = maxR + stroke.width / 2f
            if (b[2] + reach < cMinX || b[0] - reach > cMaxX ||
                b[3] + reach < cMinY || b[1] - reach > cMaxY) {
                result?.add(stroke)
                continue
            }
            if (!erasable(stroke)) { result?.add(stroke); continue }
            val out = erase(stroke)
            if (result == null && out.size == 1 && out[0] === stroke) continue
            if (result == null) {
                result = ArrayList(cutting.size)
                for (j in 0 until i) result.add(cutting[j])
            }
            result.addAll(out)
        }
        return result ?: cutting
    }
    private fun cancelSelectionGesture() {
        movingSelection = false; resizingSelection = false; rotatingSelection = false
        selectionPreviewScale = 1f; selectionPreviewDeg = 0f
    }
    private fun cancelGesture() { resetDraftGeometry(); panGate.release(); draft = null; erasing = null; lasso = null; cancelSelectionGesture(); selectionDx = 0f; selectionDy = 0f; pointerId = -1; stylus = false; ignored = false; navigating = false; offPage = false; eraserMark = null; movingText = null; pendingTextBox = null; textDx = 0f; textDy = 0f; movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null; pendingZone = null; cropEdges = 0; parent?.requestDisallowInterceptTouchEvent(false) }
    private fun lassoActive() = tool == Tool.LASSO && !navigating && !ignored
    /** Starts a fresh loop, picks up the selection to move it, or grabs a frame handle. */
    private fun beginLasso(event: MotionEvent, index: Int) {
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        val start = clampToPage(raw)
        if (hasSelection()) {
            val box = selectionBox()
            if (box != null) {
                when (InkGeometry.selectionHandleAt(box, start,
                    touch = SelectionChrome.TOUCH_RADIUS_DP * selectionUiUnit(),
                    rotateLift = SelectionChrome.ROTATE_LIFT_DP * selectionUiUnit())) {
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
            // Hoist the loop bounds once: per-stroke checks then skip the loop walk entirely.
            setSelection(if (loop.size >= 3) {
                val loopBounds = InkGeometry.lassoBounds(loop)
                CanvasSelection(
                    strokes = page.strokes.filter { editableLayer(it.layer) && InkGeometry.lassoSelects(loop, loopBounds, it) },
                    texts = page.texts.filter { editableLayer(it.layer) && InkGeometry.lassoSelectsText(loop, loopBounds, it, InkRenderer.textHeight(it)) },
                    images = page.images.filter { editableLayer(it.layer) && InkGeometry.lassoSelectsImage(loop, loopBounds, it) }
                )
            } else CanvasSelection())
        }
        movingSelection = false; resizingSelection = false; rotatingSelection = false
    }
    private fun commitSelectionMove() {
        val dropped = dragOffPage
        dragOffPage = false
        if (dropped && hasSelection() &&
            onSelectionDrop(CanvasSelection(selection, selectedTexts, selectedImages), dragWindowX, dragWindowY)) {
            setSelection(CanvasSelection())
            return
        }
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
        // Identity only: `it in selection` walked every InkPoint per stroke (O(sel×page×pts))
        // and stalled move/rotate on dense pages. `doomed` already holds the same instances.
        val doomed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(selection) }
        val allStrokes = page.strokes.filterNot { it in doomed } + strokes
        val doomedTextIds = HashSet<String>(selectedTexts.size * 2 + 1).apply { selectedTexts.forEach { add(it.id) } }
        val allTexts = (if (doomedTextIds.isEmpty()) page.texts else page.texts.filterNot { it.id in doomedTextIds }) + texts
        val doomedImageIds = HashSet<String>(selectedImages.size * 2 + 1).apply { selectedImages.forEach { add(it.id) } }
        val allImages = (if (doomedImageIds.isEmpty()) page.images else page.images.filterNot { it.id in doomedImageIds }) + images
        page = page.copy(strokes = allStrokes, texts = allTexts, images = allImages)
        setSelection(CanvasSelection(strokes, texts, images))
        onContentChanged(allStrokes, allTexts, allImages)
    }
    /**
     * The selection frame in view fractions (0..1) for the editor's floating
     * menu. Null while empty, being manipulated, or before first layout.
     */
    private fun reportSelectionViewBounds() {
        val picture = if (hasSelection() || movingImage != null) null
            else selectedImageId?.let { id -> page.images.find { it.id == id } }
        val rect = if ((!hasSelection() && picture == null) || width <= 0 || height <= 0 ||
            movingSelection || resizingSelection || rotatingSelection || lasso != null) null
        else if (picture != null) Rect(
            (originX + picture.x * scale) / width, (originY + picture.y * scale) / height,
            (originX + (picture.x + picture.width) * scale) / width, (originY + (picture.y + picture.height) * scale) / height)
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
    private fun selectionUiUnit(preview: Boolean = false): Float = SelectionChrome.pageUnit(
        resources.displayMetrics.density, scale, if (preview) selectionPreviewScale else 1f)
    private fun selectionBox(): FloatArray? = selectionBounds(margin = SelectionChrome.FRAME_MARGIN_DP * selectionUiUnit(preview = true))
    private fun drawLasso(canvas: Canvas, loop: List<InkPoint>) {
        val unit = selectionUiUnit()
        lassoEdgePaint.strokeWidth = unit
        lassoEdgePaint.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        val polygon = Path().apply { moveTo(loop.first().x, loop.first().y); loop.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        canvas.drawPath(polygon, lassoFillPaint); canvas.drawPath(polygon, lassoEdgePaint)
    }
    /** A dashed outline around a text box while it is dragged, so its extent is visible. */
    private fun drawTextBox(canvas: Canvas, box: TextBox) {
        val unit = selectionUiUnit(preview = true)
        val margin = 2f * unit
        textBoxPaint.strokeWidth = unit
        textBoxPaint.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        canvas.drawRect(box.x - margin, box.y - margin, box.x + box.width + margin,
            box.y + InkRenderer.textHeight(box) + margin, textBoxPaint)
    }
    /**
     * Dashed frame around the lasso selection with direct handles: a plain dot
     * on the bottom-right corner resizes about the center, a ringed dot with a
     * circular arrow above the top edge rotates about it. Screen-sized chrome
     * compensates for the canvas and the live handle preview scale.
     */
    private fun drawSelectionFrame(canvas: Canvas) {
        val box = selectionBox() ?: return
        val unit = selectionUiUnit(preview = true)
        val radius = SelectionChrome.HANDLE_RADIUS_DP * unit
        selectionBoxPaint.strokeWidth = unit
        selectionBoxPaint.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        selectionLinkPaint.strokeWidth = unit
        selectionGlyphPaint.strokeWidth = 1.5f * unit
        selectionHandleEdgePaint.strokeWidth = unit
        canvas.drawRect(box[0], box[1], box[2], box[3], selectionBoxPaint)
        val cx = (box[0] + box[2]) / 2f
        val rotateY = box[1] - SelectionChrome.ROTATE_LIFT_DP * unit
        canvas.drawLine(cx, box[1], cx, rotateY + radius, selectionLinkPaint)
        canvas.drawCircle(box[2], box[3], radius, imageHandlePaint)
        canvas.drawCircle(box[2], box[3], radius, selectionHandleEdgePaint)
        canvas.drawCircle(cx, rotateY, radius, imageHandlePaint)
        canvas.drawCircle(cx, rotateY, radius, selectionHandleEdgePaint)
        // Circular arrow: 300° of arc plus a V head at its end (420° == 60°).
        val r = 4f * unit
        canvas.drawArc(RectF(cx - r, rotateY - r, cx + r, rotateY + r), 120f, 300f, false, selectionGlyphPaint)
        val end = Math.toRadians(60.0)
        val ex = (cx + r * cos(end)).toFloat()
        val ey = (rotateY + r * sin(end)).toFloat()
        // Unit tangent of clockwise travel at 60°; wings fan the backward
        // direction ±25° into a V, in page units.
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val head = 2.75f * unit
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
        val unit = selectionUiUnit(preview = true)
        val margin = 2f * unit
        imageEdgePaint.strokeWidth = unit
        imageEdgePaint.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
        imageHandleEdgePaint.strokeWidth = unit
        canvas.drawRect(image.x - margin, image.y - margin,
            image.x + image.width + margin, image.y + image.height + margin, imageEdgePaint)
        if (!withHandle) return
        val r = SelectionChrome.HANDLE_RADIUS_DP * unit
        corners(image).forEach { (cx, cy) ->
            canvas.drawCircle(cx, cy, r, imageHandlePaint)
            canvas.drawCircle(cx, cy, r, imageHandleEdgePaint)
        }
    }
    // Deliver after drawing, coalescing frames so Compose state is never changed inside onDraw.
    internal var onShapeMeasurement: (ShapeMeasurement?) -> Unit = {}
    private var pendingMeasurement: ShapeMeasurement? = null
    private var reportedMeasurement: ShapeMeasurement? = null
    private val reportMeasurement = Runnable {
        if (reportedMeasurement != pendingMeasurement) {
            reportedMeasurement = pendingMeasurement
            onShapeMeasurement(pendingMeasurement)
        }
    }
    private fun reportShapeMeasurement() {
        pendingMeasurement = draft?.takeIf { shapeMeasurements && it.tool in MEASURE_TOOLS }
            ?.let { ShapeMeasurement.from(it, graphStyle, originX, originY, scale) }
        removeCallbacks(reportMeasurement)
        if (pendingMeasurement != reportedMeasurement) post(reportMeasurement)
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
        setSelection(CanvasSelection(
            page.strokes.filter { editableLayer(it.layer) }, page.texts.filter { editableLayer(it.layer) },
            page.images.filter { editableLayer(it.layer) }))
    }

    /** Begins a stroke for [index] unless the touch started outside the page, which pans instead. */
    private fun beginStroke(event: MotionEvent, index: Int) {
        // onTouchEvent already stopped follow on contact and retained finishing-mark intent.
        // Cancelling again here would clear that intent before a dot/crossbar can resume it.
        val raw = point(event, index)
        if (!onPage(raw.x, raw.y)) { navigating = true; return }
        var start = clampToPage(raw)
        if (snapEnabled && tool in ShapePickerTools && page.paper.isGrid) {
            start = InkGeometry.snapToGrid(start, page.paper.gridSpacing)
        }
        if (tool == Tool.ERASER || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) {
            val radius = if (eraserPressureEnabled && stylus) inkWidth / 2f * InkGeometry.eraserScale(start.pressure) else inkWidth / 2f
            erasing = if (eraserWholeStroke) {
                val hitAt = page.strokes.indexOfFirst {
                    if (!erasable(it)) return@indexOfFirst false
                    val b = boundsOf(it)
                    val reach = radius + it.width / 2f
                    if (start.x < b[0] - reach || start.x > b[2] + reach ||
                        start.y < b[1] - reach || start.y > b[3] + reach) return@indexOfFirst false
                    InkGeometry.hits(it, start, radius)
                }
                if (hitAt < 0) page.strokes
                else page.strokes.filterNot {
                    if (!erasable(it)) return@filterNot false
                    val b = boundsOf(it)
                    val reach = radius + it.width / 2f
                    if (start.x < b[0] - reach || start.x > b[2] + reach ||
                        start.y < b[1] - reach || start.y > b[3] + reach) return@filterNot false
                    InkGeometry.hits(it, start, radius)
                }
            } else eraseCopyOnWrite(page.strokes, start.x, start.y, start.x, start.y, radius) {
                InkGeometry.erase(it, start, radius)
            }
            eraserMark = start
            onPenInput(false)
        } else {
            if (!PageLayers.editable(page.layers, activeLayer)) { ignored = true; onLayerBlocked(); return }
            draft = Stroke(tool, inkColor, inkWidth, arrayListOf(start), inkOpacity,
                style = if (tool in ShapePickerTools) inkStyle else StrokeStyle.SOLID, layer = activeLayer)
            onPenInput(true)
        }
    }
    private fun isStylus(event: MotionEvent, index: Int) = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
    private fun isPalm(event: MotionEvent, index: Int) = palmRejectMs > 0 && !isStylus(event, index) &&
        (SystemClock.uptimeMillis() - lastStylusAt < palmRejectMs || isLargeContact(event, index))
    /** A contact far wider than a fingertip is a hand, whether or not the pen has been seen recently. */
    private fun isLargeContact(event: MotionEvent, index: Int): Boolean {
        val major = event.getTouchMajor(index)
        return major > 0f && major > PALM_CONTACT_MM * resources.displayMetrics.xdpi / 25.4f
    }
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
        val MEASURE_TOOLS = ShapePickerTools
        /** How long after stylus activity a finger still counts as a resting palm. */
        const val PALM_REJECT_MS = 500L
        /** Touch major axis above which a finger contact is treated as a resting palm. */
        const val PALM_CONTACT_MM = 22f
        /** Page units of slack around the page edge, absorbing samples reported outside the view. */
        const val EDGE_TOLERANCE = 24f
        /** How far a press on a PDF link may wander before the gesture becomes a pan. */
        const val LINK_SLOP = 12f
        const val SCRIBBLE_RADIUS = 14f
        /** Above this many selected strokes the halo double-draw is skipped to avoid 2× overdraw. */
        const val SELECTION_HALO_LIMIT = 40
        /** Preview raster budget (~2.4 MP, ~10 MB); the cache cap sits just above so rounding never falls back to vector. */
        const val NAVIGATION_INK_PIXEL_BUDGET = 2_400_000.0
        const val NAVIGATION_INK_MAX_PIXELS = 2_600_000L
        /**
         * Geometry caches hold a dense page's live strokes without thrashing: LRU keeps the
         * visible working set resident while panning, and 8k entries cover ~2× the old bound
         * for high-stroke-count documents before anything is re-smoothed.
         */
        const val MAX_CACHED_STROKES = 8000
        val SELECTION_COLOR = 0xFF2F6FBA.toInt()
    }
}
