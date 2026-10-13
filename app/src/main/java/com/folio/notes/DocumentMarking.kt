package com.folio.notes

import kotlin.math.max
import kotlin.math.min

/** A document rectangle intersected with PDF paper; seams never become part of a page's region. */
internal object DocumentMarking {
    fun parts(capture: DocumentInkCapture, pages: Map<String, NotePage>): List<MarkZonePart>? {
        if (capture.blocked) return null
        val drag = capture.dragStroke().points
        if (drag.size < 2) return emptyList()
        val a = capture.toWindow(drag.first()); val b = capture.toWindow(drag.last())
        val left = min(a.x, b.x); val top = min(a.y, b.y)
        val right = max(a.x, b.x); val bottom = max(a.y, b.y)
        val parts = capture.pages.mapNotNull { hosted ->
            val frame = hosted.frame
            val x = max(left, frame.paper.left); val y = max(top, frame.paper.top)
            val r = min(right, frame.paper.right); val d = min(bottom, frame.paper.bottom)
            if (r <= x || d <= y) return@mapNotNull null
            val page = pages[frame.pageId] ?: return null
            val pdfIndex = page.pdfIndex ?: return null
            if (!page.loaded) return null
            val start = frame.toPage(x, y)!!; val end = frame.toPage(r, d)!!
            MarkZonePart(page.id, pdfIndex, start.x, start.y, end.x - start.x, end.y - start.y)
        }
        // Award identity follows the originating page, even for an upward drag.
        return parts.sortedBy { if (it.pageId == capture.sourceId) 0 else 1 }
    }
}
