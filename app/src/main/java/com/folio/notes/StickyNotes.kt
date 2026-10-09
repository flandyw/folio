package com.folio.notes

import kotlin.math.max

/** Sticky geometry is in page units; its ink is local to the rectangle. */
object StickyNotes {
    const val PADDING = 12f
    const val COLOR = 0xFFFFE994.toInt()
    const val EDGE = 0xFFE0C867.toInt()

    /** Paper colours a note can wear; index 0 is the classic yellow every older note keeps. */
    private val PAPERS = intArrayOf(COLOR, 0xFFFFC9D6.toInt(), 0xFFBFE3FF.toInt(), 0xFFC8F0C0.toInt(), 0xFFFFD5A1.toInt(), 0xFFE2D1FF.toInt(), 0xFFFFFFFF.toInt())
    val paperCount get() = PAPERS.size

    /** A stored [StickyNotes] colour value is 0 (yellow) or the ARGB paper itself; unknown values draw as-is. */
    fun fill(stored: Int): Int = if (stored == 0) COLOR else stored
    fun edge(stored: Int): Int {
        if (stored == 0 || stored == COLOR) return EDGE
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(stored, hsv)
        hsv[1] = (hsv[1] + .25f).coerceAtMost(1f); hsv[2] *= .82f
        return android.graphics.Color.HSVToColor(hsv)
    }
    /** The paper after [stored] in the palette, wrapping; yellow is stored as 0. */
    fun nextPaper(stored: Int): Int {
        val i = PAPERS.indexOf(fill(stored))
        val next = PAPERS[(i + 1).mod(PAPERS.size)]
        return if (next == COLOR) 0 else next
    }

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
