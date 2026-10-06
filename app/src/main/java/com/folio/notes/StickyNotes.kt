package com.folio.notes

import kotlin.math.max

/** Sticky geometry is in page units; its ink is local to the rectangle. */
object StickyNotes {
    const val PADDING = 12f
    const val COLOR = 0xFFFFE994.toInt()
    const val EDGE = 0xFFE0C867.toInt()

    fun contains(box: TextBox, point: InkPoint) = box.isSticky &&
        point.x >= box.x && point.x <= box.x + box.width &&
        point.y >= box.y && point.y <= box.y + box.stickyHeight

    /** A note touching the outside belongs to the workspace, including all its local ink. */
    fun exports(box: TextBox, page: NotePage) = !box.isSticky || page.infinite ||
        (box.x >= 0f && box.y >= 0f && box.x + box.width <= page.width &&
            box.y + box.stickyHeight <= page.height)

    /**
     * Workspace beside a document's paper, in paper widths on each side: one page width, or more
     * so a note already placed further out stays reachable. Every page shares it, so the
     * document keeps one width.
     */
    fun workspaceSide(pages: List<NotePage>): Float = pages.fold(1f) { side, page ->
        if (page.infinite || page.width <= 0f) side
        else page.texts.fold(side) { widest, box ->
            if (!box.isSticky) widest
            else max(widest, (max(-box.x, box.x + box.width - page.width) + PADDING) / page.width)
        }
    }
}
