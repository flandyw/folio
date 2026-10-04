package com.folio.notes

import kotlin.math.max
import kotlin.math.min

/** One line of an imported PDF's embedded text, in Folio page coordinates (top-left origin, y down). */
data class PdfTextLine(val pageIndex: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String)

/** One glyph run as the PDF library reports it: where it starts on its baseline and how big it is, in crop-box points (origin top-left). */
data class PdfGlyph(val x: Float, val baseline: Float, val width: Float, val height: Float, val text: String)

/**
 * Text geometry without any PDF library, so mapping and layout stay JVM-testable; the repository
 * supplies the raw boxes. Mirrors [PdfLinks.mapLink]: PDF text positions measured from the crop box's
 * top-left corner, in points, land on the page the app draws.
 */
object PdfTextLayout {
    /** A page with more glyph runs than this is not read: it is a map or a dump, not a question paper. */
    const val MAX_GLYPHS = 40_000

    /**
     * Groups glyph runs into the pieces of text a reader sees: runs on one baseline that sit close
     * together form one piece, and a wide gap (a second column, a right-aligned "(3 marks)") starts
     * another. Kept apart from the library's own line merging so columns never fuse into one line.
     */
    fun group(pageIndex: Int, glyphs: List<PdfGlyph>, cropW: Float, cropH: Float, pageW: Float, pageH: Float): List<PdfTextLine> {
        if (glyphs.isEmpty() || glyphs.size > MAX_GLYPHS) return emptyList()
        val usable = glyphs.filter { it.height > 0f && it.width >= 0f && it.x.isFinite() && it.baseline.isFinite() }
            .sortedWith(compareBy({ it.baseline }, { it.x }))
        val out = ArrayList<PdfTextLine>()
        var row = ArrayList<PdfGlyph>()
        fun flush() {
            if (row.isEmpty()) return
            val sorted = row.sortedBy { it.x }
            var segment = ArrayList<PdfGlyph>()
            fun emit() {
                if (segment.isEmpty()) return
                val text = buildString {
                    var right = Float.NaN
                    for (g in segment) {
                        if (!right.isNaN() && g.x - right > g.height * .2f && !endsWith(' ') && !g.text.startsWith(' ')) append(' ')
                        append(g.text)
                        right = g.x + g.width
                    }
                }
                val tall = segment.maxOf { it.height }
                PdfTextLayout.mapLine(pageIndex, segment.minOf { it.x }, segment.minOf { it.baseline - it.height },
                    segment.maxOf { it.x + it.width }, segment.maxOf { it.baseline } + tall * .2f, cropW, cropH, pageW, pageH, text)?.let { out += it }
                segment = ArrayList()
            }
            var right = Float.NaN
            for (g in sorted) {
                if (!right.isNaN() && g.x - right > max(g.height * 2.5f, 18f)) emit()
                segment.add(g)
                right = max(if (right.isNaN()) Float.NEGATIVE_INFINITY else right, g.x + g.width)
            }
            emit()
            row = ArrayList()
        }
        var rowBaseline = Float.NaN
        var rowHeight = 0f
        for (g in usable) {
            if (!rowBaseline.isNaN() && g.baseline - rowBaseline > max(rowHeight, g.height) * .5f) flush()
            if (row.isEmpty()) { rowBaseline = g.baseline; rowHeight = g.height } else rowHeight = max(rowHeight, g.height)
            row.add(g)
        }
        flush()
        return out
    }

    /** Maps one text box from the crop box (points, origin top-left) onto the Folio page, or null if degenerate or off the page. */
    fun mapLine(pageIndex: Int, x0: Float, y0: Float, x1: Float, y1: Float, cropW: Float, cropH: Float,
                pageW: Float, pageH: Float, text: String): PdfTextLine? {
        if (cropW <= 0f || cropH <= 0f || pageW <= 0f || pageH <= 0f || text.isBlank()) return null
        val left = min(x0, x1).coerceIn(0f, cropW)
        val right = max(x0, x1).coerceIn(0f, cropW)
        val top = min(y0, y1).coerceIn(0f, cropH)
        val bottom = max(y0, y1).coerceIn(0f, cropH)
        if (right - left < .5f || bottom - top < .5f) return null
        return PdfTextLine(pageIndex, left / cropW * pageW, top / cropH * pageH, right / cropW * pageW, bottom / cropH * pageH, text.trim())
    }
}

/**
 * Answer space nobody drew a line in. Exam papers often leave a block of white paper under a question,
 * and the printed text around it says where it is: a question stem (often ending in "(3 marks)"), then
 * a gap far wider than any paragraph break, then the next question or the page's foot. The page's own
 * ink confirms it (a graph or figure is a gap in the *text* but not in the ink), and printed response
 * lines, when there are any, always take precedence.
 */
object AnswerSpaces {
    private val marksPattern = Regex("""(?i)\b\d+\s*marks?\b""")
    private val questionStart = Regex("""(?i)^\s*(question|q)\s*\d+|^\s*\d{1,2}\s*[.)]\s|^\s*\(?[a-h]\)\s|^\s*\(\s*(i{1,3}|iv|v|vi)\s*\)\s""")

    /**
     * Answer areas found in the white space of one page. [taken] are the areas printed response lines
     * already define; [inkShare] says how much of an area's rows carry printed ink (0 clear, 1 full).
     */
    fun infer(lines: List<PdfTextLine>, pageWidth: Float, pageHeight: Float, taken: List<AnswerArea>,
              inkShare: (AnswerArea) -> Float): List<AnswerArea> {
        if (pageWidth <= 0f || pageHeight <= 0f) return emptyList()
        val usable = lines.filter { it.right > it.left && it.bottom > it.top && it.text.isNotBlank() }
        val text = usable.filter { it.bottom > pageHeight * HEADER && it.top < pageHeight * FOOTER }
        if (text.size < MIN_LINES) return emptyList()
        // The running footer (page number, copyright) ends the usable page; without one, a bottom margin does.
        val floor = usable.filter { it.top >= pageHeight * FOOTER }.minOfOrNull { it.top } ?: (pageHeight * .95f)
        val mid = pageWidth / 2f
        // A column is made of substantial lines: short right-aligned pieces such as "(3 marks)" are not one.
        val wide = pageWidth * .15f
        val left = text.filter { it.right <= mid + 12f && it.right - it.left >= wide }
        val right = text.filter { it.left >= mid - 12f && it.right - it.left >= wide }
        val crossing = text.filter { it.left < mid - 12f && it.right > mid + 12f }
        val twoColumns = left.size >= MIN_LINES && right.size >= MIN_LINES && crossing.size * 4 <= left.size + right.size + crossing.size
        val columns = if (twoColumns) listOf(text.filter { it.right <= mid + 12f || it in crossing }, text.filter { it.left >= mid - 12f || it in crossing }) else listOf(text)
        return columns.flatMap { column(it, pageWidth, floor) }
            .filter { area -> taken.none { overlaps(area, it) } && inkShare(area) <= MAX_INK_SHARE }
            .distinct().sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun overlaps(a: AnswerArea, b: AnswerArea) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun column(lines: List<PdfTextLine>, pageWidth: Float, floor: Float): List<AnswerArea> {
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        if (sorted.size < MIN_LINES) return emptyList()
        val heights = sorted.map { it.bottom - it.top }
        val body = median(heights) ?: return emptyList()
        // One line of text, top to top: the unit every gap is judged against.
        val unit = median(sorted.zipWithNext { a, b -> b.top - a.top }.filter { it in body * .8f..body * 2.2f }) ?: (body * 1.3f)
        val columnLeft = sorted.minOf { it.left }
        val columnRight = max(sorted.maxOf { it.right }, columnLeft + pageWidth * .25f)
        val out = ArrayList<AnswerArea>()
        var reach = sorted[0].bottom
        for (i in 1..sorted.size) {
            val below = sorted.getOrNull(i)
            val belowTop = below?.top ?: floor
            val gap = belowTop - reach
            val above = sorted.subList(max(0, i - 3), i)
            // Printed marks, or the next question starting, say this is a space to answer in, so a shorter gap will do.
            val strong = above.any { marksPattern.containsMatchIn(it.text) } || below?.let { questionStart.containsMatchIn(it.text) } == true
            val minimum = if (strong) max(unit * 2.2f, 36f) else max(unit * 4.5f, 80f)
            // The rest of a page after its last text is only a place to answer when the paper says so.
            if (gap >= minimum && (below != null || strong)) {
                val top = reach + unit * .3f
                val bottom = belowTop - unit * .4f
                if (bottom - top >= max(32f, unit * 1.8f)) out += AnswerArea(columnLeft, top, columnRight, bottom)
            }
            if (below != null) reach = max(reach, below.bottom)
        }
        return out
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** Lines in the top and bottom bands of a page are running headers and footers, not question text. */
    const val HEADER = .07f
    const val FOOTER = .93f
    const val MIN_LINES = 4
    /** An answer space may carry a stray mark or two: this share of its rows at most. */
    const val MAX_INK_SHARE = .06f
}
