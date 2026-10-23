package com.folio.notes.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.folio.notes.FolioApplication
import com.folio.notes.app.AppPrefs
import com.folio.notes.document.DocumentToolEdits
import com.folio.notes.editor.InlineTextFrame
import com.folio.notes.editor.follow.CanvasWritingSession
import com.folio.notes.editor.follow.FollowBackHistory
import com.folio.notes.editor.follow.FollowGlide
import com.folio.notes.editor.follow.FollowLegibility
import com.folio.notes.editor.follow.FollowMode
import com.folio.notes.editor.follow.FollowNavigation
import com.folio.notes.editor.follow.FollowPreferences
import com.folio.notes.editor.follow.FollowReturnTarget
import com.folio.notes.editor.follow.FollowRhythm
import com.folio.notes.editor.follow.WritingAdvance
import com.folio.notes.editor.follow.WritingDirection
import com.folio.notes.editor.follow.WritingFollow
import com.folio.notes.editor.follow.WritingFollowState
import com.folio.notes.editor.follow.WritingFollowStatus
import com.folio.notes.editor.follow.WritingGuide
import com.folio.notes.editor.follow.WritingGuides
import com.folio.notes.editor.follow.WritingHand
import com.folio.notes.editor.follow.WritingLane
import com.folio.notes.editor.follow.WritingProgress
import com.folio.notes.editor.follow.ZoomWriting
import com.folio.notes.editor.layout.DocumentInkCapture
import com.folio.notes.editor.layout.DocumentInkPage
import com.folio.notes.editor.layout.DocumentPageGeometry
import com.folio.notes.editor.layout.InfiniteViewport
import com.folio.notes.editor.layout.ZoomRenderState
import com.folio.notes.editor.selection.ObjectLift
import com.folio.notes.editor.selection.ObjectSnapping
import com.folio.notes.editor.selection.SelectionChrome
import com.folio.notes.editor.sticky.StickyDrop
import com.folio.notes.editor.sticky.StickyLift
import com.folio.notes.editor.sticky.StickyNoteInput
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.editor.toolbar.ToolOptions
import com.folio.notes.ink.graph.GraphAxes
import com.folio.notes.ink.graph.GraphStyle
import com.folio.notes.ink.pen.PenPressure
import com.folio.notes.ink.pen.PenSmoothing
import com.folio.notes.ink.pen.PenStartHold
import com.folio.notes.ink.pen.PenWritingFeedback
import com.folio.notes.ink.pen.StrokeEnds
import com.folio.notes.ink.pen.StrokePrediction
import com.folio.notes.ink.pen.StrokeRefit
import com.folio.notes.ink.recognition.InkGestureRecognizer
import com.folio.notes.ink.recognition.InkRecognitionSnapshot
import com.folio.notes.ink.recognition.ScribbleRecognition
import com.folio.notes.ink.recognition.ScribbleSensitivity
import com.folio.notes.ink.shapes.ShapeAdjustment
import com.folio.notes.ink.shapes.ShapeAssistOptions
import com.folio.notes.ink.shapes.ShapeHold
import com.folio.notes.ink.shapes.ShapeMath
import com.folio.notes.ink.shapes.ShapeMeasurement
import com.folio.notes.ink.shapes.Tidied
import com.folio.notes.input.PanGate
import com.folio.notes.input.StylusActivity
import com.folio.notes.input.StylusInputRouter
import com.folio.notes.input.TouchChord
import com.folio.notes.input.isStylusPointer
import com.folio.notes.marking.MarkZone
import com.folio.notes.marking.MarkZonePart
import com.folio.notes.math.TextMathBitmaps
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage
import com.folio.notes.model.PageLayers
import com.folio.notes.model.PeekAnchor
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.pdf.PdfLink
import com.folio.notes.pdf.PdfReference
import com.folio.notes.storage.IdentityCache
import com.folio.notes.storage.PageContent
import com.folio.notes.storage.content
import com.folio.notes.workspace.WorkspaceViewport
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
            if (documentGesture || penStartTool != null) { inputRouter.reset(::handleTouchEvent); cancelGesture() }
            clearPenStartHold()
            clearShapeHold()
            stickyNotes.reset()
            endObjectLift()
            field = value
            // The presenter trail belongs to the laser; it never lingers under another tool.
            if (value != Tool.LASER) laser.clear()
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
    var penSmoothing = AppPrefs.DEFAULT_PEN_SMOOTHING
    /** How the raw pressure reading is bent before it sets the width; 1 is the reading itself. */
    var pressureCurve = 1f
    private var penFilter: PenSmoothing? = null
    /** Set for a pen stroke when Settings asks for thinner ink at speed; null otherwise. */
    private var speedWidth: PenPressure.SpeedWidth? = null
    var eraserPressureEnabled = true
    var scribbleToErase = true
        set(value) {
            if (field == value) return
            field = value
            recognitionEpoch++
            if (value && isAttachedToWindow) InkGestureRecognizer.prepare(InkGestureRecognizer.Model.GESTURES)
        }
    var scribbleSensitivity = ScribbleSensitivity.DEFAULT
    /** A scrub held still at its end erases what it covers without asking the classifier. */
    var scribbleHold = true
        set(value) { if (field != value) { field = value; clearShapeHold(); invalidate() } }
    var eraserWholeStroke = false
    private var inputRouter = StylusInputRouter(pinchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    /** Main-window proximity is also seen by reference panes and controls in popup windows. */
    internal var inputStylusActivity: StylusActivity? = null
        set(value) {
            if (field === value) return
            inputRouter.reset(::handleTouchEvent)
            if (isAttachedToWindow) { inputRouter.stylus.detachNavigationSurface(); inputRouter.stylus.removeArrivalListener(penArrival) }
            field = value
            inputRouter = StylusInputRouter(value ?: StylusActivity(), ViewConfiguration.get(context).scaledTouchSlop.toFloat())
            if (isAttachedToWindow) { inputRouter.stylus.attachNavigationSurface(); inputRouter.stylus.addArrivalListener(penArrival) }
        }
    /**
     * Finger strokes committed in the last moments, newest last, with their lift times. If the pen
     * comes into range right after, they were the writing hand landing and are taken back.
     */
    private val recentFingerInk = ArrayList<Pair<Stroke, Long>>()
    private val penArrival: (Long) -> Unit = { arrivedAt -> withdrawPalmInk(arrivedAt) }
    /** Removes one palm stroke from storage without leaving an undo step; null edits through [onStrokesChanged]. */
    var onStrokeWithdrawn: ((Stroke) -> Unit)? = null
    /** Proximity grace for a standalone surface; app windows use their shared preference. */
    var palmRejectMs: Long
        get() = inputRouter.stylus.graceMs
        set(value) { if (inputStylusActivity == null) inputRouter.stylus.graceMs = AppPrefs.palmMs(value) }
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
    internal var documentInputBusy: (() -> Boolean)? = null
    internal fun cancelExternalGesture() { inputRouter.reset(::handleTouchEvent); stickyNotes.cancel(); cancelGesture(); onLiveInk(null, false); invalidate() }
    internal var onDocumentGestureStart: ((Stroke, Boolean) -> Boolean)? = null
    internal var onDocumentGestureSamples: ((List<InkPoint>) -> Boolean)? = null
    internal var onDocumentGestureFinish: (() -> Boolean)? = null
    internal var onDocumentGestureCancel: (() -> Unit)? = null
    internal var onDocumentGesturePresence: (Boolean) -> Unit = {}
    internal var onDocumentSelectionDrag: ((Float, Float, Boolean) -> Boolean)? = null
    internal fun previewDocumentSelection(dx: Float, dy: Float) {
        if (documentSelectionOwner) { selectionDx = dx; selectionDy = dy; invalidate() }
    }
    private var documentGesture = false
    private var documentSpanning = false
    internal var documentSelectionOwner = false
    private val documentPenBuilders = java.util.IdentityHashMap<List<InkPoint>, InkRenderer.IncrementalPenStroke>()
    private val documentHighlightBuilders = java.util.IdentityHashMap<List<InkPoint>, InkRenderer.IncrementalHighlighterStroke>()
    internal var documentInkPreview: List<Stroke> = emptyList()
        set(value) { field = value; if (value.isEmpty()) { documentPenBuilders.clear(); documentHighlightBuilders.clear() }; invalidate() }
    internal var documentContentPreview: NotePage? = null
        set(value) { field = value; invalidate() }
    internal val hasDocumentStylusContact: Boolean get() = stylus && documentGesture
    internal var documentPreviewLiveStroke: Stroke? = null
    internal var documentLassoPreview: List<InkPoint> = emptyList()
        set(value) { field = value; invalidate() }
    internal fun bindDocumentSelection(value: CanvasSelection) {
        if (!documentSelectionOwner || selection != value.strokes || selectedTexts != value.texts || selectedImages != value.images) showDocumentSelection(value)
    }
    internal fun showDocumentSelection(value: CanvasSelection) {
        documentSelectionOwner = value.isNotEmpty()
        setSelection(value)
    }
    private var localCapture: DocumentInkCapture? = null
    private var localEraser = DocumentToolEdits.EraserSession()
    private fun beginDocument(stroke: Stroke, isLasso: Boolean): Boolean {
        // Keep finger drawing on its existing withdrawal path when a pen arrives just after lift.
        if ((stylus || isLasso || stroke.tool == Tool.ERASER || stroke.tool == Tool.MARK_AREA) && onDocumentGestureStart?.invoke(stroke, isLasso) == true) return true
        if (isLasso || stroke.tool == Tool.MARK_AREA || visibleNotes().isEmpty()) return false
        val bounds = DocumentPageGeometry.Bounds(0f, 0f, page.width, page.height)
        val frame = DocumentInkPage(DocumentPageGeometry.Frame(page.id, page.width, page.height, bounds, bounds),
            activeLayer, PageLayers.editable(page.layers, activeLayer), visibleNotes(), page.layers, page.loaded, page.infinite)
        localEraser = DocumentToolEdits.EraserSession()
        localCapture = DocumentInkCapture(page.id, stroke, listOf(frame)).also { it.add(stroke.points, false) }
        return true
    }
    private fun routedLocal(current: DocumentInkCapture): DocumentInkCapture {
        heldShape?.let { return current.generated(it) }
        if (current.template.tool !in ShapePickerTools) return current
        var drag = current.dragStroke()
        if (snapEnabled && drag.points.size >= 2) {
            var end = drag.points.last()
            if (page.paper.isGrid) end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
            if (drag.tool == Tool.LINE) end = InkGeometry.snapAngle(drag.points.first(), end, 15f)
            drag = drag.copy(points = listOf(drag.points.first(), end))
        }
        return current.generated(if (drag.tool == Tool.GRAPH) GraphAxes.strokes(drag, graphStyle) else listOf(drag))
    }
    private fun localContent(current: DocumentInkCapture): PageContent {
        val options = DocumentToolEdits.Options(current.template.tool == Tool.ERASER, eraserWholeStroke, eraserPressureEnabled && stylus,
            false, scribbleSensitivity)
        val strokes = current.strokes()[page.id].orEmpty(); val notes = current.noteStrokes()[page.id].orEmpty()
        return if (options.erasing) localEraser.content(page, strokes, notes, options) else DocumentToolEdits.content(page, strokes, notes, options)
    }
    private fun documentSamples(points: List<InkPoint>): Boolean {
        if (!documentGesture) return false
        val accepted = if (rulerEdge == 0) points else points.map(::snapToRuler)
        val local = localCapture
        if (local != null) {
            local.add(accepted, false)
            val routed = routedLocal(local)
            if (local.spanning || routed.spanning) {
                documentSpanning = true
                if (!routed.blocked) {
                    val content = localContent(routed)
                    val erasing = local.template.tool == Tool.ERASER
                    documentInkPreview = if (erasing) emptyList() else routed.strokes()[page.id].orEmpty()
                    documentContentPreview = page.copy(strokes = if (erasing) content.strokes else page.strokes, texts = content.texts)
                }
            }
        } else if (onDocumentGestureSamples?.invoke(accepted) == true) documentSpanning = true
        return documentSpanning
    }
    private fun finishDocument(): Boolean {
        val local = localCapture
        val handled = if (local != null) {
            val routed = routedLocal(local)
            val spanning = local.spanning || routed.spanning
            if (spanning) {
                if (routed.blocked) onLayerBlocked()
                else {
                    val options = DocumentToolEdits.Options(local.template.tool == Tool.ERASER, eraserWholeStroke,
                        eraserPressureEnabled && stylus, false, scribbleSensitivity)
                    val steps = if (options.erasing) listOf(localContent(routed)) else DocumentToolEdits.contentSteps(
                        page, routed.strokes()[page.id].orEmpty(), routed.noteStrokes()[page.id].orEmpty(), options)
                    for (content in steps) if (content != page.content()) {
                        page = page.copy(strokes = content.strokes, texts = content.texts)
                        onContentChanged(content.strokes, content.texts, content.images)
                    }
                    if (local.template.tool == Tool.ERASER && tool == Tool.ERASER) onEraserFinished?.invoke()
                }
            }
            spanning
        } else documentGesture && onDocumentGestureFinish?.invoke() == true
        localCapture = null; documentContentPreview = null; documentInkPreview = emptyList()
        documentGesture = false; documentSpanning = false
        onDocumentGesturePresence(false)
        return handled
    }
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
    /** Pause at the end of a pen drawing to preview a clean shape before lifting. */
    var shapeRecognition = false
        set(value) {
            if (field == value) return
            field = value
            clearShapeHold()
            if (value && isAttachedToWindow) InkGestureRecognizer.prepare(InkGestureRecognizer.Model.SHAPES)
            invalidate()
        }
    internal var shapeAssistOptions = ShapeAssistOptions()
        set(value) { if (field != value) { field = value; clearShapeHold(); invalidate() } }
    internal var onShapeAssistPreview: (Boolean) -> Unit = {}
    var shapeHoldMs = AppPrefs.DEFAULT_SHAPE_HOLD_MS
        set(value) {
            val delay = AppPrefs.shapeHoldMs(value)
            if (field != delay) { field = delay; clearShapeHold(); invalidate() }
        }
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
    fun setStickyPaper(id: String, paper: Int) = stickyNotes.setPaper(id, paper)
    fun setStickyTextSize(id: String, size: Float) = stickyNotes.setTextSize(id, size)
    /** The type size new sticky notes start with; the editor passes the Settings default. */
    var stickyTextSize: Float
        get() = stickyNotes.newTextSize
        set(value) { stickyNotes.newTextSize = StickyNotes.textSize(value) }
    fun clearStickyInk(id: String) = stickyNotes.clearInk(id)
    fun duplicateSticky(id: String) = stickyNotes.duplicate(id)
    fun bringStickyToFront(id: String) = stickyNotes.bringToFront(id)
    /** Carries a sticky note being moved over the whole editor, not just this view; see [StickyLift]. */
    internal var onStickyLift: ((StickyLift?) -> Unit)?
        get() = stickyNotes.onLift
        set(value) { stickyNotes.onLift = value }
    /** Offered a carried note on release, including refusal of a stale or protected transfer. */
    internal var onStickyDrop: ((StickyLift) -> StickyDrop)?
        get() = stickyNotes.onDrop
        set(value) { stickyNotes.onDrop = value }
    private var stickyFrameReported: Rect? = null
    private val stickyNotes = StickyNoteInput(this, { page }, ::commitTexts,
        { box -> RectF(originX + box.x * scale, originY + box.y * scale,
            originX + (box.x + box.width) * scale, originY + (box.y + box.stickyHeight) * scale) },
        { box, typing ->
            stickyFrameReported = box?.let(::stickyFraction)
            onStickyFocus(box, stickyFrameReported, typing)
        })
    private fun stickyFraction(box: TextBox): Rect {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        return Rect((originX + box.x * scale) / w, (originY + box.y * scale) / h,
            (originX + (box.x + box.width) * scale) / w, (originY + (box.y + box.stickyHeight) * scale) / h)
    }
    private fun commitTexts(texts: List<TextBox>) {
        page = page.copy(texts = texts)
        onTextsChanged(texts)
        // A note moved past the paper changes how much the one-page view fits and pans over.
        if (pageCamera) reportCanvasViewport()
        invalidate()
    }
    /** The note a stroke is being written into (its id), or null for page ink. */
    private var noteTarget: String? = null
    /**
     * Notes the eraser has worked on during this gesture: their surviving ink, note-local. Only
     * samples over a note reach its ink, and only samples clear of every note reach the page's.
     */
    private var noteErasing: MutableMap<String, List<Stroke>>? = null
    /** Visible notes, bottom to top, for hit-testing ink against them. */
    private var visibleNotesSource: NotePage? = null
    private var visibleNotesValue: List<TextBox> = emptyList()
    /** Hit-tested per pen sample, so it is only re-filtered when the layer view changes. */
    private fun visibleNotes(): List<TextBox> {
        val view = layerView()
        if (visibleNotesSource !== view) { visibleNotesValue = view.texts.filter { it.isSticky }; visibleNotesSource = view }
        return visibleNotesValue
    }
    /** Keeps the menu and typing field on the note while an infinite canvas pans or the view resizes. */
    private fun followStickyFrame() {
        val box = stickyNotes.shown() ?: return
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
    internal var onDocumentMarkRegion: (List<MarkZonePart>, android.graphics.RectF) -> Unit = { _, _ -> }
    internal var documentMarkRegionPreview: WritingLane? = null
        set(value) { field = value; invalidate() }
    internal fun showDocumentMarkRegion(parts: List<MarkZonePart>) {
        val part = parts.firstOrNull { it.pageId == page.id } ?: return
        markRegionShown = WritingLane(part.x, part.y, part.x + part.width, part.y + part.height)
        onDocumentMarkRegion(parts, android.graphics.RectF(originX + part.x * scale, originY + part.y * scale,
            originX + (part.x + part.width) * scale, originY + (part.y + part.height) * scale))
        invalidate()
    }
    private val markRegionMode get() = tool == Tool.MARK_AREA && !readOnly && page.pdfIndex != null
    private var markRegionStart: InkPoint? = null
    private var markRegionDraft: WritingLane? = null
    private var markRegionShown: WritingLane? = null
    private var pendingZone: MarkZone? = null
    private var zoneFromX = 0f
    private var zoneFromY = 0f
    private var zoneOffered = false
    private var lastZoneOfferAt = 0L
    private var recognitionEpoch = 0L
    private var shapeRequest = 0L
    private var draft: Stroke? = null
    private val penStartHold = PenStartHold()
    private var penStartTarget: Tool? = null
    private var penStartTool: Tool? = null
    private var penStartOptions: ToolOptions? = null
    internal var penStartOptionsFor: ((Tool) -> ToolOptions)? = null
    private var penStartContact = InkPoint(0f, 0f)
    private var penStartPressure = 1f
    private var penStartTilt = 0f
    private val contactTool get() = penStartTool ?: tool
    private val contactWidth get() = penStartOptions?.width ?: inkWidth
    private val contactPressureEnabled get() = penStartOptions?.pressure ?: pressureEnabled
    private fun penStartPreference(): Tool? = when (AppPrefs.penStartHold(
        context.getSharedPreferences("preferences", 0).getString(AppPrefs.PEN_START_HOLD, null))) {
        "highlighter" -> Tool.HIGHLIGHTER
        "eraser" -> Tool.ERASER
        else -> null
    }
    private val penStartHoldRunnable: Runnable = Runnable {
        val target = penStartTarget ?: return@Runnable
        if (!isAttachedToWindow || readOnly || inputBlocked || navigationOnly || navigating || ignored || !stylus ||
            tool != Tool.PEN || draft?.tool != Tool.PEN || offPage || penStartPreference() != target ||
            !editableLayer(noteTarget?.let { id -> page.texts.find { it.id == id }?.layer } ?: activeLayer)) {
            clearPenStartHold()
            return@Runnable
        }
        val now = SystemClock.uptimeMillis()
        if (!penStartHold.attempt(now)) {
            penStartHold.remaining(now)?.let { postDelayed(penStartHoldRunnable, it) }
            return@Runnable
        }
        clearPenStartHold()
        convertPenStart(target)
    }
    private fun clearPenStartHold() {
        removeCallbacks(penStartHoldRunnable)
        penStartHold.reset()
        penStartTarget = null
    }
    private fun trackPenStartHold(event: MotionEvent, index: Int) {
        if (penStartTarget == null) return
        val slop = PenStartHold.SLOP_DP * resources.displayMetrics.density
        for (h in 0 until event.historySize) {
            penStartHold.sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h), slop)
        }
        penStartHold.sample(event.getX(index), event.getY(index), slop)
        if (penStartHold.remaining(event.eventTime) == null) { clearPenStartHold(); return }
        penStartContact = point(event, index)
        penStartPressure = event.getPressure(index)
    }
    /** Restart the uncommitted capture, so every page/note receives the borrowed tool and its width. */
    private fun convertPenStart(target: Tool) {
        val source = draft ?: return
        if (documentGesture && localCapture == null) onDocumentGestureCancel?.invoke()
        localCapture = null; documentGesture = false; documentSpanning = false
        documentContentPreview = null; documentInkPreview = emptyList()
        onDocumentGesturePresence(false)
        clearShapeHold(); clearPrediction(); lowLatency?.cancel(); endLowLatency(); resetDraftGeometry()
        penFilter = null; speedWidth = null
        penStartTool = target
        val prefs = context.getSharedPreferences("preferences", 0)
        val options = penStartOptionsFor?.invoke(target)
            ?: ToolOptions.load(context.getSharedPreferences("ink-tools", 0), target)
        penStartOptions = options
        val pressure = if ((target == Tool.ERASER && eraserPressureEnabled) || (target == Tool.HIGHLIGHTER && options.pressure))
            penStartPressure.coerceIn(.25f, 1.8f) else 1f
        val start = source.points.first().copy(pressure = pressure)
        val contact = penStartContact.copy(pressure = pressure)
        if (target == Tool.ERASER) {
            draft = null; noteTarget = null; rulerEdge = 0
            beginErasing(contact)
        } else {
            val width = if (prefs.getBoolean(AppPrefs.HIGHLIGHTER_TILT, AppPrefs.DEFAULT_HIGHLIGHTER_TILT))
                options.width * PenPressure.tiltScale(penStartTilt) else options.width
            draft = Stroke(target, options.color, width, arrayListOf(start, snapToRuler(contact)), options.opacity,
                layer = source.layer)
            documentGesture = beginDocument(draft!!, false)
            if (documentGesture) { onDocumentGesturePresence(true); documentSamples(emptyList()) }
            // The end hold starts afresh on this highlighter, independently of the start hold.
            shapeContact = snapToRuler(contact)
            shapeFitPointCount = draft!!.points.size
            shapeHold.sample(originX + contact.x * scale, originY + contact.y * scale,
                SystemClock.uptimeMillis(), ShapeHold.SLOP_DP * resources.displayMetrics.density)
            if (holdRecognition) postDelayed(shapeHoldRunnable, shapeHoldMs)
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        if (target != Tool.ERASER) onPenInput(false)
        onLiveInk(draft, isWritingGesture)
        invalidate()
    }
    private val shapeHold = ShapeHold()
    /** The end-of-stroke hold serves shape assist and hold-to-erase; a scrub is never a shape. */
    private val holdRecognition get() = shapeRecognition || (scribbleToErase && scribbleHold)
    private var heldShape: List<Stroke>? = null
    /** Page ink a held scrub will erase on lift; non-null while the hold-to-erase preview shows. */
    private var heldErase: List<Stroke>? = null
    internal val documentHeldErase get() = heldErase != null
    private var shapeAdjustment: ShapeAdjustment? = null
    private var shapeContact: InkPoint? = null
    private var shapeFitPointCount = 0
    internal val documentHeldShape get() = heldShape
    private val shapeHoldRunnable: Runnable = Runnable {
        if (holdRecognition && draft?.tool in FREEHAND_TOOLS && !readOnly && !inputBlocked && !navigating &&
            PageLayers.editable(page.layers, activeLayer)) {
            if (!shapeHold.attempt(SystemClock.uptimeMillis(), shapeHoldMs)) {
                shapeHold.remaining(SystemClock.uptimeMillis(), shapeHoldMs)?.let { postDelayed(shapeHoldRunnable, it) }
                return@Runnable
            }
            val source = draft ?: return@Runnable
            if (scribbleToErase && scribbleHold && holdErase(source)) return@Runnable
            if (!shapeRecognition) return@Runnable
            val drawing = ShapeHold.drawing(source.copy(points = source.points.toList()), shapeFitPointCount)
            val request = ++shapeRequest
            val pageId = page.id
            val revision = page.revision
            val options = shapeAssistOptions
            val layer = activeLayer
            fun show(fitted: Tidied) {
                if (request != shapeRequest || draft == null || page.id != pageId || !isAttachedToWindow ||
                    !shapeRecognition || readOnly || inputBlocked || navigating ||
                    page.revision != revision || activeLayer != layer || shapeAssistOptions != options || draft?.tool != source.tool ||
                    !PageLayers.editable(page.layers, activeLayer)) return
                heldShape = snapShapes(fitted.strokes)
                shapeAdjustment = ShapeAdjustment(heldShape!!, shapeContact ?: drawing.points.last())
                removeCallbacks(longPressRunnable)
                onShapeAssistPreview(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                if (documentGesture) documentSamples(emptyList())
                invalidate()
            }
            InkGestureRecognizer.shape(drawing, options) { fitted ->
                if (fitted != null) show(fitted)
            }
        }
    }
    /** Geometry and repeated contact only: the deliberate hold is the confirmation. */
    private fun holdErase(source: Stroke): Boolean {
        if (!canClassifyScribble(source)) return false
        val (paths, notes) = scribblePaths(source.copy(points = source.points.toList()), localCapture?.let(::routedLocal))
        val after = ScribbleRecognition.prepare(mapOf(page.id to page), mapOf(page.id to paths),
            mapOf(page.id to notes), scribbleSensitivity, held = true)?.contents?.get(page.id) ?: return false
        val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(after.strokes) }
        heldErase = page.strokes.filter { it !in kept }
        removeCallbacks(longPressRunnable)
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        return true
    }
    private fun clearShapeHold() {
        heldErase = null
        shapeRequest++
        removeCallbacks(shapeHoldRunnable)
        shapeHold.reset()
        heldShape = null
        shapeAdjustment = null
        shapeContact = null
        shapeFitPointCount = 0
    }
    private fun trackShapeHold(event: MotionEvent, index: Int): Boolean {
        if (!holdRecognition || draft?.tool !in FREEHAND_TOOLS) return false
        var contact = point(event, index)
        if (rulerEdge != 0) contact = snapToRuler(contact)
        shapeContact = contact
        shapeAdjustment?.let { adjustment ->
            heldShape = snapShapes(adjustment.update(contact, ShapeHold.ADJUST_SLOP_DP * resources.displayMetrics.density / scale.coerceAtLeast(.001f)))
            invalidate()
            return false
        }
        var moved = false
        val slop = ShapeHold.SLOP_DP * resources.displayMetrics.density
        for (h in 0 until event.historySize) {
            moved = shapeHold.sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                event.getHistoricalEventTime(h), slop) || moved
        }
        moved = shapeHold.sample(event.getX(index), event.getY(index), event.eventTime, slop) || moved
        if (moved) {
            shapeRequest++; shapeFitPointCount = draft!!.points.size
            // Scrubbing on after the hold cancels it; the stroke is ordinary ink again until held anew.
            if (heldErase != null) { heldErase = null; invalidate() }
        }
        removeCallbacks(shapeHoldRunnable)
        shapeHold.remaining(SystemClock.uptimeMillis(), shapeHoldMs)?.let { postDelayed(shapeHoldRunnable, it) }
        return moved
    }
    private var erasing: List<Stroke>? = null
    private var lasso: List<InkPoint>? = null
    private var selection: List<Stroke> = emptyList()
    private var selectedTexts: List<TextBox> = emptyList()
    private var selectedImages: List<PageImage> = emptyList()
    internal var selectionSnapBypassed = false
    private var selectionSnapX: List<Pair<Float, Float>> = emptyList()
    private var selectionSnapY: List<Pair<Float, Float>> = emptyList()
    private var rawSelectionDx = 0f; private var rawSelectionDy = 0f
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
    internal var onObjectLift: ((ObjectLift?) -> Unit)? = null
    internal var onObjectDrop: ((ObjectLift) -> Boolean)? = null
    private var objectLift: ObjectLift? = null
    private var liftSelection: CanvasSelection? = null
    private var liftBounds: Rect? = null
    private var carriedBaseSource: NotePage? = null
    private var carriedBaseSelection: CanvasSelection? = null
    private var carriedBasePage: NotePage? = null

    private fun liftObjects(event: MotionEvent, index: Int, picked: CanvasSelection, dx: Float, dy: Float) {
        val report = onObjectLift ?: return
        if (picked.isEmpty() || page.infinite) return
        if (liftSelection != picked) {
            liftSelection = picked
            liftBounds = InkGeometry.contentBounds(picked.strokes, picked.texts, picked.images,
                InkRenderer::textHeight, page.width, page.height)
        }
        val bounds = liftBounds ?: return
        val where = IntArray(2).also { getLocationInWindow(it) }
        val bitmaps = imageBitmaps
        val held = ObjectLift(picked, where[0] + originX + (bounds.left + dx) * scale,
            where[1] + originY + (bounds.top + dy) * scale,
            bounds.right - bounds.left, bounds.bottom - bounds.top, scale,
            where[0] + event.getX(index), where[1] + event.getY(index)) { canvas ->
            canvas.save(); canvas.translate(-bounds.left, -bounds.top)
            picked.images.forEach { image -> bitmaps[image.id]?.let { InkRenderer.image(canvas, it, image) } }
            picked.strokes.forEach { InkRenderer.drawRendered(canvas, it, renderedOf(it)) }
            picked.texts.forEach { InkRenderer.text(canvas, it) }
            canvas.restore()
        }
        objectLift = held
        report(held)
    }

    /** Scrolling can move the source view even while the pointer is held still. */
    private fun positionObjectsOnRelease(event: MotionEvent) {
        val held = objectLift ?: return
        val bounds = liftBounds ?: return
        val index = event.findPointerIndex(pointerId).coerceAtLeast(0)
        val where = IntArray(2).also { getLocationInWindow(it) }
        val left = held.left + where[0] + event.getX(index) - held.fingerX
        val top = held.top + where[1] + event.getY(index) - held.fingerY
        val dx = (left - where[0] - originX) / scale - bounds.left
        val dy = (top - where[1] - originY) / scale - bounds.top
        if (movingImage != null) movingImage = held.selection.images.firstOrNull()?.moved(dx, dy)
        else if (movingText != null) { textDx = dx; textDy = dy }
        else if (movingSelection) { selectionDx = dx; selectionDy = dy }
        liftObjects(event, index, held.selection, dx, dy)
    }

    private fun endObjectLift() {
        val held = objectLift
        objectLift = null; liftSelection = null; liftBounds = null
        carriedBaseSource = null; carriedBaseSelection = null; carriedBasePage = null
        if (held != null) onObjectLift?.invoke(null)
    }

    private fun dropObjects(): Boolean {
        val held = objectLift
        val accepted = held != null && onObjectDrop?.invoke(held) == true
        endObjectLift()
        return accepted
    }
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
        set(value) { field = value; reportEraserBrush() }
    /**
     * When set, the editor draws the eraser ring itself, at the pen's window position, so it can leave
     * this page; the view then only says how big the ring is and whether it is cutting.
     */
    internal var onEraserBrush: ((EraserBrush?) -> Unit)? = null
    private var reportedBrush: EraserBrush? = null
    private fun reportEraserBrush() {
        val report = onEraserBrush ?: return
        val at = eraserMark
        val next = at?.let { EraserBrush(eraserRadiusAt(it) * scale, eraserWholeStroke, erasing != null) }
        if (next != reportedBrush) { reportedBrush = next; report(next) }
    }
    /** The laser tool's fading presenter trail; see [LaserTrail]. */
    private val laser = LaserTrail()
    /**
     * Straightedge switch from the toolbar. While on and no [ruler] has been placed on this page yet,
     * the view puts one across the middle of what is on screen and reports it through [onRulerChanged].
     */
    var rulerEnabled = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) endRulerGesture()
            invalidate()
        }
    /** The straightedge on this page, or null; shown only while [rulerEnabled]. Session-only. */
    var ruler: RulerLine? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    /** Reports each move of the ruler, so the editor keeps the place it was put. */
    var onRulerChanged: (RulerLine) -> Unit = {}
    private val shownRuler: RulerLine? get() = if (rulerEnabled) ruler else null
    /** Fingers holding the ruler (one slides it, two turn it), by pointer id, with their last positions. */
    private val rulerFingers = LinkedHashMap<Int, InkPoint>()
    /** The ruler as the fingers have actually turned it, before the angle settles on a nearby step. */
    private var rulerRaw: RulerLine? = null
    /** The edge (-1 top, +1 bottom) the stroke in progress runs along, or 0 when it is freehand. */
    private var rulerEdge = 0
    private val rulerPainter = RulerPainter()
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
    private val eraserRing = EraserRingPainter()
    private val chrome = SelectionChromePainter()
    // Reused across onDraw frames so selection previews and writing lanes allocate nothing per frame.
    private val selectionPreviewMatrix = Matrix()
    /** The throwaway tail drawn ahead of the live pen tip; see [PenTipPrediction]. */
    private val prediction = PenTipPrediction()
    /** The optional front-buffered layer drawing this view's pen stroke ahead of the page, and how much of the stroke it has. */
    private var lowLatency: LowLatencyInk? = null
    private var lowLatencySent = 0
    private val lowLatencyClip = RectF()
    private val visibleScratch = android.graphics.Rect()
    private val followOverlay = FollowOverlayPainter()
    /** The answer-area box takes the app's accent, so it reads as part of the theme rather than as ink. */
    var answerAreaColor = 0xFF387C83.toInt()
        set(value) { if (field != value) { field = value; invalidate() } }
    /** False hides the box; an area being dragged out is always shown, so selecting one still works. */
    var showAnswerAreas = true
        set(value) { if (field != value) { field = value; invalidate() } }
    val writingFollow = WritingFollow()
    private val followRhythm = FollowRhythm()
    private var guideRegions: List<WritingLane> = emptyList()
    var writingGuides: List<WritingGuide> = emptyList()
        set(value) {
            if (field != value) { field = value; guideRegions = WritingGuides.regions(value) }
        }
    private var lineAdvance: WritingAdvance? = null
    var followPreferences = FollowPreferences()
        set(value) {
            if (field != value) { holdWritingFollow(); field = value }
        }
    var writingIndent: Float? = null
    var writingRegion: WritingLane? = null
        set(value) { if (field != value) { suspendWritingFollow(); writingIndent = null; field = value; invalidate() } }
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
        set(value) { if (field != value) { field = value; onFollowLanding(value); invalidate() } }
    var onFollowLanding: (WritingGuide?) -> Unit = {}
    var followLandingPreview: WritingGuide? = null
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
            if (field != value) {
                if (value?.continues(field) != true) suspendWritingFollow()
                if (field?.column != value?.column || field?.direction != value?.direction) writingIndent = null
                field = value; invalidate()
            }
        }
    /** The response's first stroke places its column ([CanvasWritingSession.placedAt]). */
    var onCanvasWritingSession: (CanvasWritingSession) -> Unit = {}
    private val canFollow get() = followEnabled && (!page.infinite || canvasWritingSession != null)
    /** A response waiting for its first stroke has no line to move from yet. */
    private fun responseUnplaced(): Boolean {
        if (!page.infinite || canvasWritingSession?.placed != false) return false
        reportFollowStatus(UNPLACED_RESPONSE, attention = true)
        return true
    }
    private val responseStart get() = if (page.infinite) canvasWritingSession?.startX else null
    private fun automaticFollowReturn(region: WritingLane): WritingAdvance? = writingFollow.returnFor(
        region, writingGuides, followPreferences, responseStart, writingIndent)
    /**
     * A canvas has no edge to stop a pan, so a screen-position target would put the response's
     * margin mid-screen. In a response, a proposed sideways pan [dx] frames the column instead.
     * Needs [followVisible] measured.
     */
    private fun responseDx(dx: Float): Float {
        val session = canvasWritingSession?.takeIf { page.infinite }
            ?: if (zoomWritingPane) CanvasWritingSession(followRegion(), followPreferences.direction) else return dx
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
        writingIndent ?: responseStart ?: writingFollow.state.lineStartX, followPreferences.direction,
        overrideStart = writingIndent != null)

    fun nextWritingLine(paragraph: Boolean = false) {
        if (!canFollow || isWritingGesture || inputBlocked || readOnly || lineAdvance != null || responseUnplaced()) return
        val region = followRegion()
        val baseline = writingFollow.state.baselineY ?: followLastPoint?.y
            ?: if (zoomWritingPane) {
                val anchor = visibleWritingWindow()
                val y = anchor.top + (anchor.bottom - anchor.top) * .4f
                writingGuides.filter { it.y in region.top..region.bottom && it.right > anchor.left && it.left < anchor.right }
                    .minByOrNull { kotlin.math.abs(it.y - y) }?.y ?: y
            } else ((if (page.infinite) currentPeekAnchor()?.top ?: 0f else region.top) + followPreferences.spacing)
        val first = nextFollowLine(baseline, region)
        val next = if (paragraph) first?.let { nextFollowLine(it.to.y, region) } else first
        if (next == null) { reportFollowStatus("End of answer area", attention = true); return }
        followPaused = false; writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
        pendingReturn = null
        startLineAdvance(next, manual = true)
    }
    fun indentWritingLine(reset: Boolean = false) {
        if (!canFollow || isWritingGesture || inputBlocked || readOnly || responseUnplaced()) return
        holdWritingFollow()
        val region = followRegion()
        val ltr = followPreferences.direction == WritingDirection.LTR
        val start = if (ltr) region.left else region.right
        val room = minOf(region.width * .5f, maxOf(64f, followPreferences.spacing * 2f))
        writingIndent = if (reset) null else ((writingIndent ?: start) +
            followPreferences.spacing * if (ltr) 1f else -1f).coerceIn(
                region.left + if (ltr) 0f else room, region.right - if (ltr) room else 0f)
        if (reset) writingFollow.state = writingFollow.state.copy(lineStartX = start, textStartX = null, markerEdge = null)
        reportFollowStatus(if (reset) "Indent reset · next lines use the margin" else "Indent set for next lines")
    }
    /** Goes up one line, to correct or add to it; the opposite of [nextWritingLine]. */
    fun previousWritingLine() {
        if (!canFollow || isWritingGesture || inputBlocked || readOnly || lineAdvance != null || responseUnplaced()) return
        val region = followRegion()
        val baseline = writingFollow.state.baselineY ?: followLastPoint?.y
            ?: run { reportFollowStatus("Write a line first", attention = true); return }
        val previous = FollowNavigation.previous(baseline, region, writingGuides, writingFollow.lineSpacing(followPreferences.spacing,
            followPreferences.adaptiveSpacing && followPreferences.mode == FollowMode.TEXT),
            responseStart ?: writingFollow.state.lineStartX, followPreferences.direction)
            ?: run { reportFollowStatus("Already on the first line", attention = true); return }
        followPaused = false; writingFollow.state = writingFollow.state.copy(suspendedUntil = 0)
        pendingReturn = null
        startLineAdvance(previous, manual = true)
    }
    /** Reverses follow moves one at a time, newest first; navigation clears the history. */
    fun backWritingView() {
        if (isWritingGesture || inputBlocked || readOnly) return
        val back = followBack.pop() ?: run { reportFollowStatus("No previous follow movement"); return }
        val dx = -back.x; val dy = -back.y
        // Keep the older moves: a suspend would clear them, so stop motion without it.
        followPaused = true
        cancelFollowMotion()
        automaticReturnTarget.clear()
        landingGuide = null
        if (page.infinite || cameraPage) { camera.pan(dx, dy); reportCanvasViewport(); invalidate() } else onFollowPan(dx, dy)
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
    private val previousFollowVisible = android.graphics.Rect()
    private val nextFollowVisible = android.graphics.Rect()
    private val followViewportListener = android.view.ViewTreeObserver.OnPreDrawListener {
        // The document host pans finite pages by moving their AndroidView. Android can
        // reuse that view's display list, including a strip painted at the old edge.
        // Measure after parent placement and repaint only when its visible bounds change.
        if (canFollow && followPreferences.horizontalFollow && followPreferences.mode == FollowMode.TEXT) {
            if (!getLocalVisibleRect(nextFollowVisible)) nextFollowVisible.setEmpty()
            if (nextFollowVisible != previousFollowVisible) {
                previousFollowVisible.set(nextFollowVisible)
                invalidate()
            }
        }
        true
    }
    private val followGlide = FollowGlide()
    private val automaticReturnTarget = FollowReturnTarget()
    private var captureFollowBack = false
    private var manualReveal = false
    private fun cancelFollowMotion() {
        pendingReturn = null; followBack.cancelPending()
        followGlide.cancel()
        lineAdvance = null; captureFollowBack = false; resumeFollowAfterMark = false
        manualReveal = false
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
        if (!responseUnplaced()) reportFollowStatus("Write to start following")
    }
    /**
     * Stops any movement but keeps the line being written: switching to the eraser or lasso and
     * back is part of writing, not navigation, so the baseline, frontier and Back history stay.
     */
    fun holdWritingFollow() {
        if (pendingReturn == null && !followGlide.active && lineAdvance == null) return
        cancelFollowMotion()
        landingGuide = null
        reportFollowStatus(if (followManuallyPaused) "Paused · tap Resume when ready" else "Held · write to continue")
    }
    fun suspendWritingFollow(clearBack: Boolean = true) {
        followPaused = true
        cancelFollowMotion()
        automaticReturnTarget.clear()
        if (clearBack) followBack.clear()
        landingGuide = null
        followRhythm.reset()
        writingFollow.suspend(SystemClock.uptimeMillis())
        if (page.infinite) followLastPoint = null
        reportFollowStatus(if (followManuallyPaused) "Paused · tap Resume when ready" else "Follow paused · write to resume")
    }
    var inputBlocked = false
        set(value) {
            if (field == value) return
            if (value) inputRouter.reset(::handleTouchEvent)
            field = value
        }
    val isWritingGesture get() = draft != null || erasing != null || lasso != null || documentGesture
    /** The linked lower editor owns ink; the overview only renders this transient preview. */
    var liveInkPreview: Stroke? = null
        set(value) { field = value; invalidate() }
    var onLiveInk: (Stroke?, Boolean) -> Unit = { _, _ -> }
    var zoomWritingPane = false
    /** The linked overview can navigate and move its source frame, but cannot edit page content. */
    var navigationOnly = false
    var writingWindow: PeekAnchor? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var onWritingWindowRequest: (PeekAnchor) -> Unit = {}
    var onWritingWindowChanged: (PeekAnchor) -> Unit = {}
    private var windowDrag: PeekAnchor? = null
    private var windowDragStart: InkPoint? = null
    private var resizingWindow = false
    private var writingWindowTarget: PeekAnchor? = null
    private var lastWritingWindow: PeekAnchor? = null
    private var lastWritingScale = 1f

    /** An explicit relocation; normal follow only changes the camera, never this target. */
    fun frameWritingWindow(anchor: PeekAnchor) {
        if (anchor == writingWindowTarget || isWritingGesture || anchor.pageId != page.id) return
        writingWindowTarget = anchor
        suspendWritingFollow()
        if (width > 0 && height > 0) { fitPeekAnchor(anchor); reportCanvasViewport() }
    }

    private fun visibleWritingWindow(): PeekAnchor = PeekAnchor(page.id, -originX / scale, -originY / scale,
        (width - originX) / scale, (height - originY) / scale)

    /** The overview stays still until the linked source window leaves its visible area. */
    fun revealWritingWindow(anchor: PeekAnchor, topInset: Float) {
        if (isWritingGesture || windowDrag != null || anchor.pageId != page.id || !getLocalVisibleRect(followVisible)) return
        val padding = 16f * resources.displayMetrics.density
        val left = originX + anchor.left * scale; val right = originX + anchor.right * scale
        val top = originY + anchor.top * scale; val bottom = originY + anchor.bottom * scale
        val visibleTop = minOf(followVisible.bottom - padding, followVisible.top + topInset + padding)
        fun shift(from: Float, to: Float, start: Float, end: Float): Float = when {
            to - from > end - start -> start - from
            from < start -> start - from
            to > end -> end - to
            else -> 0f
        }
        val dx = shift(left, right, followVisible.left + padding, followVisible.right - padding)
        val dy = shift(top, bottom, visibleTop, followVisible.bottom - padding)
        if (kotlin.math.abs(dx) < 1f && kotlin.math.abs(dy) < 1f) return
        if (page.infinite || cameraPage) { camera.pan(dx, dy); reportCanvasViewport(); invalidate() }
        else onFollowPan(dx, dy)
    }

    /** Manual reveal and automatic following share the same movement history. */
    fun revealWritingAhead() {
        if (!canFollow || isWritingGesture || inputBlocked || readOnly || !getLocalVisibleRect(followVisible) || responseUnplaced()) return
        val anchor = visibleWritingWindow()
        val shift = ZoomWriting.reveal(WritingLane(anchor.left, anchor.top, anchor.right, anchor.bottom),
            followRegion(), followPreferences.direction)
        if (shift == 0f) { reportFollowStatus("Answer margin reached · use Next line", attention = true); return }
        cancelFollowMotion()
        followPaused = false
        followBack.begin(writingFollow.state)
        followGlide.start(-shift * scale, 0f, SystemClock.uptimeMillis(), 0, followPreferences.glideDurationMs,
            followVisible.width().toFloat(), followVisible.height().toFloat(), instant = reducedMotion)
        reportFollowStatus("More room on this line")
        manualReveal = true
        scheduleFollow()
    }
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
    private var pendingRegion: PeekAnchor? = null
    /** Frames [anchor] in the editable view ("go to" from a peek), once the view has a size if it has none yet. */
    fun showRegion(anchor: PeekAnchor) {
        if (anchor.pageId != page.id || isWritingGesture) return
        suspendWritingFollow()
        if (width > 0 && height > 0) { pendingRegion = null; fitPeekAnchor(anchor); reportCanvasViewport() }
        else pendingRegion = anchor
    }
    fun fitPeekAnchor(anchor: PeekAnchor) {
        if (width == 0 || height == 0 || anchor.pageId != page.id) return
        val base = if (page.infinite) 1f else pageScale
        val z = minOf(width / (anchor.right - anchor.left), height / (anchor.bottom - anchor.top)) / base
        val ox = if (page.infinite) 0f else (width - page.width * base) / 2
        val oy = if (page.infinite) 0f else (height - page.height * base) / 2
        val zoom = z.coerceIn(.1f, camera.maxZoom)
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
            if (isWritingGesture || selectingWritingRegion || followPaused || (followManuallyPaused && lineAdvance == null && !manualReveal)) return
            pendingReturn?.let { pending ->
                val remaining = returnStartAt - now
                if (remaining > 0) {
                    reportFollowStatus("Next line in ${FollowPreferences.returnDelayLabel(remaining.toInt())} · touch down to cancel")
                    postDelayed(this, minOf(remaining, 100L)); return
                }
                pendingReturn = null
                startLineAdvance(pending, manual = false)
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
            val applied = if (page.infinite || cameraPage) {
                val x = camera.x; val y = camera.y
                camera.pan(step.dx, step.dy); reportCanvasViewport(); invalidate()
                (camera.x - x) to (camera.y - y)
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
        if (arrived) {
            writingFollow.arrived(advance!!, followPreferences.direction); landingGuide = advance.to
            automaticReturnTarget.clear()
        }
        if (advance == null) writingFollow.placed()
        val moved = followGlide.moved
        if (moved) followRhythm.glided()
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
        val delay = followRhythm.returnDelay(followPreferences.automaticReturnDelayMs)
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
    private fun startLineAdvance(advance: WritingAdvance, manual: Boolean) {
        if (!getLocalVisibleRect(followVisible)) return
        followBack.begin(writingFollow.state)
        val target = followPreferences.horizontalTarget(writingHand)
        val desiredX = followVisible.left + followVisible.width() * target
        val desiredY = followVisible.top + followVisible.height() * followPreferences.position
        val dx = if (followPreferences.mode == FollowMode.MATH) 0f else responseDx(desiredX -
            (originX + advance.startX(if (followPreferences.direction == WritingDirection.LTR) WritingHand.RIGHT else WritingHand.LEFT) * scale))
        val nextY = originY + advance.to.y * scale
        val dy = if (manual) {
            automaticReturnTarget.clear()
            desiredY - nextY
        } else {
            val targetY = automaticReturnTarget.target(advance, originY + advance.from.y * scale, desiredY)
            (targetY - nextY).coerceAtMost(0f)
        }
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
    var maxZoom: Float
        get() = camera.maxZoom
        set(value) {
            val limit = AppPrefs.maxZoom(value)
            if (camera.maxZoom == limit) return
            camera.maxZoom = limit
            camera.scaleBy(1f, width / 2f, height / 2f)
            reportCanvasViewport()
            invalidate()
        }
    var onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {}
    /**
     * Whether a zoomed page card is panned flush against its left / right side (both when it is
     * narrower than the view). The one-page editor lets a swipe turn the page only from such an edge.
     */
    var onPageEdges: (left: Boolean, right: Boolean) -> Unit = { _, _ -> }
    private fun reportPageEdges() {
        if (!pageCamera || zoomWritingPane || page.infinite || width <= 0 || page.width <= 0f) return
        val zoom = camera.zoom.coerceIn(1f, camera.maxZoom)
        val ws = workspace()
        val fitted = pageScale
        val spanW = (ws[2] - ws[0]) * fitted * zoom
        val left = (width - (ws[2] - ws[0]) * fitted) / 2 * zoom + camera.x
        val slack = 2f
        onPageEdges(left >= -slack, left + spanW <= width + slack)
    }
    /**
     * The paper plus any sticky notes hanging past it, in page units (left, top, right, bottom).
     * A one-page sheet fits and pans over all of it, so a note beside the paper stays reachable. The
     * read-only neighbour sliding in during a swipe uses the same fit, so it never refits when it lands.
     * Reference and peek views see just the paper.
     */
    private fun workspace(): FloatArray {
        val flags = (if (pageCamera) 1 else 0) + (if (zoomWritingPane) 4 else 0)
        if (workspaceSource === page && workspaceFlags == flags) return workspaceValue
        var l = 0f; var t = 0f; var r = page.width; var b = page.height
        if (pageCamera && !zoomWritingPane && !page.infinite) page.texts.forEach { box ->
            if (!box.isSticky) return@forEach
            l = min(l, box.x); t = min(t, box.y); r = max(r, box.x + box.width); b = max(b, box.y + box.stickyHeight)
        }
        workspaceValue = floatArrayOf(l, t, r, b); workspaceSource = page; workspaceFlags = flags
        return workspaceValue
    }
    private var workspaceSource: NotePage? = null
    private var workspaceFlags = -1
    private var workspaceValue = floatArrayOf(0f, 0f, 0f, 0f)
    /**
     * True when a touch at this window position lands on the focused note's resize grip or, if
     * [bodies] is set, on any sticky note: a finger there is moving, resizing or typing, never turning a page.
     */
    fun touchesNote(windowX: Float, windowY: Float, bodies: Boolean = true): Boolean {
        val at = IntArray(2).also(::getLocationInWindow)
        return touchesLocalNote(windowX - at[0], windowY - at[1], bodies)
    }
    internal fun touchesLocalNote(x: Float, y: Float, bodies: Boolean = true): Boolean {
        if (page.infinite || width <= 0) return false
        val px = (x - originX) / scale; val py = (y - originY) / scale
        if (bodies && visibleNotes().any { StickyNotes.contains(it, px, py) }) return true
        val focus = stickyNotes.focused() ?: return false
        // A picked-up note is dragged by a finger whatever the tool.
        if (StickyNotes.contains(focus, px, py)) return true
        if (stickyNotes.typing) return false
        return hypot(x - (originX + (focus.x + focus.width) * scale), y - (originY + (focus.y + focus.stickyHeight) * scale)) <= 28f * resources.displayMetrics.density
    }
    /** Keeps a zoomed score on its sheet: never smaller than the fit, never panned past an edge. */
    private fun clampPageCamera() {
        if (zoomWritingPane) return
        if (!pageCamera || page.infinite || width <= 0 || height <= 0 || page.width <= 0f || page.height <= 0f) return
        val ws = workspace()
        val zoom = camera.zoom.coerceIn(1f, camera.maxZoom)
        val fitted = pageScale
        val pageW = (ws[2] - ws[0]) * fitted * zoom
        val pageH = (ws[3] - ws[1]) * fitted * zoom
        val baseX = (width - (ws[2] - ws[0]) * fitted) / 2 * zoom
        val baseY = (height - (ws[3] - ws[1]) * fitted) / 2 * zoom
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
        reportPageEdges()
        if (zoomWritingPane && width > 0 && height > 0) {
            lastWritingWindow = visibleWritingWindow()
            lastWritingScale = scale
            onWritingWindowChanged(lastWritingWindow!!)
        }
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
        if (zoomWritingPane && isWritingGesture && (w != oldw || h != oldh)) {
            inputRouter.reset(::handleTouchEvent)
            onLiveInk(null, false)
        }
        prewarmInk()
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) suspendWritingFollow(clearBack = false)
        if (zoomWritingPane) {
            val previous = lastWritingWindow
            if (previous != null) {
                val unit = lastWritingScale
                fitPeekAnchor(PeekAnchor(page.id, previous.left,
                    previous.top, previous.left + w / unit, previous.top + h / unit))
            } else writingWindowTarget?.let(::fitPeekAnchor)
        } else (peekRegion ?: pendingRegion?.takeIf { it.pageId == page.id })?.let(::fitPeekAnchor)
        pendingRegion = null
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
    private val pageScale get() = if (zoomWritingPane) (width / page.width).coerceAtLeast(.01f)
        else if (!readOnly && !page.infinite && documentPaperWidth > 0f)
        (documentPaperWidth / page.width).coerceAtLeast(.01f)
        else workspace().let { ws -> min(width / (ws[2] - ws[0]), height / (ws[3] - ws[1])).coerceAtLeast(.01f) }
    private val originX get() = if (page.infinite) camera.x else if (cameraPage) { val ws = workspace(); (width - (ws[2] - ws[0]) * pageScale) / 2 * camera.zoom + camera.x - ws[0] * scale } else (width - page.width * scale) / 2
    private val originY get() = if (page.infinite) camera.y else if (cameraPage) { val ws = workspace(); (height - (ws[3] - ws[1]) * pageScale) / 2 * camera.zoom + camera.y - ws[1] * scale } else if (documentPaperWidth > 0f) -documentTop * scale else (height - page.height * scale) / 2
    init {
        isFocusable = true; contentDescription = "Notebook page. Draw with a pen or finger. Palm touches are ignored while you write with a stylus. Use two fingers to zoom and pan."
    }
    /**
     * Hover arms proximity rejection before contact; EXIT retains a grace period because Android
     * also sends it when the tip lands. The router cancels an earlier touch without committing it.
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        inputRouter.hover(event, ::handleTouchEvent)
        if ((0 until event.pointerCount).any { isStylus(event, it) }) {
            val leaving = event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
            // Hovering with the eraser previews the same outline it will cut with, before the tip lands.
            if (!leaving && !readOnly && markZones.isNotEmpty()) {
                val at = point(event, 0)
                val zone = markZones.lastOrNull { it.contains(at.x, at.y) }
                val now = SystemClock.uptimeMillis()
                // Re-offered while hovering so the chip's timeout restarts, but never faster than a glance.
                if (zone != null && now - lastZoneOfferAt > 150L) offerZone(zone)
            }
            if (leaving) {
                if (eraserMark != null) { eraserMark = null; if (onEraserBrush == null) invalidate() }
                if (laser.hover != null) { laser.hover = null; invalidate() }
            } else if (tool == Tool.LASER) {
                laser.hover = point(event, 0); invalidate()
            } else if (tool == Tool.ERASER) {
                val next = clampToPage(point(event, 0))
                val last = eraserMark
                if (last == null || hypot(next.x - last.x, next.y - last.y) > .75f) { eraserMark = next; if (onEraserBrush == null) invalidate() }
            }
        }
        return if ((0 until event.pointerCount).any { isStylus(event, it) }) true else super.onHoverEvent(event)
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
            windowDrag = null; windowDragStart = null
            writingWindowTarget = null; lastWritingWindow = null
            liveInkPreview = null; followLandingPreview = null
            inputRouter.reset(::handleTouchEvent)
            stickyNotes.reset(clearDrop = true)
            followPaused = false; followLastPoint = null
            followBack.clear(); writingFollow.state = WritingFollowState(); cancelFollowMotion()
            automaticReturnTarget.clear()
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
        post { stickyNotes.refresh() }
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
            selectionDx = 0f; selectionDy = 0f; rawSelectionDx = 0f; rawSelectionDy = 0f
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
    private val visibleInkBounds = android.graphics.Rect()
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
    private var taperVersion = InkTaper.version
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
        // Only a scale change softens ink: a pan at a steady scale keeps the sharp overscanned raster
        // below, and a raster that no longer covers the screen is rebuilt off the UI thread meanwhile.
        val preview = zoomRenderState.usePreview(scale, lineAdvance != null, SystemClock.uptimeMillis())
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
                    // Rasterize the stable visible viewport (plus overscan) instead, so committed ink
                    // remains a single bitmap draw throughout the gesture even on a dense page, and a
                    // follow glide pans across pixels already drawn. While a dense page's raster is
                    // being built, the soft whole-page preview stands in so revealed ink never pops in.
                    updateStableInkBounds(content)
                    committedInk.draw(inkCanvas, content.strokes, scale, ::boundsOf, ::renderedOf,
                        rasterViewport = stableInkBounds, deferred = !content.infinite || navigating, visible = visibleInkBounds,
                        placeholder = { drawNavigationInk(it, content) })
                }
            })
    }

    /**
     * [visibleInkBounds] is what the view shows; [stableInkBounds] adds overscan around it (as much
     * as the raster budget allows), so camera pans and follow glides reuse one raster instead of
     * rebuilding it every frame.
     */
    private fun updateStableInkBounds(content: NotePage) {
        val left = floor(-originX / scale).toInt()
        val top = floor(-originY / scale).toInt()
        val right = ceil((width - originX) / scale).toInt()
        val bottom = ceil((height - originY) / scale).toInt()
        clampInkBounds(content, visibleInkBounds, left, top, right, bottom)
        val w = right - left; val h = bottom - top
        for (fraction in INK_OVERSCAN_STEPS) {
            val dx = (w * fraction).toInt(); val dy = (h * fraction).toInt()
            clampInkBounds(content, stableInkBounds, left - dx, top - dy, right + dx, bottom + dy)
            val pixels = (stableInkBounds.width() * scale.toDouble()) * (stableInkBounds.height() * scale.toDouble())
            if (pixels <= INK_OVERSCAN_MAX_PIXELS) return
        }
    }

    private fun clampInkBounds(content: NotePage, out: android.graphics.Rect, left: Int, top: Int, right: Int, bottom: Int) {
        if (content.infinite) out.set(left, top, right, bottom)
        else out.set(left.coerceAtLeast(0), top.coerceAtLeast(0),
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

    /** A formula in a text box or note finished rendering: repaint so it replaces its typed source. */
    private val mathReady: () -> Unit = { postInvalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scribbleToErase) InkGestureRecognizer.prepare(InkGestureRecognizer.Model.GESTURES)
        if (shapeRecognition) InkGestureRecognizer.prepare(InkGestureRecognizer.Model.SHAPES)
        previousFollowVisible.setEmpty()
        viewTreeObserver.addOnPreDrawListener(followViewportListener)
        (rootView as? ViewGroup)?.let(TextMathBitmaps::attach)
        TextMathBitmaps.addListener(mathReady)
        inputRouter.stylus.attachNavigationSurface()
        inputRouter.stylus.addArrivalListener(penArrival)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(followViewportListener)
        TextMathBitmaps.removeListener(mathReady)
        inputRouter.stylus.detachNavigationSurface()
        inputRouter.stylus.removeArrivalListener(penArrival)
        recentFingerInk.clear()
        // A router reset emits CANCEL, which would discard the held position before the drop.
        stickyNotes.dropLift()
        inputRouter.reset(::handleTouchEvent)
        onLiveInk(null, false)
        onFollowLanding(null)
        endObjectLift()
        stickyNotes.reset(clearDrop = true)
        removeCallbacks(followFrame)
        removeCallbacks(reportMeasurement)
        removeCallbacks(reportTextFrame)
        removeCallbacks(longPressRunnable)
        clearPenStartHold()
        clearShapeHold()
        if (eraserMark != null) eraserMark = null
        clearInkLayers(); renderCache.clear(); boundsCache.clear(); restCache = null; restCacheKeyPage = null
        resetDraftGeometry()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (taperVersion != InkTaper.version) {
            // The stroke-end look changed in Settings: everything drawn under the old look is rebuilt.
            taperVersion = InkTaper.version
            renderCache.clear(); clearInkLayers(); InkRasterStore.clear()
        }
        followTextEditor()
        reportShapeMeasurement()
        canvas.drawColor(canvasBackgroundColor)
        canvas.save(); canvas.translate(originX, originY); canvas.scale(scale, scale)
        if (!page.infinite) canvas.drawRect(-1f, -1f, page.width + 2f, page.height + 3f, shadowPaint)
        canvas.save()
        if (!page.infinite) canvas.clipRect(0f, 0f, page.width, page.height)
        val layered = documentContentPreview?.let { PageLayers.view(it) } ?: layerView()
        if (paperLayerSource !== layered) {
            paperLayerSource = layered
            // Sticky notes may cross the paper edge, so they draw after the page clip below.
            paperLayerValue = if (layered.texts.any { it.isSticky || it.id == editingText?.id })
                layered.copy(texts = layered.texts.filterNot { it.isSticky || it.id == editingText?.id }) else layered
        }
        val shown = paperLayerValue!!
        val visible = if (erasing != null && !documentSpanning) shown.copy(strokes = PageLayers.viewStrokes(erasing!!, page.layers)) else shown
        // A text box follows the finger while it is dragged, before the move is committed.
        val dragging = movingText
        val laid = if (dragging == null) visible else visible.copy(texts = visible.texts.map { if (it.id == dragging.id) it.moved(textDx, textDy) else it })
        // A picture follows the finger the same way, so a move or resize reads live.
        val liveImage = movingImage
        val previewPage = if (liveImage == null) laid else laid.copy(images = laid.images.map { if (it.id == liveImage.id) liveImage else it })
        val carried = objectLift?.selection
        val placed = if (carried == null) previewPage
        else if (carriedBaseSource === visible && carriedBaseSelection == carried) carriedBasePage!!
        else visible.copy(
            texts = visible.texts.filterNot { box -> carried.texts.any { it.id == box.id } },
            images = visible.images.filterNot { image -> carried.images.any { it.id == image.id } }).also {
            carriedBaseSource = visible; carriedBaseSelection = carried; carriedBasePage = it
        }
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
        if (objectLift == null && selection.isNotEmpty()) {
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
        if (objectLift == null && selectedTexts.isNotEmpty()) {
            canvas.save(); canvas.translate(selectionDx, selectionDy)
            selectedTexts.filterNot { it.isSticky }.forEach {
                InkRenderer.text(canvas, it)
                drawTextBox(canvas, it)
            }
            canvas.restore()
        }
        if (objectLift == null && selectedImages.isNotEmpty()) {
            canvas.save(); canvas.translate(selectionDx, selectionDy)
            selectedImages.forEach {
                imageBitmaps[it.id]?.let { bitmap -> InkRenderer.image(canvas, bitmap, it) }
                drawImageSelection(canvas, it, withHandle = false)
            }
            canvas.restore()
        }
        if (objectLift == null && hasSelection()) drawSelectionFrame(canvas)
        if (previewing) canvas.restore()
        liveInkPreview?.takeIf { PageLayers.editable(page.layers, it.layer) }?.let { InkRenderer.stroke(canvas, it) }
        // Page ink in progress draws here, under the notes, exactly where it will land; ink being
        // written into a note draws with that note instead (see the notes below).
        if (noteTarget == null && !documentSpanning) drawDraft(canvas)
        if (!documentSpanning) lasso?.takeIf { it.size > 1 }?.let { drawLasso(canvas, it) }
        if (documentInkPreview.isNotEmpty() || documentLassoPreview.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, 0f, page.width, page.height)
            documentInkPreview.forEach { stroke ->
                if (stroke.tool != Tool.PEN && stroke.tool != Tool.HIGHLIGHTER) { InkRenderer.stroke(canvas, stroke); return@forEach }
                val geometry = if (stroke.tool == Tool.HIGHLIGHTER)
                    documentHighlightBuilders.getOrPut(stroke.points) { InkRenderer.IncrementalHighlighterStroke() }.update(stroke.points)
                    else documentPenBuilders.getOrPut(stroke.points) { InkRenderer.IncrementalPenStroke() }.update(stroke.points)
                InkRenderer.drawRendered(canvas, stroke, geometry)
            }
            documentPreviewLiveStroke?.takeIf { it.tool == Tool.PEN }?.let { prediction.drawGuess(canvas, it, scale) }
            if (documentLassoPreview.size > 1) drawLasso(canvas, documentLassoPreview)
            canvas.restore()
        }
        dragging?.takeIf { objectLift == null }?.let { drawTextBox(canvas, it.moved(textDx, textDy)) }
        // The selected picture keeps its outline while another picture is dragged, unless it is
        // part of the lasso selection, which already draws its own outline at the drag offset.
        val outlined = selectedImageId?.let { id ->
            (liveImage?.takeIf { it.id == id } ?: placed.images.find { it.id == id })
                ?.takeIf { hand -> selectedImages.none { it.id == hand.id } }
        }
        outlined?.takeIf { objectLift == null }?.let { if (cropImageId == null) drawImageSelection(canvas, it) }
        cropImageId?.let { id -> placed.images.find { it.id == id } }?.let { drawCropOverlay(canvas, it) }
        // Shown during See all working too, so the overview says where the response is.
        // Margins run down from where the response began; an unplaced one has no margins yet.
        if (page.infinite) canvasWritingSession?.takeIf { it.placed }?.column?.let { column ->
            val top = maxOf(column.top, -originY / scale)
            val bottom = (height - originY) / scale
            if (top < bottom) followOverlay.column(canvas, column, top, bottom, selectionUiUnit(), answerAreaColor)
        }
        val areas = (if (showAnswerAreas && followEnabled) writingRegions + listOfNotNull(writingRegion) else emptyList()) + listOfNotNull(regionDraft)
        if (areas.isNotEmpty()) followOverlay.areas(canvas, areas, selectionUiUnit(), answerAreaColor)
        if (canFollow && followPreferences.showLandingGuide) landingGuide?.let { drawLandingGuide(canvas, it) }
        followLandingPreview?.let { drawLandingGuide(canvas, it) }
        writingWindow?.takeIf { it.pageId == page.id }?.let { followOverlay.window(canvas, it, selectionUiUnit(), answerAreaColor) }
        if (canFollow && followPreferences.horizontalFollow && followPreferences.mode == FollowMode.TEXT && getLocalVisibleRect(followVisible)) {
            val left = (followVisible.left - originX) / scale
            val right = (followVisible.right - originX) / scale
            val area = followRegion()
            val ltr = followPreferences.direction == WritingDirection.LTR
            if (FollowNavigation.hasRoomAhead(left, right, area, followPreferences.direction)) {
                val strip = (right - left) * followPreferences.edgeStripWidth(writingHand)
                followOverlay.edgeStrip(canvas, if (ltr) right - strip else left, (followVisible.top - originY) / scale,
                    if (ltr) right else left + strip, (followVisible.bottom - originY) / scale, answerAreaColor,
                    (AppPrefs.followEdgeStripOpacity(followPreferences.edgeStripOpacity) * 255).roundToInt())
            }
        }
        (documentMarkRegionPreview ?: markRegionDraft ?: markRegionShown)?.let { followOverlay.markRegion(canvas, it, selectionUiUnit(), answerAreaColor) }
        canvas.restore() // Page clip: sticky notes sit on top and may hang past the paper edge.
        if (!workspaceNotes) drawStickySurfaces(canvas)
        // The typing field sits in this page's row, under the shared workspace, so the note being typed draws here.
        else typingStickyId()?.let { id -> drawStickySurfaces(canvas) { it.id == id } }
        // Tools and guides sit above everything on the page, notes included.
        if (onEraserBrush == null) eraserMark?.let { drawEraser(canvas, it) }
        placeRuler()
        shownRuler?.let { rulerPainter.draw(canvas, it, selectionUiUnit(), rulerHalf(), rulerHalfLength()) }
        if (!laser.isEmpty && laser.draw(canvas, inkColor, selectionUiUnit(), showHead = tool == Tool.LASER)) postInvalidateOnAnimation()
        followStickyFrame()
        canvas.restore()
        drawShapeAssistHint(canvas)
    }

    /** Screen-sized feedback stays above the tip, including in a clipped scrolling page. */
    private fun drawShapeAssistHint(canvas: Canvas) {
        if (heldShape == null || !getLocalVisibleRect(followVisible)) return
        val tip = shapeAdjustment?.tip ?: draft?.points?.lastOrNull() ?: return
        followOverlay.shapeHint(canvas, originX + tip.x * scale, originY + tip.y * scale, followVisible, resources.displayMetrics)
    }

    /** Finite-column notes are painted on the shared workspace, outside every paper/item clip. */
    internal var workspaceNotes = false
        set(value) { if (field != value) { field = value; invalidate() } }
    internal var onWorkspaceNotesInvalidated: (() -> Unit)? = null
    override fun invalidate() { super.invalidate(); onWorkspaceNotesInvalidated?.invoke() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun invalidate(left: Int, top: Int, right: Int, bottom: Int) {
        super.invalidate(left, top, right, bottom); onWorkspaceNotesInvalidated?.invoke()
    }
    internal fun drawWorkspaceNotes(canvas: Canvas) {
        if (!workspaceNotes) return
        canvas.save()
        canvas.translate(originX, originY); canvas.scale(scale, scale)
        val typing = typingStickyId()
        drawStickySurfaces(canvas) { it.id != typing }
        canvas.restore()
        followStickyFrame()
    }
    private fun typingStickyId(): String? = stickyNotes.focusedId?.takeIf { stickyNotes.typing }
    private fun drawStickySurfaces(canvas: Canvas, keep: (TextBox) -> Boolean = { true }) {
        val selectedStickies = selectedTexts.filter { it.isSticky }
        val target = noteTarget
        stickyNotes.draw(canvas, (documentContentPreview?.let { PageLayers.view(it) } ?: layerView()).texts.filter { box -> box.isSticky && keep(box) && selectedStickies.none { it.id == box.id } && objectLift?.selection?.texts?.any { it.id == box.id } != true },
            selectionUiUnit(), if (documentContentPreview == null) noteErasing.orEmpty() else emptyMap()) { box ->
            if (box.id == target && !documentSpanning) {
                canvas.save()
                canvas.clipRect(box.x, box.y, box.x + box.width, box.y + box.stickyHeight)
                drawDraft(canvas)
                canvas.restore()
            }
        }
        if (objectLift == null && selectedStickies.isNotEmpty()) {
            canvas.save()
            if (hasSelection() && (selectionPreviewScale != 1f || selectionPreviewDeg != 0f)) canvas.concat(selectionPreviewMatrix)
            canvas.translate(selectionDx, selectionDy)
            selectedStickies.forEach { InkRenderer.text(canvas, it); drawTextBox(canvas, it) }
            canvas.restore()
        }
    }

    /** The stroke in progress, or the shape a held pen has tidied it into. */
    private fun drawDraft(canvas: Canvas) {
        val draftStroke = draft
        // Live freehand ink retains settled spline segments and resamples only the changing tip.
        // Drawing/tapering still visits the centreline, but smoothing no longer grows with the line.
        heldErase?.forEach { InkRenderer.stroke(canvas, it.copy(color = HELD_ERASE_COLOR, opacity = .6f)) }
        if (heldShape != null) heldShape!!.forEach { InkRenderer.stroke(canvas, it) }
        else if (draftStroke == null) resetDraftGeometry()
        else {
            val live = if (draftStroke.tool in FREEHAND_TOOLS) draftGeometry(draftStroke) else null
            if (live != null) {
                InkRenderer.drawRendered(canvas, draftStroke, live)
                // Stylus only: a finger is slow and its tail would read as lag the other way.
                if (stylus && draftStroke.tool == Tool.PEN && !prediction.drawTail(canvas, draftStroke) && prediction.idle)
                    prediction.drawGuess(canvas, draftStroke, scale)
            }
            else if (draftStroke.tool == Tool.GRAPH) GraphAxes.strokes(draftStroke, graphStyle).forEach { InkRenderer.stroke(canvas, it) }
            else InkRenderer.stroke(canvas, draftStroke)
        }
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
        val margin = maxOf(contactWidth, draft?.width ?: 0f) * scale + 16f * scale.coerceAtMost(2f) + 12f + StrokePrediction.MAX_TAIL_PX
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
        val margin = max(contactWidth, 14f) * scale + 16f
        val l = (originX + min(start.x, end.x) * scale - margin).toInt()
        val t = (originY + min(start.y, end.y) * scale - margin).toInt()
        val r = (originX + max(start.x, end.x) * scale + margin).toInt()
        val b = (originY + max(start.y, end.y) * scale + margin).toInt()
        @Suppress("DEPRECATION")
        invalidate(l, t, r, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = inputRouter.touch(event) { admitted ->
        selectionSnapBypassed = (admitted.metaState and android.view.KeyEvent.META_SHIFT_ON) != 0
        val predicting = tool == Tool.PEN && !readOnly && (0 until admitted.pointerCount).any { isStylus(admitted, it) }
        if (predicting) prediction.record(this, admitted)
        val handled = handleTouchEvent(admitted)
        writingFeedback(admitted)
        if (predicting) refreshPrediction(admitted) else clearPrediction()
        // A held pause turned the stroke into a shape the page draws; the freehand preview must not linger over it.
        if (heldShape != null) lowLatency?.let { it.cancel(); lowLatency = null }
        onLiveInk(draft?.let { if (it.layer == activeLayer) it else it.copy(layer = activeLayer) }, isWritingGesture)
        handled
    }

    private val penFeedback = (context.applicationContext as? FolioApplication)?.penFeedback

    /** Buzzes the pen while its tip is down with a writing tool; lift, cancel or a second finger stops it. */
    private fun writingFeedback(event: MotionEvent) {
        val feedback = penFeedback?.takeIf { it.isReady } ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (readOnly || !isStylus(event, 0)) return
                val eraserTip = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                PenWritingFeedback.textureFor(tool, eraserTip)?.let(feedback::begin)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> feedback.end()
        }
    }

    private fun handleWritingWindow(event: MotionEvent): Boolean {
        val frame = writingWindow?.takeIf { it.pageId == page.id } ?: return false
        val pt = point(event, 0)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val reach = 24f * resources.displayMetrics.density / scale
            resizingWindow = kotlin.math.abs(pt.x - frame.right) <= reach && kotlin.math.abs(pt.y - frame.bottom) <= reach
            if (!resizingWindow && !(pt.x in frame.left..frame.right && pt.y in frame.top..frame.bottom)) return false
            windowDrag = frame; windowDragStart = pt
            holdWritingFollow()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        val original = windowDrag ?: return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Consume the remainder: the normal navigation router never received this DOWN.
            windowDragStart = null
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            val start = windowDragStart
            if (start == null) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    windowDrag = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            val lane = WritingLane(original.left, original.top, original.right, original.bottom)
            val changed = if (resizingWindow) ZoomWriting.resize(lane, pt.x, pt.y)
                else ZoomWriting.move(lane, pt.x - start.x, pt.y - start.y, page.width, page.height, page.infinite)
            onWritingWindowRequest(PeekAnchor(page.id, changed.left, changed.top, changed.right, changed.bottom))
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                windowDrag = null; windowDragStart = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** Receives admitted pointers only; a pen is pointer zero in its own complete DOWN..UP stream. */
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) recognitionEpoch++
        // Cancellation must precede blocked input and every early-return tool path.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            windowDrag = null; windowDragStart = null
            parent?.requestDisallowInterceptTouchEvent(false)
            holdWritingFollow()
            stickyNotes.cancel()
            endRulerGesture()
            markRegionDraft = null; markRegionStart = null
            regionDraft = null; regionStart = null
            reportNavigating(false); removeCallbacks(longPressRunnable)
            longPressFired = false; tapOff = false; touchChord.reset()
            zoomDetector.onTouchEvent(event)
            cancelGesture(); reportSelectionViewBounds(); invalidate()
            return true
        }
        if (documentInputBusy?.invoke() == true) { stickyNotes.cancel(); cancelGesture(); return true }
        if (inputBlocked) return true
        if (handleWritingWindow(event)) return true
        val touchNavigation = inputRouter.stylus.isNavigation(event.deviceId, event.downTime)
        // Fingers on the straightedge move it before anything else sees them, notes included.
        if (handleRuler(event, touchNavigation)) return true
        if (!touchNavigation && event.actionMasked == MotionEvent.ACTION_DOWN && editingText != null) onTextEditingExit()
        if (!touchNavigation && !readOnly && !navigationOnly && !selectingWritingRegion) {
            val pen = isStylus(event, 0)
            // A finger that will navigate rather than draw (and the hand tool) can tap a note for its menu.
            val tapFocuses = tool == Tool.HAND || (!pen && !fingerDrawing && tool != Tool.LASSO && tool != Tool.TEXT)
            if (stickyNotes.touch(event, point(event, 0), tool, pen || fingerDrawing, tapFocuses, activeLayer)) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onActive(); suspendWritingFollow(); removeCallbacks(longPressRunnable)
                    // Seed navigation before a possible second finger hands this gesture back.
                    pointerId = event.getPointerId(0); stylus = pen; ignored = false
                    if ((page.infinite || cameraPage) && !pen) zoomDetector.onTouchEvent(event)
                }
                return true
            }
        }
        if (markRegionMode && !touchNavigation) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { cancelGesture(); markRegionDraft = null; markRegionStart = null; invalidate(); return true }
            val raw = point(event, 0)
            val pt = clampToPage(raw)
            fun box(from: InkPoint) = WritingLane(min(from.x, pt.x), min(from.y, pt.y), max(from.x, pt.x), max(from.y, pt.y))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onActive(); dismissZone()
                    // Starting again abandons a rectangle still waiting for its marks.
                    if (markRegionShown != null) { markRegionShown = null; onMarkRegion(null, null) }
                    pointerId = event.getPointerId(0); stylus = isStylus(event, 0)
                    documentGesture = beginDocument(Stroke(Tool.MARK_AREA, inkColor, inkWidth, listOf(raw), layer = activeLayer), false)
                    if (documentGesture) onDocumentGesturePresence(true)
                    markRegionStart = pt; parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> markRegionStart?.let { documentSamples(listOf(raw)); markRegionDraft = box(it) }
                MotionEvent.ACTION_UP -> {
                    documentSamples(listOf(raw))
                    val handled = finishDocument()
                    if (!handled) markRegionStart?.let { from ->
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
        if (selectingWritingRegion && !touchNavigation) {
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (pendingReturn != null) { reportFollowStatus("Return cancelled · keep writing"); landingGuide = null }
            else if (lineAdvance != null || followGlide.moved) reportFollowStatus("Movement stopped · keep writing")
            val resume = pendingReturn != null || followGlide.active
            if (tool == Tool.PEN && event.actionMasked == MotionEvent.ACTION_DOWN) followRhythm.touched(SystemClock.uptimeMillis())
            cancelFollowMotion()
            resumeFollowAfterMark = resume
        }
        var dirtyInvalidated = false
        val hasStylus = (0 until event.pointerCount).any { isStylus(event, it) }
        if (hasStylus && (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            requestUnbufferedDispatch(event)
        }
        if (!multiTouchUndo || hasStylus || touchNavigation || inputRouter.stylus.isRecent(event.eventTime)) touchChord.reset()
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
        if ((page.infinite || cameraPage) && !stylus && !hasStylus) {
            zoomDetector.onTouchEvent(event)
        }
        if (longPressFired && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) longPressFired = false
            return true
        }
        // A presenter stroke owns its pen until lift, so a stale flag can never swallow a new press.
        if (laser.down && event.actionMasked != MotionEvent.ACTION_DOWN) {
            handleLaser(event)
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
                ignored = false
                // Typing is a finger job even when finger drawing is off, so the text tool never pans.
                navigating = !ignored && (touchNavigation || tool == Tool.HAND || tool == Tool.STICKY_NOTE || (tool != Tool.TEXT && !fingerDrawing && !stylus))
                if (navigating) suspendWritingFollow()
                lastX = event.rawX; lastY = event.rawY
                panVelocity.resetTracking()
                panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                longPressFired = false
                removeCallbacks(longPressRunnable)
                if (!touchNavigation && !stylus && !ignored && !readOnly && !navigationOnly && !inputBlocked) {
                    longPressX = event.x; longPressY = event.y
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                tapOffX = event.x; tapOffY = event.y
                tapOff = false
                if (touchNavigation) {
                    // This delayed DOWN belongs to a recognized pinch, never an ink/tool tap.
                    tapOff = false
                } else if (cropImageId != null && !readOnly && !navigationOnly && tool == Tool.HAND && !ignored) {
                    if (beginCrop(event, 0)) navigating = false
                    else tapOff = !cropFrameContains(point(event, 0))
                } else if (!readOnly && !navigationOnly && tool == Tool.HAND && !ignored && beginImage(event, 0)) {
                    navigating = false
                } else if (tool == Tool.HAND && !ignored && beginLink(event, 0)) {
                    navigating = false
                } else if (!stylus && !ignored && !readOnly && !navigationOnly && beginZone(event, 0)) {
                    navigating = false
                } else if (lassoActive()) beginLasso(event, 0)
                else if (tool == Tool.TEXT && !ignored) beginText(event, 0)
                else if (!navigating && !ignored) beginStroke(event, 0)
                // Pen-only mode is where a finger means "navigate", so a hand that has not travelled
                // yet is left where it is. The pen takes the gesture over the moment its tip lands.
                if (!touchNavigation && cropImageId == null) tapOff = !readOnly && !ignored &&
                    ((tool == Tool.HAND && selectedImageId != null && movingImage == null) || (tool == Tool.LASSO && hasSelection() && navigating))
                panGate.arm(navigating && !touchNavigation && !stylus && !fingerDrawing, centroidX(event), centroidY(event))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable); tapOff = false
                if (!stylus && !ignored) {
                    clearShapeHold()
                    endObjectLift()
                    suspendWritingFollow()
                    draft = null; erasing = null; lasso = null; cancelSelectionGesture(); movingText = null; pendingTextBox = null; movingImage = null; resizingImage = false; pendingLink = null; navigating = true
                    panGate.release()
                    lastX = centroidX(event); lastY = centroidY(event)
                    panVelocity.resetTracking()
                    panVelocity.addPosition(event.eventTime, Offset(lastX, lastY))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
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
                            movingImage = if (page.infinite || onObjectLift != null) current.moved(dx, dy)
                            else current.moved(dx, dy).let {
                                it.copy(x = it.x.coerceIn(-it.width + 40f, page.width - 40f),
                                    y = it.y.coerceIn(-it.height + 40f, page.height - 40f))
                            }
                            if (hypot(dx, dy) > 1f) imageMoved = true
                        }
                    }
                    if (!resizingImage && imageMoved) {
                        val original = page.images.find { it.id == current.id }
                        val preview = movingImage
                        if (original != null && preview != null) liftObjects(event, index, CanvasSelection(images = listOf(original)),
                            preview.x - original.x, preview.y - original.y)
                    }
                    imageFromX = at.x; imageFromY = at.y
                } else if (tool == Tool.TEXT && movingText != null) {
                    val raw = point(event, index)
                    val moved = if (onObjectLift != null) raw else clampToPage(raw)
                    val dx = moved.x - textFromX; val dy = moved.y - textFromY
                    if (hypot(dx, dy) * scale > ViewConfiguration.get(context).scaledTouchSlop) textDragged = true
                    if (textDragged) {
                        textDx = dx; textDy = dy
                        liftObjects(event, index, CanvasSelection(texts = listOf(movingText!!)), dx, dy)
                    }
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
                        val moved = if (onObjectLift != null) rawPoint else clampToPage(rawPoint)
                        rawSelectionDx += moved.x - lastMoveX; rawSelectionDy += moved.y - lastMoveY
                        selectionDx = rawSelectionDx; selectionDy = rawSelectionDy
                        if (!documentSelectionOwner && (event.metaState and android.view.KeyEvent.META_SHIFT_ON) == 0 && context.getSharedPreferences("preferences", 0).getBoolean(AppPrefs.UX_SNAP, AppPrefs.DEFAULT_UX_SNAP)) {
                            val bounds = InkGeometry.selectionBounds(selection, selectedTexts, selectedImages, { InkRenderer.textHeight(it) }, 0f)
                            if (bounds != null) {
                                selectionDx = ObjectSnapping.offset(bounds[0], bounds[2], rawSelectionDx, page.width.takeUnless { page.infinite }, selectionSnapX, 8f / scale)
                                selectionDy = ObjectSnapping.offset(bounds[1], bounds[3], rawSelectionDy, page.height.takeUnless { page.infinite }, selectionSnapY, 8f / scale)
                            }
                        }
                        lastMoveX = moved.x; lastMoveY = moved.y
                        if (documentSelectionOwner) onDocumentSelectionDrag?.invoke(selectionDx, selectionDy, false)
                        else liftObjects(event, index, CanvasSelection(selection, selectedTexts, selectedImages), selectionDx, selectionDy)
                    } else {
                        // Reuse the backing list: rebuilding the whole loop per MOVE is O(N²).
                        val backing = (lasso as? ArrayList<InkPoint>) ?: ArrayList(lasso ?: emptyList())
                        val raw = ArrayList<InkPoint>(event.historySize + 1)
                        for (history in 0 until event.historySize) raw += point(event, index, history)
                        raw += point(event, index)
                        if (!documentSamples(raw)) { val onNotes = visibleNotes().isNotEmpty(); raw.forEach { backing += if (onNotes) it else clampToPage(it) } }
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
                    trackPenStartHold(event, index)
                    val points = samples(event, index)
                    // The eraser cuts out the samples it touches and leaves the rest of the stroke behind.
                    // One pass applies all of this frame's samples: re-walking every stroke once per
                    // sample is what made a drag feel heavy once a page had ink on it.
                    val cutting = erasing
                    val documentErasing = cutting != null && documentSamples(points)
                    if (documentErasing) eraserMark = points.lastOrNull()
                    if (cutting != null && !documentErasing) {
                        val notes = visibleNotes()
                        val all = points.map { p -> if (notes.any { StickyNotes.contains(it, p.x, p.y) }) p else clampToPage(p) }
                        eraserMark = all.last()
                        val centers = eraseNotes(all)
                        if (centers.isNotEmpty()) {
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
                                centers.maxOf { contactWidth / 2f * InkGeometry.eraserScale(it.pressure) } else contactWidth / 2f
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
                                    val r = if (eraserPressureEnabled && stylus) contactWidth / 2f * InkGeometry.eraserScale(c.pressure) else contactWidth / 2f
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
                                    val r = if (eraserPressureEnabled && stylus) contactWidth / 2f * InkGeometry.eraserScale(c.pressure) else contactWidth / 2f
                                    InkGeometry.hits(hitStroke, c, r)
                                }
                            }
                        } else if (eraserPressureEnabled && stylus) {
                            val radii = centers.map { contactWidth / 2f * InkGeometry.eraserScale(it.pressure) }
                            val maxR = radii.max()
                            eraseCopyOnWrite(cutting, cMinX, cMinY, cMaxX, cMaxY, maxR) { stroke ->
                                InkGeometry.erase(stroke, centers, radii)
                            }
                        } else {
                            val radius = contactWidth / 2f
                            eraseCopyOnWrite(cutting, cMinX, cMinY, cMaxX, cMaxY, radius) { stroke ->
                                InkGeometry.erase(stroke, centers, radius)
                            }
                        }
                        }
                    }
                    draft?.let { current ->
                        val restartedHold = trackShapeHold(event, index)
                        val accepted = if (documentSamples(if (heldShape != null) emptyList() else points)) { if (rulerEdge == 0) points else points.map(::snapToRuler) } else pagePoints(points)
                        draft = when {
                            heldShape != null -> current
                            accepted.isEmpty() -> current
                            current.tool in FREEHAND_TOOLS -> {
                                // Drafts own their mutable samples until pen-up. Neither the list
                                // nor the Stroke needs allocating again for each input event.
                                val growing = current.points as ArrayList<InkPoint>
                                val before = growing.size
                                growing.addAll(accepted)
                                settleLiveStart(growing, before)
                                streamLowLatency()
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
                        if (restartedHold) shapeFitPointCount = draft!!.points.size
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
                if (event.getPointerId(event.actionIndex) == pointerId && stylus) {
                    if (lassoActive()) { documentSamples(listOf(point(event, event.actionIndex))); finishLasso() }
                    else { finishStrokeSamples(event, event.actionIndex); finishInkGesture() }
                }
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
                positionObjectsOnRelease(event)
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
                if (touchNavigation) cancelGesture()
                else if (tool == Tool.TEXT) finishText()
                else if (lassoActive()) { documentSamples(listOf(point(event, event.findPointerIndex(pointerId).coerceAtLeast(0)))); finishLasso() }
                else {
                    // Follow the tracked pointer, which may be a stylus that took over from a finger.
                    finishStrokeSamples(event, event.findPointerIndex(pointerId).coerceAtLeast(0))
                    finishInkGesture()
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
    private fun finishStrokeSamples(event: MotionEvent, index: Int) {
        clearPenStartHold()
        if (erasing != null) { documentSamples(samples(event, index, finishing = true)); return }
        val current = draft ?: return
        trackShapeHold(event, index)
        if (heldShape != null) { documentSamples(emptyList()); return }
        val raw = samples(event, index, finishing = true)
        val accepted = if (documentSamples(raw)) { if (rulerEdge == 0) raw else raw.map(::snapToRuler) } else pagePoints(raw)
        var end = accepted.lastOrNull() ?: return
        if (snapEnabled && current.tool in MEASURE_TOOLS) {
            if (page.paper.isGrid) end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
            if (current.tool == Tool.LINE) end = InkGeometry.snapAngle(current.points.first(), end, 15f)
        }
        draft = if (current.tool in FREEHAND_TOOLS) {
            (current.points as ArrayList<InkPoint>).addAll(accepted)
            streamLowLatency()
            current
        } else current.copy(points = listOf(current.points.first(), end))
    }
    /** [stroke] with redundant samples thinned (see [StrokeRefit]); the same instance when nothing changes or it's switched off. */
    private fun refitted(stroke: Stroke): Stroke {
        if (stroke.tool != Tool.PEN) return stroke
        // Pen-down/lift pressure readings and skids are cleaned first, so no blob or hook is stored.
        val settled = StrokeEnds.settle(stroke.points, stroke.width)
        val thin = if (context.getSharedPreferences("preferences", 0)
                .getBoolean(AppPrefs.REFIT_STROKES, AppPrefs.DEFAULT_REFIT_STROKES)) StrokeRefit.simplify(settled) else settled
        return if (thin === stroke.points) stroke else stroke.copy(points = thin)
    }
    /**
     * The pen-down sample's pressure is often unlike the samples after it (a full reading at the tip), which
     * shows as a blob at the start of the live stroke. Once the second sample arrives the first takes its pressure.
     */
    private fun settleLiveStart(points: ArrayList<InkPoint>, before: Int) {
        if (before != 1 || points.size < 2 || draft?.tool != Tool.PEN) return
        val first = points[0]; val next = points[1]
        if (first.pressure == next.pressure) return
        points[0] = InkPoint(first.x, first.y, next.pressure)
        // The live geometry already folded the old sample; start it again (cheap: the stroke is two samples long).
        resetDraftGeometry()
    }
    /** Physical movement, rather than touch sample count, gates expensive recognition. */
    internal fun canClassifyScribble(stroke: Stroke): Boolean =
        InkGeometry.isScribble(stroke.points, scribbleSensitivity) &&
            ShapeMath.length(stroke.points) * scale >= 48f * resources.displayMetrics.density &&
            ShapeMath.span(stroke.points) * scale >= 16f * resources.displayMetrics.density

    /** The scrub as page ink and as ink on the sticky notes it was drawn on. */
    private fun scribblePaths(drawn: Stroke, local: DocumentInkCapture?): Pair<List<Stroke>, Map<String, List<Stroke>>> {
        val paths = local?.strokes()?.get(page.id)?.map { it.copy(points = it.points.toList()) }
            ?: if (noteTarget == null) listOf(drawn) else emptyList()
        val notes = local?.noteStrokes()?.get(page.id)?.mapValues { (_, ink) -> ink.map { it.copy(points = it.points.toList()) } }
            ?: noteTarget?.let { id -> page.texts.find { it.id == id }?.let { box ->
                mapOf(id to listOf(StickyNotes.toNote(box, drawn)))
            } }.orEmpty()
        return paths to notes
    }

    /** A held scrub never becomes ink: the erasure is the gesture's only, single undo step. */
    private fun finishHeldErase(drawing: Stroke): Boolean {
        val (paths, notes) = scribblePaths(drawing.copy(points = drawing.points.toList()), localCapture?.let(::routedLocal))
        val after = ScribbleRecognition.prepare(mapOf(page.id to page), mapOf(page.id to paths),
            mapOf(page.id to notes), scribbleSensitivity, held = true)?.contents?.get(page.id)
            ?: return false.also { heldErase = null }
        cancelGesture()
        if (readOnly || inputBlocked) { invalidate(); return true }
        val strokesChanged = page.strokes != after.strokes
        val textsChanged = page.texts != after.texts
        page = page.copy(strokes = after.strokes, texts = after.texts)
        when {
            strokesChanged && textsChanged -> onContentChanged(after.strokes, after.texts, after.images)
            strokesChanged -> onStrokesChanged(after.strokes)
            textsChanged -> onTextsChanged(after.texts)
        }
        invalidate()
        return true
    }

    /** Commit visible ink immediately; a model-approved erasure is a separate, guarded undo step. */
    private fun finishInkGesture() {
        val drawing = draft
        // Spanning document gestures are interpreted by DocumentInputCoordinator instead.
        val external = documentGesture && localCapture == null && documentSpanning
        if (drawing != null && heldErase != null && !external && finishHeldErase(drawing)) return
        if (drawing == null || !scribbleToErase || heldShape != null || drawing.tool !in FREEHAND_TOOLS ||
            external || !canClassifyScribble(drawing)) {
            if (finishDocument()) cancelGesture() else finishGesture()
            return
        }
        val before = page
        val drawn = drawing.copy(points = drawing.points.toList())
        val (paths, notes) = scribblePaths(drawn, localCapture?.let(::routedLocal))
        val sensitivity = scribbleSensitivity
        if (finishDocument()) cancelGesture() else finishGesture()
        val expected = page.content()
        if (expected == before.content()) return
        val guard = InkRecognitionSnapshot(page, before.revision + 1)
        val epoch = recognitionEpoch
        InkGestureRecognizer.scribble(drawn, before, paths, notes, sensitivity) { after ->
            if (after == null || epoch != recognitionEpoch || !isAttachedToWindow || !guard.matches(page) ||
                !scribbleToErase || readOnly || inputBlocked) return@scribble
            val strokesChanged = page.strokes != after.strokes
            val textsChanged = page.texts != after.texts
            page = page.copy(strokes = after.strokes, texts = after.texts)
            when {
                strokesChanged && textsChanged -> onContentChanged(after.strokes, after.texts, after.images)
                strokesChanged -> onStrokesChanged(after.strokes)
                textsChanged -> onTextsChanged(after.texts)
            }
            invalidate()
        }
    }

    private fun finishGesture() {
        noteTarget?.let { id -> finishNoteStroke(page.texts.find { it.id == id && it.isSticky }); return }
        // Captured before cancelGesture clears it: the note ink the eraser left behind.
        val erasedNotes = noteErasing
        val wasErasing = erasing != null
        val byFinger = !stylus
        val drawn = draft?.let { it.copy(points = it.points.toList()) }
        // Only a completed pen-down hold authorises tidy-up; lifting alone always keeps freehand.
        val tidied = heldShape
        // What is stored is the drawn stroke with its redundant samples thinned, so notebooks stay small.
        val stored = drawn?.let(::refitted)
        val strokes = (tidied ?: stored?.let { if (it.tool == Tool.GRAPH) GraphAxes.strokes(it, graphStyle) else listOf(it) })
            // Shapes tidied or generated from the drag are new strokes; they join the layer being drawn on.
            ?.map { if (it.layer == activeLayer) it else it.copy(layer = activeLayer) }
            ?.let { page.strokes + it } ?: erasing
        if (canFollow && drawn?.tool == Tool.PEN) {
            followCompletedStroke(drawn, recognise = tidied == null)
        }
        val appendedStroke = stored?.takeIf { tidied == null && it.tool != Tool.GRAPH }
        val beforeStrokes = page.strokes
        val changed = strokes != null && (appendedStroke != null || strokes != beforeStrokes)
        if (changed) {
            if (drawn != null && stored === drawn && drawn.tool in FREEHAND_TOOLS && tidied == null &&
                (drawn.tool != Tool.PEN || InkTaper.amount == 0f)) {
                // Hand the final live geometry to the retained layer; pen-up need not smooth it again.
                // Not when the ends are tapered: the live pass can't know where the stroke ends.
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
                // Save the drawing first so Undo can restore the shape's freehand source.
                val original = drawn.let { if (it.layer == activeLayer) it else it.copy(layer = activeLayer) }
                val withOriginal = beforeStrokes + original
                if (onStrokeAppended != null) onStrokeAppended!!.invoke(beforeStrokes, original, withOriginal)
                else onStrokesChanged(withOriginal)
                onStrokesChanged(page.strokes)
            } else if (appendedStroke != null && onStrokeAppended != null)
                onStrokeAppended!!.invoke(beforeStrokes, appendedStroke, page.strokes)
            else onStrokesChanged(page.strokes)
            if (byFinger && appendedStroke != null && !readOnly) {
                val now = SystemClock.uptimeMillis()
                recentFingerInk.removeAll { now - it.second > StylusActivity.RETRO_MAX_MS }
                recentFingerInk += page.strokes.last() to now
            }
        }
        if (!erasedNotes.isNullOrEmpty()) {
            commitTexts(page.texts.map { box -> erasedNotes[box.id]?.let { box.copy(stickyInk = it) } ?: box })
        }
        if (shouldNotifyEraser) onEraserFinished?.invoke()
    }

    /**
     * Files the stroke just drawn under [note], in the note's own coordinates. Shape tidy-up, graph
     * axes and scribble-to-erase behave as they do on the page. Scribble erasure has its own undo
     * step after the drawing, so an intentional scribble can be recovered as note-local ink.
     */
    private fun finishNoteStroke(note: TextBox?) {
        val drawn = draft?.let { it.copy(points = it.points.toList()) }
        val tidied = heldShape
        cancelGesture()
        if (note == null || drawn == null) { invalidate(); return }
        val added = (tidied ?: if (drawn.tool == Tool.GRAPH) GraphAxes.strokes(drawn, graphStyle) else listOf(drawn))
            .map { StickyNotes.toNote(note, it.copy(layer = 0)) }
        commitTexts(page.texts.map { if (it.id == note.id) it.copy(stickyInk = it.stickyInk + added) else it })
    }

    /**
     * The pen just came into range. Finger ink that lifted moments earlier was the hand settling
     * to write; it is withdrawn newest first, and only while it is still the page's last stroke, so
     * nothing drawn since (or on top of it) is ever touched.
     */
    private fun withdrawPalmInk(arrivedAt: Long) {
        var withdrew = false
        while (recentFingerInk.isNotEmpty() && !readOnly) {
            val (stroke, endedAt) = recentFingerInk.removeAt(recentFingerInk.lastIndex)
            // The arrival may be stamped a little before this view finished the lift; that is still "right after".
            if (!inputRouter.stylus.withdraws(endedAt, maxOf(arrivedAt, endedAt)) || page.strokes.lastOrNull() !== stroke) break
            page = page.copy(strokes = page.strokes.dropLast(1))
            onStrokeWithdrawn?.invoke(stroke) ?: onStrokesChanged(page.strokes)
            withdrew = true
        }
        recentFingerInk.clear()
        if (withdrew) invalidate()
    }

    private fun followCompletedStroke(drawn: Stroke, recognise: Boolean = true) {
        val box = FollowNavigation.bounds(drawn.points) ?: return
        canvasWritingSession?.takeIf { page.infinite && !it.placed }?.let { session ->
            val placed = session.placedAt(box, followPreferences.spacing)
            canvasWritingSession = placed; onCanvasWritingSession(placed)
            reportFollowStatus("Response column set · keep writing")
        }
        val now = SystemClock.uptimeMillis()
        followRhythm.lifted(now)
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
        val progress = if (recognise) writingFollow.completed(drawn.points, now, followPreferences, writingGuides)
            else WritingProgress.NONE
        if (progress == WritingProgress.NEW_LINE) followRhythm.lineBreak()
        else if (writingFollow.state.candidateLane != null) followRhythm.possibleLineBreak()
        else followRhythm.discardLineBreak()
        if (progress != WritingProgress.NONE && pendingReturn == null) landingGuide = null
        val resumeMark = resumeFollowAfterMark && writingFollow.finishingMark(box, followPreferences)
        resumeFollowAfterMark = false
        if (followManuallyPaused) { reportFollowStatus("Paused · tap Resume when ready"); return }
        // The strip is an input zone, not a handwriting-recognition result. Dots, tall letters,
        // retraced strokes and the first mark in a line must all be able to reveal more room.
        if (!getLocalVisibleRect(followVisible)) return
        val visible = WritingLane((followVisible.left - originX) / scale, (followVisible.top - originY) / scale,
            (followVisible.right - originX) / scale, (followVisible.bottom - originY) / scale)
        val stripShift = writingFollow.stripShift(box, visible, strokeRegion, followPreferences, writingHand) * scale
        val stripDx = if (kotlin.math.abs(stripShift) >= .5f) responseDx(stripShift) else 0f
        fun revealStrip(): Boolean {
            if (kotlin.math.abs(stripDx) < .5f) return false
            followGlide.start(stripDx, 0f, now, followPreferences.glideDelayMs, followPreferences.glideDurationMs,
                followVisible.width().toFloat(), followVisible.height().toFloat(), instant = reducedMotion)
            captureFollowBack = true
            reportFollowStatus("More room in ${FollowPreferences.returnDelayLabel(followPreferences.glideDelayMs)} · touch down to cancel")
            scheduleFollow()
            return true
        }
        if (progress == WritingProgress.NONE && !resumeMark) {
            if (revealStrip()) return
            reportFollowStatus(if (writingFollow.state.candidateLane != null) "New line · keep writing" else "View held · keep writing")
            return
        }
        val baseline = writingFollow.state.baselineY ?: run { revealStrip(); return }
        // A natural canvas line break can establish a different start column. Use it
        // immediately rather than deciding whether to return against the previous lane.
        val region = if (page.infinite && writingRegion == null) followRegion(drawn.points) else strokeRegion
        val next = nextFollowLine(baseline, region)
        if (page.infinite || zoomWritingPane) {
            if (!FollowLegibility.isReadable(writingFollow.laneHeight(), scale)) {
                if (revealStrip()) return
                reportFollowStatus("Zoom in a little for writing follow · Next line moves manually", attention = true)
                return
            }
        } else if (documentFollowZoom < followPreferences.minimumZoom) {
            if (revealStrip()) return
            reportFollowStatus("Zoom to ${"%.1f".format(followPreferences.minimumZoom)}× to follow · Next line moves manually", attention = true)
            return
        }
        val frontier = if (followPreferences.direction == WritingDirection.LTR)
            writingFollow.state.frontierRight ?: point.x else writingFollow.state.frontierLeft ?: point.x
        // Finishing a word can end with a dot/crossbar behind its furthest letter. The
        // accepted line frontier, rather than that last pen sample, determines the return.
        val returnAdvance = automaticFollowReturn(region)
        val atEnd = returnAdvance != null
        val returnDelay = followRhythm.returnDelay(followPreferences.automaticReturnDelayMs)
        reportFollowStatus(when {
            kotlin.math.abs(stripDx) >= .5f -> "More room in ${FollowPreferences.returnDelayLabel(followPreferences.glideDelayMs)} · touch down to cancel"
            atEnd && followPreferences.automaticReturn -> "Next line in ${FollowPreferences.returnDelayLabel(returnDelay)} · touch down to cancel"
            atEnd -> "Next line ready · tap Next line"
            next == null -> "End of answer area"
            else -> "Following"
        })
        if (atEnd && followPreferences.automaticReturn && kotlin.math.abs(stripDx) < .5f) {
            queueFollowReturn(returnAdvance!!)
            return
        }
        if (!getLocalVisibleRect(followVisible)) return
        val placing = writingFollow.state.needsPlacement
        val sx = originX + frontier * scale
        val sy = originY + baseline * scale
        val fraction = (sx - followVisible.left) / followVisible.width().coerceAtLeast(1)
        val target = followPreferences.horizontalTarget(writingHand)
        val roomAhead = FollowNavigation.hasRoomAhead((followVisible.left - originX) / scale,
            (followVisible.right - originX) / scale, region, followPreferences.direction)
        val dx = if (kotlin.math.abs(stripDx) >= .5f) stripDx
        else if (!followPreferences.horizontalFollow || followPreferences.mode != FollowMode.TEXT || (!placing && !roomAhead)) 0f
        else responseDx(if (placing) {
            val startX = writingFollow.state.lineStartX ?: frontier
            (followVisible.left + followVisible.width() * target) - (originX + startX * scale)
        } else writingFollow.horizontalShift(fraction, target, followPreferences.direction,
            followPreferences.edgeThreshold) * followVisible.width())
        val desiredY = followVisible.top + followVisible.height() * followPreferences.position
        val dy = writingFollow.verticalShift(sy, desiredY, followVisible.height().toFloat(), followPreferences, placing)
        if (kotlin.math.abs(dx) < .5f && kotlin.math.abs(dy) < .5f) {
            if (placing) {
                writingFollow.placed()
                if (followPreferences.automaticReturn)
                    automaticFollowReturn(region)?.let(::queueFollowReturn)
            }
            return
        }
        // Every pen lift starts the full configured pause; entering the strip never rushes it.
        followGlide.start(dx, dy, now, followPreferences.glideDelayMs, followPreferences.glideDurationMs,
            followVisible.width().toFloat(), followVisible.height().toFloat(), instant = reducedMotion)
        captureFollowBack = true
        scheduleFollow()
    }

    /** A tidied single line follows the grid and 15° snapping the user already has switched on. */
    private fun snapShapes(shapes: List<Stroke>): List<Stroke> {
        val line = shapes.singleOrNull()?.takeIf { it.tool == Tool.LINE || it.tool == Tool.HIGHLIGHTER } ?: return shapes
        if (!snapEnabled || rulerEdge != 0) return shapes
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
            if (textDragged && dropObjects()) {
                pendingTextBox = null; textDx = 0f; textDy = 0f
                return
            }
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

    private fun cropImage(): PageImage? = cropImageId?.let { id -> page.images.find { it.id == id } }
    private fun cropFrameContains(at: InkPoint): Boolean = cropImage()?.let { ImageCropFrame.contains(it, cropInset, at) } == true

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
            ImageCropFrame.applied(image, inset)?.let { placed ->
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
        cropEdges = ImageCropFrame.edgesAt(image, cropInset, at, SelectionChrome.TOUCH_RADIUS_DP * selectionUiUnit())
        return cropEdges != 0
    }

    private fun dragCrop(at: InkPoint) {
        val image = cropImage() ?: return
        ImageCropFrame.drag(image, cropInset, cropEdges, at)
    }

    private fun drawLandingGuide(canvas: Canvas, guide: WritingGuide) = followOverlay.landingGuide(canvas, guide,
        followPreferences.direction == WritingDirection.LTR, writingFollow.lineSpacing(followPreferences.spacing) * 6f,
        selectionUiUnit(), answerAreaColor)

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

    private fun drawCropOverlay(canvas: Canvas, image: PageImage) = chrome.crop(canvas, image, cropInset, selectionUiUnit(preview = true))

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
        if (stylus || ignored || readOnly || laser.down || movingSelection || resizingSelection || rotatingSelection || movingImage != null || cropEdges != 0) return@Runnable
        longPressFired = true
        // The held finger may have started a dot, an eraser pass or a lasso: drop them, it was a hold.
        cancelGesture()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val at = IntArray(2); getLocationInWindow(at)
        reportSelectionViewBounds()
        onLongPress(at[0] + longPressX, at[1] + longPressY, InkPoint((longPressX - originX) / scale, (longPressY - originY) / scale))
        invalidate()
    }

    /** The corner handle under [at], or -1. */
    private fun cornerAt(image: PageImage, at: InkPoint, reach: Float): Int =
        imageCorners(image).indexOfFirst { (cx, cy) -> abs(at.x - cx) <= reach && abs(at.y - cy) <= reach }

    private fun startResize(image: PageImage, corner: Int) {
        movingImage = image; resizingImage = true; imageMoved = false; imageCorner = corner
        val (ax, ay) = imageCorners(image)[(corner + 2) % 4]
        imageAnchorX = ax; imageAnchorY = ay
    }

    /** A drag commits the picture's new place or size; a tap reports it as selected. */
    private fun finishImage() {
        val preview = movingImage
        movingImage = null
        val wasResize = resizingImage
        resizingImage = false
        if (preview == null) { endObjectLift(); return }
        if (!wasResize && imageMoved && dropObjects()) {
            imageMoved = false; clearImageSelection()
            return
        }
        endObjectLift()
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
        endObjectLift()
        movingSelection = false; resizingSelection = false; rotatingSelection = false
        selectionPreviewScale = 1f; selectionPreviewDeg = 0f
    }
    private fun cancelGesture() { recognitionEpoch++; clearPenStartHold(); penStartTool = null; penStartOptions = null; if (movingSelection && documentSelectionOwner) onDocumentSelectionDrag?.invoke(0f, 0f, false); if (documentGesture && localCapture == null) onDocumentGestureCancel?.invoke(); localCapture = null; markRegionDraft = null; markRegionStart = null; documentMarkRegionPreview = null; documentContentPreview = null; documentInkPreview = emptyList(); documentGesture = false; documentSpanning = false; onDocumentGesturePresence(false); endObjectLift(); penFilter = null; speedWidth = null; clearPrediction(); endLowLatency(); if (laser.down) liftLaser(); noteTarget = null; noteErasing = null; rulerEdge = 0; clearShapeHold(); resetDraftGeometry(); panGate.release(); draft = null; erasing = null; lasso = null; cancelSelectionGesture(); selectionDx = 0f; selectionDy = 0f; rawSelectionDx = 0f; rawSelectionDy = 0f; pointerId = -1; stylus = false; ignored = false; navigating = false; offPage = false; eraserMark = null; movingText = null; pendingTextBox = null; textDx = 0f; textDy = 0f; movingImage = null; resizingImage = false; imageMoved = false; pendingLink = null; pendingZone = null; cropEdges = 0; parent?.requestDisallowInterceptTouchEvent(false) }
    private fun lassoActive() = tool == Tool.LASSO && !navigating && !ignored
    /** Starts a fresh loop, picks up the selection to move it, or grabs a frame handle. */
    private fun beginLasso(event: MotionEvent, index: Int) {
        val raw = point(event, index)
        val onNote = visibleNotes().any { StickyNotes.contains(it, raw.x, raw.y) }
        if (!onNote && !onPage(raw.x, raw.y)) { navigating = true; return }
        val start = if (onNote) raw else clampToPage(raw)
        if (hasSelection() && !documentSelectionOwner) {
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
        if (insideSelection(start)) {
            if (context.getSharedPreferences("preferences", 0).getBoolean(AppPrefs.UX_SNAP, AppPrefs.DEFAULT_UX_SNAP)) {
                val visible = layerView()
                val items = visible.strokes.filterNot { it in selection }.take(200).map { CanvasSelection(strokes = listOf(it)) } +
                    visible.texts.filterNot { it in selectedTexts }.take(100).map { CanvasSelection(texts = listOf(it)) } +
                    visible.images.filterNot { it in selectedImages }.take(100).map { CanvasSelection(images = listOf(it)) }
                val targets = items.map { item -> val b = InkGeometry.contentBounds(item.strokes, item.texts, item.images, InkRenderer::textHeight, page.width, page.height); ObjectSnapping.Bounds(b.left, b.top, b.right, b.bottom) }
                selectionSnapX = targets.map { it.left to it.right }; selectionSnapY = targets.map { it.top to it.bottom }
            } else { selectionSnapX = emptyList(); selectionSnapY = emptyList() }
            movingSelection = true; lastMoveX = start.x; lastMoveY = start.y; return
        }
        setSelection(CanvasSelection())
        lasso = listOf(start)
        documentGesture = beginDocument(Stroke(Tool.LASSO, inkColor, inkWidth, listOf(start), inkOpacity, layer = activeLayer), true)
        if (documentGesture) onDocumentGesturePresence(true)
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
        if (finishDocument()) { lasso = null; movingSelection = false; resizingSelection = false; rotatingSelection = false; return }
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
        if (documentSelectionOwner) {
            onDocumentSelectionDrag?.invoke(selectionDx, selectionDy, true)
            selectionDx = 0f; selectionDy = 0f; rawSelectionDx = 0f; rawSelectionDy = 0f; dragOffPage = false
            return
        }
        if (dropObjects()) {
            dragOffPage = false
            setSelection(CanvasSelection())
            return
        }
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
        selectionDx = 0f; selectionDy = 0f; rawSelectionDx = 0f; rawSelectionDy = 0f
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
        // Each member is swapped in place (the transforms keep the selection's order), so moving
        // or turning ink never lifts it above what was drawn over it. Identity lookups: `it in
        // selection` walked every InkPoint per stroke and stalled dense pages.
        val strokeFor = java.util.IdentityHashMap<Stroke, Stroke>(selection.size * 2 + 1)
        if (strokes.size == selection.size) selection.forEachIndexed { i, old -> strokeFor[old] = strokes[i] }
        val allStrokes = if (strokeFor.size == selection.size && page.strokes.count { strokeFor.containsKey(it) } == selection.size) page.strokes.map { strokeFor[it] ?: it }
            else page.strokes.filterNot { s -> selection.any { it === s } } + strokes
        val textFor = if (texts.size == selectedTexts.size && selectedTexts.all { box -> page.texts.any { it.id == box.id } }) selectedTexts.indices.associate { selectedTexts[it].id to texts[it] } else null
        val allTexts = if (textFor != null) page.texts.map { textFor[it.id] ?: it }
            else page.texts.filterNot { box -> selectedTexts.any { it.id == box.id } } + texts
        val imageFor = if (images.size == selectedImages.size && selectedImages.all { image -> page.images.any { it.id == image.id } }) selectedImages.indices.associate { selectedImages[it].id to images[it] } else null
        val allImages = if (imageFor != null) page.images.map { imageFor[it.id] ?: it }
            else page.images.filterNot { image -> selectedImages.any { it.id == image.id } } + images
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
    /**
     * The loop being drawn: a white-haloed blue line over a light wash, a faint guide back to the
     * start, and a start dot that grows into a ring once the tip is close enough to close the loop.
     */
    private fun drawLasso(canvas: Canvas, loop: List<InkPoint>) = chrome.lasso(canvas, loop, selectionUiUnit())
    private fun drawTextBox(canvas: Canvas, box: TextBox) = chrome.textBox(canvas, box, selectionUiUnit(preview = true))
    /** The lasso selection's frame; a document-wide selection is moved by its owner, so it has no handles here. */
    private fun drawSelectionFrame(canvas: Canvas) {
        val box = selectionBox() ?: return
        chrome.frame(canvas, box, selectionUiUnit(preview = true), handles = !documentSelectionOwner)
    }
    private fun drawImageSelection(canvas: Canvas, image: PageImage, withHandle: Boolean = true) =
        chrome.image(canvas, image, selectionUiUnit(preview = true), withHandle)
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
        pendingMeasurement = (heldShape?.singleOrNull() ?: draft)?.takeIf { shapeMeasurements && it.tool in MEASURE_TOOLS }
            ?.let { ShapeMeasurement.from(it, graphStyle, originX, originY, scale) }
        removeCallbacks(reportMeasurement)
        if (pendingMeasurement != reportedMeasurement) post(reportMeasurement)
    }

    /**
     * A ring under the tip, so the eraser's size is visible while it hovers and while it cuts.
     * Rubber is a solid ring with a centre dot; whole-stroke is dashed with no dot, since it
     * removes what the ring touches rather than the area inside it. Screen-sized chrome.
     */
    private fun drawEraser(canvas: Canvas, at: InkPoint) =
        eraserRing.draw(canvas, at.x, at.y, eraserRadiusAt(at), selectionUiUnit(), erasing != null, eraserWholeStroke)
    /** Half the ruler's thickness in page units: the band is a fixed width on screen. */
    private fun rulerHalf() = RULER_THICKNESS_DP / 2f * selectionUiUnit()
    /** Half its length in page units: long enough to span the view from anywhere on it. */
    private fun rulerHalfLength() = hypot(width.toFloat(), height.toFloat()) * 1.5f / scale.coerceAtLeast(1e-3f)

    /**
     * One finger on the ruler slides it and a second finger turns it about their midpoint; the
     * angle settles on 15° steps as it passes them. The pen never moves the ruler. Returns true
     * while the ruler owns the gesture.
     */
    private fun handleRuler(event: MotionEvent, touchNavigation: Boolean): Boolean {
        val line = shownRuler
        if (line == null) { if (rulerFingers.isNotEmpty()) endRulerGesture(); return false }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            rulerFingers.clear()
            if (touchNavigation || readOnly || navigationOnly || isStylus(event, 0)) return false
            val at = point(event, 0)
            if (!line.onBand(at.x, at.y, rulerHalf(), rulerHalfLength())) return false
            rulerFingers[event.getPointerId(0)] = at; rulerRaw = line
            parent?.requestDisallowInterceptTouchEvent(true)
            onActive(); suspendWritingFollow(); removeCallbacks(longPressRunnable)
            invalidate()
            return true
        }
        if (rulerFingers.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                if (rulerFingers.size < 2 && !isStylus(event, i)) rulerFingers[event.getPointerId(i)] = point(event, i)
            }
            MotionEvent.ACTION_MOVE -> {
                val ids = rulerFingers.keys.toList()
                val now = ids.map { id -> event.findPointerIndex(id).let { if (it < 0) null else point(event, it) } }
                if (now.all { it != null }) {
                    var raw = rulerRaw ?: line
                    if (ids.size == 1) {
                        val last = rulerFingers.getValue(ids[0]); val p = now[0]!!
                        raw = raw.moved(p.x - last.x, p.y - last.y)
                    } else {
                        val a0 = rulerFingers.getValue(ids[0]); val b0 = rulerFingers.getValue(ids[1])
                        val a1 = now[0]!!; val b1 = now[1]!!
                        val px = (a0.x + b0.x) / 2f; val py = (a0.y + b0.y) / 2f
                        val delta = RulerLine.turn(RulerLine.angle(a0.x, a0.y, b0.x, b0.y), RulerLine.angle(a1.x, a1.y, b1.x, b1.y))
                        raw = raw.twisted(px, py, delta, (a1.x + b1.x) / 2f - px, (a1.y + b1.y) / 2f - py)
                    }
                    ids.forEachIndexed { k, id -> rulerFingers[id] = now[k]!! }
                    rulerRaw = raw
                    val shown = raw.settled()
                    if (shown != ruler) { ruler = shown; onRulerChanged(shown) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                rulerFingers.remove(event.getPointerId(event.actionIndex))
                if (rulerFingers.isEmpty()) endRulerGesture()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endRulerGesture()
        }
        invalidate()
        return true
    }
    private fun endRulerGesture() {
        if (rulerFingers.isEmpty() && rulerRaw == null) return
        rulerFingers.clear(); rulerRaw = null
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }
    /** Puts a ruler across the middle of what is on screen the first time it is switched on for this page. */
    private fun placeRuler() {
        if (!rulerEnabled || ruler != null || width <= 0 || height <= 0) return
        val visible = android.graphics.Rect()
        val shown = getLocalVisibleRect(visible) && !visible.isEmpty
        val sx = if (shown) visible.exactCenterX() else width / 2f
        val sy = if (shown) visible.exactCenterY() else height / 2f
        val placed = RulerLine((sx - originX) / scale, (sy - originY) / scale)
        ruler = placed
        post { onRulerChanged(placed) }
    }
    /**
     * Decides, as a stroke starts at [start], whether it runs along the ruler: a pen or highlighter
     * stroke that lands on the band or within reach of an edge follows that edge for its whole length.
     */
    private fun lockToRuler(start: InkPoint, drawing: Tool): InkPoint {
        rulerEdge = 0
        val line = shownRuler ?: return start
        if (drawing !in FREEHAND_TOOLS) return start
        rulerEdge = line.edgeAt(start.x, start.y, rulerHalf(), rulerHalfLength(), RULER_SNAP_DP * selectionUiUnit())
        return snapToRuler(start)
    }
    /** A sample of a stroke locked to the ruler, carried onto its edge. */
    private fun snapToRuler(p: InkPoint): InkPoint {
        val line = shownRuler
        if (rulerEdge == 0 || line == null) return p
        return line.onEdge(p, rulerEdge, rulerHalf())
    }
    /**
     * A translucent straightedge with millimetre and centimetre ticks along both edges (A4-sized
     * pages are in points, 72 to the inch) and its angle in the middle. Ticks thin out when zoomed
     * far enough out that they would crowd.
     */
    /** Starts a presenter stroke at [start]. Nothing is written to the page. */
    private fun beginLaser(start: InkPoint) {
        onPenInput(false)
        laser.begin(start)
        documentGesture = onDocumentGestureStart?.invoke(Stroke(Tool.LASER, inkColor, inkWidth, listOf(start)), false) == true
        if (documentGesture) onDocumentGesturePresence(true)
        invalidate()
    }
    internal fun addDocumentLaser(points: List<InkPoint>) {
        laser.addDocument(points)
        invalidate()
    }
    internal fun finishDocumentLaser() {
        laser.finishDocument()
        invalidate()
    }
    /** Extends the trail with the pen's samples; lifting or a second finger ends the stroke and it fades out. */
    private fun handleLaser(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) {
                val points = samples(event, index)
                points.forEach(laser::add)
                documentSamples(points)
            }
            MotionEvent.ACTION_UP -> { if (index >= 0) documentSamples(listOf(point(event, index))); finishDocument(); cancelGesture() }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelGesture()
        }
        invalidate()
    }
    private fun liftLaser() {
        laser.lift()
        invalidate()
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
        clearPenStartHold()
        penFilter = null
        // onTouchEvent already stopped follow on contact and retained finishing-mark intent.
        // Cancelling again here would clear that intent before a dot/crossbar can resume it.
        val raw = point(event, index)
        // The laser points anywhere in the view, paper or not, and writes nothing.
        if (tool == Tool.LASER) { beginLaser(raw); return }
        val erases = tool == Tool.ERASER || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        // Ink belongs to what the pen lands on: a stroke that starts on a note is the note's, even
        // when the note hangs off the paper, and it is never clamped to the page.
        val note = visibleNotes().lastOrNull { StickyNotes.contains(it, raw.x, raw.y) }
        // A note on a locked layer is still paper over the page: nothing may be written beneath it.
        if (note != null && !editableLayer(note.layer)) { ignored = true; onLayerBlocked(); return }
        if (note == null && !onPage(raw.x, raw.y)) { navigating = true; return }
        var start = if (note != null) raw else clampToPage(raw)
        if (!erases) start = lockToRuler(start, tool)
        if (snapEnabled && tool in ShapePickerTools && page.paper.isGrid) {
            start = InkGeometry.snapToGrid(start, page.paper.gridSpacing)
        }
        if (erases) {
            beginErasing(start)
        } else {
            // A note is written on whatever layer is active: it carries its own ink on its own layer.
            if (note == null && !PageLayers.editable(page.layers, activeLayer)) { ignored = true; onLayerBlocked(); return }
            noteTarget = note?.id
            if (tool == Tool.PEN && rulerEdge == 0) {
                penFilter = PenSmoothing(penSmoothing, scale).also { it.sample(start, event.eventTime) }
            }
            val feel = context.getSharedPreferences("preferences", 0)
            speedWidth = if (tool == Tool.PEN && feel.getBoolean(AppPrefs.PEN_SPEED_WIDTH, AppPrefs.DEFAULT_PEN_SPEED_WIDTH))
                PenPressure.SpeedWidth(scale).also { it.factor(start.x, start.y, event.eventTime) } else null
            // A highlighter has one width per stroke, so a tilted pen lays its broad edge down once, at contact.
            val strokeWidth = if (tool == Tool.HIGHLIGHTER && isStylus(event, index) &&
                feel.getBoolean(AppPrefs.HIGHLIGHTER_TILT, AppPrefs.DEFAULT_HIGHLIGHTER_TILT))
                inkWidth * PenPressure.tiltScale(event.getAxisValue(MotionEvent.AXIS_TILT, index)) else inkWidth
            draft = Stroke(tool, inkColor, strokeWidth, arrayListOf(start), inkOpacity,
                style = if (tool in ShapePickerTools) inkStyle else StrokeStyle.SOLID, layer = activeLayer)
            startLowLatency(note == null)
            clearShapeHold()
            trackShapeHold(event, index)
            if (tool in FREEHAND_TOOLS || tool in ShapePickerTools) {
                documentGesture = beginDocument(draft!!, false)
                if (documentGesture) { onDocumentGesturePresence(true); documentSamples(emptyList()) }
            }
            if (tool == Tool.PEN && stylus) {
                penStartTarget = penStartPreference()
                if (penStartTarget != null) {
                    penStartContact = raw
                    penStartPressure = event.getPressure(index)
                    penStartTilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
                    penStartHold.begin(event.getX(index), event.getY(index), event.eventTime)
                    penStartHold.remaining(SystemClock.uptimeMillis())?.let { postDelayed(penStartHoldRunnable, it) }
                }
            }
            onPenInput(true)
        }
    }


    private fun beginErasing(start: InkPoint) {
        val radius = eraserRadiusAt(start)
        // Over a note the eraser works on the note's ink, never the page ink hidden beneath it.
        noteErasing = null
        erasing = if (eraseNotes(listOf(start)).isEmpty()) page.strokes
        else if (eraserWholeStroke) {
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
        documentGesture = beginDocument(Stroke(Tool.ERASER, inkColor, contactWidth, listOf(start), layer = activeLayer), false)
        if (documentGesture) { onDocumentGesturePresence(true); documentSamples(emptyList()) }
        eraserMark = start
        onPenInput(false)
    }

    /** Starts the optional low-latency layer for a plain opaque pen stroke on the page, when it is on and ready. */
    private fun startLowLatency(onPage: Boolean) {
        lowLatency = null; lowLatencySent = 0
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        if (!context.getSharedPreferences("preferences", 0).getBoolean(AppPrefs.LOW_LATENCY_INK, AppPrefs.DEFAULT_LOW_LATENCY_INK)) {
            LowLatencyInk.dispose(this); return
        }
        if (tool != Tool.PEN || !stylus || !onPage || inkOpacity < .99f || rulerEdge != 0 || readOnly) return
        val layer = LowLatencyInk.obtain(this)?.takeIf { it.ready } ?: return
        layer.begin()
        lowLatency = layer
        streamLowLatency()
    }

    /** Sends the samples added to the draft since the last call to the low-latency layer. */
    private fun streamLowLatency() {
        val layer = lowLatency ?: return
        val stroke = draft ?: return
        if (stroke.tool != Tool.PEN || heldShape != null || stroke.points.size <= lowLatencySent) return
        getLocalVisibleRect(visibleScratch)
        lowLatencyClip.set(visibleScratch)
        if (!page.infinite) lowLatencyClip.intersect(originX, originY, originX + page.width * scale, originY + page.height * scale)
        layer.append(this, originX, originY, scale, lowLatencyClip, stroke.color, stroke.width, stroke.points, lowLatencySent)
        lowLatencySent = stroke.points.size
    }

    private fun endLowLatency() { lowLatency?.end(); lowLatency = null; lowLatencySent = 0 }

    private fun clearPrediction() = prediction.clear()

    /** After a move is applied, refreshes the predicted tail of a live pen stroke, or drops it when there is none to draw. */
    private fun refreshPrediction(event: MotionEvent) {
        val current = draft
        if (event.actionMasked != MotionEvent.ACTION_MOVE || current == null || current.tool != Tool.PEN ||
            !stylus || offPage || heldShape != null || documentSpanning) { clearPrediction(); return }
        val index = event.findPointerIndex(pointerId)
        val tip = current.points.lastOrNull()
        if (index < 0 || tip == null) { clearPrediction(); return }
        prediction.refresh(event, index, tip, originX, originY, scale)
    }

    private fun isStylus(event: MotionEvent, index: Int) = isStylusPointer(event, index)
    private fun point(e: MotionEvent, i: Int, history: Int? = null, speed: Float = 1f): InkPoint {
        val x = if (history == null) e.getX(i) else e.getHistoricalX(i, history)
        val y = if (history == null) e.getY(i) else e.getHistoricalY(i, history)
        val rawPressure = if (history == null) e.getPressure(i) else e.getHistoricalPressure(i, history)
        val pressure = when (contactTool) {
            Tool.PEN -> PenPressure.sample(rawPressure, stylus && pressureEnabled, pressureSensitivity, pressureVariation, pressureCurve, speed)
            Tool.ERASER -> if (!stylus || !eraserPressureEnabled) 1f else rawPressure.coerceIn(.25f, 1.8f)
            else -> if (!stylus || !contactPressureEnabled) 1f else rawPressure.coerceIn(.25f, 1.8f)
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
        // A note's ink is clipped to the note when drawn, so its samples need no clamping.
        if (noteTarget != null) return if (rulerEdge == 0) points else points.map(::snapToRuler)
        if (offPage) return emptyList()
        val accepted = mutableListOf<InkPoint>()
        for (p in points) {
            accepted += snapToRuler(clampToPage(p))
            if (!onPage(p.x, p.y)) { offPage = true; break }
        }
        return accepted
    }
    private fun eraserRadiusAt(p: InkPoint) =
        if (eraserPressureEnabled && stylus) contactWidth / 2f * InkGeometry.eraserScale(p.pressure) else contactWidth / 2f
    /**
     * Applies eraser samples that land on a note to that note's ink (held in [noteErasing] until the
     * eraser lifts) and returns the samples clear of every note, which are the page's to erase.
     * A sample over a note on a locked layer erases nothing: the page ink there is hidden.
     */
    private fun eraseNotes(centers: List<InkPoint>): List<InkPoint> {
        val notes = visibleNotes()
        if (notes.isEmpty()) return centers
        val outside = ArrayList<InkPoint>(centers.size)
        for (c in centers) {
            val top = notes.lastOrNull { StickyNotes.contains(it, c.x, c.y) }
            if (top == null) { outside += c; continue }
            if (!editableLayer(top.layer)) continue
            val erased = noteErasing ?: HashMap<String, List<Stroke>>().also { noteErasing = it }
            val ink = erased[top.id] ?: top.stickyInk
            if (ink.isEmpty()) continue
            val local = InkPoint(c.x - top.x, c.y - top.y, c.pressure)
            val r = eraserRadiusAt(c)
            var changed = false
            val kept = ArrayList<Stroke>(ink.size)
            for (stroke in ink) {
                if (eraserWholeStroke) {
                    if (InkGeometry.hits(stroke, local, r)) changed = true else kept += stroke
                } else {
                    val out = InkGeometry.erase(stroke, local, r)
                    if (out.size != 1 || out[0] !== stroke) changed = true
                    kept.addAll(out)
                }
            }
            if (changed) erased[top.id] = kept
        }
        return outside
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
    private fun samples(event: MotionEvent, index: Int, finishing: Boolean = false): ArrayList<InkPoint> {
        val points = ArrayList<InkPoint>(event.historySize + 1)
        val filter = penFilter.takeIf { draft?.tool == Tool.PEN && rulerEdge == 0 }
        val thinning = speedWidth.takeIf { draft?.tool == Tool.PEN }
        for (history in 0 until event.historySize) {
            val time = event.getHistoricalEventTime(history)
            val speed = thinning?.factor((event.getHistoricalX(index, history) - originX) / scale,
                (event.getHistoricalY(index, history) - originY) / scale, time) ?: 1f
            val raw = point(event, index, history, speed)
            points += filter?.sample(raw, time) ?: raw
        }
        val speed = thinning?.factor((event.getX(index) - originX) / scale, (event.getY(index) - originY) / scale, event.eventTime) ?: 1f
        val raw = point(event, index, speed = speed)
        points += filter?.sample(raw, event.eventTime, final = finishing) ?: raw
        return points
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private companion object {
        const val UNPLACED_RESPONSE = "Start writing where your response begins"
        /** Tint over the ink a held scrub is about to erase. */
        const val HELD_ERASE_COLOR = 0xFFE53935.toInt()
        /** Freehand tools grow sample-by-sample; shapes re-derive from two corners. */
        val FREEHAND_TOOLS = setOf(Tool.PEN, Tool.HIGHLIGHTER)
        /** Shapes that show live measurements while drawn. */
        val MEASURE_TOOLS = ShapePickerTools
        /** Page units of slack around the page edge, absorbing samples reported outside the view. */
        const val EDGE_TOLERANCE = 24f
        /** How far a press on a PDF link may wander before the gesture becomes a pan. */
        const val LINK_SLOP = 12f
        /** Above this many selected strokes the halo double-draw is skipped to avoid 2× overdraw. */
        const val SELECTION_HALO_LIMIT = 40
        /** The straightedge: its on-screen thickness and how far off an edge a pen still lands on it, in dp. */
        const val RULER_THICKNESS_DP = 72f
        const val RULER_SNAP_DP = 20f
        /** Preview raster budget (~2.4 MP, ~10 MB); the cache cap sits just above so rounding never falls back to vector. */
        const val NAVIGATION_INK_PIXEL_BUDGET = 2_400_000.0
        /** Overscan around the visible ink raster, as a fraction of the viewport per side; the last step is none. */
        val INK_OVERSCAN_STEPS = floatArrayOf(.5f, .25f, 0f)
        /** Kept under [CommittedInkCache]'s default 8 MP so overscan never pushes a page onto the vector path. */
        const val INK_OVERSCAN_MAX_PIXELS = 7_500_000.0
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
