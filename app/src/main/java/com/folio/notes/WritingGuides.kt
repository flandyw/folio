package com.folio.notes

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Printed answer rules in page coordinates; [block] indexes the detected areas, or is null on ruled paper. */
data class WritingGuide(val left: Float, val right: Float, val y: Float, val block: Int? = null)

/** The answer space inferred from a group of response lines, not a printed rectangle. */
data class AnswerArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val size get() = (right - left) * (bottom - top)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** What the page's printed background offers the follow engine. */
data class DetectedGuides(val guides: List<WritingGuide>, val areas: List<AnswerArea>)

object WritingGuides {
    /** The tightest response-line area around the writer. */
    fun areaAt(areas: List<AnswerArea>, x: Float, y: Float): AnswerArea? =
        areas.filter { it.contains(x, y) }.minByOrNull { it.size }

    /** Only move within the same answer column, never across a question-sized gap. */
    fun next(line: WritingGuide, guides: List<WritingGuide>): WritingGuide? = guides.asSequence()
        .filter { follows(line, it) }.minByOrNull { it.y }

    private fun follows(line: WritingGuide, next: WritingGuide): Boolean {
        val overlap = min(line.right, next.right) - max(line.left, next.left)
        return line.block == next.block && next.y - line.y in 12f..64f &&
            overlap >= min(line.right - line.left, next.right - next.left) * .8f &&
            abs(next.left - line.left) <= 32f && abs(next.right - line.right) <= 32f
    }

    fun spacing(line: WritingGuide, guides: List<WritingGuide>): Float? =
        next(line, guides)?.let { it.y - line.y }
            ?: guides.asSequence().filter { follows(it, line) }.minOfOrNull { line.y - it.y }

    fun ruled(width: Float, height: Float): List<WritingGuide> =
        generateSequence(70f) { it + 28f }.takeWhile { it < height }
            .map { WritingGuide(36f, width - 36f, it) }.toList()

    /**
     * Scan the unannotated PDF raster off the UI thread. Join dots, dashes and antialiasing gaps,
     * merge the rows of each thin rule, and reject text, solid bars and table borders.
     * Short rules need an aligned neighbour; a long isolated rule can be a one-line answer.
     */
    fun detect(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float): List<WritingGuide> =
        analyze(pixels, width, height, pageWidth, pageHeight).guides

    fun analyze(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float,
                text: List<PdfTextLine> = emptyList()): DetectedGuides {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        return analyze(width, height, pageWidth, pageHeight, { y, row -> System.arraycopy(pixels, y * width, row, 0, width) }, text)
    }

    /** One byte per pixel: ink dark enough to be a printed rule. Computed once, then only looked up. */
    private fun darkMask(width: Int, height: Int, readRow: (Int, IntArray) -> Unit): BooleanArray {
        val mask = BooleanArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            readRow(y, row)
            val base = y * width
            for (x in 0 until width) {
                val c = row[x]
                val a = c ushr 24
                if (a == 0) continue
                val luminance = (2126 * ((c ushr 16) and 255) + 7152 * ((c ushr 8) and 255) + 722 * (c and 255)) / 10000
                // Composited over white, like the page it is drawn on.
                mask[base + x] = 255 - a * (255 - luminance) / 255 < 225
            }
        }
        return mask
    }

    /** Detected areas for an unannotated page, reused when the same page comes back into view. */
    fun cached(key: String, compute: () -> DetectedGuides): DetectedGuides {
        synchronized(cache) { cache[key]?.let { return it } }
        return compute().also { found -> synchronized(cache) { cache[key] = found } }
    }

    private val cache = object : LinkedHashMap<String, DetectedGuides>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DetectedGuides>?) = size > 32
    }

    /**
     * Group response lines into answer areas using alignment, spacing and clear space between them.
     * [readRow] fills one raster row (ARGB), so a caller never needs the whole page's pixels at once.
     * With the page's embedded [text], white space under a question that has no response lines becomes an
     * answer area too (after the ruled ones, so their indices stay put), when the raster confirms it is blank.
     */
    fun analyze(width: Int, height: Int, pageWidth: Float, pageHeight: Float, readRow: (Int, IntArray) -> Unit,
                text: List<PdfTextLine> = emptyList()): DetectedGuides {
        require(width > 0 && height > 0)
        require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0)
        val sx = pageWidth / width
        val sy = pageHeight / height
        val gap = ceil(6f / sx).toInt().coerceAtLeast(1)
        val minLength = max(60f, pageWidth * .12f) / sx
        val mask = darkMask(width, height, readRow)
        fun dark(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && mask[y * width + x]
        /** The share of an area's rows that carry printed ink, away from its edges. */
        fun inkShare(area: AnswerArea): Float {
            val x0 = ceil((area.left + 4f) / sx).toInt()
            val x1 = ((area.right - 4f) / sx).toInt()
            val y0 = ceil((area.top + 4f) / sy).toInt()
            val y1 = ((area.bottom - 4f) / sy).toInt()
            if (x1 <= x0 || y1 <= y0) return 1f
            val minInk = ceil(3f / sx).toInt().coerceAtLeast(2)
            var rows = 0
            for (y in y0..y1) {
                var ink = 0
                for (x in x0..x1) if (dark(x, y) && ++ink >= minInk) break
                if (ink >= minInk) rows++
            }
            return rows.toFloat() / (y1 - y0 + 1)
        }
        fun blank(ruled: List<AnswerArea>): List<AnswerArea> =
            if (text.isEmpty()) emptyList() else AnswerSpaces.infer(text, pageWidth, pageHeight, ruled, ::inkShare)
        /** A dashed or dotted rule has evenly spaced gaps; a row of text does not. */
        fun regularDashes(y: Int, left: Int, right: Int): Boolean {
            val gaps = ArrayList<Int>()
            var x = left
            var inGap = 0
            while (x <= right) {
                if (mask[y * width + x]) { if (inGap > 0) gaps += inGap; inGap = 0 } else inGap++
                x++
            }
            if (gaps.size < 8) return false
            val sorted = gaps.sorted()
            val median = sorted[sorted.size / 2]
            val tolerance = max(1, ceil(median * .4f).toInt())
            return gaps.count { abs(it - median) <= tolerance } >= gaps.size * .85f
        }
        data class Band(var left: Int, var right: Int, val top: Int, var bottom: Int)
        val bands = mutableListOf<Band>()
        var open = mutableListOf<Band>()
        for (y in 0 until height) {
            val previous = open
            open = mutableListOf()
            var x = 0
            while (x < width) {
                if (!dark(x, y)) { x++; continue }
                val left = x
                var right = x
                var ink = 0
                while (x < width && x - right <= gap) {
                    if (dark(x, y)) { right = x; ink++ }
                    x++
                }
                if (right - left < minLength || ink.toFloat() / (right - left + 1) < .15f) continue
                if (ink.toFloat() / (right - left + 1) < .45f && !regularDashes(y, left, right)) continue
                val band = previous.lastOrNull { abs(it.left - left) <= gap * 2 && abs(it.right - right) <= gap * 2 }
                if (band == null) Band(left, right, y, y).also { bands += it; open += it }
                else { band.left = min(band.left, left); band.right = max(band.right, right); band.bottom = y; open += band }
            }
        }
        if (bands.isEmpty()) return DetectedGuides(emptyList(), blank(emptyList()))
        // A rule whose ends meet a printed border may be an answer-box line or a table row; blocks decide.
        val bordered = mutableSetOf<WritingGuide>()
        val candidates = bands.mapNotNull { band ->
            val thin = (band.bottom - band.top + 1) * sy <= 3.5f
            val reach = ceil(16f / sy).toInt().coerceAtLeast(3)
            fun verticalBorder(x: Int): Boolean = (-1..1).any { offset ->
                (1..reach).count { dark(x + offset, band.top - it) } >= reach * .8f ||
                    (1..reach).count { dark(x + offset, band.bottom + it) } >= reach * .8f
            }
            // Text rows have ink above and below the putative rule. Require a
            // mostly clear strip so joining dash gaps cannot join printed words.
            val clearance = ceil(4f / sy).toInt().coerceAtLeast(2)
            val occupied = (band.left..band.right).count { x ->
                (2..clearance).any { dark(x, band.top - it) || dark(x, band.bottom + it) }
            }
            if (!thin || occupied.toFloat() / (band.right - band.left + 1) >= .18f) return@mapNotNull null
            WritingGuide(band.left * sx, band.right * sx, (band.top + band.bottom) * .5f * sy).also {
                if (verticalBorder(band.left) || verticalBorder(band.right)) bordered += it
            }
        }
        // Only inspect the shared column: content in a neighbouring column is irrelevant.
        fun clearBetween(above: WritingGuide, below: WritingGuide): Boolean {
            val left = ceil(max(above.left, below.left) / sx).toInt()
            val right = (min(above.right, below.right) / sx).toInt()
            val from = ceil((above.y + 4f) / sy).toInt()
            val to = ((below.y - 4f) / sy).toInt()
            val minInk = ceil(3f / sx).toInt().coerceAtLeast(2)
            val textHeight = ceil(2f / sy).toInt().coerceAtLeast(2)
            var occupiedRows = 0
            for (y in from..to) {
                var ink = 0
                for (x in left..right) {
                    if (dark(x, y) && ++ink >= minInk) break
                }
                occupiedRows = if (ink >= minInk) occupiedRows + 1 else 0
                if (occupiedRows >= textHeight) return false
            }
            return true
        }
        val ordered = candidates.sortedWith(compareBy({ it.y }, { it.left }))
        val following = ordered.associateWith { above ->
            next(above, ordered)?.takeIf { clearBetween(above, it) }
        }
        // Measure the local pitch at both ends. A larger gap between two regularly spaced
        // groups is a question break, even when it is inside the broad 12..64 rule range.
        val pitches = mutableMapOf<WritingGuide, Float>()
        for ((above, below) in following) {
            if (below == null) continue
            val pitch = below.y - above.y
            for (guide in listOf(above, below)) pitches[guide] = min(pitches[guide] ?: pitch, pitch)
        }
        val blocks = mutableListOf<MutableList<WritingGuide>>()
        for (guide in ordered) {
            val block = blocks.lastOrNull { group ->
                val previous = group.last()
                following[previous] == guide && guide.y - previous.y <=
                    min(pitches.getValue(previous), pitches.getValue(guide)) * 1.35f + 2f
            }
            if (block == null) blocks += mutableListOf(guide) else block += guide
        }
        /** Fraction of the columns in [x0, x1] (pixels) with ink anywhere in rows [y0, y1]. */
        fun coverage(x0: Int, x1: Int, y0: Int, y1: Int): Float {
            if (x1 < x0) return 0f
            var columns = 0
            for (x in x0..x1) if ((y0..y1).any { dark(x, it) }) columns++
            return columns.toFloat() / (x1 - x0 + 1)
        }
        fun textLeft(rule: WritingGuide, reachUnits: Float): Boolean {
            val reach = ceil(reachUnits / sx).toInt()
            val rows = ceil(5f / sy).toInt().coerceAtLeast(1)
            val row = (rule.y / sy).toInt()
            val x0 = ceil(rule.left / sx).toInt()
            return coverage(x0 - reach, x0 - 1, row - rows, row + rows) > 0f
        }
        fun textRight(rule: WritingGuide, reachUnits: Float): Boolean {
            val reach = ceil(reachUnits / sx).toInt()
            val rows = ceil(5f / sy).toInt().coerceAtLeast(1)
            val row = (rule.y / sy).toInt()
            val x1 = (rule.right / sx).toInt()
            return coverage(x1 + 1, x1 + reach, row - rows, row + rows) > 0f
        }
        /** Tick marks or grid lines cross the rule: it is an axis or a grid, not writing space. */
        fun crossed(rule: WritingGuide): Boolean {
            val row = (rule.y / sy).toInt()
            val near = ceil(2f / sy).toInt().coerceAtLeast(2)
            val far = ceil(7f / sy).toInt().coerceAtLeast(near + 1)
            val x0 = ceil(rule.left / sx).toInt()
            val x1 = (rule.right / sx).toInt()
            var both = 0
            for (x in x0..x1) {
                if ((near..far).any { dark(x, row - it) } && (near..far).any { dark(x, row + it) }) both++
            }
            return both >= 2
        }
        /** Leader lines carry text at both ends; chart gridlines carry axis labels at one. */
        fun labelled(block: List<WritingGuide>): Boolean {
            val tagged = block.count { rule ->
                if (block.size >= 3) textLeft(rule, 12f) || textRight(rule, 12f)
                else textLeft(rule, 24f) && textRight(rule, 24f)
            }
            return tagged * 2 >= block.size
        }
        // Running header and footer rules; a rule of exactly their extent anywhere else is the same furniture.
        val furniture = candidates.filter { it.y < pageHeight * .07f || it.y > pageHeight * .93f }
        fun plausibleSingle(rule: WritingGuide): Boolean {
            if (rule.y < pageHeight * .07f || rule.y > pageHeight * .95f) return false
            if (furniture.any { it.y != rule.y && abs(it.left - rule.left) <= 6f && abs(it.right - rule.right) <= 6f }) return false
            val x0 = ceil(rule.left / sx).toInt()
            val x1 = (rule.right / sx).toInt()
            val near = ceil(3f / sy).toInt().coerceAtLeast(2)
            val far = ceil(14f / sy).toInt()
            val row = (rule.y / sy).toInt()
            if (max(coverage(x0, x1, row - far, row - near), coverage(x0, x1, row + near, row + far)) >= .2f) return false
            // An answer line leaves a line of writing room above it (a short label on its own row aside);
            // a rule with text just above is a divider or underline.
            if (coverage(x0, x1, row - ceil(28f / sy).toInt(), row - ceil(8f / sy).toInt()) >= .1f) return false
            // Writing room lies below an answer line's question, not under the next paragraph of text.
            if (coverage(x0, x1, row + near, row + ceil(22f / sy).toInt()) >= .3f) return false
            val labelLeft = textLeft(rule, 12f)
            val labelRight = textRight(rule, 12f)
            // A leader line runs between two pieces of text on its own row.
            if (textLeft(rule, 24f) && textRight(rule, 24f)) return false
            if (crossed(rule)) return false
            val neighbours = candidates.count { it !== rule && (follows(it, rule) || follows(rule, it)) }
            return !(neighbours >= 2 || ((labelLeft || labelRight) && neighbours >= 1))
        }
        /**
         * One line spacing above the first rule is the writing room, unless question text reaches into
         * it: then the area starts just under that text, so the outline never cuts through it.
         */
        fun topOf(first: WritingGuide, left: Float, right: Float, pitch: Float): Float {
            val x0 = ceil(left / sx).toInt()
            val x1 = (right / sx).toInt()
            val minInk = ceil(3f / sx).toInt().coerceAtLeast(2)
            val textHeight = ceil(2f / sy).toInt().coerceAtLeast(2)
            val limit = max(0f, first.y - pitch)
            val floor = first.y - 4f
            var rows = 0
            var y = ((first.y - 4f) / sy).toInt()
            while (y * sy >= limit && y >= 0) {
                var ink = 0
                for (x in x0..x1) if (dark(x, y) && ++ink >= minInk) break
                rows = if (ink >= minInk) rows + 1 else 0
                if (rows >= textHeight) return min(floor, (y + rows) * sy).coerceAtLeast(limit)
                y--
            }
            return limit
        }
        val guides = mutableListOf<WritingGuide>()
        val areas = mutableListOf<AnswerArea>()
        for (found in blocks) {
            // Lines inside a ruled box touch its borders. The box's own top and bottom edges are not
            // places to write, and fewer than three inner rules looks like a table, not an answer.
            val block = if (found.none { it in bordered }) found else {
                val inner = found.toMutableList()
                if (inner.first() in bordered) inner.removeAt(0)
                if (inner.isNotEmpty() && inner.last() in bordered) inner.removeAt(inner.lastIndex)
                if (inner.size < 3) continue
                inner
            }
            // Decoration is not an answer: a rule hugging text (page headers, dividers, leader lines)
            // and gridlines whose ends carry axis labels both look like rules but have nothing to write in.
            if (block.size == 1 && !plausibleSingle(block.first())) continue
            if (found.none { it in bordered } && (labelled(block) || block.count(::crossed) * 2 >= block.size)) continue
            if (block.size == 1 && block.first().right - block.first().left < max(120f, pageWidth * .25f)) continue
            val pitch = block.zipWithNext { a, b -> b.y - a.y }.minOrNull() ?: 28f
            val id = areas.size
            guides += block.map { it.copy(block = id) }
            areas += AnswerArea(block.minOf { it.left }, topOf(block.first(), block.minOf { it.left }, block.maxOf { it.right }, pitch),
                block.maxOf { it.right }, block.last().y)
        }
        return DetectedGuides(guides.sortedWith(compareBy({ it.y }, { it.left })), areas + blank(areas))
    }
}
