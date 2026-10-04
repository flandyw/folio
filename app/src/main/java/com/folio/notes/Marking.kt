package com.folio.notes

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What an armed marking tool does at the spot the page is next tapped. */
sealed interface MarkingAction {
    data class Comment(val text: String) : MarkingAction
    data class Mark(val label: String) : MarkingAction
    /** A numbered ring on the page plus its matching line on the feedback sheet. */
    data object Flag : MarkingAction
    /**
     * A note anchored to the tapped point: a dot and leader line to a box in the nearest clear space.
     * [handwritten] makes the box a small white writing panel to write or revise a sentence in with the
     * pen; otherwise it opens a typed text box.
     */
    data class Note(val handwritten: Boolean) : MarkingAction
    /** Everything below the tap moves down by [amount] page units. */
    data class MakeRoom(val amount: Float) : MarkingAction
}

/** What a make-room shift produced; [height] is unchanged on an infinite canvas. */
data class RoomShift(
    val strokes: List<Stroke>, val texts: List<TextBox>, val images: List<PageImage>,
    val height: Float, val moved: Int
)

/** Ink and, for a typed note, the empty text box to edit; [box] is null for a handwritten panel. */
data class NotePlan(val strokes: List<Stroke>, val box: TextBox?)

data class PageMarks(val pageIndex: Int, val title: String, val marks: Float, val items: Int)

/**
 * Marking and feedback on ordinary page content. Comments, marks and flags are plain [TextBox]es and
 * ink strokes, so they move, restyle, erase, export and back up like anything else and the storage
 * format never changes. The only convention is the feedback sheet, found by its title, whose
 * numbered lines are the single source of truth for the next flag number.
 *
 * Pure geometry and parsing — no Android imports.
 */
object Marking {
    const val PREF_COMMENTS = "marking.comments"
    const val PREF_COLOR = "marking.color"
    const val PREF_AUTO_PLACE = "marking.autoPlace"
    /** Offer tick/cross on printed "[4 marks]" labels; on by default, and it only does anything on a PDF. */
    const val PREF_ASSIST = "marking.assist"
    const val FEEDBACK_TITLE = "Feedback"

    val DEFAULT_COLOR: Int = 0xFFC62828.toInt()
    /** Pen colours that read as "not the student's writing": red, blue, green, purple. */
    val COLORS: List<Int> = listOf(0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
    val MARK_LABELS: List<String> = listOf("✓", "½", "✗", "+1", "+2", "+3", "+4")
    val DEFAULT_COMMENTS: List<String> = listOf(
        "Link back to the question", "Needs evidence", "Define the key term", "Explain, don't just describe",
        "Good analysis", "Unclear — rephrase", "Show working", "Units?", "Check arithmetic", "Expand this point"
    )
    val ROOM_AMOUNTS: List<Pair<String, Float>> = listOf("Small" to 160f, "Medium" to 320f, "Large" to 560f)

    const val MAX_COMMENTS = 24
    const val MAX_COMMENT_LENGTH = 90
    /** A grown page stops here, so a stray repeat never builds a bitmap too tall to render. */
    const val MAX_PAGE_HEIGHT = 5000f

    private const val COMMENT_SIZE = 22f
    private const val MARK_SIZE = 34f
    private const val FLAG_RADIUS = 22f
    private const val FLAG_SIZE = 24f
    private const val LINE_HEIGHT = 1.22f
    private const val CELL = 12f
    private const val EDGE = 14f
    private const val SEPARATOR = '\u001F'

    // ---- Comment bank ---------------------------------------------------------------------------

    /** The saved bank; a missing preference is the defaults, an empty one is a deliberately empty bank. */
    fun loadBank(raw: String?): List<String> =
        if (raw == null) DEFAULT_COMMENTS
        else raw.split(SEPARATOR).map(::cleanComment).filter { it.isNotEmpty() }.distinct().take(MAX_COMMENTS)

    fun encodeBank(bank: List<String>): String = bank.joinToString(SEPARATOR.toString())

    /** The bank with [text] added at the front; blank, duplicate or over-full input leaves it as it was. */
    fun withComment(bank: List<String>, text: String): List<String> {
        val clean = cleanComment(text)
        if (clean.isEmpty() || clean in bank) return bank
        return (listOf(clean) + bank).take(MAX_COMMENTS)
    }

    fun cleanComment(text: String): String =
        text.replace(SEPARATOR, ' ').replace(Regex("\\s+"), " ").trim().take(MAX_COMMENT_LENGTH)

    // ---- Content builders -----------------------------------------------------------------------

    fun commentBox(text: String, x: Float, y: Float, page: NotePage, color: Int): TextBox {
        val room = if (page.infinite) TextBox.DEFAULT_WIDTH else page.width - x - EDGE
        return TextBox(x = x, y = y, width = room.coerceIn(TextBox.MIN_WIDTH, TextBox.DEFAULT_WIDTH),
            text = text, size = COMMENT_SIZE, color = color)
    }

    fun markBox(label: String, x: Float, y: Float, color: Int) =
        TextBox(x = x, y = y, width = 110f, text = label, size = MARK_SIZE, color = color, bold = true)

    /** A ring with its number centred inside, centred on ([cx], [cy]). */
    fun flag(number: Int, cx: Float, cy: Float, color: Int): Pair<Stroke, TextBox> {
        val ring = Stroke(Tool.ELLIPSE, color, 2.4f,
            listOf(InkPoint(cx - FLAG_RADIUS, cy - FLAG_RADIUS), InkPoint(cx + FLAG_RADIUS, cy + FLAG_RADIUS)))
        val label = TextBox(x = cx - FLAG_RADIUS, y = cy - FLAG_SIZE * LINE_HEIGHT / 2f, width = FLAG_RADIUS * 2f,
            text = number.toString(), size = FLAG_SIZE, color = color, bold = true, align = TextAlignMode.CENTER)
        return ring to label
    }

    /** Layout-free height guess, good enough to reserve room before the renderer has measured the box. */
    fun estimateHeight(box: TextBox): Float {
        val perLine = max(1f, box.width / (box.size * 0.52f))
        val lines = box.text.split('\n').sumOf { max(1, ceil(it.length / perLine).toInt()) }
        return lines * box.size * LINE_HEIGHT
    }

    // ---- Feedback sheet -------------------------------------------------------------------------

    fun findSheet(pages: List<NotePage>): NotePage? =
        pages.firstOrNull { it.title.trim().equals(FEEDBACK_TITLE, ignoreCase = true) }

    private val numbered = Regex("^\\s*(\\d{1,3})\\b")

    /** One past the highest number any line of the sheet starts with. */
    fun nextFlagNumber(sheet: NotePage?): Int =
        (sheet?.texts.orEmpty().mapNotNull { numbered.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    private const val SHEET_TOP = 150f
    private const val SHEET_ROW = 72f

    fun sheetHeader(): List<TextBox> = listOf(
        TextBox(x = 40f, y = 40f, width = 520f, text = "Feedback", size = 44f, bold = true, color = DEFAULT_COLOR),
        TextBox(x = 40f, y = 96f, width = 700f, text = "Numbers match the flags on the pages — write beside each one.",
            size = 20f, color = 0xFF6B6F6C.toInt())
    )

    /** The next row on the sheet for flag [number] raised on page [pageNumber]; the sheet grows when full. */
    fun sheetEntry(number: Int, pageNumber: Int, sheet: NotePage, color: Int): Pair<TextBox, Float> {
        val bottom = sheet.texts.filter { numbered.containsMatchIn(it.text) }.maxOfOrNull { it.y + SHEET_ROW }
        val y = max(SHEET_TOP, bottom ?: SHEET_TOP)
        val needed = y + SHEET_ROW + 40f
        val height = if (needed > sheet.height) min(MAX_PAGE_HEIGHT, max(needed, sheet.height + 600f)) else sheet.height
        return TextBox(x = 40f, y = y, width = 180f, text = "$number · p.$pageNumber", size = 26f, bold = true, color = color) to height
    }

    // ---- Free space -----------------------------------------------------------------------------

    /**
     * The top-left of the clearest spot for a [w]×[h] note, nearest [near] (default: the top of the right
     * margin), or null when nothing that size is clear. Only the page's own ink, text and pictures count
     * as occupied — a PDF's printed content is not known here — so the result is a good first guess the
     * marker can drag, not a promise of blank paper.
     */
    fun freeSlot(page: NotePage, w: Float, h: Float, textHeight: (TextBox) -> Float, near: InkPoint? = null): InkPoint? {
        val bounds: Rect4 = if (page.infinite) {
            val c = InkGeometry.contentBounds(page.strokes, page.texts, page.images, textHeight, page.width, page.height)
            Rect4(c.left, c.top, c.right + w + 3 * EDGE, max(c.bottom, c.top + h + 2 * EDGE))
        } else Rect4(0f, 0f, page.width, page.height)
        val cols = ceil((bounds.right - bounds.left) / CELL).toInt().coerceIn(1, 400)
        val rows = ceil((bounds.bottom - bounds.top) / CELL).toInt().coerceIn(1, 800)
        // Occupancy as a summed-area table: one subtraction answers "is this rectangle clear?".
        val busy = Array(rows) { BooleanArray(cols) }
        fun cover(l: Float, t: Float, r: Float, b: Float) {
            val c0 = ((l - bounds.left) / CELL).toInt().coerceIn(0, cols - 1)
            val c1 = ((r - bounds.left) / CELL).toInt().coerceIn(0, cols - 1)
            val r0 = ((t - bounds.top) / CELL).toInt().coerceIn(0, rows - 1)
            val r1 = ((b - bounds.top) / CELL).toInt().coerceIn(0, rows - 1)
            if (r < bounds.left || l > bounds.right || b < bounds.top || t > bounds.bottom) return
            for (y in r0..r1) for (x in c0..c1) busy[y][x] = true
        }
        for (s in page.strokes) {
            if (s.points.isEmpty()) continue
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (p in InkGeometry.pathPoints(s)) { l = min(l, p.x); r = max(r, p.x); t = min(t, p.y); b = max(b, p.y) }
            val pad = s.width + 6f
            cover(l - pad, t - pad, r + pad, b + pad)
        }
        for (box in page.texts) cover(box.x, box.y, box.x + box.width, box.y + max(textHeight(box), estimateHeight(box)))
        for (image in page.images) cover(image.x, image.y, image.x + image.width, image.y + image.height)
        val sums = Array(rows + 1) { IntArray(cols + 1) }
        for (y in 0 until rows) for (x in 0 until cols) {
            sums[y + 1][x + 1] = sums[y][x + 1] + sums[y + 1][x] - sums[y][x] + if (busy[y][x]) 1 else 0
        }
        val cw = ceil((w + 2f) / CELL).toInt().coerceAtLeast(1)
        val ch = ceil((h + 2f) / CELL).toInt().coerceAtLeast(1)
        val ax = near?.x ?: (bounds.right - w - EDGE)
        val ay = near?.y ?: (bounds.top + EDGE)
        var best: InkPoint? = null
        var bestCost = Float.MAX_VALUE
        val edgeCells = (EDGE / CELL).toInt().coerceAtLeast(1)
        for (r0 in edgeCells..(rows - ch - edgeCells)) for (c0 in edgeCells..(cols - cw - edgeCells)) {
            val total = sums[r0 + ch][c0 + cw] - sums[r0][c0 + cw] - sums[r0 + ch][c0] + sums[r0][c0]
            if (total != 0) continue
            val x = bounds.left + c0 * CELL
            val y = bounds.top + r0 * CELL
            val cost = (x - ax) * (x - ax) * 0.6f + (y - ay) * (y - ay)
            if (cost < bestCost) { bestCost = cost; best = InkPoint(x, y) }
        }
        return best
    }

    private data class Rect4(val left: Float, val top: Float, val right: Float, val bottom: Float)

    // ---- Anchored notes -------------------------------------------------------------------------

    private const val PANEL_W = 380f
    private const val PANEL_H = 170f
    private const val TYPED_W = 320f
    private const val FILL_WIDTH = 44f
    private const val FILL_STEP = 36f

    /**
     * A feedback note for the spot [at]: a box in the clearest space near it, tied back with a leader
     * line and a dot. The handwritten panel is white ink rows under a border, so it covers whatever the
     * free-space guess missed and still erases, moves and exports as ordinary strokes. Null when no
     * clear space is big enough.
     */
    fun feedbackNote(page: NotePage, at: InkPoint, handwritten: Boolean, color: Int, textHeight: (TextBox) -> Float): NotePlan? {
        val w = if (handwritten) PANEL_W else TYPED_W
        val h = if (handwritten) PANEL_H else COMMENT_SIZE * LINE_HEIGHT * 3f + 12f
        val slot = freeSlot(page, w, h, textHeight, near = at) ?: return null
        val strokes = mutableListOf<Stroke>()
        if (handwritten) {
            val left = slot.x + FILL_WIDTH / 2f
            val right = slot.x + w - FILL_WIDTH / 2f
            val last = slot.y + h - FILL_WIDTH / 2f
            var y = slot.y + FILL_WIDTH / 2f
            while (true) {
                strokes += Stroke(Tool.LINE, 0xFFFFFFFF.toInt(), FILL_WIDTH, listOf(InkPoint(left, y), InkPoint(right, y)))
                if (y >= last) break
                y = min(y + FILL_STEP, last)
            }
            strokes += Stroke(Tool.RECTANGLE, color, 1.8f, listOf(InkPoint(slot.x, slot.y), InkPoint(slot.x + w, slot.y + h)))
        }
        val ex = at.x.coerceIn(slot.x, slot.x + w)
        val ey = at.y.coerceIn(slot.y, slot.y + h)
        if ((ex - at.x) * (ex - at.x) + (ey - at.y) * (ey - at.y) > 100f) {
            strokes += Stroke(Tool.LINE, color, 1.8f, listOf(InkPoint(at.x, at.y), InkPoint(ex, ey)))
        }
        strokes += Stroke(Tool.ELLIPSE, color, 3f, listOf(InkPoint(at.x - 4f, at.y - 4f), InkPoint(at.x + 4f, at.y + 4f)))
        val box = if (handwritten) null
        else TextBox(x = slot.x + 6f, y = slot.y + 4f, width = w - 12f, text = "", size = COMMENT_SIZE, color = color)
        return NotePlan(strokes, box)
    }

    // ---- Make room ------------------------------------------------------------------------------

    /**
     * Opens [amount] of space at [y]: everything that starts at or below it moves down, anything
     * straddling the line stays put so a circled word is never torn. A finite page grows by the same
     * amount. Returns null for an imported PDF page — its printed background is stretched to the page,
     * so growing it would distort the paper — and when the page would pass [MAX_PAGE_HEIGHT].
     */
    fun makeRoom(page: NotePage, y: Float, amount: Float, textHeight: (TextBox) -> Float): RoomShift? {
        if (!page.infinite && page.pdfIndex != null) return null
        if (!y.isFinite() || !amount.isFinite() || amount <= 0f) return null
        val height = if (page.infinite) page.height else page.height + amount
        if (!page.infinite && height > MAX_PAGE_HEIGHT) return null
        var moved = 0
        val strokes = page.strokes.map { s ->
            val top = s.points.minOfOrNull { it.y }
            if (top != null && top >= y) { moved++; InkGeometry.translate(s, 0f, amount) } else s
        }
        val texts = page.texts.map { if (it.y >= y) { moved++; it.moved(0f, amount) } else it }
        val images = page.images.map { if (it.y >= y) { moved++; it.moved(0f, amount) } else it }
        return RoomShift(strokes, texts, images, height, moved)
    }

    // ---- Marks ----------------------------------------------------------------------------------

    private val plus = Regex("^\\+\\s*(\\d+(?:\\.\\d+)?)$")
    private val ticks = Regex("^[✓✔]+$")

    /** Marks a text box awards — "+2", "✓", "✓✓", "½" — or null for any other text. */
    fun markValue(text: String): Float? {
        val t = text.trim()
        plus.find(t)?.let { return it.groupValues[1].toFloatOrNull() }
        if (ticks.matches(t)) return t.length.toFloat()
        if (t == "½") return 0.5f
        return null
    }

    /** Pages that carry at least one mark, with their running totals. */
    fun tally(pages: List<NotePage>): List<PageMarks> = pages.mapIndexedNotNull { index, page ->
        val values = page.texts.mapNotNull { markValue(it.text) }
        if (values.isEmpty()) null
        else PageMarks(index, page.title.ifBlank { "Page ${index + 1}" }, values.sum(), values.size)
    }

    fun format(marks: Float): String =
        if (marks == marks.toInt().toFloat()) marks.toInt().toString() else "%.1f".format(marks)
}
