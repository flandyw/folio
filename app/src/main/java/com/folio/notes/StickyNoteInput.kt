package com.folio.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import kotlin.math.*

/** Direct manipulation and an editor anchored on the note, without a modal dialog. */
internal class StickyNoteInput(
    private val host: View,
    private val page: () -> NotePage,
    private val commit: (List<TextBox>) -> Unit,
    private val frame: (TextBox) -> RectF
) {
    private var start: InkPoint? = null
    private var original: TextBox? = null
    private var preview: TextBox? = null
    private var points = arrayListOf<InkPoint>()
    private var gestureTool = Tool.STICKY_NOTE
    private var drawingId: String? = null
    private var cancelled = false
    private var resize = false
    private var moved = false
    private var popup: PopupWindow? = null
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var fromX = 0f
    private var fromY = 0f
    private var gestureStroke: Stroke? = null

    fun close() { popup?.dismiss(); popup = null }
    fun cancel() { start = null; original = null; preview = null; points.clear(); host.invalidate() }
    fun reset() { close(); cancel(); drawingId = null }

    fun touch(event: MotionEvent, at: InkPoint, tool: Tool, canDraw: Boolean, layer: Int,
              color: Int, width: Float, opacity: Float, wholeEraser: Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val hit = page().texts.lastOrNull { StickyNotes.contains(it, at) && PageLayers.editable(page().layers, it.layer) }
            val drawing = canDraw && (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER || (tool == Tool.STICKY_NOTE && hit?.id == drawingId))
            if (tool != Tool.STICKY_NOTE && !(hit != null && (drawing || tool == Tool.TEXT || tool == Tool.HAND))) return false
            if (hit == null && !PageLayers.editable(page().layers, layer)) return false
            close()
            host.parent?.requestDisallowInterceptTouchEvent(true)
            start = at; original = hit; preview = hit; moved = false; cancelled = false
            fromX = event.x; fromY = event.y
            gestureTool = if (drawing && hit != null) { if (tool == Tool.STICKY_NOTE) Tool.PEN else tool } else Tool.STICKY_NOTE
            resize = hit != null && !drawing &&
                hypot(event.x - frame(hit).right, event.y - frame(hit).bottom) < 28 * host.resources.displayMetrics.density
            gestureStroke = Stroke(gestureTool, color, width, emptyList(), opacity)
            points.clear()
            if (drawing && hit != null) addSamples(event, at, wholeEraser)
            host.invalidate()
            return true
        }
        val began = start ?: return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { cancelled = true; preview = null; host.invalidate(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!cancelled) {
                moved = moved || hypot(event.x - fromX, event.y - fromY) > slop
                val box = original
                if (box != null && gestureTool != Tool.STICKY_NOTE) addSamples(event, at, wholeEraser)
                else if (box != null) {
                    preview = if (resize) box.copy(width = (at.x - box.x).coerceIn(90f, TextBox.MAX_WIDTH), stickyHeight = (at.y - box.y).coerceIn(60f, 10000f))
                    else box.moved(at.x - began.x, at.y - began.y)
                } else {
                    preview = TextBox(x = min(began.x, at.x), y = min(began.y, at.y),
                        width = abs(at.x - began.x), stickyHeight = abs(at.y - began.y), size = 22f, layer = layer)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val next = preview
                    if (box != null) {
                        if (gestureTool != Tool.STICKY_NOTE || moved) next?.let(::replace)
                        else if (tool == Tool.TEXT) edit(box) else controls(box)
                    } else if (moved && next != null && next.width >= 90f && next.stickyHeight >= 60f) {
                        commit(page().texts + next)
                        controls(next)
                    }
                    cancel(); host.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> { cancel(); host.parent?.requestDisallowInterceptTouchEvent(false) }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && cancelled) { cancel(); host.parent?.requestDisallowInterceptTouchEvent(false) }
        host.invalidate()
        return true
    }

    private fun addSamples(event: MotionEvent, at: InkPoint, whole: Boolean) {
        val box = original ?: return
        // Event history is interpolated in page units from the current screen/page transform.
        val bounds = frame(box)
        val scale = bounds.width() / box.width
        for (h in 0 until event.historySize) {
            points += InkPoint(((event.getHistoricalX(0, h) - bounds.left) / scale).coerceIn(0f, box.width),
                ((event.getHistoricalY(0, h) - bounds.top) / scale).coerceIn(0f, box.stickyHeight), at.pressure)
        }
        points += InkPoint((at.x - box.x).coerceIn(0f, box.width), (at.y - box.y).coerceIn(0f, box.stickyHeight), at.pressure)
        val stroke = gestureStroke ?: return
        if (gestureTool == Tool.ERASER) {
            var ink = preview?.stickyInk ?: box.stickyInk
            for (p in points) ink = if (whole) ink.filterNot { InkGeometry.hits(it, p, stroke.width / 2f) }
                else ink.flatMap { InkGeometry.erase(it, p, stroke.width / 2f) }
            preview = box.copy(stickyInk = ink)
            points.clear()
        } else preview = box.copy(stickyInk = box.stickyInk + stroke.copy(points = points.toList()))
    }

    private fun replace(box: TextBox) {
        // Deletion/undo during editing must never resurrect a stale note.
        val current = page().texts.find { it.id == box.id } ?: return
        if (current != box) commit(page().texts.map { if (it.id == box.id) box else it })
    }

    fun draw(canvas: Canvas, boxes: List<TextBox>, draft: Boolean = true) {
        val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9D8845.toInt(); strokeWidth = 1.5f }
        boxes.forEach {
            val box = if (it.id == original?.id) preview ?: it else it
            InkRenderer.text(canvas, box)
            canvas.drawLine(box.x + box.width - 16f, box.y + box.stickyHeight - 4f,
                box.x + box.width - 4f, box.y + box.stickyHeight - 16f, handle)
            canvas.drawLine(box.x + box.width - 10f, box.y + box.stickyHeight - 4f,
                box.x + box.width - 4f, box.y + box.stickyHeight - 10f, handle)
        }
        if (draft && original == null) preview?.takeIf { it.isSticky }?.let { InkRenderer.text(canvas, it) }
    }

    private fun button(label: String, action: () -> Unit) = Button(host.context).apply {
        text = label; isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun show(content: View, box: TextBox, width: Int, height: Int, focusable: Boolean): PopupWindow {
        val location = IntArray(2); host.getLocationInWindow(location)
        val bounds = frame(box)
        val window = PopupWindow(content, width, height, focusable).apply {
            setBackgroundDrawable(ColorDrawable(StickyNotes.COLOR)); elevation = 8 * host.resources.displayMetrics.density
            isOutsideTouchable = true
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        popup = window
        window.showAtLocation(host, Gravity.TOP or Gravity.LEFT,
            (location[0] + bounds.left).roundToInt().coerceAtLeast(0),
            (location[1] + bounds.top).roundToInt().coerceAtLeast(0))
        return window
    }

    private fun controls(box: TextBox) {
        close()
        val row = LinearLayout(host.context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Type") { close(); edit(box) })
        row.addView(button("Draw") { drawingId = box.id; close() })
        row.addView(button("Delete") { close(); commit(page().texts.filterNot { it.id == box.id }) })
        show(row, box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
    }

    private fun edit(box: TextBox) {
        val column = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        val edit = EditText(host.context).apply {
            setText(box.text); setSelection(text.length)
            hint = "Type a note…"; gravity = Gravity.TOP or Gravity.LEFT
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(box.color); setHintTextColor(0xFF71663F.toInt())
            setBackgroundColor(Color.TRANSPARENT); typeface = Typeface.SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_PX, box.size * (frame(box).width() / box.width))
            val pad = (StickyNotes.PADDING * frame(box).width() / box.width).roundToInt()
            setPadding(pad, pad, pad, pad)
        }
        column.addView(button("Done") { close() })
        column.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val density = host.resources.displayMetrics.density
        val window = show(column, box, max(frame(box).width().roundToInt(), (220 * density).roundToInt()),
            max(frame(box).height().roundToInt(), (180 * density).roundToInt()), true)
        window.setOnDismissListener {
            val live = page().texts.find { it.id == box.id }
            if (live != null && PageLayers.editable(page().layers, live.layer)) replace(live.copy(text = edit.text.toString()))
            (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
        }
        edit.requestFocus()
        edit.post { (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }
}
