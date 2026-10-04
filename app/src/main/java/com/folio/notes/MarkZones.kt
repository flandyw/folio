package com.folio.notes

import kotlin.math.max
import kotlin.math.min

/**
 * A printed mark allocation such as "[4 marks]" on an imported PDF page, in Folio page coordinates.
 * Found from the PDF's own text layer, so a scanned paper simply has none.
 */
data class MarkZone(
    val pageIndex: Int,
    val x: Float, val y: Float, val width: Float, val height: Float,
    val marks: Int
) {
    /** True with a finger-sized margin, so a small label is still easy to tap. */
    fun contains(px: Float, py: Float, slop: Float = SLOP): Boolean =
        px >= x - slop && px <= x + width + slop && py >= y - slop && py <= y + height + slop

    companion object { const val SLOP = 10f }
}

/** One allocation inside a line of text: where it starts and ends (end exclusive) and how many marks. */
data class MarkMatch(val start: Int, val end: Int, val marks: Int)

/**
 * Finding allocations and the stamp that answers one. Pure, so the matching and placement stay cheap to
 * exercise without a PDF or a device.
 */
object MarkZones {
    const val MAX_MARKS = 40
    private const val STAMP_W = 110f
    private const val STAMP_H = 40f
    private val allocation = Regex("""[\[(]?\s*(\d{1,2})\s*marks?\s*[\])]?""", RegexOption.IGNORE_CASE)

    /** Allocations in [line]: "[4 marks]", "(2 marks)", "3 marks", "1 mark". Zero and absurd counts are skipped. */
    fun find(line: String): List<MarkMatch> = allocation.findAll(line).mapNotNull { m ->
        val marks = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        if (marks !in 1..MAX_MARKS) null else MarkMatch(m.range.first, m.range.last + 1, marks)
    }.toList()

    /** Where the awarded mark is stamped for [zone]: just right of the label, or left of it by the page edge. */
    fun stampSlot(zone: MarkZone, page: NotePage): InkPoint {
        val right = zone.x + zone.width + 8f
        val x = if (right + STAMP_W <= page.width - 8f) right else max(8f, zone.x - STAMP_W - 8f)
        val y = (zone.y + zone.height / 2f - STAMP_H / 2f).coerceIn(0f, max(0f, page.height - STAMP_H))
        return InkPoint(x, y)
    }

    /** The mark box already stamped for [zone] on [page], if any: the nearest awarded value in its slot. */
    fun stampFor(zone: MarkZone, page: NotePage): TextBox? {
        val slot = stampSlot(zone, page)
        return page.texts.filter { Marking.markValue(it.text) != null }
            .filter { it.x < slot.x + STAMP_W && it.x + it.width > slot.x - 24f && it.y < slot.y + STAMP_H && it.y + STAMP_H > slot.y - 24f }
            .minByOrNull { (it.x - slot.x) * (it.x - slot.x) + (it.y - slot.y) * (it.y - slot.y) }
    }

    /** The marks already awarded for [zone], or null while it is unmarked. */
    fun awarded(zone: MarkZone, page: NotePage): Float? = stampFor(zone, page)?.let { Marking.markValue(it.text) }

    /** The label stamped for an award; "+0" still counts as marked, so a zero is a decision, not a gap. */
    fun label(value: Int): String = "+${value.coerceIn(0, MAX_MARKS)}"

    /** The page's text boxes with [zone] answered by [value]: the existing stamp changed, or a new one added. */
    fun withAward(zone: MarkZone, page: NotePage, value: Int, color: Int): List<TextBox> {
        val existing = stampFor(zone, page)
        val text = label(min(value, zone.marks))
        if (existing != null) return page.texts.map { if (it.id == existing.id) it.copy(text = text) else it }
        val slot = stampSlot(zone, page)
        return page.texts + Marking.markBox(text, slot.x, slot.y, color)
    }

    /** Total marks available across the zones — the paper's own total when it prints every allocation. */
    fun total(zones: List<MarkZone>): Int = zones.sumOf { it.marks }
}
