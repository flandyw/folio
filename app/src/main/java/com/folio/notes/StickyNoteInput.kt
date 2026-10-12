package com.folio.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * A note being carried by hand, in window pixels: where its top-left corner is now, how many pixels
 * one page unit is, and where the finger is. [box] is the note as it was picked up. The editor draws
 * it above every page and may re-home it on another page when it is let go.
 */
internal class StickyLift(val box: TextBox, val left: Float, val top: Float, val unit: Float, val fingerX: Float, val fingerY: Float)

/**
 * Sticky note handling on an [InkView]: making, picking up, moving, resizing and typing into notes.
 * Writing and erasing inside a note is *not* here — the pen, highlighter, shapes and eraser go
 * through the page's own ink pipeline (same smoothing, pressure, dots, ruler and shape tidy-up),
 * and [InkView] files a stroke under the note it started on.
 *
 * With the sticky tool: a tap on bare space drops a default note, a drag draws one to size, a drag
 * on a note moves it, the focused note's corner grip resizes it, a tap focuses a note (its menu
 * appears) and a tap on the focused note types in it. The focused note's corner grip resizes it under any
 * tool. With the text tool a tap on a note types in
 * it. With the hand — or a finger that is navigating rather than drawing — a clean tap on a note
 * focuses it, so its menu is always one tap away without changing tools, and that same input then drags the
 * focused note to move it.
 */
internal class StickyNoteInput(
    private val host: View,
    private val page: () -> NotePage,
    private val commit: (List<TextBox>) -> Unit,
    /** The note's rectangle in view pixels. */
    private val frame: (TextBox) -> RectF,
    /** The focused note (menu above it) and whether it is being typed in; null when none. */
    private val onFocus: (TextBox?, Boolean) -> Unit
) {
    private enum class Mode { CREATE, MOVE, RESIZE, TYPE_TAP }

    private var mode: Mode? = null
    private var start: InkPoint? = null
    private var original: TextBox? = null
    private var preview: TextBox? = null
    private var moved = false
    private var cancelled = false
    /** The press put a focused note down; a plain tap then only does that, it makes no new note. */
    private var dismissed = false
    private var tapHit: TextBox? = null
    private var fromX = 0f
    private var fromY = 0f
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val density = host.resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = FOCUS_COLOR }
    private val gripFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val gripEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = FOCUS_COLOR }
    /** The paper the next new note wears: whichever colour was picked last. */
    private var nextPaper = 0

    /**
     * While a note is being moved, reports it in window pixels (null once let go) so the editor can
     * carry it over the whole workspace rather than only the page's own view. Left null, the note is
     * moved inside its view.
     */
    var onLift: ((StickyLift?) -> Unit)? = null
    /** Offered a lifted note on release; true when the editor placed it (e.g. on another page). */
    var onDrop: ((StickyLift) -> Boolean)? = null
    private var lift: StickyLift? = null

    var focusedId: String? = null
        private set
    var typing = false
        private set

    fun focused(): TextBox? = focusedId?.let { id -> page().texts.find { it.id == id && it.isSticky } }

    /** The focused note where the user sees it: mid-drag it is the live preview, so its menu travels with it. */
    fun shown(): TextBox? {
        val live = preview?.takeIf { moved && mode != Mode.CREATE && it.id == focusedId }
        return live ?: focused()
    }

    fun focus(box: TextBox?, type: Boolean = false) {
        focusedId = box?.id; typing = box != null && type
        onFocus(box, typing)
        host.invalidate()
    }

    /** The view is going away mid-carry: place the note where it was last held instead of losing the drop. */
    fun dropLift() { lift?.let { onDrop?.invoke(it) } }

    /** Drops a gesture in progress and the focus, e.g. on a tool or page change. */
    fun reset() {
        clearGesture()
        tapHit = null
        if (focusedId != null) focus(null)
    }

    /** Abandons a gesture in progress without changing the focused note. */
    fun cancel() {
        clearGesture()
        tapHit = null
        host.parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** Re-reads the focused note after the page changed underneath it (undo, delete, move). */
    fun refresh() {
        if (focusedId == null) return
        val box = focused()?.takeIf { PageLayers.editable(page().layers, it.layer) }
        if (box == null) focus(null) else onFocus(shown() ?: box, typing)
    }

    fun setText(id: String, text: String) {
        page().texts.find { it.id == id && it.isSticky }?.let { replace(it.copy(text = text)) }
    }

    /** Gives the note the stored paper [paper] (0 is yellow); new notes wear it too from now on. */
    fun setPaper(id: String, paper: Int) {
        nextPaper = paper
        page().texts.find { it.id == id && it.isSticky }?.let { replace(it.copy(stickyColor = paper)) }
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

    /** Lifts the note to the top of the stack, above any note it overlaps. */
    fun bringToFront(id: String) {
        val texts = page().texts
        val box = texts.find { it.id == id && it.isSticky } ?: return
        if (texts.last { it.isSticky }.id == id) return
        commit(texts.filterNot { it.id == id } + box)
    }

    fun delete(id: String) {
        if (focusedId == id) focus(null)
        commit(page().texts.filterNot { it.id == id })
    }

    /**
     * Offers the gesture at [at] (page units) to the notes. Returns true when the notes own it, in
     * which case the view must not start its own gesture. [canCreate] says whether this pointer may
     * make a new note on bare space (a pen, or a finger while finger drawing is on). [tapFocuses]
     * says whether a clean tap on a note should focus it while the view handles the gesture itself.
     */
    fun touch(event: MotionEvent, at: InkPoint, tool: Tool, canCreate: Boolean, tapFocuses: Boolean, layer: Int): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearGesture()
            val layers = page().layers
            val editable = { l: Int -> PageLayers.editable(layers, l) }
            val hit = StickyNotes.at(page().texts, at.x, at.y, editable)
            val focusedBox = focused()
            // The grip hangs off the corner, so it is tested before the note it belongs to. It is drawn
            // whatever the tool, so it works with every tool; it is hidden (and dead) while typing.
            val onGrip = focusedBox != null && !typing && gripHit(focusedBox, event.x, event.y)
            // Touching anything but the focused note puts it down.
            dismissed = focusedBox != null && !onGrip && hit?.id != focusedBox.id
            if (dismissed) focus(null)
            fromX = event.x; fromY = event.y
            tapHit = null
            mode = when {
                onGrip -> Mode.RESIZE
                tool == Tool.STICKY_NOTE && hit != null -> Mode.MOVE
                // A note already picked up follows a hand or navigating finger, whatever the tool: that
                // input cannot write on it, so dragging it can only mean moving it.
                tapFocuses && hit != null && hit.id == focusedBox?.id && !typing -> Mode.MOVE
                tool == Tool.STICKY_NOTE && canCreate && editable(layer) -> Mode.CREATE
                tool == Tool.TEXT && hit != null -> Mode.TYPE_TAP
                else -> null
            }
            if (mode == null) {
                // Another tool owns this gesture; a clean tap on a note still focuses it.
                if (tapFocuses) tapHit = hit
                return false
            }
            host.parent?.requestDisallowInterceptTouchEvent(true)
            start = at; moved = false; cancelled = false
            original = if (onGrip) focusedBox else hit
            preview = original
            host.invalidate()
            return true
        }
        val current = mode ?: return trackTap(event)
        val began = start ?: return trackTap(event)
        when (event.actionMasked) {
            // A second finger is a pinch: abandon the note gesture and let the workspace zoom.
            MotionEvent.ACTION_POINTER_DOWN -> { cancelled = true; preview = original; endLift() }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!cancelled) {
                moved = moved || hypot(event.x - fromX, event.y - fromY) > slop
                val box = original
                if (moved) preview = when (current) {
                    Mode.CREATE -> StickyNotes.spanning(began.x, began.y, at.x, at.y, layer, nextPaper)
                    // The corner follows the finger's travel, so grabbing the grip off-centre never makes it jump.
                    Mode.RESIZE -> box?.let { StickyNotes.resized(it, it.x + it.width + at.x - began.x, it.y + it.stickyHeight + at.y - began.y) }
                    Mode.MOVE -> box?.moved(at.x - began.x, at.y - began.y)
                    Mode.TYPE_TAP -> box
                }
                if (moved && current == Mode.MOVE) liftNote(event, began, at, box)
                // The note's menu rides along with the note instead of waiting where it was picked up.
                if (moved && current != Mode.CREATE && box?.id == focusedId) preview?.let { onFocus(it, typing) }
                if (event.actionMasked == MotionEvent.ACTION_UP) finish(current, began, layer)
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

    private fun finish(mode: Mode, began: InkPoint, layer: Int) {
        val box = original
        when (mode) {
            Mode.CREATE -> {
                val made = if (moved) preview else if (dismissed) null else StickyNotes.centred(began.x, began.y, layer, nextPaper)
                if (made != null) { commit(page().texts + made); focus(made) }
            }
            Mode.MOVE, Mode.RESIZE -> when {
                box == null -> Unit
                moved && lift?.let { onDrop?.invoke(it) } == true -> focus(null)
                moved -> preview?.let { next -> replace(next); page().texts.find { it.id == next.id }?.let { focus(it) } }
                // A tap focuses the note; tapping the focused note again types in it.
                focusedId == box.id && mode == Mode.MOVE -> focus(box, type = true)
                else -> focus(box)
            }
            Mode.TYPE_TAP -> if (!moved && box != null) focus(box, type = true)
        }
    }

    private fun clearGesture() {
        mode = null; start = null; original = null; preview = null; moved = false; cancelled = false
        endLift()
        host.invalidate()
    }

    private fun liftNote(event: MotionEvent, began: InkPoint, at: InkPoint, box: TextBox?) {
        val report = onLift ?: return
        if (box == null) return
        val r = frame(box)
        val unit = r.width() / box.width
        val origin = IntArray(2).also(host::getLocationInWindow)
        val held = StickyLift(box, origin[0] + r.left + (at.x - began.x) * unit, origin[1] + r.top + (at.y - began.y) * unit,
            unit, origin[0] + event.x, origin[1] + event.y)
        lift = held
        report(held)
    }

    private fun endLift() {
        if (lift == null) return
        lift = null
        onLift?.invoke(null)
    }

    private fun gripHit(box: TextBox, x: Float, y: Float): Boolean {
        val r = frame(box)
        return hypot(x - r.right, y - r.bottom) <= GRIP_REACH_DP * density
    }

    private fun replace(box: TextBox) {
        // An undo or delete while the gesture ran must never resurrect a stale note.
        val current = page().texts.find { it.id == box.id } ?: return
        if (current != box) commit(page().texts.map { if (it.id == box.id) box else it })
    }

    /**
     * Draws [notes] bottom to top with any live gesture applied, then a note still being dragged
     * out. [liveInk] replaces a note's ink while the eraser works on it, and [afterNote] runs right
     * after each note so ink being written into it lands above that note but below any note on top.
     * [unit] is one dp in page units.
     */
    fun draw(canvas: Canvas, notes: List<TextBox>, unit: Float,
             liveInk: Map<String, List<Stroke>> = emptyMap(), afterNote: (TextBox) -> Unit = {}) {
        notes.forEach { note ->
            // A carried note is drawn by the editor above every page.
            if (lift != null && note.id == original?.id) return@forEach
            var box = if (note.id == original?.id) preview ?: note else note
            liveInk[box.id]?.let { box = box.copy(stickyInk = it) }
            // The typing field shows the words while it is open; the note keeps its paper and ink.
            InkRenderer.text(canvas, if (typing && box.id == focusedId) box.copy(text = "") else box)
            afterNote(box)
            if (box.id == focusedId) drawFocus(canvas, box, unit)
        }
        if (mode == Mode.CREATE) preview?.let { InkRenderer.text(canvas, it); drawFocus(canvas, it, unit) }
    }

    private fun drawFocus(canvas: Canvas, box: TextBox, unit: Float) {
        val inset = -2f * unit
        outline.strokeWidth = 2f * unit
        canvas.drawRoundRect(box.x + inset, box.y + inset, box.x + box.width - inset, box.y + box.stickyHeight - inset,
            StickyNotes.CORNER + 2f * unit, StickyNotes.CORNER + 2f * unit, outline)
        if (typing && box.id == focusedId) return
        val right = box.x + box.width; val bottom = box.y + box.stickyHeight
        gripEdge.strokeWidth = 2f * unit
        canvas.drawCircle(right, bottom, GRIP_RADIUS_DP * unit, gripFill)
        canvas.drawCircle(right, bottom, GRIP_RADIUS_DP * unit, gripEdge)
    }

    companion object {
        private const val GRIP_REACH_DP = 28f
        private const val GRIP_RADIUS_DP = 7f
        private const val DUPLICATE_OFFSET = 36f
        /** InkView's selection blue. */
        private const val FOCUS_COLOR = 0xFF2F6FBA.toInt()
    }
}
