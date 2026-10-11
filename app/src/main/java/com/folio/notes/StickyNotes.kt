package com.folio.notes

import kotlin.math.abs
import kotlin.math.max

/**
 * Sticky notes are [TextBox]es with a [TextBox.stickyHeight]: an opaque square of paper that sits
 * on top of the page (and may hang off it into the workspace beside it), carrying its own typed
 * words and its own ink. Geometry is in page units; the note's ink is stored relative to its
 * top-left corner, so moving the note carries the ink along, and it is drawn clipped to the note.
 *
 * Ink belongs to whatever the pen lands on: a stroke that starts on a note is the note's, a stroke
 * that starts on the page is the page's (and passes under any note it crosses). The eraser works
 * the same way per sample — over a note it erases the note's ink, elsewhere the page's.
 */
object StickyNotes {
    const val PADDING = 12f
    /** Corner radius of the paper, in page units. */
    const val CORNER = 4f
    /** A tap with the sticky tool makes a note this size, centred on the tap. */
    const val DEFAULT_WIDTH = 220f
    const val DEFAULT_HEIGHT = 200f
    const val MIN_WIDTH = 90f
    const val MIN_HEIGHT = 60f
    const val MAX_HEIGHT = 10000f
    const val TEXT_SIZE = 22f
    const val COLOR = 0xFFFFE994.toInt()
    const val EDGE = 0xFFE0C867.toInt()

    /** Paper colours a note can wear; index 0 is the classic yellow every older note keeps. */
    private val PAPERS = intArrayOf(COLOR, 0xFFFFC9D6.toInt(), 0xFFBFE3FF.toInt(), 0xFFC8F0C0.toInt(), 0xFFFFD5A1.toInt(), 0xFFE2D1FF.toInt(), 0xFFFFFFFF.toInt())
    val paperCount get() = PAPERS.size
    /** Every paper as its stored value (yellow is 0), in palette order, for a colour picker. */
    val papers: List<Int> get() = PAPERS.map { if (it == COLOR) 0 else it }

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

    fun contains(box: TextBox, point: InkPoint) = contains(box, point.x, point.y)
    fun contains(box: TextBox, x: Float, y: Float) = box.isSticky &&
        x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.stickyHeight

    /** The topmost note under ([x], [y]) whose layer [editable] allows touching, or null. */
    fun at(texts: List<TextBox>, x: Float, y: Float, editable: (Int) -> Boolean): TextBox? =
        texts.lastOrNull { contains(it, x, y) && editable(it.layer) }

    /** True when ([x], [y]) is hidden by any note, touchable or not: page ink there cannot be seen. */
    fun covers(texts: List<TextBox>, x: Float, y: Float): Boolean = texts.any { contains(it, x, y) }

    /** A page-space stroke as the note's own ink. */
    fun toNote(box: TextBox, stroke: Stroke): Stroke = InkGeometry.translate(stroke, -box.x, -box.y)
    /** The note's ink in page space, e.g. to hit-test it against a page-space eraser. */
    fun toPage(box: TextBox, stroke: Stroke): Stroke = InkGeometry.translate(stroke, box.x, box.y)

    /** A new note at least the minimum size, spanning the drag from ([ax], [ay]) to ([bx], [by]). */
    fun spanning(ax: Float, ay: Float, bx: Float, by: Float, layer: Int, paper: Int): TextBox {
        val w = max(abs(bx - ax), MIN_WIDTH); val h = max(abs(by - ay), MIN_HEIGHT)
        // Grown from the corner that was pressed, so a short drag never jumps away from the finger.
        val x = if (bx >= ax) ax else ax - w
        val y = if (by >= ay) ay else ay - h
        return TextBox(x = x, y = y, width = w, stickyHeight = h, size = TEXT_SIZE, layer = layer, stickyColor = paper)
    }

    /** A new default-sized note centred on ([x], [y]). */
    fun centred(x: Float, y: Float, layer: Int, paper: Int): TextBox =
        TextBox(x = x - DEFAULT_WIDTH / 2f, y = y - DEFAULT_HEIGHT / 2f, width = DEFAULT_WIDTH,
            stickyHeight = DEFAULT_HEIGHT, size = TEXT_SIZE, layer = layer, stickyColor = paper)

    /** [box] with its bottom-right corner dragged to ([x], [y]), never smaller than the minimum. */
    fun resized(box: TextBox, x: Float, y: Float): TextBox = box.copy(
        width = (x - box.x).coerceIn(MIN_WIDTH, TextBox.MAX_WIDTH),
        stickyHeight = (y - box.y).coerceIn(MIN_HEIGHT, MAX_HEIGHT))

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
