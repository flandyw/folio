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
 * one page unit is, and where the finger is. [box] is the note as it was picked up, or the current creation/resize preview. The editor draws
 * it above every page and may re-home it on another page when it is let go.
 */
internal class StickyLift(val box: TextBox, val left: Float, val top: Float, val unit: Float, val fingerX: Float, val fingerY: Float, val moving: Boolean = true, val drawControls: ((Canvas) -> Unit)? = null)

/** A rejected transfer must never fall through to committing the stale local preview. */
internal enum class StickyDrop { LOCAL, MOVED, REFUSED }

/**
 * Sticky note handling on an [InkView]: making, picking up, moving, resizing and typing into notes.
 * Writing and erasing inside a note is *not* here — the pen, highlighter, shapes and eraser go
 * through the page's own ink pipeline (same smoothing, pressure, dots, ruler and shape tidy-up),
 * and [InkView] routes ink across the visible page and note surfaces.
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
    private enum class Mode { CREATE, MOVE, RESIZE, TYPE_TAP, BLOCKED }

    private var mode: Mode? = null
    private var start: InkPoint? = null
    private var original: TextBox? = null
    private var preview: TextBox? = null
    private var moved = false
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
    /** The type size the next new note gets; the Settings default, set by the editor. */
    var newTextSize = StickyNotes.TEXT_SIZE

    /**
     * While a note is being moved, reports it in window pixels (null once let go) so the editor can
     * carry it over the whole workspace rather than only the page's own view. Left null, the note is
     * moved inside its view.
     */
    var onLift: ((StickyLift?) -> Unit)? = null
    /** Offered a lifted note on release; distinguishes staying here from a refused transfer. */
    var onDrop: ((StickyLift) -> StickyDrop)? = null
    private var lift: StickyLift? = null
    /** Keep an accepted drop out of the source until its next content bind acknowledges removal. */
    private var droppedId: String? = null

    var focusedId: String? = null
        private set
    var typing = false
        private set

    private fun editableNote(id: String): TextBox? {
        val current = page()
        return current.texts.find { it.id == id && it.isSticky && PageLayers.editable(current.layers, it.layer) }
    }

    fun focused(): TextBox? = focusedId?.let(::editableNote)

    /** The focused note where the user sees it: mid-drag it is the live preview, so its menu travels with it. */
    fun shown(): TextBox? {
        val live = preview?.takeIf { moved && mode != Mode.CREATE && it.id == focusedId }
        return live ?: focused()
    }

    fun focus(box: TextBox?, type: Boolean = false) {
        val current = box?.let { editableNote(it.id) }
        focusedId = current?.id; typing = current != null && type
        onFocus(current, typing)
        host.invalidate()
    }

    /** The view is going away mid-carry: place the note where it was last held instead of losing the drop. */
    fun dropLift() {
        val held = lift?.takeIf { it.moving } ?: return
        val box = original ?: return
        if (editableNote(box.id) != box) return
        when (onDrop?.invoke(held) ?: StickyDrop.LOCAL) {
            StickyDrop.MOVED -> droppedId = box.id
            StickyDrop.REFUSED -> Unit
            StickyDrop.LOCAL -> {
                val r = frame(box)
                val origin = IntArray(2).also(host::getLocationInWindow)
                replace(box.moved((held.left - origin[0] - r.left) / held.unit,
                    (held.top - origin[1] - r.top) / held.unit), box)
            }
        }
    }

    /** Drops a gesture in progress and the focus, e.g. on a tool or page change. */
    fun reset(clearDrop: Boolean = false) {
        clearGesture()
        tapHit = null
        if (clearDrop) droppedId = null
        if (focusedId != null) focus(null)
    }

    /** Abandons a gesture in progress without changing the focused note. */
    fun cancel() {
        clearGesture()
        tapHit = null
        refresh()
        host.parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** Re-reads the focused note after the page changed underneath it (undo, delete, move). */
    fun refresh() {
        if (page().texts.none { it.id == droppedId }) droppedId = null
        original?.let { if (editableNote(it.id) != it) clearGesture() }
        if (focusedId == null) return
        val box = focused()
        if (box == null) focus(null) else onFocus(shown() ?: box, typing)
    }

    fun setText(id: String, text: String) {
        editableNote(id)?.let { replace(it.copy(text = text)) }
    }

    /** Gives the note the stored paper [paper] (0 is yellow); new notes wear it too from now on. */
    fun setPaper(id: String, paper: Int) {
        editableNote(id)?.let { nextPaper = paper; replace(it.copy(stickyColor = paper)) }
    }

    /** Gives the note its own type size; the note's words reflow inside it, its paper stays as it is. */
    fun setTextSize(id: String, size: Float) {
        editableNote(id)?.let { replace(it.copy(size = StickyNotes.textSize(size))) }
    }

    fun clearInk(id: String) {
        editableNote(id)?.takeIf { it.stickyInk.isNotEmpty() }?.let { replace(it.copy(stickyInk = emptyList())) }
    }

    /** A copy offset down and right, focused so it can be dragged straight away. */
    fun duplicate(id: String) {
        val box = editableNote(id) ?: return
        val copy = box.copy(id = java.util.UUID.randomUUID().toString(), x = box.x + DUPLICATE_OFFSET, y = box.y + DUPLICATE_OFFSET)
        commit(page().texts + copy)
        focus(copy)
    }

    /** Lifts the note to the top of the stack, above any note it overlaps. */
    fun bringToFront(id: String) {
        val texts = page().texts
        val box = editableNote(id) ?: return
        if (texts.last { it.isSticky && PageLayers.resolve(page().layers, it.layer) == PageLayers.resolve(page().layers, box.layer) }.id == id) return
        commit(texts.filterNot { it.id == id } + box)
    }

    fun delete(id: String) {
        if (editableNote(id) == null) return
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
            refresh()
            val layers = page().layers
            val editable = { l: Int -> PageLayers.editable(layers, l) }
            val visible = PageLayers.view(page()).texts
            val covered = visible.lastOrNull { StickyNotes.contains(it, at.x, at.y) }
            val hit = StickyNotes.at(visible, at.x, at.y, editable)
            val focusedBox = focused()
            // The grip hangs off the corner, so it is tested before the note it belongs to. It is drawn
            // whatever the tool, so it works with every tool; it is hidden (and dead) while typing.
            val onGrip = focusedBox != null && !typing && (covered == null || covered.id == focusedBox.id) && gripHit(focusedBox, event.x, event.y)
            // Touching anything but the focused note puts it down.
            dismissed = focusedBox != null && !onGrip && hit?.id != focusedBox.id
            if (dismissed) focus(null)
            fromX = event.x; fromY = event.y
            tapHit = null
            mode = when {
                onGrip -> Mode.RESIZE
                covered != null && hit == null && (tool == Tool.STICKY_NOTE || tool == Tool.TEXT) -> Mode.BLOCKED
                tool == Tool.STICKY_NOTE && hit != null && !typing -> Mode.MOVE
                // A note already picked up follows a hand or navigating finger, whatever the tool: that
                // input cannot write on it, so dragging it can only mean moving it.
                tapFocuses && hit != null && hit.id == focusedBox?.id && !typing -> Mode.MOVE
                tool == Tool.STICKY_NOTE && covered == null && canCreate && editable(layer) -> Mode.CREATE
                tool == Tool.TEXT && hit != null -> Mode.TYPE_TAP
                else -> null
            }
            if (mode == null) {
                // Another tool owns this gesture; a clean tap on a note still focuses it.
                if (tapFocuses) tapHit = hit
                return false
            }
            host.parent?.requestDisallowInterceptTouchEvent(true)
            start = at; moved = false
            original = if (onGrip) focusedBox else hit
            preview = original
            host.invalidate()
            return true
        }
        val current = mode ?: return trackTap(event)
        val began = start ?: return trackTap(event)
        when (event.actionMasked) {
            // A second finger is a pinch: abandon the note gesture and let the workspace zoom.
            MotionEvent.ACTION_POINTER_DOWN -> { cancel(); return false }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                moved = moved || travelled(event)
                val box = original
                if (moved) preview = when (current) {
                    Mode.CREATE -> StickyNotes.spanning(began.x, began.y, at.x, at.y, layer, nextPaper, newTextSize)
                    // The corner follows the finger's travel, so grabbing the grip off-centre never makes it jump.
                    Mode.RESIZE -> box?.let { StickyNotes.resized(it, it.x + it.width + at.x - began.x, it.y + it.stickyHeight + at.y - began.y) }
                    Mode.MOVE -> box?.moved(at.x - began.x, at.y - began.y)
                    Mode.TYPE_TAP, Mode.BLOCKED -> box
                }
                if (moved && current == Mode.MOVE) liftNote(event, began, at, box)
                else if (moved && (current == Mode.CREATE || current == Mode.RESIZE)) preview?.let { liftPreview(event, it) }
                // The note's menu rides along with the note instead of waiting where it was picked up.
                if (moved && current != Mode.CREATE && box?.id == focusedId) preview?.let { onFocus(it, typing) }
                if (event.actionMasked == MotionEvent.ACTION_UP) finish(current, began, layer)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancel() else clearGesture()
            host.parent?.requestDisallowInterceptTouchEvent(false)
        }
        host.invalidate()
        return true
    }

    /** Watches a gesture another tool owns, and focuses the note under it if it ends as a tap. */
    private fun trackTap(event: MotionEvent): Boolean {
        val box = tapHit ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (travelled(event)) tapHit = null
            MotionEvent.ACTION_UP -> { tapHit = null; if (!travelled(event)) editableNote(box.id)?.let { focus(it) } }
            else -> tapHit = null
        }
        return false
    }

    private fun finish(mode: Mode, began: InkPoint, layer: Int) {
        val box = original
        if (box != null && editableNote(box.id) != box) { refresh(); return }
        if (box != null && moved && mode == Mode.MOVE) {
            when (lift?.let { onDrop?.invoke(it) } ?: StickyDrop.LOCAL) {
                StickyDrop.MOVED -> { droppedId = box.id; focus(null); return }
                StickyDrop.REFUSED -> { focus(null); return }
                StickyDrop.LOCAL -> Unit
            }
        }
        when (mode) {
            Mode.CREATE -> {
                if (!PageLayers.editable(page().layers, layer)) return
                val made = if (moved) preview else if (dismissed) null else StickyNotes.centred(began.x, began.y, layer, nextPaper, newTextSize)
                if (made != null) { commit(page().texts + made); focus(made) }
            }
            Mode.MOVE, Mode.RESIZE -> when {
                box == null -> Unit
                moved -> preview?.let { next -> replace(next, box); page().texts.find { it.id == next.id }?.let { focus(it) } }
                // A tap focuses the note; tapping the focused note again types in it.
                focusedId == box.id && mode == Mode.MOVE -> focus(box, type = true)
                else -> focus(box)
            }
            Mode.TYPE_TAP -> if (!moved && box != null) focus(box, type = true)
            Mode.BLOCKED -> Unit
        }
    }

    private fun travelled(event: MotionEvent): Boolean =
        hypot(event.x - fromX, event.y - fromY) > slop || (0 until event.historySize).any {
            hypot(event.getHistoricalX(0, it) - fromX, event.getHistoricalY(0, it) - fromY) > slop
        }

    private fun clearGesture() {
        mode = null; start = null; original = null; preview = null; moved = false
        endLift()
        host.invalidate()
    }

    private fun liftNote(event: MotionEvent, began: InkPoint, at: InkPoint, box: TextBox?) {
        val report = onLift ?: return
        if (box == null) return
        val r = frame(box)
        val unit = frame(box.copy(width = 100f)).width() / 100f
        val origin = IntArray(2).also(host::getLocationInWindow)
        val held = StickyLift(box, origin[0] + r.left + (at.x - began.x) * unit, origin[1] + r.top + (at.y - began.y) * unit,
            unit, origin[0] + event.x, origin[1] + event.y)
        lift = held
        report(held)
    }

    /** Creation and resize previews also cross item boundaries, but never re-home the note. */
    private fun liftPreview(event: MotionEvent, box: TextBox) {
        val report = onLift ?: return
        val r = frame(box)
        // Derive scale from a normal-sized frame; tiny notes may round to zero screen width.
        val unit = frame(box.copy(width = 100f)).width() / 100f
        val origin = IntArray(2).also(host::getLocationInWindow)
        val held = StickyLift(box, origin[0] + r.left, origin[1] + r.top, unit,
            origin[0] + event.x, origin[1] + event.y, moving = false,
            drawControls = { canvas -> drawFocus(canvas, box, density / unit) })
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

    private fun replace(box: TextBox, expected: TextBox? = null) {
        // An undo or delete while the gesture ran must never resurrect a stale note.
        val current = editableNote(box.id) ?: return
        if (expected != null && current != expected) return
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
            if (note.id == droppedId || (lift != null && note.id == original?.id)) return@forEach
            var box = if (note.id == original?.id) preview ?: note else note
            liveInk[box.id]?.let { box = box.copy(stickyInk = it) }
            // The typing field shows the words while it is open; the note keeps its paper and ink.
            InkRenderer.text(canvas, if (typing && box.id == focusedId) box.copy(text = "") else box)
            afterNote(box)
            if (box.id == focusedId) drawFocus(canvas, box, unit)
        }
        if (mode == Mode.CREATE && lift == null) preview?.let { InkRenderer.text(canvas, it); drawFocus(canvas, it, unit) }
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
