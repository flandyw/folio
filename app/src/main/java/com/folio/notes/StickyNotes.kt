package com.folio.notes

import kotlin.math.abs
import kotlin.math.max

/**
 * Sticky notes are [TextBox]es with a [TextBox.stickyHeight]: an opaque square of paper that sits
 * on top of the page (and may hang off it into the workspace beside it), carrying its own typed
 * words and its own ink. Geometry is in page units; the note's ink is stored relative to its
 * top-left corner, so moving the note carries the ink along, and it is drawn clipped to the note.
 *
 * Drawing and erasing follow the visible surface along the contact: over a note they affect
 * note-local ink, elsewhere the page. DocumentInkCapture splits at paper and note edges, including
 * notes outside the paper, without drawing or erasing beneath an opaque note.
 */
object StickyNotes {
    const val PADDING = 12f
    /** Corner radius of the paper, in page units. */
    const val CORNER = 4f
    /** A tap with the sticky tool makes a note this size, centred on the tap. */
    const val DEFAULT_WIDTH = 220f
    const val DEFAULT_HEIGHT = 200f
    // Keep dimensions positive: zero height denotes an ordinary text box in storage.
    const val SMALLEST_DIMENSION = 0.001f
    const val MAX_HEIGHT = 10000f
    /** The type size a new note starts with, unless the Settings default says otherwise. */
    const val TEXT_SIZE = 16f
    /** The range the Settings default and each note's own text size slider allow, in page units. */
    const val TEXT_SIZE_MIN = 8f
    const val TEXT_SIZE_MAX = 48f
    fun textSize(value: Float): Float = value.coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)
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

    /** A new note spanning the drag from ([ax], [ay]) to ([bx], [by]). */
    fun spanning(ax: Float, ay: Float, bx: Float, by: Float, layer: Int, paper: Int, textSize: Float): TextBox {
        val w = max(abs(bx - ax), SMALLEST_DIMENSION); val h = max(abs(by - ay), SMALLEST_DIMENSION)
        val x = if (bx >= ax) ax else ax - w
        val y = if (by >= ay) ay else ay - h
        return TextBox(x = x, y = y, width = w, stickyHeight = h, size = textSize, layer = layer, stickyColor = paper)
    }

    /** A new default-sized note centred on ([x], [y]). */
    fun centred(x: Float, y: Float, layer: Int, paper: Int, textSize: Float): TextBox =
        TextBox(x = x - DEFAULT_WIDTH / 2f, y = y - DEFAULT_HEIGHT / 2f, width = DEFAULT_WIDTH,
            stickyHeight = DEFAULT_HEIGHT, size = textSize, layer = layer, stickyColor = paper)

    /** [box] with its bottom-right corner dragged to ([x], [y]), keeping both dimensions positive. */
    fun resized(box: TextBox, x: Float, y: Float): TextBox = box.copy(
        width = (x - box.x).coerceIn(SMALLEST_DIMENSION, TextBox.MAX_WIDTH),
        stickyHeight = (y - box.y).coerceIn(SMALLEST_DIMENSION, MAX_HEIGHT))

    /** Retain the owner's input surface when a note is visible beyond its row in the column. */
    fun spansRows(box: TextBox, page: NotePage): Boolean = box.isSticky &&
        (box.y < 0f || box.y + box.stickyHeight > page.height)

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
