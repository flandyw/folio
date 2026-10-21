package com.folio.notes.editor

import androidx.compose.ui.geometry.Rect
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.layout.DocumentPageGeometry
import com.folio.notes.editor.selection.ObjectLift
import com.folio.notes.editor.sticky.StickyDrop
import com.folio.notes.editor.sticky.StickyLift
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage

/** Visible geometry only. The ViewModel still owns destination-first writes and per-page history. */
internal class EditorTransferCoordinator {
    var onGeometryChanged: (() -> Unit)? = null
    private val papers = mutableMapOf<String, Rect>()
    private val regions = mutableMapOf<String, Rect>()
    private var index: DocumentPageGeometry.Index? = null
    private var sizes = emptyMap<String, Pair<Float, Float>>()
    fun visiblePaperFrames(): Map<String, Rect> = papers.toMap()
    fun workspaceFrame(id: String): Rect? = regions[id]
    fun paperFrame(id: String): Rect? = papers[id]
    fun paper(id: String, frame: Rect?) { if (frame == null) papers.remove(id) else papers[id] = frame; index = null; onGeometryChanged?.invoke() }
    fun region(id: String, frame: Rect?) { if (frame == null) regions.remove(id) else regions[id] = frame; index = null; onGeometryChanged?.invoke() }
    fun update(pages: List<NotePage>) {
        val next = pages.filterNot { it.infinite }.associate { it.id to (it.width to it.height) }
        if (next != sizes) { sizes = next; index = null; onGeometryChanged?.invoke() }
    }
    private fun geometry(): DocumentPageGeometry.Index = index ?: DocumentPageGeometry.Index(papers.mapNotNull { (id, paper) ->
        val size = sizes[id] ?: return@mapNotNull null
        val workspace = regions[id] ?: paper
        fun Rect.bounds() = DocumentPageGeometry.Bounds(left, top, right, bottom)
        DocumentPageGeometry.Frame(id, size.first, size.second, paper.bounds(), workspace.bounds())
    }).also { index = it }
    private fun moveTarget(source: String, x: Float, y: Float, middle: Float): Pair<String, InkPoint>? {
        val frame = geometry().nearest(middle)?.takeIf { it.pageId != source } ?: return null
        val point = frame.toPage(x, y) ?: return null
        return frame.pageId to InkPoint(point.x, point.y)
    }
    fun dropObject(model: FolioViewModel, source: String, lift: ObjectLift): Boolean {
        val (target, point) = moveTarget(source, lift.left, lift.top, lift.top + lift.height * lift.unit / 2) ?: return false
        model.moveSelectionToPage(source, target, lift.selection, point, centred = false)
        return true
    }
    fun dropSticky(model: FolioViewModel, source: String, lift: StickyLift): StickyDrop {
        val frame = geometry().nearest(lift.top + lift.box.stickyHeight * lift.unit / 2) ?: return StickyDrop.LOCAL
        if (frame.pageId == source) return StickyDrop.LOCAL
        val point = frame.toPage(lift.left, lift.top) ?: return StickyDrop.REFUSED
        val scale = lift.unit * frame.width / frame.paper.width
        return if (model.moveStickyToPage(source, frame.pageId, lift.box, point.x, point.y, scale)) StickyDrop.MOVED else StickyDrop.REFUSED
    }
    fun dropSelection(model: FolioViewModel, source: String, selection: CanvasSelection, x: Float, y: Float): Boolean {
        val frame = geometry().hit(x, y, source) ?: return false
        val point = frame.toPage(x, y) ?: return false
        model.moveSelectionToPage(source, frame.pageId, selection, InkPoint(point.x, point.y))
        return true
    }
}
