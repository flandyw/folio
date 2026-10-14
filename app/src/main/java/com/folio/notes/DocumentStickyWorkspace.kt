package com.folio.notes

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.viewinterop.AndroidView

/** One transparent surface above the column. Page ownership is storage, never a visual boundary. */
@Composable internal fun DocumentStickyWorkspace(
    surfaces: EditorSurfaceRegistry, transfers: EditorTransferCoordinator, modifier: Modifier,
) {
    // Ownership is a native property that can change without a registry identity change. Keep
    // all main handles subscribed, and filter current ownership at draw/input time.
    val views = surfaces.mainSurfaces()
    AndroidView(factory = { StickyWorkspaceView(it) }, modifier = modifier,
        update = { it.bind(views, transfers) }, onRelease = { it.release() })
}

private class StickyWorkspaceView(context: Context) : View(context) {
    private var surfaces = emptyList<InkView>()
    private var transfers: EditorTransferCoordinator? = null
    private var contact: InkView? = null
    private val origin = IntArray(2)
    private val redraw: () -> Unit = { invalidate() }
    private data class Placement(val view: InkView, val frame: Rect)
    private var lastPlacements = emptyList<Placement>()
    private var lastLeft = 0
    private var lastTop = 0
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        getLocationInWindow(origin)
        val current = placements()
        // A scrolling parent's transform need not invalidate a child's display list. Re-record
        // the overlay at the same frame as layout, including prefetch attachment and removal.
        if (current != lastPlacements || origin[0] != lastLeft || origin[1] != lastTop) {
            lastPlacements = current; lastLeft = origin[0]; lastTop = origin[1]
            invalidate()
        }
        true
    }

    private fun placements(): List<Placement> = surfaces.mapNotNull { view ->
        if (!view.workspaceNotes || !view.page.loaded || !view.isAttachedToWindow || !view.isShown ||
            !view.isLaidOut || view.width <= 0 || view.height <= 0) return@mapNotNull null
        // Attached prefetch views can still be unplaced at (0, 0). Only published page layout
        // participates, and the same frame drives drawing, hit-testing and forwarded input.
        val frame = transfers?.workspaceFrame(view.page.id) ?: return@mapNotNull null
        if (frame.width <= 0f || frame.height <= 0f) return@mapNotNull null
        Placement(view, frame)
    }.sortedWith(compareBy({ it.frame.top }, { it.view.page.id })).distinctBy { it.view.page.id }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(beforeDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(beforeDraw)
        contact?.cancelExternalGesture(); contact = null
        lastPlacements = emptyList()
        super.onDetachedFromWindow()
    }

    fun bind(views: List<InkView>, geometry: EditorTransferCoordinator) {
        contact?.takeIf { it !in views }?.let { it.cancelExternalGesture(); contact = null }
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
        lastPlacements = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationInWindow(origin)
        placements().forEach { (source, frame) ->
            canvas.save()
            canvas.translate(frame.left - origin[0], frame.top - origin[1])
            source.drawWorkspaceNotes(canvas)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationInWindow(origin)
        val windowX = origin[0] + event.x
        val windowY = origin[1] + event.y
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val placed = placements()
            contact = placed.asReversed().firstOrNull { (view, frame) ->
                !view.inputBlocked && view.touchesLocalNote(windowX - frame.left, windowY - frame.top)
            }?.view
            // Bare space between papers also accepts the sticky tool. Keep the page coordinate
            // conversion, including negative/out-of-paper coordinates, without growing a row.
            if (contact == null) contact = placed.filter { (view, _) ->
                !view.inputBlocked && view.tool == Tool.STICKY_NOTE &&
                    (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || view.fingerDrawing)
            }.minByOrNull {
                val paper = transfers?.paperFrame(it.view.page.id)
                if (paper == null) Float.MAX_VALUE else when {
                    windowY < paper.top -> paper.top - windowY
                    windowY > paper.bottom -> windowY - paper.bottom
                    else -> 0f
                }
            }?.view
        }
        val source = contact ?: return false
        if (!source.isAttachedToWindow) { contact = null; return false }
        val frame = transfers?.workspaceFrame(source.page.id) ?: run {
            source.cancelExternalGesture(); contact = null; return false
        }
        val forwarded = MotionEvent.obtain(event)
        forwarded.offsetLocation(origin[0] - frame.left, origin[1] - frame.top)
        val handled = try { source.onTouchEvent(forwarded) } finally { forwarded.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) contact = null
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) contact = null
        invalidate()
        return handled
    }
}
