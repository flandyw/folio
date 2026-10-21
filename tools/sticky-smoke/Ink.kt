package com.folio.notes.ink

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.folio.notes.model.NotePage
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool

/** Minimal platform boundaries; the runner compiles the production input/overlay classes. */
object InkRenderer {
    val drawn = mutableListOf<TextBox>()
    fun text(canvas: Canvas, box: TextBox) { drawn += box }
}

class InkView(context: Context, var page: NotePage) : View(context) {
    var workspaceNotes = true
    var inputBlocked = false
    var tool = Tool.PEN
    var fingerDrawing = false
    var onWorkspaceNotesInvalidated: (() -> Unit)? = null
    var cancellations = 0
    var hit: (Float, Float) -> Boolean = { _, _ -> false }
    var event: MotionEvent? = null
    val drawn = mutableListOf<Pair<Float, Float>>()
    fun cancelExternalGesture() { cancellations++ }
    fun drawWorkspaceNotes(canvas: Canvas) { drawn += canvas.left to canvas.top }
    fun touchesLocalNote(x: Float, y: Float) = hit(x, y)
    override fun onTouchEvent(event: MotionEvent): Boolean { this.event = MotionEvent.obtain(event); return true }
}
