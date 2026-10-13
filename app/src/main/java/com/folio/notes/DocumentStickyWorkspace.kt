package com.folio.notes

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** One transparent surface above the column. Page ownership is storage, never a visual boundary. */
@Composable internal fun DocumentStickyWorkspace(
    surfaces: EditorSurfaceRegistry, transfers: EditorTransferCoordinator, modifier: Modifier,
) {
    val views = surfaces.mainSurfaces().filter { it.workspaceNotes }
    AndroidView(factory = { StickyWorkspaceView(it) }, modifier = modifier,
        update = { it.bind(views, transfers) }, onRelease = { it.release() })
}

private class StickyWorkspaceView(context: Context) : View(context) {
    private var surfaces = emptyList<InkView>()
    private var transfers: EditorTransferCoordinator? = null
    private var contact: InkView? = null
    private val origin = IntArray(2)
    private val sourceOrigin = IntArray(2)
    private val redraw: () -> Unit = { invalidate() }

    fun bind(views: List<InkView>, geometry: EditorTransferCoordinator) {
        surfaces.filter { it !in views }.forEach { it.onWorkspaceNotesInvalidated = null }
        surfaces = views
        views.forEach { it.onWorkspaceNotesInvalidated = redraw }
        if (transfers !== geometry) transfers?.onGeometryChanged = null
        transfers = geometry
        geometry.onGeometryChanged = redraw
        invalidate()
    }

    fun release() {
        contact?.cancelExternalGesture()
        contact = null
        surfaces.forEach { it.onWorkspaceNotesInvalidated = null }
        transfers?.onGeometryChanged = null
        surfaces = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationInWindow(origin)
        surfaces.forEach { source ->
            if (!source.isAttachedToWindow) return@forEach
            source.getLocationInWindow(sourceOrigin)
            canvas.save()
            canvas.translate((sourceOrigin[0] - origin[0]).toFloat(), (sourceOrigin[1] - origin[1]).toFloat())
            source.drawWorkspaceNotes(canvas)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationInWindow(origin)
        val windowX = origin[0] + event.x
        val windowY = origin[1] + event.y
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            contact = surfaces.asReversed().firstOrNull {
                !it.inputBlocked && it.isAttachedToWindow && it.touchesNote(windowX, windowY)
            }
            // Bare space between papers also accepts the sticky tool. Keep the page coordinate
            // conversion, including negative/out-of-paper coordinates, without growing a row.
            if (contact == null) contact = surfaces.filter {
                !it.inputBlocked && it.isAttachedToWindow && it.tool == Tool.STICKY_NOTE &&
                    (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || it.fingerDrawing)
            }.minByOrNull {
                val paper = transfers?.paperFrame(it.page.id)
                if (paper == null) Float.MAX_VALUE else when {
                    windowY < paper.top -> paper.top - windowY
                    windowY > paper.bottom -> windowY - paper.bottom
                    else -> 0f
                }
            }
        }
        val source = contact ?: return false
        if (!source.isAttachedToWindow) { contact = null; return false }
        source.getLocationInWindow(sourceOrigin)
        val forwarded = MotionEvent.obtain(event)
        forwarded.offsetLocation((origin[0] - sourceOrigin[0]).toFloat(), (origin[1] - sourceOrigin[1]).toFloat())
        val handled = try { source.onTouchEvent(forwarded) } finally { forwarded.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) contact = null
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) contact = null
        invalidate()
        return handled
    }
}
