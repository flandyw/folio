package com.folio.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Sticky note gestures on an [InkView]. With the sticky tool, a drag on bare workspace makes a
 * note, a drag on a note moves it, its corner grip resizes it and a tap focuses it (a second tap
 * types). Pen, highlighter and eraser strokes that start inside a note go into the note's own ink.
 * Typing itself is a Compose field laid over the note; this class only tracks which note has it.
 */
internal class StickyNoteInput(
    private val host: View,
    private val page: () -> NotePage,
    private val commit: (List<TextBox>) -> Unit,
    /** The note's rectangle in view pixels. */
    private val frame: (TextBox) -> RectF,
    /** Page samples for the current and historical positions of the first pointer. */
    private val samples: (MotionEvent) -> List<InkPoint>,
    /** The focused note (menu above it) and whether it is being typed in; null when none. */
    private val onFocus: (TextBox?, Boolean) -> Unit
) {
    private var start: InkPoint? = null
    private var original: TextBox? = null
    private var preview: TextBox? = null
    private var ink: Stroke? = null
    private val points = arrayListOf<InkPoint>()
    private var resize = false
    private var erasing = false
    private var moved = false
    private var cancelled = false
    private var tapHit: TextBox? = null
    private var fromX = 0f
    private var fromY = 0f
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9D8845.toInt(); strokeWidth = 1.5f }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2B3440.toInt() }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The eraser ring while a note is being erased, in page units. */
    private var eraserAt: InkPoint? = null
    private var eraserRadius = 0f
    private var eraserWhole = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    var focusedId: String? = null
        private set
    var typing = false
        private set

    fun focused(): TextBox? = focusedId?.let { id -> page().texts.find { it.id == id && it.isSticky } }

    fun focus(box: TextBox?, type: Boolean = false) {
        focusedId = box?.id; typing = box != null && type
        onFocus(box, typing)
        host.invalidate()
    }

    /** Drops a gesture in progress and the focus, e.g. on a tool or page change. */
    fun reset() {
        clearGesture()
        if (focusedId != null) focus(null)
    }

    /** Abandons ink/erase/move previews and tap intent without changing the focused note. */
    fun cancel() {
        clearGesture()
        tapHit = null
        host.parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** Re-reads the focused note after the page changed underneath it (undo, delete, move). */
    fun refresh() {
        if (focusedId == null) return
        val box = focused()?.takeIf { PageLayers.editable(page().layers, it.layer) }
        if (box == null) focus(null) else onFocus(box, typing)
    }

    fun setText(id: String, text: String) {
        page().texts.find { it.id == id && it.isSticky }?.let { replace(it.copy(text = text)) }
    }

    /** Cycles the focused note through the paper colours. */
    fun recolor(id: String) {
        page().texts.find { it.id == id && it.isSticky }?.let { replace(it.copy(stickyColor = StickyNotes.nextPaper(it.stickyColor))) }
    }

    fun clearInk(id: String) {
        page().texts.find { it.id == id && it.isSticky && it.stickyInk.isNotEmpty() }?.let { replace(it.copy(stickyInk = emptyList())) }
    }

    /** A copy offset down and right, focused so it can be dragged straight away. */
    fun duplicate(id: String) {
        val box = page().texts.find { it.id == id && it.isSticky } ?: return
        val copy = box.copy(id = java.util.UUID.randomUUID().toString(), x = box.x + DUPLICATE_OFFSET, y = box.y + DUPLICATE_OFFSET)
        commit(page().texts + copy)
        focus(copy)
    }

    fun delete(id: String) {
        if (focusedId == id) focus(null)
        commit(page().texts.filterNot { it.id == id })
    }

    fun touch(event: MotionEvent, at: InkPoint, tool: Tool, canInk: Boolean, layer: Int,
              color: Int, width: Float, opacity: Float, wholeEraser: Boolean,
              pressureEraser: Boolean = false, pageInk: List<Stroke> = emptyList()): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val layers = page().layers
            val hit = page().texts.lastOrNull { StickyNotes.contains(it, at) && PageLayers.editable(layers, it.layer) }
            // The pen's eraser end and its barrel button erase whatever tool is chosen, like the page does.
            val erases = tool == Tool.ERASER || (tool in INK_TOOLS &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)))
            erasing = erases
            val radius = eraserRadius(width, at, pressureEraser)
            // The page's ink shows through a note; an eraser over only that ink belongs to the page.
            val pageOnly = erases && hit != null &&
                hit.stickyInk.none { InkGeometry.hits(it, InkPoint(at.x - hit.x, at.y - hit.y, at.pressure), radius) } &&
                pageInk.any { PageLayers.editable(layers, it.layer) && InkGeometry.hits(it, at, radius) }
            val inking = hit != null && canInk && (tool in INK_TOOLS || erases) && !pageOnly
            val handles = inking || (tool == Tool.STICKY_NOTE && (hit != null || canInk && PageLayers.editable(layers, layer))) ||
                (tool == Tool.TEXT && hit != null)
            // Touching anything but the focused note puts it down.
            if (focusedId != null && hit?.id != focusedId) focus(null)
            // Any other tool leaves its own gesture alone; a clean tap on a note still focuses it.
            tapHit = if (handles) null else hit
            fromX = event.x; fromY = event.y
            if (!handles) return false
            host.parent?.requestDisallowInterceptTouchEvent(true)
            start = at; original = hit; preview = hit; moved = false; cancelled = false
            fromX = event.x; fromY = event.y
            resize = hit != null && !inking &&
                hypot(event.x - frame(hit).right, event.y - frame(hit).bottom) < GRIP_DP * host.resources.displayMetrics.density
            ink = if (inking) Stroke(if (erases) Tool.ERASER else tool, color, width, emptyList(), opacity) else null
            points.clear()
            if (inking) addInk(event, wholeEraser, pressureEraser)
            host.invalidate()
            return true
        }
        val began = start ?: return trackTap(event)
        when (event.actionMasked) {
            // A second finger is a pinch: abandon the note gesture and let the workspace zoom.
            MotionEvent.ACTION_POINTER_DOWN -> { cancelled = true; preview = original }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!cancelled) {
                moved = moved || hypot(event.x - fromX, event.y - fromY) > slop
                val box = original
                when {
                    ink != null -> addInk(event, wholeEraser, pressureEraser)
                    box == null -> preview = TextBox(x = min(began.x, at.x), y = min(began.y, at.y),
                        width = max(abs(at.x - began.x), MIN_WIDTH), stickyHeight = max(abs(at.y - began.y), MIN_HEIGHT),
                        size = TEXT_SIZE, layer = layer)
                    resize -> preview = box.copy(width = (at.x - box.x).coerceIn(MIN_WIDTH, TextBox.MAX_WIDTH),
                        stickyHeight = (at.y - box.y).coerceIn(MIN_HEIGHT, MAX_HEIGHT))
                    moved -> preview = box.moved(at.x - began.x, at.y - began.y)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) finish(tool)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clearGesture()
            host.parent?.requestDisallowInterceptTouchEvent(false)
        }
        host.invalidate()
        return true
    }

    /** Watches a gesture another tool owns, and focuses the note under it if it ends as a tap. */
    private fun trackTap(event: MotionEvent): Boolean {
        val box = tapHit ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(event.x - fromX, event.y - fromY) > slop) tapHit = null
            MotionEvent.ACTION_UP -> { tapHit = null; page().texts.find { it.id == box.id }?.let { focus(it) } }
            else -> tapHit = null
        }
        return false
    }

    private fun finish(tool: Tool) {
        val box = original
        val next = preview ?: return
        when {
            // A tap with a pen is a tap, not a dot of ink.
            ink != null -> if (moved) replace(next) else box?.let { focus(it) }
            box == null -> if (moved) { commit(page().texts + next); focus(next) }
            moved -> { replace(next); focus(next) }
            // A tap focuses the note; tapping the focused note again types in it.
            focusedId == box.id && tool == Tool.STICKY_NOTE -> focus(box, type = true)
            else -> focus(box)
        }
    }

    private fun clearGesture() {
        eraserAt = null; erasing = false
        start = null; original = null; preview = null; ink = null; points.clear()
        host.invalidate()
    }

    private fun eraserRadius(width: Float, at: InkPoint, pressure: Boolean) =
        width / 2f * if (pressure) InkGeometry.eraserScale(at.pressure) else 1f

    private fun addInk(event: MotionEvent, whole: Boolean, pressure: Boolean) {
        val box = original ?: return
        val stroke = ink ?: return
        // Note ink is stored relative to the note, so moving the note carries it along. It is not
        // clamped to the note: a stroke begun inside may run out over the page.
        val raw = samples(event)
        raw.forEach { points += InkPoint(it.x - box.x, it.y - box.y, it.pressure) }
        if (stroke.tool == Tool.ERASER) {
            var kept = (preview ?: box).stickyInk
            // Same rules as the page eraser: pressure scales the ring, whole-stroke removes what it touches.
            for (p in points) {
                val radius = eraserRadius(stroke.width, p, pressure)
                kept = if (whole) kept.filterNot { InkGeometry.hits(it, p, radius) }
                    else kept.flatMap { InkGeometry.erase(it, p, radius) }
            }
            preview = box.copy(stickyInk = kept)
            raw.lastOrNull()?.let {
                eraserAt = it; eraserRadius = eraserRadius(stroke.width, it, pressure); eraserWhole = whole
            }
            points.clear()
        } else preview = box.copy(stickyInk = box.stickyInk + stroke.copy(points = points.toList()))
    }

    private fun replace(box: TextBox) {
        // An undo or delete while the gesture ran must never resurrect a stale note.
        val current = page().texts.find { it.id == box.id } ?: return
        if (current != box) commit(page().texts.map { if (it.id == box.id) box else it })
    }

    /** Draws [notes] with any live gesture applied, plus a note still being dragged out. */
    fun draw(canvas: Canvas, notes: List<TextBox>, unit: Float, beneath: List<Stroke> = emptyList()) {
        notes.forEach { note ->
            val box = if (note.id == original?.id) preview ?: note else note
            // The typing field shows the words while it is open; the note keeps its paper and ink.
            InkRenderer.text(canvas, if (typing && box.id == focusedId) box.copy(text = "") else box, beneath)
            if (box.id == focusedId) {
                outline.color = FOCUS_COLOR; outline.strokeWidth = 2f * unit
                canvas.drawRect(box.x, box.y, box.x + box.width, box.y + box.stickyHeight, outline)
            }
            val right = box.x + box.width; val bottom = box.y + box.stickyHeight
            canvas.drawLine(right - 16f, bottom - 4f, right - 4f, bottom - 16f, grip)
            canvas.drawLine(right - 10f, bottom - 4f, right - 4f, bottom - 10f, grip)
        }
        if (original == null) preview?.let { InkRenderer.text(canvas, it, beneath) }
        eraserAt?.let { c ->
            ringFill.color = 0x3A2B3440
            canvas.drawCircle(c.x, c.y, eraserRadius, ringFill)
            ring.strokeWidth = 1.5f * unit
            ring.pathEffect = if (eraserWhole) android.graphics.DashPathEffect(floatArrayOf(5f * unit, 4f * unit), 0f) else null
            canvas.drawCircle(c.x, c.y, eraserRadius, ring)
        }
    }

    companion object {
        private val INK_TOOLS = setOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER)
        private const val GRIP_DP = 28f
        private const val DUPLICATE_OFFSET = 36f
        private const val MIN_WIDTH = 90f
        private const val MIN_HEIGHT = 60f
        private const val MAX_HEIGHT = 10000f
        private const val TEXT_SIZE = 22f
        /** InkView's selection blue. */
        private const val FOCUS_COLOR = 0xFF2F6FBA.toInt()
    }
}
