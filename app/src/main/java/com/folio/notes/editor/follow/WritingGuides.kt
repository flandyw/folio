package com.folio.notes.editor.follow

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Printed answer rules in page coordinates; [block] indexes the detected areas, or is null on ruled paper.
 * [areaTop] is where the detected area's writing room starts above its first rule (null: one line spacing).
 */
data class WritingGuide(val left: Float, val right: Float, val y: Float, val block: Int? = null, val areaTop: Float? = null)

/** The answer space inferred from a group of response lines, not a printed rectangle. */
data class AnswerArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val size get() = (right - left) * (bottom - top)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** A move from one printed rule to the next. */
data class WritingAdvance(val from: WritingGuide, val to: WritingGuide) {
    fun startX(hand: WritingHand) = if (hand == WritingHand.RIGHT) to.left else to.right
}

/** What the page's printed background offers the follow engine. */
data class DetectedGuides(val guides: List<WritingGuide>, val areas: List<AnswerArea>)

object WritingGuides {
    /** Partition all printed rules into separate answer areas, including adjacent columns. */
    fun regions(guides: List<WritingGuide>): List<WritingLane> {
        val result = mutableListOf<WritingLane>()
        // Detection already decided which rules form one answer, including a one-line answer and the
        // writing room above its first rule; ruled paper is still grouped by spacing below.
        val (detected, printed) = guides.partition { it.block != null && it.block >= 0 }
        for (block in detected.groupBy { it.block }.values) {
            val first = block.minBy { it.y }
            result += WritingLane(block.minOf { it.left }, (first.areaTop ?: (first.y - 28f)).coerceAtLeast(0f),
                block.maxOf { it.right }, block.maxOf { it.y })
        }
        // Each nearest-neighbour scan is O(N); resolve it once, not for every edge candidate.
        val following = printed.associateWith { next(it, printed) }
        val remaining = printed.sortedWith(compareBy({ it.y }, { it.left })).toMutableSet()
        while (remaining.isNotEmpty()) {
            val group = mutableSetOf(remaining.first())
            val queue = java.util.ArrayDeque<WritingGuide>().apply { add(remaining.first()) }
            while (queue.isNotEmpty()) {
                val line = queue.removeFirst()
                remaining.remove(line)
                val neighbours = remaining.filter { following[line] == it || following[it] == line }
                for (neighbour in neighbours) if (group.add(neighbour)) queue.add(neighbour)
            }
            if (group.size >= 2) result += WritingLane(group.minOf { it.left },
                (group.minOf { it.y } - if (group.all { it.block == HANZI_BLOCK }) HANZI_STEP else 28f).coerceAtLeast(0f), group.maxOf { it.right }, group.maxOf { it.y })
        }
        return result.sortedWith(compareBy({ it.top }, { it.left }))
    }

    fun regionAt(regions: List<WritingLane>, x: Float, y: Float): WritingLane? = regions
        .filter { x in it.left..it.right && y in it.top..it.bottom }
        .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }

    /** The tightest response-line area around the writer. */
    fun areaAt(areas: List<AnswerArea>, x: Float, y: Float): AnswerArea? =
        areas.filter { it.contains(x, y) }.minByOrNull { it.size }

    /** Only move within the same answer column, never across a question-sized gap. */
    fun next(line: WritingGuide, guides: List<WritingGuide>): WritingGuide? = guides.asSequence()
        .filter { follows(line, it) }.minByOrNull { it.y }

    private fun follows(line: WritingGuide, next: WritingGuide): Boolean {
        val overlap = min(line.right, next.right) - max(line.left, next.left)
        val gap = next.y - line.y
        val stepOk = if (line.block == HANZI_BLOCK) abs(gap - HANZI_STEP) < .5f else gap in 12f..64f
        // Detection already joined a labelled first rule ("Advantage ____") to the full-width line under it.
        val detected = line.block != null && line.block >= 0
        return line.block == next.block && stepOk &&
            overlap >= min(line.right - line.left, next.right - next.left) * .8f &&
            (detected || abs(next.left - line.left) <= 32f) && abs(next.right - line.right) <= 32f
    }

    fun spacing(line: WritingGuide, guides: List<WritingGuide>): Float? =
        next(line, guides)?.let { it.y - line.y }
            ?: guides.asSequence().filter { follows(it, line) }.minOfOrNull { line.y - it.y }

    /**
     * The printed rules of ruled paper. Split paper is two columns either side of its centre divider, each
     * its own block, so a line ends at the divider and a return never crosses into the other column.
     */
    fun ruled(width: Float, height: Float, split: Boolean = false): List<WritingGuide> =
        generateSequence(70f) { it + 28f }.takeWhile { it < height }.flatMap { y ->
            if (split) sequenceOf(WritingGuide(36f, width / 2f, y, 0), WritingGuide(width / 2f, width - 36f, y, 1))
            else sequenceOf(WritingGuide(36f, width - 36f, y))
        }.toList()

    /** Closest spacing of detected answer lines; tighter rules are a chart's grid. */
    private const val MIN_PITCH = 18f

    /** Answer lines are spaced at least this far apart; labelled rules any closer are a chart's scale. */
    private const val ROOMY_PITCH = 30f

    /** Block id of hanzi-paper rules: one row of squares per line, a full cell apart. */
    const val HANZI_BLOCK = -1

    /** Distance between hanzi rows; equals `Paper.HANZI_CELL`, kept here so this file stays free of Android models. */
    private const val HANZI_STEP = 84f

    /**
     * The printed rows of tian/mi paper: one writing line per row of squares, sitting just above each
     * row's bottom edge, spanning the centred block of squares exactly as `InkRenderer.drawHanzi` lays it out.
     */
    fun hanzi(width: Float, height: Float, cell: Float = HANZI_STEP): List<WritingGuide> {
        val cols = (width / cell).toInt().coerceAtLeast(1)
        val rows = (height / cell).toInt().coerceAtLeast(1)
        val left = (width - cols * cell) / 2f
        val top = (height - rows * cell) / 2f
        return (1..rows).map { WritingGuide(left, left + cols * cell, top + it * cell - cell * .14f, HANZI_BLOCK) }
    }

    /**
     * Scan the unannotated PDF raster off the UI thread. Join dots, dashes and antialiasing gaps,
     * merge the rows of each thin rule, and reject text, solid bars and table borders.
     * Short rules need an aligned neighbour; a long isolated rule can be a one-line answer.
     */
    fun detect(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float): List<WritingGuide> =
        analyze(pixels, width, height, pageWidth, pageHeight).guides

    fun analyze(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float): DetectedGuides {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        return analyze(width, height, pageWidth, pageHeight) { y, row -> System.arraycopy(pixels, y * width, row, 0, width) }
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
     */
    fun analyze(width: Int, height: Int, pageWidth: Float, pageHeight: Float, readRow: (Int, IntArray) -> Unit): DetectedGuides {
        require(width > 0 && height > 0)
        require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0)
        val sx = pageWidth / width
        val sy = pageHeight / height
        val gap = ceil(6f / sx).toInt().coerceAtLeast(1)
        val fine = (gap / 4).coerceAtLeast(1)
        // Short enough for an inline blank ("Formula = ____"); such a rule must also carry a label to count.
        val minLength = max(48f, pageWidth * .06f) / sx
        val mask = darkMask(width, height, readRow)
        fun dark(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && mask[y * width + x]
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
                var left = x
                var right = x
                var ink = 0
                // The longest unbroken stretch: a label sitting on the rule ("Advantage ____") joins
                // across the dash gap, but its letters break the stretch, so it stays off the rule.
                var start = x
                var solidLeft = x
                var solidRight = x
                while (x < width && x - right <= gap) {
                    if (dark(x, y)) {
                        if (x - right > fine) start = x
                        right = x; ink++
                        if (right - start > solidRight - solidLeft) { solidLeft = start; solidRight = right }
                    }
                    x++
                }
                if (solidRight - solidLeft >= minLength && solidRight - solidLeft < right - left) {
                    left = solidLeft; right = solidRight
                    ink = (left..right).count { dark(it, y) }
                }
                if (right - left < minLength || ink.toFloat() / (right - left + 1) < .15f) continue
                if (ink.toFloat() / (right - left + 1) < .45f && !regularDashes(y, left, right)) continue
                val band = previous.lastOrNull { abs(it.left - left) <= gap * 2 && abs(it.right - right) <= gap * 2 }
                if (band == null) Band(left, right, y, y).also { bands += it; open += it }
                else { band.left = min(band.left, left); band.right = max(band.right, right); band.bottom = y; open += band }
            }
        }
        if (bands.isEmpty()) return DetectedGuides(emptyList(), emptyList())
        // Thin horizontal edges as [left, right, top, bottom] in pixels, for boxes and table cells.
        val edges = bands.filter { (it.bottom - it.top + 1) * sy <= 3.5f }.map { intArrayOf(it.left, it.right, it.top, it.bottom) }
        // A rule whose ends meet a printed border may be an answer-box line or a table row; blocks decide.
        val bordered = mutableSetOf<WritingGuide>()
        val dotted = mutableSetOf<WritingGuide>()
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
            fun text(x: Int) = (2..clearance).any { dark(x, band.top - it) || dark(x, band.bottom + it) }
            // A dotted leader runs into the words at its ends ("Flames ........ Robbie Arnott"): those words
            // are not part of the line, so it starts and ends where a stretch of clear rule does.
            val clear = ceil(6f / sx).toInt()
            val left = (band.left..band.right).firstOrNull { x -> (x..min(x + clear, band.right)).none(::text) } ?: return@mapNotNull null
            val right = (band.right downTo left).first { x -> (max(x - clear, left)..x).none(::text) }
            if (right - left < minLength) return@mapNotNull null
            val occupied = (left..right).count(::text)
            if (!thin || occupied.toFloat() / (right - left + 1) >= .18f) return@mapNotNull null
            WritingGuide(left * sx, right * sx, (band.top + band.bottom) * .5f * sy).also {
                if (verticalBorder(left) || verticalBorder(right)) bordered += it
                val row = (band.top + band.bottom) / 2
                if ((left..right).count { x -> dark(x, row) } < (right - left + 1) * .45f) dotted += it
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
        /** Fraction of the columns in [x0, x1] (pixels) with ink anywhere in rows [y0, y1]. */
        fun coverage(x0: Int, x1: Int, y0: Int, y1: Int): Float {
            if (x1 < x0) return 0f
            var columns = 0
            for (x in x0..x1) if ((y0..y1).any { dark(x, it) }) columns++
            return columns.toFloat() / (x1 - x0 + 1)
        }
        /**
         * "Advantage ____" over a full-width line: the first rule starts after its label, and the
         * answer continues on the line below, which runs back under the label to the margin.
         */
        fun continuation(above: WritingGuide): WritingGuide? = candidates.asSequence().filter { below ->
            val indent = above.left - below.left
            // A frame's edge under the last line is not its continuation, however dark the margin beside it.
            below !in bordered && below.y - above.y in 12f..64f && abs(below.right - above.right) <= 32f &&
                indent > 32f && indent <= (below.right - below.left) * .4f
        }.minByOrNull { it.y }?.takeIf { below ->
            val row = (above.y / sy).toInt()
            val label = coverage(ceil(below.left / sx).toInt(), (above.left / sx).toInt() - 1,
                row - ceil(12f / sy).toInt(), row)
            label >= .15f && candidates.none { it !== below && it.y in above.y..below.y && follows(above, it) }
        }
        val ordered = candidates.sortedWith(compareBy({ it.y }, { it.left }))
        // A free-standing line never continues into a frame's edge, nor a box's line into one outside it.
        val following = ordered.associateWith { above ->
            (next(above, ordered) ?: continuation(above))?.takeIf { (above in bordered) == (it in bordered) && clearBetween(above, it) }
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
        /**
         * Ink beside a rule's end, on its own row. A rule's ends are exact raster columns, so they are
         * rounded back rather than truncated: a truncated end lands on the rule's own last pixel and makes
         * every rule look labelled. A border running through the whole row is a frame or an axis, not text.
         */
        class Beside(val ink: Boolean, val rise: Float, val columns: Int, val descending: Int) {
            /**
             * Words resting on the rule ("Country ____"): they rise a line's x-height above it and only their
             * descenders drop below. A number centred on a gridline drops below it in nearly every column.
             */
            val sits get() = ink && rise >= 6f && descending * 10 < columns * 7
        }
        fun beside(rule: WritingGuide, reachUnits: Float, left: Boolean): Beside {
            val reach = ceil(reachUnits / sx).toInt()
            val up = ceil(10f / sy).toInt().coerceAtLeast(2)
            val down = ceil(5f / sy).toInt().coerceAtLeast(1)
            val drop = ceil(2f / sy).toInt().coerceAtLeast(1)
            val row = (rule.y / sy).roundToInt()
            val range = if (left) (rule.left / sx).roundToInt().let { (it - reach) until it }
                else (rule.right / sx).roundToInt().let { (it + 1)..(it + reach) }
            var top = Int.MAX_VALUE
            var columns = 0
            var descending = 0
            for (x in range) {
                // A border running through the label's whole height is a frame or an axis, not text.
                if ((row - down..row + down).all { dark(x, it) }) continue
                var inked = false
                for (y in row - up..row + down) if (dark(x, y)) { inked = true; top = min(top, y) }
                if (!inked) continue
                columns++
                if ((row + drop..row + down).any { dark(x, it) }) descending++
            }
            return Beside(columns > 0, if (columns > 0) (row - top) * sy else 0f, columns, descending)
        }
        /**
         * Nothing touches the rule just past its end, and no tick stands on the end itself: a diagram's edge
         * joins the next stroke at a corner, and a scale bar or dimension line is closed by ticks.
         */
        fun freeEnd(rule: WritingGuide, left: Boolean): Boolean {
            val row = (rule.y / sy).roundToInt()
            val rows = ceil(2f / sy).toInt().coerceAtLeast(1)
            val width = ceil(1.5f / sx).toInt().coerceAtLeast(1)
            val end = ((if (left) rule.left else rule.right) / sx).roundToInt()
            val beyond = if (left) (end - width) until end else (end + 1)..(end + width)
            if (beyond.any { x -> (row - rows..row + rows).any { dark(x, it) } }) return false
            val tick = (ceil(2.5f / sy).toInt().coerceAtLeast(rows + 1))..(ceil(4f / sy).toInt().coerceAtLeast(rows + 2))
            val inside = if (left) end..(end + width) else (end - width)..end
            return inside.none { x -> tick.all { dark(x, row - it) } || tick.all { dark(x, row + it) } }
        }
        /** A vertical line meets the rule's end, or just inside it where a tick mark overhangs: a chart's axis. */
        fun meetsVertical(rule: WritingGuide, left: Boolean): Boolean {
            val row = (rule.y / sy).roundToInt()
            val end = ((if (left) rule.left else rule.right) / sx).roundToInt()
            val out = ceil(2f / sx).toInt().coerceAtLeast(1)
            val inside = ceil(8f / sx).toInt().coerceAtLeast(2)
            val reach = ceil(6f / sy).toInt().coerceAtLeast(3)
            return (if (left) end - out..end + inside else end - inside..end + out).any { x ->
                (1..reach).all { dark(x, row - it) } || (1..reach).all { dark(x, row + it) }
            }
        }
        fun textLeft(rule: WritingGuide, reachUnits: Float) = beside(rule, reachUnits, true).ink
        fun textRight(rule: WritingGuide, reachUnits: Float) = beside(rule, reachUnits, false).ink
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
        /**
         * Text at both ends of a rule on its own row: a leader, unless it is a short solid blank standing free
         * between words resting on the line ("Colour change from ____ to ____"); dots between words are a leader.
         */
        fun leader(rule: WritingGuide): Boolean {
            val left = beside(rule, 24f, true)
            val right = beside(rule, 24f, false)
            return left.ink && right.ink && !(left.sits && right.sits && rule !in dotted &&
                rule.right - rule.left < max(120f, pageWidth * .3f) && freeEnd(rule, true) && freeEnd(rule, false))
        }
        /**
         * Leader lines carry text at both ends; chart gridlines carry labels centred on them, at an axis or
         * closer together than handwriting needs. A form row's label rests on its rule ("Oxygen demand ____")
         * and a numbered answer line ("1. ____") is spaced for writing, so both are still lines to write on.
         */
        fun labelled(block: List<WritingGuide>): Boolean {
            val tight = block.size > 1 && block.zipWithNext { a, b -> b.y - a.y }.min() < ROOMY_PITCH
            val tagged = block.count { rule ->
                val left = beside(rule, 12f, true)
                val right = beside(rule, 12f, false)
                val axis = (left.ink && !left.sits && meetsVertical(rule, true)) ||
                    (right.ink && !right.sits && meetsVertical(rule, false))
                // Lines too close for handwriting with words or numbers beside them are a scale or a legend,
                // whose labels can stand well clear of the lines ("13.6 eV ____").
                val scale = tight && (beside(rule, 24f, true).ink || beside(rule, 24f, false).ink ||
                    meetsVertical(rule, true) != meetsVertical(rule, false))
                axis || scale || if (block.size > 1) left.ink && right.ink else leader(rule)
            }
            return tagged * 2 >= block.size
        }
        /** Evenly spaced marks hanging off one side of a rule: a graph axis, not a line to write on. */
        fun ticked(rule: WritingGuide): Boolean {
            val row = (rule.y / sy).roundToInt()
            val edge = ceil(1.5f / sy).toInt().coerceAtLeast(1)
            val reach = ceil(3f / sy).toInt().coerceAtLeast(edge + 2)
            val inset = ceil(4f / sx).toInt()
            val x0 = (rule.left / sx).roundToInt() + inset
            val x1 = (rule.right / sx).roundToInt() - inset
            fun marks(sign: Int): List<Int> {
                val found = mutableListOf<Int>()
                var x = x0
                while (x <= x1) {
                    if ((edge..reach).all { dark(x, row + sign * it) }) {
                        val start = x
                        while (x <= x1 && (edge..reach).all { dark(x, row + sign * it) }) x++
                        found += (start + x) / 2
                    }
                    x++
                }
                return found
            }
            return listOf(1, -1).any { sign ->
                val gaps = marks(sign).zipWithNext { a, b -> b - a }
                if (gaps.size < 2) return@any false
                val median = gaps.sorted()[gaps.size / 2]
                gaps.count { abs(it - median) <= max(2, median / 5) } * 4 >= gaps.size * 3
            }
        }
        /** An arrowhead closes the rule: an axis or a pointer, not a line to write on. */
        fun arrow(rule: WritingGuide): Boolean {
            val row = (rule.y / sy).roundToInt()
            val rise = ceil(1.5f / sy).toInt().coerceAtLeast(2)
            val head = ceil(8f / sx).toInt()
            val x0 = (rule.left / sx).roundToInt()
            val x1 = (rule.right / sx).roundToInt()
            fun headed(range: IntRange) = range.count { x -> dark(x, row - rise) && dark(x, row + rise) } >= 2
            // A head drawn on the shaft's end, solid through the line but short (a box's border runs on),
            // rather than a label standing clear of it.
            val far = ceil(6f / sy).toInt().coerceAtLeast(rise + 2)
            fun capped(range: IntRange, attached: Int) = dark(attached, row) && range.count { x ->
                (row - rise..row + rise).all { dark(x, it) } && !dark(x, row - far) && !dark(x, row + far)
            } >= 3
            return headed(x0..min(x0 + head, x1)) || headed(max(x1 - head, x0)..x1) ||
                capped(x0 - head until x0, x0 - 1) || capped(x1 + 1..x1 + head, x1 + 1)
        }
        /** The rule runs into a filled shape, as a gridline does into a bar; an answer line never does. */
        fun abutsFill(rule: WritingGuide): Boolean {
            val row = (rule.y / sy).roundToInt()
            val rows = ceil(4f / sy).toInt().coerceAtLeast(2)
            val near = ceil(2f / sx).toInt().coerceAtLeast(1)
            val width = ceil(5f / sx).toInt().coerceAtLeast(3)
            val x0 = (rule.left / sx).roundToInt()
            val x1 = (rule.right / sx).roundToInt()
            fun solid(from: Int, to: Int) = (from..to).all { x -> (row - rows..row + rows).all { dark(x, it) } }
            return (0..near).any { solid(x0 - it - width, x0 - it - 1) || solid(x1 + it + 1, x1 + it + width) }
        }
        /** A plotted curve or bar crosses the space between gridlines; nothing is printed between answer lines. */
        fun plotted(block: List<WritingGuide>): Boolean {
            if (block.size < 3) return false
            val crossed = block.zipWithNext().count { (above, below) ->
                val x0 = ceil((max(above.left, below.left) + 4f) / sx).toInt()
                val x1 = ((min(above.right, below.right) - 4f) / sx).toInt()
                val top = ((above.y + (below.y - above.y) / 3f) / sy).roundToInt()
                val bottom = ((below.y - (below.y - above.y) / 3f) / sy).roundToInt()
                val from = ((above.y + 2f) / sy).roundToInt()
                val to = ((below.y - 2f) / sy).roundToInt()
                // A border or divider runs through the whole gap; a curve only passes through it.
                (x0..x1).any { x -> (top..bottom).any { dark(x, it) } && !(from..to).all { dark(x, it) } }
            }
            return crossed * 3 >= (block.size - 1)
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
            if (leader(rule)) return false
            if (crossed(rule) || ticked(rule) || arrow(rule)) return false
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
            // Text straddling the limit still reaches into the room: start under its lowest row.
            return if (rows > 0) min(floor, (y + 1 + rows) * sy) else limit
        }
        val guides = mutableListOf<WritingGuide>()
        val areas = mutableListOf<AnswerArea>()
        for (found in blocks) {
            // Lines inside a ruled box touch its borders. The box's own top and bottom edges are not
            // places to write, and fewer than three inner rules looks like a table, not an answer.
            val block = if (found.none { it in bordered }) found else {
                val inner = found.toMutableList()
                if (inner.first() in bordered) inner.removeAt(0)
                val edge = inner.lastOrNull()?.takeIf { it in bordered }
                if (edge != null) inner.removeAt(inner.lastIndex)
                if (inner.size < 3) continue
                // Except where it keeps the lines' spacing: then the last row is written on the box's edge.
                val spacing = inner.zipWithNext { a, b -> b.y - a.y }.min()
                if (edge != null && abs(edge.y - inner.last().y - spacing) <= spacing * .15f) inner += edge
                inner
            }
            // Decoration is not an answer: a rule hugging text (page headers, dividers, leader lines)
            // and gridlines whose ends carry axis labels both look like rules but have nothing to write in.
            if (block.size == 1 && !plausibleSingle(block.first())) continue
            if (labelled(block) || (found.none { it in bordered } && block.count(::crossed) * 2 >= block.size)) continue
            // A short rule on its own is only an answer when it stands free and a label rests beside it
            // ("Answer ____", "x = ____ cm"), not when it is the edge of a drawing.
            if (block.size == 1 && block.first().right - block.first().left < max(120f, pageWidth * .25f) &&
                block.first().let { rule ->
                    !freeEnd(rule, true) || !freeEnd(rule, false) || (!beside(rule, 12f, true).sits && !beside(rule, 12f, false).sits)
                }) continue
            val pitch = block.zipWithNext { a, b -> b.y - a.y }.minOrNull() ?: 28f
            // Handwriting needs room: rules closer than this are a chart's gridlines, as are rules that
            // a plotted line crosses or that carry an axis's tick marks.
            if (pitch < MIN_PITCH || plotted(block) || block.count(::ticked) * 2 >= block.size ||
                block.count(::abutsFill) * 2 >= block.size || block.count(::arrow) * 2 >= block.size) continue
            // Short free-standing lines in a column must stand free like a blank, not join a plotted step.
            if (block.size > 1 && found.none { it in bordered } && block.first().right - block.first().left < max(120f, pageWidth * .25f) &&
                block.any { !freeEnd(it, true) || !freeEnd(it, false) }) continue
            val id = areas.size
            // Over the first rule only: a label beside it is not question text above the area.
            val top = topOf(block.first(), block.first().left, block.first().right, pitch)
            guides += block.map { it.copy(block = id, areaTop = top) }
            areas += AnswerArea(block.minOf { it.left }, top, block.maxOf { it.right }, block.last().y)
        }
        for (cell in emptyCells(edges, width, height, sx, sy, pageHeight, ::dark)) {
            // Lines already found inside it make it a lined area (or a ruled table's row), not an empty box.
            if (guides.any { it.y >= cell.top - 2f && it.y <= cell.bottom + 2f && it.right > cell.left && it.left < cell.right }) continue
            val id = areas.size
            guides += WritingGuide(cell.left, cell.right, cell.bottom, id, cell.top)
            areas += cell
        }
        return DetectedGuides(guides.sortedWith(compareBy({ it.y }, { it.left })), areas)
    }

    /**
     * Boxes and table cells left empty for an answer ("Complete the table", "Working space", a box for the final
     * value). A label tucked against the top, left or right side ("Working space", "$", "nm") is allowed and
     * excluded from the writing room. Table corner headings, tick boxes and page frames are not answers.
     * Each cell is returned as the writing room inside it, down to its bottom edge, in page units.
     */
    private fun emptyCells(
        bands: List<IntArray>, width: Int, height: Int, sx: Float, sy: Float, pageHeight: Float, dark: (Int, Int) -> Boolean,
    ): List<AnswerArea> {
        // Thin vertical rules, each as [x, top, bottom] in pixels.
        val minRun = ceil(12f / sy).toInt().coerceAtLeast(4)
        class Run(val x0: Int, var x1: Int, val top: Int, val bottom: Int)
        val runs = mutableListOf<Run>()
        var previous = emptyList<Run>()
        for (x in 0 until width) {
            val current = mutableListOf<Run>()
            var y = 0
            while (y < height) {
                if (!dark(x, y)) { y++; continue }
                val start = y
                while (y < height && dark(x, y)) y++
                if (y - start < minRun) continue
                val run = previous.firstOrNull { abs(it.top - start) <= 2 && abs(it.bottom - (y - 1)) <= 2 }
                    ?.also { it.x1 = x } ?: Run(x, x, start, y - 1).also { runs += it }
                current += run
            }
            previous = current
        }
        val verticals = runs.filter { (it.x1 - it.x0 + 1) * sx <= 3.5f }.map { intArrayOf((it.x0 + it.x1) / 2, it.top, it.bottom) }
        if (verticals.size < 2) return emptyList()
        val slack = 2
        val minHeight = ceil(20f / sy).toInt()
        val minWidth = 50f
        class Cell(val left: Int, val top: Int, val right: Int, val bottom: Int, val content: Content)
        val cells = mutableListOf<Cell>()
        for (edge in bands) {
            val row = (edge[2] + edge[3]) / 2
            val down = verticals.filter { it[0] in edge[0] - slack..edge[1] + slack && it[1] <= row + slack && it[2] >= row + minHeight }
                .sortedBy { it[0] }
            for ((p, q) in down.zipWithNext()) {
                if ((q[0] - p[0]) * sx < minWidth) continue
                val bottom = bands.asSequence().map { (it[2] + it[3]) / 2 to it }
                    .filter { (y, b) -> y >= row + minHeight && y <= min(p[2], q[2]) + slack && b[0] <= p[0] + slack && b[1] >= q[0] - slack }
                    .minByOrNull { it.first }?.first ?: continue
                if ((bottom - row) * sy > pageHeight * .6f) continue
                // A rule dividing the box lower down makes it two cells, found from their own top edges.
                if (verticals.any { v -> v[0] > p[0] + slack && v[0] < q[0] - slack && min(v[2], bottom) - max(v[1], row) > (bottom - row) / 2 }) continue
                cells += Cell(p[0], row, q[0], bottom, content(p[0], row, q[0], bottom, sx, sy, dark))
            }
        }
        // Cells sharing a side form one table (or one chart's grid).
        val group = IntArray(cells.size) { it }
        fun root(i: Int): Int { var r = i; while (group[r] != r) r = group[r]; return r }
        fun touching(a: Cell, b: Cell): Boolean {
            val across = min(a.bottom, b.bottom) - max(a.top, b.top) > 0 &&
                (abs(a.right - b.left) <= slack || abs(b.right - a.left) <= slack)
            val stacked = min(a.right, b.right) - max(a.left, b.left) > 0 &&
                (abs(a.bottom - b.top) <= slack || abs(b.bottom - a.top) <= slack)
            return across || stacked
        }
        for (i in cells.indices) for (j in i + 1 until cells.size) if (touching(cells[i], cells[j])) group[root(j)] = root(i)
        val tables = cells.indices.groupBy(::root).values.map { members -> members.map { cells[it] } }
        val result = mutableListOf<AnswerArea>()
        for (table in tables) {
            // A plotted line runs from cell to cell; a table's words sit inside their cells.
            if (table.any { it.content.crossed }) continue
            // Rows and columns of identical blank squares with no headings are a graph's grid for sketching.
            val widths = table.map { it.right - it.left }.sorted()
            val heights = table.map { it.bottom - it.top }.sorted()
            val uniform = table.size >= 3 && widths.last() <= widths.first() * 1.1f + slack && heights.last() <= heights.first() * 1.1f + slack
            if (uniform && table.all { it.content.blank }) continue
            // A table that is mostly filled in keeps an empty cell only where it is part of something to
            // complete: a run of empty cells, or a row that is mostly empty. The odd gap in a formula sheet
            // or a data table is not an answer.
            val sparse = table.size > 1 && table.count { it.content.blank } * 2 < table.count { !it.content.blank }
            fun completes(cell: Cell): Boolean {
                if (table.any { it !== cell && it.content.blank && touching(it, cell) }) return true
                val row = table.filter { abs(it.top - cell.top) <= slack && abs(it.bottom - cell.bottom) <= slack }
                return row.count { it.content.blank } > row.count { !it.content.blank }
            }
            for (cell in table) {
                val room = cell.content.room ?: continue
                // In a table a cell holding only a value or a heading is filled in, not waiting for an answer;
                // only a box standing on its own keeps the room beside its label ("Working space", "nm").
                if (!cell.content.blank && table.size > 1) continue
                if (sparse && !completes(cell)) continue
                if ((room.right - room.left) < minWidth || (room.bottom - room.top) < 18f) continue
                // A table's empty corner: nothing above it or to its left, with headings beside it.
                val above = table.any { abs(it.bottom - cell.top) <= slack && min(it.right, cell.right) - max(it.left, cell.left) > slack }
                val left = table.any { abs(it.right - cell.left) <= slack && min(it.bottom, cell.bottom) - max(it.top, cell.top) > slack }
                val headings = table.any { abs(it.left - cell.right) <= slack && min(it.bottom, cell.bottom) - max(it.top, cell.top) > slack && it.content.room == null }
                if (!above && !left && headings) continue
                if (room !in result) result += room
            }
        }
        return result
    }

    /**
     * What is printed inside a box: [room] is the writing room left beside a label, or null when more than a
     * label is printed; [blank] means nothing at all; [crossed] means ink runs into the box's sides, as a
     * plotted line does through a chart's grid.
     */
    private class Content(val room: AnswerArea?, val blank: Boolean, val crossed: Boolean)

    private fun content(left: Int, top: Int, right: Int, bottom: Int, sx: Float, sy: Float, dark: (Int, Int) -> Boolean): Content {
        val insetX = ceil(2f / sx).toInt().coerceAtLeast(2)
        val insetY = ceil(2f / sy).toInt().coerceAtLeast(2)
        var x0 = left + insetX
        var x1 = right - insetX
        var y0 = top + insetY
        val y1 = bottom - insetY
        if (x1 <= x0 || y1 <= y0) return Content(null, false, false)
        var inkLeft = Int.MAX_VALUE
        var inkRight = Int.MIN_VALUE
        var inkTop = Int.MAX_VALUE
        var inkBottom = Int.MIN_VALUE
        for (y in y0..y1) for (x in x0..x1) if (dark(x, y)) {
            inkLeft = min(inkLeft, x); inkRight = max(inkRight, x); inkTop = min(inkTop, y); inkBottom = max(inkBottom, y)
        }
        if (inkLeft == Int.MAX_VALUE) return Content(AnswerArea(x0 * sx, y0 * sy, x1 * sx, bottom * sy), true, false)
        val crossed = inkLeft == x0 || inkRight == x1 || inkTop == y0 || inkBottom == y1
        when {
            crossed -> return Content(null, false, true)
            (inkBottom - top) * sy <= 24f -> y0 = inkBottom + insetY
            (inkRight - left) * sx <= 20f -> x0 = inkRight + insetX
            (right - inkLeft) * sx <= 40f -> x1 = inkLeft - insetX
            else -> return Content(null, false, false)
        }
        return Content(AnswerArea(x0 * sx, y0 * sy, x1 * sx, bottom * sy), false, false)
    }
}
