package com.folio.notes

/** Sticky geometry is in page units; its ink is local to the rectangle. */
object StickyNotes {
    const val GUTTER = 180f
    const val PADDING = 12f
    val COLOR = 0xFFFFE994.toInt()

    fun contains(box: TextBox, point: InkPoint) = box.isSticky &&
        point.x >= box.x && point.x <= box.x + box.width &&
        point.y >= box.y && point.y <= box.y + box.stickyHeight

    /** A note touching the outside belongs to the workspace, including all its local ink. */
    fun exports(box: TextBox, page: NotePage) = !box.isSticky || page.infinite ||
        (box.x >= 0f && box.y >= 0f && box.x + box.width <= page.width &&
            box.y + box.stickyHeight <= page.height)
}
