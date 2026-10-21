package com.folio.notes.marking

import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.storage.PageContent
import com.folio.notes.storage.content
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One page-local segment of a manual region, keyed by notebook page identity. */
data class MarkZonePart(val pageId: String, val pageIndex: Int, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun zone(marks: Int) = MarkZone(pageIndex, x, y, width, height, marks, manual = true)
}

/**
 * A printed mark allocation such as "[4 marks]" on an imported PDF page, in Folio page coordinates.
 * Found from the PDF text layer or offline OCR of its rendered background.
 */
data class MarkZone(
    val pageIndex: Int,
    val x: Float, val y: Float, val width: Float, val height: Float,
    val marks: Int,
    /** Drawn by the marker around an allocation the scan missed, rather than found by it. */
    val manual: Boolean = false,
    /** First part owns the single award; the remaining parts are continuation hit targets. */
    val parts: List<MarkZonePart> = emptyList()
) {
    val anchorPageId: String? get() = parts.firstOrNull()?.pageId
    fun canonical(): MarkZone = parts.firstOrNull()?.let { it.zone(marks).copy(parts = parts) } ?: this
    fun onPage(page: NotePage): MarkZone? = if (parts.isEmpty()) takeIf { pageIndex == page.pdfIndex }
        else parts.firstOrNull { it.pageId == page.id && it.pageIndex == page.pdfIndex }?.let { it.zone(marks).copy(parts = parts) }

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
    private const val STAMP_W = 60f
    private const val STAMP_H = 24f
    private const val STAMP_SIZE = 16f
    private const val PAD = 3f
    // "marks" as OCR tends to misread it: "rn" for "m", a stray space, a trailing 5 for the s.
    private val allocation = Regex(
        """(?<![\w.])(?:[\[(]\s*)?(\d{1,2})\s*(?:m|rn)\s?a\s?r\s?k\s?[s5]?(?![a-z])(?:\s*[\])])?""", RegexOption.IGNORE_CASE)
    private val bareNumber = Regex("""(?<![\d.])(\d{1,2})(?![\d.])""")

    /** A lone number in a small boxed region, for prefilling the marks of a hand-drawn area. */
    fun numberIn(text: String): Int? {
        find(text).firstOrNull()?.let { return it.marks }
        return bareNumber.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.singleOrNull()?.takeIf { it in 1..MAX_MARKS }
    }

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

    /** Full marks box the label; fewer strike it through. Both are ordinary strokes derived from the zone. */
    private fun box(zone: MarkZone, color: Int) = Stroke(Tool.RECTANGLE, color, 2f, listOf(
        InkPoint(zone.x - PAD, zone.y - PAD), InkPoint(zone.x + zone.width + PAD, zone.y + zone.height + PAD)))
    private fun strike(zone: MarkZone, color: Int) = Stroke(Tool.LINE, color, 2f, listOf(
        InkPoint(zone.x - PAD, zone.y + zone.height / 2f), InkPoint(zone.x + zone.width + PAD, zone.y + zone.height / 2f)))

    private fun isDecoration(zone: MarkZone, s: Stroke): Boolean {
        val candidates = listOf(box(zone, 0), strike(zone, 0))
        return candidates.any { c ->
            c.tool == s.tool && s.points.size == 2 &&
                c.points.indices.all { abs(c.points[it].x - s.points[it].x) < 0.5f && abs(c.points[it].y - s.points[it].y) < 0.5f }
        }
    }

    /** The page with [zone] answered by [value]: stamp text changed or added, and its box/strikethrough redrawn. */
    fun withAward(zone: MarkZone, page: NotePage, value: Int, color: Int): Pair<List<TextBox>, List<Stroke>> {
        val existing = stampFor(zone, page)
        val clamped = min(value, zone.marks)
        val text = label(clamped)
        val texts = if (existing != null) page.texts.map { if (it.id == existing.id) it.copy(text = text) else it }
        else {
            val slot = stampSlot(zone, page)
            page.texts + Marking.markBox(text, slot.x, slot.y, color, STAMP_SIZE)
        }
        val strokes = page.strokes.filterNot { isDecoration(zone, it) } +
            if (clamped >= zone.marks) box(zone, color) else strike(zone, color)
        return texts to strokes
    }

    /** The page with [zone]'s award removed: its stamp (so it stops counting) and its decoration. */
    fun withoutAward(zone: MarkZone, page: NotePage): Pair<List<TextBox>, List<Stroke>> {
        val existing = stampFor(zone, page)
        return page.texts.filterNot { it.id == existing?.id } to page.strokes.filterNot { isDecoration(zone, it) }
    }

    /** One linked award: only the anchor gets text, every segment gets its decoration. */
    fun regionContent(zone: MarkZone, pages: Map<String, NotePage>, value: Int?, color: Int): Map<String, PageContent>? {
        val root = zone.canonical()
        if (root.parts.isEmpty() || root.parts.any { part -> pages[part.pageId]?.let { it.loaded && it.pdfIndex == part.pageIndex } != true }) return null
        return root.parts.associate { part ->
            val page = pages.getValue(part.pageId)
            val local = part.zone(root.marks)
            val (texts, strokes) = if (part.pageId == root.anchorPageId) {
                if (value == null) withoutAward(local, page) else withAward(local, page, value, color)
            } else page.texts to (page.strokes.filterNot { isDecoration(local, it) } +
                if (value == null) emptyList() else listOf(if (value.coerceIn(0, root.marks) >= root.marks) box(local, color) else strike(local, color)))
            part.pageId to page.content().copy(texts = texts, strokes = strokes)
        }
    }

    /** Existing six-field rows stay unchanged; a linked region appends its page-local parts. */
    fun encode(zones: List<MarkZone>): String = zones.joinToString(";") { projected ->
        val zone = projected.canonical()
        val base = "${zone.pageIndex},${zone.x},${zone.y},${zone.width},${zone.height},${zone.marks}"
        if (zone.parts.isEmpty()) base else base + "," + zone.parts.joinToString("|") {
            "${java.net.URLEncoder.encode(it.pageId, Charsets.UTF_8)}~${it.pageIndex}~${it.x}~${it.y}~${it.width}~${it.height}"
        }
    }
    fun decode(text: String?): List<MarkZone> = text.orEmpty().split(';').mapNotNull { row ->
        val f = row.split(',')
        if (f.size !in 6..7) return@mapNotNull null
        val page = f[0].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val n = f.drop(1).take(4).map { it.toFloatOrNull()?.takeIf { n -> n.isFinite() } ?: return@mapNotNull null }
        val marks = f[5].toIntOrNull()?.takeIf { it in 1..MAX_MARKS } ?: return@mapNotNull null
        val parts = if (f.size == 6) emptyList() else {
            val values = f[6].split('|').map { encoded ->
                val p = encoded.split('~')
                if (p.size != 6 || p[0].isBlank()) return@mapNotNull null
                val index = p[1].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                val bounds = p.drop(2).map { it.toFloatOrNull()?.takeIf { n -> n.isFinite() } ?: return@mapNotNull null }
                if (bounds[2] <= 0f || bounds[3] <= 0f) return@mapNotNull null
                val id = runCatching { java.net.URLDecoder.decode(p[0], Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MarkZonePart(id, index, bounds[0], bounds[1], bounds[2], bounds[3])
            }
            if (values.size < 2 || values.map { it.pageId }.distinct().size != values.size) return@mapNotNull null
            values
        }
        MarkZone(page, n[0], n[1], n[2], n[3], marks, manual = true, parts = parts).canonical()
    }

    /** Total marks available across the zones — the paper's own total when it prints every allocation. */
    fun total(zones: List<MarkZone>): Int = zones.sumOf { it.marks }
}
