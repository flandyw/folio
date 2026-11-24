package com.folio.notes.marking

import com.folio.notes.ink.InkGeometry
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox

/** What a make-room shift produced; [height] is unchanged on an infinite canvas. */
data class RoomShift(
    val strokes: List<Stroke>, val texts: List<TextBox>, val images: List<PageImage>,
    val height: Float, val moved: Int
)

data class PageMarks(val pageIndex: Int, val title: String, val marks: Float, val items: Int)

/**
 * Marking and feedback on ordinary page content. Comments, marks and flags are plain [TextBox]es and
 * ink strokes, so they move, restyle, erase, export and back up like anything else and the storage
 * format never changes.
 *
 * Pure geometry and parsing — no Android imports.
 */
object Marking {
    const val PREF_COLOR = "marking.color"
    /** Offer tick/cross on printed "[4 marks]" labels; on by default, and it only does anything on a PDF. */
    const val PREF_ASSIST = "marking.assist"
    const val FEEDBACK_TITLE = "Feedback"

    val DEFAULT_COLOR: Int = 0xFFC62828.toInt()
    /** Pen colours that read as "not the student's writing": red, blue, green, purple. */
    val COLORS: List<Int> = listOf(0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
    fun colorName(color: Int): String = when (color) { COLORS[0] -> "red"; COLORS[1] -> "blue"; COLORS[2] -> "green"; COLORS[3] -> "purple"; else -> "custom" }

    /** A grown page stops here, so a stray repeat never builds a bitmap too tall to render. */
    const val MAX_PAGE_HEIGHT = 5000f

    private const val MARK_SIZE = 34f
    private const val LINE_HEIGHT = 1.22f

    // ---- Content builders -----------------------------------------------------------------------

    fun markBox(label: String, x: Float, y: Float, color: Int, size: Float = MARK_SIZE) =
        TextBox(x = x, y = y, width = if (size < MARK_SIZE) 60f else 110f, text = label, size = size, color = color, bold = true)

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

    /** Closes an empty horizontal strip; occupied strips are never cut through. */
    fun removeRoom(page: NotePage, y: Float, amount: Float, textHeight: (TextBox) -> Float): RoomShift? {
        if (page.pdfIndex != null || !y.isFinite() || !amount.isFinite() || amount <= 0f || (!page.infinite && y < 0f)) return null
        if (!page.infinite && (y + amount > page.height || page.height - amount < 160f)) return null
        val end = y + amount
        fun overlaps(top: Float, bottom: Float) = top < end && bottom > y
        if (page.strokes.any { s -> s.points.isNotEmpty() && overlaps(
                s.points.minOf { it.y } - s.width * 2, s.points.maxOf { it.y } + s.width * 2) } ||
            page.texts.any { overlaps(it.y, it.y + textHeight(it)) } ||
            page.images.any { overlaps(it.y, it.y + it.height) }) return null
        val moved = page.strokes.count { (it.points.minOfOrNull { p -> p.y } ?: -Float.MAX_VALUE) >= end } +
            page.texts.count { it.y >= end } + page.images.count { it.y >= end }
        // Use the same boundary as makeRoom, moving upwards instead of opening space.
        return RoomShift(
            page.strokes.map { s -> if ((s.points.minOfOrNull { it.y } ?: -Float.MAX_VALUE) >= end) InkGeometry.translate(s, 0f, -amount) else s },
            page.texts.map { if (it.y >= end) it.moved(0f, -amount) else it },
            page.images.map { if (it.y >= end) it.moved(0f, -amount) else it },
            if (page.infinite) page.height else page.height - amount, moved)
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
